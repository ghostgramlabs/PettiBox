package com.ghostgramlabs.pettibox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ghostgramlabs.pettibox.data.backup.LocalBackupWorker
import com.ghostgramlabs.pettibox.data.preferences.AppLockPreferences
import com.ghostgramlabs.pettibox.data.preferences.BackupPreferences
import com.ghostgramlabs.pettibox.data.preferences.OnboardingPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderPreferences
import com.ghostgramlabs.pettibox.data.reminders.ReminderNotifications
import com.ghostgramlabs.pettibox.data.reminders.ShelfNudgePreferences
import com.ghostgramlabs.pettibox.data.reminders.ShelfNudgeWorker
import com.ghostgramlabs.pettibox.ui.lock.AppLockSession
import com.ghostgramlabs.pettibox.ui.widget.ShelfWidget
import com.ghostgramlabs.pettibox.ui.widget.WidgetTitlesExpiryWorker
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PettiBoxApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var repository: SaveRepository
    @Inject lateinit var backupPreferences: BackupPreferences
    @Inject lateinit var onboardingPreferences: OnboardingPreferences
    @Inject lateinit var shelfNudgePreferences: ShelfNudgePreferences
    @Inject lateinit var reminderPreferences: ReminderPreferences
    @Inject lateinit var appLockPreferences: AppLockPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Register the notification channel up-front. Idempotent — the OS
        // ignores duplicate registrations after the first one took effect.
        ReminderNotifications.ensureChannel(this)
        // How long the widget may show titles follows the unlock session.
        // Conflated: only the latest value matters, and writes stay in order.
        val titlesUntil = MutableStateFlow<Long?>(null)
        AppLockSession.onTitlesVisibleUntil = { until ->
            titlesUntil.value = until
            val wait = until - System.currentTimeMillis()
            if (until != Long.MAX_VALUE && wait > 0) WidgetTitlesExpiryWorker.schedule(this, wait)
        }
        appScope.launch {
            titlesUntil.filterNotNull().collect { appLockPreferences.setWidgetTitlesVisibleUntil(it) }
        }
        AppLockSession.install()
        appScope.launch {
            appLockPreferences.lockAfterMs.collect { AppLockSession.lockAfterMs = it }
        }
        // Seed starter collections once per install. Users can rename and
        // delete starters now, so seeding must never re-run — the flag,
        // not the table contents, decides.
        appScope.launch {
            if (!onboardingPreferences.categoriesSeeded.first()) {
                repository.seedDefaultCategories()
                onboardingPreferences.markCategoriesSeeded()
            }
        }
        // Saves deleted more than 30 days ago leave Recently deleted for
        // good (files included) — otherwise the bin would grow forever.
        appScope.launch { repository.purgeExpiredTrash() }
        // Keep any placed "Unread" widget in step with the shelf.
        appScope.launch { ShelfWidget.keepUpdated(this@PettiBoxApp) }
        // Make sure the weekly shelf nudge is queued (or not), like backups.
        appScope.launch {
            ShelfNudgeWorker.reconcile(
                this@PettiBoxApp,
                shelfNudgePreferences.enabled.first(),
                reminderPreferences
            )
        }
        // Reconcile the saved backup preference with WorkManager state on
        // every cold start. If the user enabled auto-backup but the
        // WorkManager db got wiped (data clear, fresh install over old
        // install, OS update edge cases), the periodic worker would never
        // run again until they toggled the setting. ExistingPeriodicWorkPolicy
        // .UPDATE makes this idempotent — re-running on every start is
        // cheap and safe.
        appScope.launch {
            val status = backupPreferences.status.first()
            if (status.enabled) {
                LocalBackupWorker.schedule(this@PettiBoxApp)
            } else {
                LocalBackupWorker.cancel(this@PettiBoxApp)
            }
        }
        // Re-arm any reminder alarms that should still fire. AlarmManager
        // alarms do not survive a reboot or a data wipe; the BOOT_COMPLETED
        // receiver handles boot, this handles cold-start after a data
        // clear or fresh install. Past-due reminders fire immediately so
        // the user doesn't lose them entirely.
        appScope.launch {
            ReminderScheduler.rescheduleAll(this@PettiBoxApp, repository)
        }
    }
}
