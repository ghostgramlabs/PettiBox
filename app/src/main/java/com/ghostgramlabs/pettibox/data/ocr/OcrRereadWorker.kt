package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reads saved images and PDFs again, newest first: all of them after a
 * language was turned on, or only PDFs after the page limit was raised. A
 * job may run for 10 minutes, so it stops after a few, remembers where it
 * got to ([OcrPreferences.rereadCursor]) and carries on in the next. A
 * request made meanwhile is seen when it reaches the end
 * ([OcrPreferences.finishReread]) and it goes round once more. A new
 * reading replaces the old text only when it's better ([TextReader.improves]).
 */
@HiltWorker
class OcrRereadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val prefs: OcrPreferences,
    private val repository: SaveRepository,
    private val reader: TextReader,
    private val sources: SourceReader
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        var job = prefs.reread() ?: return Result.success()
        reader.refresh()
        val started = System.currentTimeMillis()
        var cursor = job.cursor
        while (System.currentTimeMillis() - started < RUN_MS) {
            val batch = repository.withTextSourcesBelow(cursor, BATCH)
            if (batch.isEmpty()) {
                // Done, unless another request came in meanwhile: then once more, with it.
                job = prefs.finishReread(job.request) ?: return Result.success()
                // The newer request may be for a higher page limit (read per file), or a new language.
                reader.refresh()
                cursor = job.cursor
                continue
            }
            for (item in batch) {
                if (isStopped) return Result.success() // the cursor says where to go on
                // Out of time partway through a long PDF: its pages so far are saved,
                // and the next job carries on from there (the cursor stays before it).
                if (!reread(item, pdfOnly = job.pdfOnly, stop = { System.currentTimeMillis() - started > RUN_MS })) break
                cursor = item.id
                prefs.setRereadCursor(cursor)
            }
        }
        enqueue(applicationContext)
        return Result.success()
    }

    /** Re-reads a save's images and PDFs; false if a PDF paused partway (to carry on next job). */
    private suspend fun reread(item: SaveItemEntity, pdfOnly: Boolean, stop: () -> Boolean): Boolean {
        val attachments = repository.attachmentsOf(item.id)
        val files = if (attachments.isEmpty()) {
            listOfNotNull(item.localUri?.takeIf { item.contentType == "IMAGE" || item.contentType == "PDF" }
                ?.let { Triple(null as Long?, it, item.contentType == "PDF") })
        } else {
            attachments.filter { it.kind == "IMAGE" || it.kind == "PDF" }.map { Triple(it.id as Long?, it.uri, it.kind == "PDF") }
        }
        for ((attachmentId, uri, pdf) in files) {
            if (pdfOnly && !pdf) continue
            if (uri.startsWith("pettibox-sync:")) continue // not on this phone yet; read when it arrives
            when (sources.read(item.id, attachmentId, uri, pdf, lastTry = false, stop = stop)) {
                SourceReader.Outcome.PAUSED -> return false
                // Not skipped quietly: an ordinary reading job tries it again with
                // backoff, and records "couldn't read" if it keeps failing.
                SourceReader.Outcome.RETRY -> OcrWorker.enqueue(applicationContext, item.id, uri, attachmentId, pdf)
                SourceReader.Outcome.DONE -> {}
            }
        }
        return true
    }

    companion object {
        private const val NAME = "ocr_reread"
        private const val BATCH = 20
        /**
         * When to stop starting new saves: a 50-page PDF in several languages
         * can take some minutes, and must finish inside the 10 a job is given
         * (cut off, it would start again from its first page).
         */
        private const val RUN_MS = 4 * 60_000L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OcrRereadWorker>()
                .addTag(OcrWorkTags.TEXT_INDEXING)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
