package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Gets the models for the languages turned on, frees those turned off, and,
 * once a language newly can be read, has every saved image and PDF read
 * again ([OcrRereadWorker]) so older saves are found in it too.
 */
@HiltWorker
class OcrModelWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val prefs: OcrPreferences,
    private val models: OcrModels,
    private val reader: TextReader
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val wanted = prefs.languages.first()
        models.removeUnused(wanted)
        var missing = false
        for (code in wanted) {
            val lang = OcrLanguages.of(code) ?: continue
            if (!models.fetch(lang)) missing = true
        }
        reader.refresh()
        val ready = models.ready(wanted)
        if ((ready - prefs.readWith()).isNotEmpty()) {
            prefs.requestReread()
            OcrRereadWorker.enqueue(applicationContext)
        }
        prefs.setReadWith(ready)
        val retrying = missing && runAttemptCount < MAX_ATTEMPTS
        // Out of tries: say which couldn't be fetched, so Settings offers Retry
        // instead of "Downloading…" for ever. Ready ones and ones turned off drop out.
        val failed = prefs.failedLanguages.first().filterTo(HashSet()) { it in wanted && it !in ready }
        if (!retrying) failed += wanted - ready
        prefs.setFailedLanguages(failed)
        return if (retrying) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "ocr_models"
        private const val MAX_ATTEMPTS = 8

        /** After the choice changes, and at app start (a download may have been cut off). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OcrModelWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
