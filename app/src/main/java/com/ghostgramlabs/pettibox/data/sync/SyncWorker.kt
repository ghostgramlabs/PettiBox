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
import kotlinx.coroutines.flow.first
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

    /**
     * "Sync now", opening the app, and a few seconds after an edit. Asked for
     * while a round is already running, one more round follows it: the
     * running one may have started before the change it's asked for.
     */
    fun now(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** After a local edit: the app waits for the edits to pause (see PettiBoxApp), then [now]. */
    fun soon(context: Context) = now(context)

    /**
     * [now], unless a round is already waiting to start (offline, say): that
     * one will see everything, so automatic asks don't pile up behind it.
     */
    suspend fun nowUnlessWaiting(context: Context) {
        val waiting = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW).first()
            .any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.BLOCKED }
        if (!waiting) now(context)
    }
}
