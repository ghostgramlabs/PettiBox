package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * PDFs are read by [OcrWorker] now (pages drawn and read like images, see
 * [TextReader.readPdf]). This stays so jobs an earlier version queued still
 * run: each is handed over to [OcrWorker].
 */
@HiltWorker
class PdfTextWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        val uri = inputData.getString(KEY_URI) ?: return Result.failure()
        if (itemId <= 0L) return Result.failure()
        enqueue(ctx, itemId, uri, inputData.getLong(KEY_ATTACHMENT_ID, -1L).takeIf { it > 0 })
        return Result.success()
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_ATTACHMENT_ID = "attachment_id"
        const val KEY_URI = "uri"

        fun enqueue(ctx: Context, itemId: Long, uri: String, attachmentId: Long? = null) =
            OcrWorker.enqueue(ctx, itemId, uri, attachmentId, pdf = true)
    }
}
