package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ghostgramlabs.pettibox.data.local.OcrStatusEntity
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Reads the text in one image or PDF: a save's own file, or (with
 * [KEY_ATTACHMENT_ID]) one of the files added to it, whose text then makes
 * up the save's with the others ([SaveRepository.setSourceText]).
 *
 * One job per file at a time ([enqueue] replaces a waiting one), and its
 * result only replaces existing text when it's better
 * ([TextReader.improves]), so asking twice, retrying, or a file arriving
 * from another phone never doubles or worsens the text. A file that can't be
 * opened has nothing to read; an engine failure is tried again a few times.
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val repository: SaveRepository,
    private val reader: TextReader,
    private val ocrPreferences: OcrPreferences
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        val attachmentId = inputData.getLong(KEY_ATTACHMENT_ID, -1L).takeIf { it > 0 }
        val uri = inputData.getString(KEY_URI) ?: return Result.failure()
        val pdf = inputData.getBoolean(KEY_PDF, false)
        if (itemId <= 0L) return Result.failure()

        // How it went is kept per file (ocr_status), so the save can say "no text"
        // or "couldn't read" and offer Try again, instead of looking finished.
        suspend fun state(value: String?) = repository.setReadingState(itemId, attachmentId, uri, pdf, value)
        return try {
            val readable = reader.readableScripts()
            val text = if (pdf) reader.readPdf(Uri.parse(uri), ocrPreferences.pdfPageLimit.first())
            else reader.readImage(Uri.parse(uri))
            repository.acceptSourceText(itemId, attachmentId, text, readable)
            state(if (repository.sourceHasText(itemId, attachmentId)) null else OcrStatusEntity.NO_TEXT)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnreadableSourceException) {
            Log.i(TAG, "nothing to read: ${e.message}")
            state(OcrStatusEntity.UNREADABLE) // any text it had stays
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "reading text failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else {
                state(OcrStatusEntity.FAILED) // any text it had stays
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "PettiBoxOcr"
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
