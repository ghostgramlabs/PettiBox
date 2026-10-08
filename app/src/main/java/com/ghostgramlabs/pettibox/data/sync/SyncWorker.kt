package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Runs one sync round. Rounds never overlap: [SyncEngine] holds one lock. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = when (val outcome = engine.round()) {
        is SyncOutcome.Failed -> if (outcome.retry && runAttemptCount < 5) Result.retry() else Result.success()
        else -> Result.success()
    }
}

/** When sync runs. Nothing is scheduled while sync is off. */
object SyncScheduler {
    private const val PERIODIC = "sync_periodic"
    private const val NOW = "sync_now"
    private const val SOON = "sync_soon"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Matches background syncing to the setting. */
    fun apply(context: Context, enabled: Boolean, interval: SyncInterval) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(SOON)
            wm.cancelUniqueWork(NOW)
            FileSyncWorker.cancel(context)
            return
        }
        if (interval == SyncInterval.MANUAL) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.minutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** "Sync now", and opening the app. Joins a round already running. */
    fun now(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * About a minute after the last local edit. Each edit pushes it back, so a
     * burst of edits is one round, not one per edit.
     */
    fun soon(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }
}
