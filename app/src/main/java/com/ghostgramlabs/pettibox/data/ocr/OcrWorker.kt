package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCRs a single image. If [KEY_ATTACHMENT_ID] is present, the result is
 * written to that attachment AND merged into the parent item's ocr_text so
 * FTS picks it up. Otherwise the result is written directly on the item.
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val repository: SaveRepository
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        val attachmentId = inputData.getLong(KEY_ATTACHMENT_ID, -1L)
        val uriStr = inputData.getString(KEY_URI) ?: return Result.failure()
        if (itemId <= 0L) return Result.failure()

        return runCatching {
            val image = OcrImageLoader.load(ctx, Uri.parse(uriStr))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val text = suspendCancellableCoroutine<String> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            if (text.isNotBlank()) {
                if (attachmentId > 0L) {
                    repository.setAttachmentOcr(attachmentId, text)
                    // Merge atomically into parent so concurrent OCR workers
                    // for multi-image saves cannot overwrite each other.
                    repository.appendOcrText(itemId, text)
                } else {
                    repository.setOcrText(itemId, text)
                }
            }
            Result.success()
        }.getOrElse {
            // OCR is best-effort — don't loop on failure.
            Result.success()
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_ATTACHMENT_ID = "attachment_id"
        const val KEY_URI = "uri"

        fun enqueueForItem(ctx: Context, itemId: Long, uri: String) {
            val req = OneTimeWorkRequestBuilder<OcrWorker>()
                .addTag(OcrWorkTags.TEXT_INDEXING)
                .setInputData(workDataOf(KEY_ITEM_ID to itemId, KEY_URI to uri))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }

        fun enqueueForAttachment(ctx: Context, itemId: Long, attachmentId: Long, uri: String) {
            val req = OneTimeWorkRequestBuilder<OcrWorker>()
                .addTag(OcrWorkTags.TEXT_INDEXING)
                .setInputData(
                    workDataOf(
                        KEY_ITEM_ID to itemId,
                        KEY_ATTACHMENT_ID to attachmentId,
                        KEY_URI to uri
                    )
                )
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
