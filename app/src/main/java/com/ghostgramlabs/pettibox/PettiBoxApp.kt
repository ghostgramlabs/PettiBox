package com.ghostgramlabs.pettibox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ghostgramlabs.pettibox.data.backup.LocalBackupWorker
import com.ghostgramlabs.pettibox.data.preferences.BackupPreferences
import com.ghostgramlabs.pettibox.data.preferences.OnboardingPreferences
import com.ghostgramlabs.pettibox.data.preferences.ReminderPreferences
import com.ghostgramlabs.pettibox.data.reminders.ReminderNotifications
import com.ghostgramlabs.pettibox.data.reminders.ShelfNudgePreferences
import com.ghostgramlabs.pettibox.data.reminders.ShelfNudgeWorker
import com.ghostgramlabs.pettibox.ui.lock.AppLockSession
import com.ghostgramlabs.pettibox.ui.widget.ShelfWidget
import com.ghostgramlabs.pettibox.data.reminders.ReminderScheduler
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        AppLockSession.install()
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
