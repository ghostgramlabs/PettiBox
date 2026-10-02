package com.ghostgramlabs.pettibox.ui.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Hides the widget's titles again once PettiBox would have relocked. The
 * app may be gone from memory by then, so this can't be a timer inside it.
 */
class WidgetTitlesExpiryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = EntryPointAccessors
            .fromApplication(applicationContext, ShelfWidgetEntryPoint::class.java)
            .appLockPreferences()
        val until = prefs.widgetTitlesVisibleUntil.first()
        // Back in the app (MAX) or already hidden (0): nothing to do. A newer
        // deadline replaces this work, so a still-future one is left alone.
        if (until != Long.MAX_VALUE && until in 1..System.currentTimeMillis()) {
            prefs.setWidgetTitlesVisibleUntil(0L)
        }
        runCatching { ShelfWidget().updateAll(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget-titles-expiry"

        fun schedule(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<WidgetTitlesExpiryWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
