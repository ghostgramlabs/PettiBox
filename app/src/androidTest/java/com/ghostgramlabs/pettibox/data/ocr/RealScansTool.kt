package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a check: a tool for real scans. Copy pictures (each made into a
 * one-page image-only PDF, as a scanner gives) or PDFs into the app's
 * `files/ocr-samples` folder (`adb shell run-as`); each is read with the
 * languages named in `languages.txt` there (default: ru) and its text
 * written beside it as `<name>.out.txt`. Runs only when asked
 * (`am instrument -e realScans true`, optionally `-e only <file>`, and
 * `-e asImage true` to read pictures as photos rather than scanned PDFs).
 */
@RunWith(AndroidJUnit4::class)
class RealScansTool {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun read_the_pushed_scans() = runBlocking {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        assumeTrue("run only on request: -e realScans true", args.getString("realScans") == "true")
        val dir = File(context.filesDir, "ocr-samples")
        assumeTrue("no samples pushed", dir.isDirectory)
        val prefs = OcrPreferences(context)
        val models = OcrModels(context)
        val reader = TextReader(context, models, prefs)
        val before = prefs.languages.first()
        val langs = File(dir, "languages.txt").takeIf { it.exists() }?.readText()?.trim()?.split(",")?.toSet() ?: setOf("ru")
        try {
            prefs.setLanguages(langs)
            for (code in langs) check(models.fetch(OcrLanguages.of(code)!!)) { "model for $code didn't arrive" }
            reader.refresh()
            // `-e only <file name>` reads just that one.
            val only = args.getString("only")
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val ext = f.extension.lowercase()
                if (ext !in setOf("jpg", "jpeg", "png", "pdf") || (only != null && f.name != only)) continue
                val started = System.currentTimeMillis()
                // `-e asImage true`: pictures read as a saved photo is, not as a scanned PDF.
                val text = if (ext != "pdf" && args.getString("asImage") == "true") reader.readImage(Uri.fromFile(f)).text
                else reader.readPdf(Uri.fromFile(if (ext == "pdf") f else imagePdf(f)), pageLimit = 10).text
                File(dir, "${f.nameWithoutExtension}.out.txt").writeText(text)
                Log.i("RealScans", "${f.name}: ${text.length} chars in ${System.currentTimeMillis() - started} ms")
            }
        } finally {
            prefs.setLanguages(before)
        }
    }

    /** A picture as a one-page A4 PDF holding only that image. */
    private fun imagePdf(image: File): File {
        val bmp = BitmapFactory.decodeFile(image.absolutePath)
        val w = 595; val h = (595f * bmp.height / bmp.width).toInt()
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        page.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
        bmp.recycle()
        val out = File(context.cacheDir, "${image.nameWithoutExtension}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
