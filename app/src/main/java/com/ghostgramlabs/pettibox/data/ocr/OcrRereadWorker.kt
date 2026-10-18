package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
    private val reader: TextReader
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        var job = prefs.reread() ?: return Result.success()
        reader.refresh()
        val started = System.currentTimeMillis()
        var pages = prefs.pdfPageLimit.first()
        var cursor = job.cursor
        while (System.currentTimeMillis() - started < RUN_MS) {
            val batch = repository.withTextSourcesBelow(cursor, BATCH)
            if (batch.isEmpty()) {
                // Done, unless another request came in meanwhile: then once more, with it.
                job = prefs.finishReread(job.request) ?: return Result.success()
                // The newer request may be for a higher page limit, or a new language.
                reader.refresh()
                pages = prefs.pdfPageLimit.first()
                cursor = job.cursor
                continue
            }
            for (item in batch) {
                if (isStopped) return Result.success() // the cursor says where to go on
                reread(item, pages, pdfOnly = job.pdfOnly)
                cursor = item.id
                prefs.setRereadCursor(cursor)
            }
        }
        enqueue(applicationContext)
        return Result.success()
    }

    private suspend fun reread(item: SaveItemEntity, pages: Int, pdfOnly: Boolean) {
        val attachments = repository.attachmentsOf(item.id)
        if (attachments.isEmpty()) {
            if (pdfOnly && item.contentType != "PDF") return
            val uri = item.localUri ?: return
            val text = read(uri, item.contentType, pages) ?: return
            repository.acceptSourceText(item.id, null, text, reader.readableScripts())
            return
        }
        for (a in attachments) {
            if (a.kind != "PDF" && (pdfOnly || a.kind != "IMAGE")) continue
            val text = read(a.uri, a.kind, pages) ?: continue
            repository.acceptSourceText(item.id, a.id, text, reader.readableScripts())
        }
    }

    /** The text in one file, or null if it can't be read now. */
    private suspend fun read(uri: String, kind: String, pages: Int): String? {
        if (uri.startsWith("pettibox-sync:")) return null // not on this phone yet; read when it arrives
        return try {
            when (kind) {
                "IMAGE" -> reader.readImage(Uri.parse(uri))
                "PDF" -> reader.readPdf(Uri.parse(uri), pages)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PettiBoxOcr", "re-reading $uri failed", e)
            null
        }
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
