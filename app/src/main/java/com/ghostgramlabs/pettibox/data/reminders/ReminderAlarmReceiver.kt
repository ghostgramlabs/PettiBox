package com.ghostgramlabs.pettibox.data.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ghostgramlabs.pettibox.MainActivity
import com.ghostgramlabs.pettibox.R
import com.ghostgramlabs.pettibox.data.preferences.ReminderPreferences
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fired by [AlarmManager] when a reminder's wall-clock time arrives.
 *
 * The receiver:
 *   1. Looks up the item and verifies the alarm is still wanted (the
 *      user may have cancelled it, archived the row, or re-snoozed since
 *      we were scheduled).
 *   2. Posts a notification on the reminders channel.
 *   3. Clears remind_at on the row so the bell glyph disappears from
 *      cards and the user can re-snooze from scratch.
 *
 * Uses `goAsync()` + a coroutine because Room calls suspend, but Android
 * lets us live for ~10s after onReceive returns when goAsync is held.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: SaveRepository
    @Inject lateinit var reminderPreferences: ReminderPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ReminderScheduler.ACTION_FIRE && action != ACTION_SNOOZE) return
        val itemId = intent.getLongExtra(ReminderScheduler.EXTRA_ITEM_ID, -1L)
        if (itemId <= 0L) return
        val expectedAt = intent.getLongExtra(ReminderScheduler.EXTRA_EXPECTED_AT, 0L)
        val snoozeAt = intent.getLongExtra(EXTRA_SNOOZE_AT, 0L)
        val appContext = context.applicationContext
        val pending = goAsync()
        // SupervisorJob so a coroutine failure doesn't tear down a shared
        // scope; receivers must call finish() on the PendingResult
        // exactly once regardless of how the work completed.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                if (action == ACTION_SNOOZE) snooze(appContext, itemId, snoozeAt)
                else fire(appContext, itemId, expectedAt)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Notification "Snooze" action: re-arm the reminder for [at] and clear
     * the posted notification. Guards against a stale/zero time by falling
     * back to one hour out.
     */
    private suspend fun snooze(ctx: Context, itemId: Long, at: Long) {
        repository.getById(itemId) ?: return
        val now = System.currentTimeMillis()
        val target = if (at > now) at else now + ONE_HOUR_MS
        repository.setRemindAt(itemId, target)
        ReminderScheduler.schedule(ctx, itemId, target)
        NotificationManagerCompat.from(ctx).cancel(itemId.toInt())
    }

    private suspend fun fire(ctx: Context, itemId: Long, expectedAt: Long) {
        val item = repository.getById(itemId) ?: return
        if (item.remindAt == null) return
        // Stale alarm: the item's remind_at was changed (re-snoozed) since
        // we were scheduled. A newer alarm is already in flight for the
        // current time; do nothing.
        if (expectedAt > 0L && item.remindAt != expectedAt) return
        if (item.isArchived) {
            repository.setRemindAt(itemId, null)
            return
        }
        ReminderNotifications.ensureChannel(ctx)
        // A locked collection's save still reminds you, but never says what
        // it is on the lock screen; tapping it asks to unlock first.
        val title = if (repository.isInLockedCollection(item)) "A save in a locked collection"
            else item.title.ifBlank { "Saved item" }
        postNotification(ctx, itemId, title)
        repository.setRemindAt(itemId, null)
    }

    private suspend fun postNotification(ctx: Context, itemId: Long, title: String) {
        val nm = NotificationManagerCompat.from(ctx)
        // Skip the post entirely when notifications are off for the app
        // (Android 13+ runtime permission revoked, channel disabled, or
        // the user globally muted us). The previous runCatching wrapper
        // hid the SecurityException but still left the user expecting a
        // notification that never arrived; explicitly bailing means we
        // can hook a recovery surface here later (e.g. setting a
        // preference that drives a Home banner). Lint's MissingPermission
        // error is silenced because the call is gated by the runtime
        // check this method now performs.
        if (!nm.areNotificationsEnabled()) {
            reminderPreferences.setNotificationsBlocked(true)
            return
        }
        reminderPreferences.setNotificationsBlocked(false)
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_ITEM_ID, itemId)
        }
        val openPi = PendingIntent.getActivity(
            ctx,
            itemId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        // "This evening" snoozes to the same evening time the user configured
        // for the "Tonight" preset in Settings, so a snooze from a notification
        // and a save-time reminder land at the same hour instead of two
        // different hard-coded ones.
        val evening = reminderPreferences.eveningTime.first()
        val notif = NotificationCompat.Builder(ctx, ReminderNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("From your shelf")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            // Snooze actions let the user re-defer without opening the app —
            // the whole point of a "remind me later" save. Tapping the body
            // still opens the item.
            .addAction(snoozeAction(ctx, itemId, "Snooze 1h", "1h", now + ONE_HOUR_MS))
            .addAction(
                snoozeAction(
                    ctx, itemId, "This evening", "evening",
                    thisEveningMillis(now, evening.hour, evening.minute)
                )
            )
            .build()
        runCatching {
            @SuppressLint("MissingPermission")
            nm.notify(itemId.toInt(), notif)
        }
    }

    /**
     * Builds a notification action that re-schedules the reminder. The
     * intent's data Uri is made unique per (item, variant) so the two
     * snooze PendingIntents don't collapse into one — `filterEquals` ignores
     * extras, so without distinct data they'd share a PendingIntent and the
     * second action would overwrite the first's snooze time.
     */
    private fun snoozeAction(
        ctx: Context,
        itemId: Long,
        label: String,
        variant: String,
        at: Long
    ): NotificationCompat.Action {
        val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_SNOOZE
            data = Uri.parse("pettibox://reminder/snooze/$itemId/$variant")
            putExtra(ReminderScheduler.EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_SNOOZE_AT, at)
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            itemId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    /** Today at the user's evening time, or tomorrow if it's already past. */
    private fun thisEveningMillis(now: Long, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    companion object {
        const val EXTRA_OPEN_ITEM_ID = "pettibox.reminder.itemId"
        const val ACTION_SNOOZE = "com.ghostgramlabs.pettibox.action.REMINDER_SNOOZE"
        const val EXTRA_SNOOZE_AT = "pettibox.reminder.snoozeAt"
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}
