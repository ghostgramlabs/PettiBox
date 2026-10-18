package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.AttachmentEntity
import com.ghostgramlabs.pettibox.data.local.OcrStatusEntity
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import com.ghostgramlabs.pettibox.data.repository.SaveRepository
import com.ghostgramlabs.pettibox.data.util.AttachmentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.Character.UnicodeScript

/**
 * The reading jobs as they run in the app (real engines, a fresh database):
 * what's recorded when there's no text or the file can't be opened, that a
 * stale reading can't overwrite newer text, that repeats don't double text,
 * that a raised PDF limit reaches saved PDFs, and that a re-read restarted
 * midway still serves a newer request.
 */
@RunWith(AndroidJUnit4::class)
class OcrStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: SaveRepository
    private val prefs = OcrPreferences(context)
    private val reader = TextReader(context, OcrModels(context), prefs)

    // These run in the app's own settings: leave them as they were.
    private var languagesBefore: Set<String> = emptySet()
    private var pagesBefore = OcrPreferences.DEFAULT_PDF_PAGES

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = SaveRepository(db, db.saveDao(), db.categoryDao(), db.attachmentDao(), db.tagDao(), db.articleDao(), AttachmentStore(context))
        languagesBefore = prefs.languages.first()
        pagesBefore = prefs.pdfPageLimit.first()
        prefs.setLanguages(emptySet()) // Latin only: fast, and needs no downloads
        reader.refresh()
        clearReread()
    }

    @After fun tearDown() = runBlocking {
        clearReread()
        prefs.setLanguages(languagesBefore)
        prefs.setPdfPageLimit(pagesBefore)
        db.close()
    }

    private suspend fun clearReread() {
        while (true) {
            val job = prefs.reread() ?: return
            if (prefs.finishReread(job.request) == null) return
        }
    }

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
            when (workerClassName) {
                OcrWorker::class.java.name -> OcrWorker(appContext, workerParameters, repo, reader, prefs)
                OcrRereadWorker::class.java.name -> OcrRereadWorker(appContext, workerParameters, prefs, repo, reader)
                else -> null
            }
    }

    private fun readOnce(itemId: Long, uri: String, attachmentId: Long? = null, pdf: Boolean = false) = runBlocking {
        TestListenableWorkerBuilder<OcrWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(
                workDataOf(
                    OcrWorker.KEY_ITEM_ID to itemId, OcrWorker.KEY_URI to uri,
                    OcrWorker.KEY_ATTACHMENT_ID to (attachmentId ?: -1L), OcrWorker.KEY_PDF to pdf
                )
            )
            .build().doWork()
    }

    private fun picture(vararg lines: String): String {
        val bmp = Bitmap.createBitmap(1200, 120 + lines.size * 90, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 56f }
            lines.forEachIndexed { i, l -> drawText(l, 40f, 100f + i * 90, paint) }
        }
        val f = File(context.cacheDir, "state-${System.nanoTime()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.fromFile(f).toString()
    }

    private suspend fun image(uri: String) = repo.insert(
        SaveItemEntity(title = "img", contentType = "IMAGE", sourceApp = "TEST", localUri = uri)
    )

    private suspend fun states(itemId: Long) = repo.observeReadingStates(itemId).first().associate { it.source to it.state }

    @Test fun a_file_that_cant_be_opened_is_recorded_not_passed_off_as_read() = runBlocking {
        val id = image("file:///nowhere/missing.png")
        readOnce(id, "file:///nowhere/missing.png")
        assertEquals(OcrStatusEntity.UNREADABLE, states(id)["item:$id"])
    }

    @Test fun a_picture_with_no_text_says_so() = runBlocking {
        val uri = picture()
        val id = image(uri)
        readOnce(id, uri)
        assertEquals(OcrStatusEntity.NO_TEXT, states(id)["item:$id"])
        assertNull(repo.getById(id)!!.ocrText)
    }

    @Test fun a_picture_with_text_is_stored_and_leaves_no_state() = runBlocking {
        val uri = picture("Grocery receipt total 420")
        val id = image(uri)
        repo.setReadingState(id, null, uri, false, OcrStatusEntity.PENDING) // as a download notes it
        readOnce(id, uri)
        assertTrue(repo.getById(id)!!.ocrText.orEmpty().contains("Grocery"))
        assertTrue(states(id).isEmpty())
    }

    @Test fun a_stale_reading_cant_overwrite_newer_text() = runBlocking {
        val id = image("file:///x.png")
        val latin = setOf(UnicodeScript.LATIN)
        // Two jobs both compared against "no text"; the full reading lands first.
        assertTrue(repo.acceptSourceText(id, null, "Invoice reference ALPHA paid on Monday", latin))
        assertFalse(repo.acceptSourceText(id, null, "Invoice", latin))
        assertEquals("Invoice reference ALPHA paid on Monday", repo.getById(id)!!.ocrText)
    }

    @Test fun reading_a_file_twice_doesnt_double_the_saves_text() = runBlocking {
        val id = image("file:///x.png")
        val a1 = db.attachmentDao().insert(AttachmentEntity(itemId = id, uri = "file:///a.png", kind = "IMAGE", sortOrder = 0))
        val a2 = db.attachmentDao().insert(AttachmentEntity(itemId = id, uri = "file:///b.png", kind = "IMAGE", sortOrder = 1))
        val latin = setOf(UnicodeScript.LATIN)
        repo.acceptSourceText(id, a2, "second picture words", latin)
        repo.acceptSourceText(id, a1, "first picture words", latin)
        repo.acceptSourceText(id, a1, "first picture words", latin) // again
        assertEquals("first picture words\n\nsecond picture words", repo.getById(id)!!.ocrText)
    }

    @Test fun raising_the_pdf_limit_reads_the_extra_pages_of_saved_pdfs() = runBlocking {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
        for (n in 1..25) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, n).create())
            page.canvas.drawText(if (n == 20) "Page twenty says ZEBRA crossing" else "Page number $n of the report", 60f, 120f, paint)
            pdf.finishPage(page)
        }
        val f = File(context.cacheDir, "long-${System.nanoTime()}.pdf")
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        val uri = Uri.fromFile(f).toString()
        val id = repo.insert(SaveItemEntity(title = "pdf", contentType = "PDF", sourceApp = "TEST", localUri = uri))

        prefs.setPdfPageLimit(10)
        readOnce(id, uri, pdf = true)
        assertFalse(repo.getById(id)!!.ocrText.orEmpty().contains("ZEBRA"))

        prefs.setPdfPageLimit(30) // as Settings does when the limit goes up
        prefs.requestReread(pdfOnly = true)
        TestListenableWorkerBuilder<OcrRereadWorker>(context).setWorkerFactory(factory).build().doWork()
        assertTrue(repo.getById(id)!!.ocrText.orEmpty().contains("ZEBRA"))
        assertNull(prefs.reread()) // finished
    }

    @Test fun a_reread_restarted_midway_still_serves_a_newer_request() = runBlocking {
        prefs.requestReread(pdfOnly = true) // round 1: PDFs
        prefs.setRereadCursor(100) // got some way
        prefs.requestReread() // round 2 asked for: everything
        // The job is stopped and a new one starts: it goes on with round 1.
        val resumed = prefs.reread()!!
        assertEquals(100L, resumed.cursor)
        assertTrue(resumed.pdfOnly)
        // At the end of round 1, round 2 starts from the newest save, covering everything.
        val next = prefs.finishReread(resumed.request)!!
        assertEquals(Long.MAX_VALUE, next.cursor)
        assertFalse(next.pdfOnly)
        assertNull(prefs.finishReread(next.request))
        assertNull(prefs.reread())
    }
}
