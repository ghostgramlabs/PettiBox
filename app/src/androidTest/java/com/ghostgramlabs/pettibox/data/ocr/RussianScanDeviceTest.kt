package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * A scanned Russian official letter (an image-only PDF, as a tester sent):
 * an all-caps letterhead on the left, the addressee on the right, then the
 * body. Read with Russian on, it must come out in Cyrillic (not Latin
 * look-alikes such as "MHHHCTEPCTBO"), with each block kept together.
 */
@RunWith(AndroidJUnit4::class)
class RussianScanDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = OcrPreferences(context)
    private val models = OcrModels(context)
    private val reader = TextReader(context, models, prefs)

    private var before: Set<String>? = null
    @Before fun remember() = runBlocking { before = prefs.languages.first() }
    @After fun restore() = runBlocking { before?.let { prefs.setLanguages(it) }; Unit }

    private val letterhead = listOf("МИНИСТЕРСТВО ТРУДА", "И СОЦИАЛЬНОЙ ПОЛИТИКИ", "ПРИМОРСКОГО КРАЯ")
    private val address = listOf("ул. Светланская, д. 22, г. Владивосток, 690110", "тел. (423) 226-57-27", "от 14.10.2026 № 26/4815")
    private val addressee = listOf("Руководителю краевого", "государственного казённого", "учреждения", "Иванову И. И.")
    private val greeting = "Уважаемый Иван Иванович!"
    private val body = listOf(
        "В соответствии с пунктом 4 постановления Правительства Приморского края от 12 марта 2026 года " +
            "№ 118-пп министерство труда и социальной политики Приморского края направляет для исполнения " +
            "порядок предоставления ежемесячной денежной выплаты семьям с детьми.",
        "Просим в срок до 1 ноября 2026 года представить сведения о численности получателей, а также " +
            "информацию о сумме произведённых выплат за третий квартал текущего года.",
        "Приложение: на 3 л. в 1 экз."
    )
    private val signature = "Заместитель министра" to "А. В. Петрова"

    /** The page as a scanner at 200 dpi would give it: grey paper, a slight tilt, blur, noise, JPEG. */
    private fun scannedPage(tilt: Float, noise: Int, seed: Int): Bitmap {
        val w = 1654; val h = 2339 // A4 at 200 dpi
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        c.save(); c.rotate(tilt, w / 2f, h / 2f)
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f; typeface = Typeface.create(serif, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f; typeface = serif; textAlign = Paint.Align.CENTER }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 33f; typeface = serif }
        var y = 220f
        letterhead.forEach { c.drawText(it, 460f, y, bold); y += 46f }
        y += 14f
        address.forEach { c.drawText(it, 460f, y, small); y += 36f }
        var ry = 250f
        addressee.forEach { c.drawText(it, 980f, ry, text); ry += 48f }
        y = maxOf(y, ry) + 110f
        c.drawText(greeting, w / 2f, y, Paint(text).apply { textAlign = Paint.Align.CENTER }); y += 80f
        for (para in body) {
            var line = "      "
            for (word in para.split(" ")) {
                if (text.measureText("$line $word") > w - 340) { c.drawText(line, 190f, y, text); y += 48f; line = word } else line = if (line.isBlank()) "$line$word" else "$line $word"
            }
            c.drawText(line, 190f, y, text); y += 64f
        }
        y += 90f
        c.drawText(signature.first, 190f, y, text)
        c.drawText(signature.second, w - 190f - text.measureText(signature.second), y, text)
        c.restore()

        // Blur (down and up again), grey paper, speckle.
        val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(page, w * 2 / 3, h * 2 / 3, true), w, h, true)
        val px = IntArray(w * h); soft.getPixels(px, 0, w, 0, 0, w, h)
        val rnd = Random(seed)
        for (i in px.indices) {
            val v = (Color.red(px[i]) * 0.86 + 22 + rnd.nextInt(-noise, noise + 1)).toInt().coerceIn(0, 255)
            px[i] = Color.rgb(v, v, v)
        }
        soft.setPixels(px, 0, w, 0, 0, w, h)
        val jpg = File(context.cacheDir, "scan-$seed.jpg")
        jpg.outputStream().use { soft.compress(Bitmap.CompressFormat.JPEG, 55, it) }
        page.recycle(); soft.recycle()
        return android.graphics.BitmapFactory.decodeFile(jpg.absolutePath)
    }

    private fun scannedPdf(pages: List<Bitmap>): Uri {
        val pdf = PdfDocument()
        pages.forEachIndexed { i, bmp ->
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            p.canvas.drawBitmap(bmp, null, Rect(0, 0, 595, 842), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(p)
            bmp.recycle()
        }
        val f = File(context.cacheDir, "russian-scan-${System.nanoTime()}.pdf")
        f.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return Uri.fromFile(f)
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }

    /** Latin words in a reading of an all-Russian page: look-alike guesses such as "MHHHCTEPCTBO". */
    private fun latinWords(s: String) = s.split(Regex("[^\\p{L}]+")).filter { w ->
        w.length >= 3 && w.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
    }

    @Test fun a_scanned_russian_letter_reads_in_cyrillic_and_in_order() = runBlocking {
        prefs.setLanguages(setOf("ru"))
        assertTrue("Russian model didn't arrive", models.fetch(OcrLanguages.of("ru")!!))
        reader.refresh()
        val uri = scannedPdf(listOf(scannedPage(0.4f, 10, 1), scannedPage(-0.7f, 18, 2), scannedPage(0.9f, 24, 3)))
        val reading = reader.readPdf(uri, pageLimit = 10)
        val pages = reading.text.split("\n\n")
        val expected = words((letterhead + address + addressee + greeting + body + signature.toList()).joinToString(" "))

        var found = 0; var total = 0; var latin = 0; var orderOk = 0
        pages.forEachIndexed { i, page ->
            val got = words(page).toSet()
            val hit = expected.count { it in got }
            found += hit; total += expected.size
            val lat = latinWords(page); latin += lat.size
            // Each block whole and in reading order: the letterhead before the addressee, the addressee before the body.
            val lower = page.lowercase()
            val idx = listOf("министерство", "политики", "приморского края", "руководителю", "учреждения", "уважаемый", "приложение")
                .map { lower.indexOf(it) }
            val inOrder = idx.none { it < 0 } && idx.zipWithNext().all { (a, b) -> a < b }
            if (inOrder) orderOk++
            Log.i("RussianScan", "page ${i + 1}: words $hit/${expected.size}, latin ${lat.size} $lat, inOrder=$inOrder\n$page")
        }
        Log.i("RussianScan", "TOTAL words $found/$total, latin $latin, pages in order $orderOk/${pages.size}")
        assertTrue("only $found of $total words read", found >= total * 0.85)
        assertTrue("$latin Latin look-alike words", latin <= 2)
        assertTrue("only $orderOk of ${pages.size} pages in reading order", orderOk == pages.size)
    }
}
