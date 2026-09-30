package com.ghostgramlabs.pettibox.data.reminders

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghostgramlabs.pettibox.R
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderPreferences
import com.ghostgramlabs.pettibox.data.util.TimeFormat
import com.ghostgramlabs.pettibox.ui.nav.AppLaunch
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.nudgeDataStore by preferencesDataStore(name = "shelf_nudge_preferences")

@Singleton
class ShelfNudgePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val enabledKey = booleanPreferencesKey("weekly_nudge_enabled")
    private val recentIdsKey = stringPreferencesKey("recently_nudged_ids")

    /** On by default; only ever shows if notifications are allowed. */
    val enabled: Flow<Boolean> = context.nudgeDataStore.data.map { it[enabledKey] ?: true }

    suspend fun setEnabled(enabled: Boolean) {
        context.nudgeDataStore.edit { it[enabledKey] = enabled }
    }

    /** The last few saves we nudged about, so the same one doesn't come up week after week. */
    suspend fun recentlyNudged(): List<Long> =
        context.nudgeDataStore.data.first()[recentIdsKey].orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }

    suspend fun remember(id: Long) {
        context.nudgeDataStore.edit { prefs ->
            val ids = (listOf(id) + prefs[recentIdsKey].orEmpty().split(',').mapNotNull { it.toLongOrNull() })
                .distinct().take(RECENT_MEMORY)
            prefs[recentIdsKey] = ids.joinToString(",")
        }
    }

    private companion object {
        const val RECENT_MEMORY = 8
    }
}

/**
 * Once a week (Saturday, at the user's morning reminder time) resurfaces
 * one thing they saved but never opened. Read-later apps live or die on
 * whether saves come back out; this is the gentle version: one save, no
 * sound, and nothing at all when the shelf has nothing unread.
 */
@HiltWorker
class ShelfNudgeWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val saveDao: SaveDao,
    private val nudgePreferences: ShelfNudgePreferences,
    private val reminderPreferences: ReminderPreferences,
    private val appLockPreferences: AppLockPreferences
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!nudgePreferences.enabled.first()) return Result.success()
        // Book the next week first, so a failure below can't end the cycle.
        schedule(ctx, reminderPreferences, replace = false)

        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return Result.success()
        val pick = saveDao.unreadForNudge(
            before = System.currentTimeMillis() - MIN_AGE_MS,
            exclude = nudgePreferences.recentlyNudged().ifEmpty { listOf(-1L) }
        ) ?: return Result.success()
        val othersUnread = (saveDao.unreadTotal() - 1).coerceAtLeast(0)
        nudgePreferences.remember(pick.id)

        ensureChannel(ctx)
        // With App lock on, never put a save's title on the lock screen.
        val locked = appLockPreferences.enabled.first()
        val title = if (locked) "Something on your shelf is waiting" else "From your shelf"
        val body = when {
            locked -> "You have ${othersUnread + 1} unread save${if (othersUnread == 0) "" else "s"}."
            othersUnread == 0 -> "“${pick.title}” — saved ${TimeFormat.relative(pick.createdAt)}."
            else -> "“${pick.title}” — saved ${TimeFormat.relative(pick.createdAt)}. " +
                "Plus $othersUnread more unread."
        }
        val open = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID,
            AppLaunch.intent(ctx, if (locked) AppLaunch.Unread else AppLaunch.OpenItem(pick.id)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val seeAll = PendingIntent.getActivity(
            ctx, NOTIFICATION_ID + 1,
            AppLaunch.intent(ctx, AppLaunch.Unread),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { if (!locked && othersUnread > 0) addAction(0, "See all unread", seeAll) }
            .build()
        runCatching {
            @SuppressLint("MissingPermission")
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "pettibox_weekly_nudge"
        private const val CHANNEL_ID = "pettibox_shelf_nudge"
        private const val NOTIFICATION_ID = 7_000_001
        /** Don't resurface something saved this week — it isn't forgotten yet. */
        private const val MIN_AGE_MS = 3L * 24 * 60 * 60 * 1000

        /**
         * Queues the next nudge for the coming Saturday at the morning
         * reminder time. [replace] = false keeps an already-queued nudge
         * (cold start); the worker itself appends the following week.
         */
        suspend fun schedule(context: Context, reminderPreferences: ReminderPreferences, replace: Boolean) {
            val morning = reminderPreferences.morningTime.first()
            val delay = millisUntilNextSaturday(morning.hour, morning.minute)
            val request = OneTimeWorkRequestBuilder<ShelfNudgeWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        /** Cold-start reconcile: make sure exactly one nudge is queued, or none. */
        suspend fun reconcile(context: Context, enabled: Boolean, reminderPreferences: ReminderPreferences) {
            if (!enabled) return cancel(context)
            val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
                .any { !it.state.isFinished }
            if (!queued) schedule(context, reminderPreferences, replace = true)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun millisUntilNextSaturday(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                val daysAhead = (Calendar.SATURDAY - get(Calendar.DAY_OF_WEEK) + 7) % 7
                add(Calendar.DAY_OF_YEAR, daysAhead)
                // Already past this Saturday's slot (or within the hour): next week.
                if (timeInMillis <= now.timeInMillis + 60 * 60 * 1000) add(Calendar.DAY_OF_YEAR, 7)
            }
            return next.timeInMillis - now.timeInMillis
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Weekly shelf nudge", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Once a week, one thing you saved but haven't opened yet."
                }
            )
        }
    }
}
