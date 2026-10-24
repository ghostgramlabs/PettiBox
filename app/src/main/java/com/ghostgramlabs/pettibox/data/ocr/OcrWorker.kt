package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reads the text in one image or PDF: a save's own file, or (with
 * [KEY_ATTACHMENT_ID]) one of the files added to it, whose text then makes
 * up the save's with the others ([com.ghostgramlabs.pettibox.data.repository.SaveRepository.acceptSourceText]).
 *
 * One job per file at a time ([enqueue] replaces a waiting one). The reading
 * itself, what's stored and what's recorded is [SourceReader]: asking twice,
 * retrying, or a file arriving from another phone never doubles or loses
 * text. An engine failure is tried again a few times, then recorded; a long
 * PDF stopped midway carries on from its next page.
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val sources: SourceReader
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        val attachmentId = inputData.getLong(KEY_ATTACHMENT_ID, -1L).takeIf { it > 0 }
        val uri = inputData.getString(KEY_URI) ?: return Result.failure()
        val pdf = inputData.getBoolean(KEY_PDF, false)
        if (itemId <= 0L) return Result.failure()

        return when (sources.read(itemId, attachmentId, uri, pdf, lastTry = runAttemptCount >= MAX_ATTEMPTS)) {
            SourceReader.Outcome.RETRY -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_ATTACHMENT_ID = "attachment_id"
        const val KEY_URI = "uri"
        const val KEY_PDF = "pdf"
        private const val MAX_ATTEMPTS = 3

        /**
         * Reads a file's text: the save's own ([attachmentId] null) or one
         * added to it. [policy] KEEP leaves one already queued or running alone.
         */
        fun enqueue(ctx: Context, itemId: Long, uri: String, attachmentId: Long?, pdf: Boolean, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val req = OneTimeWorkRequestBuilder<OcrWorker>()
                .addTag(OcrWorkTags.TEXT_INDEXING)
                .setInputData(
                    workDataOf(
                        KEY_ITEM_ID to itemId,
                        KEY_ATTACHMENT_ID to (attachmentId ?: -1L),
                        KEY_URI to uri,
                        KEY_PDF to pdf
                    )
                )
                .build()
            // One reading per file at a time: a newer request replaces one still waiting.
            WorkManager.getInstance(ctx).enqueueUniqueWork("ocr-$itemId-${attachmentId ?: 0}", policy, req)
        }

        fun enqueueForItem(ctx: Context, itemId: Long, uri: String) = enqueue(ctx, itemId, uri, null, pdf = false)

        fun enqueueForAttachment(ctx: Context, itemId: Long, attachmentId: Long, uri: String) =
            enqueue(ctx, itemId, uri, attachmentId, pdf = false)
    }
}
