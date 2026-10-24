package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostgramlabs.pettibox.data.preferences.OcrPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real models on a real (emulated) phone: each language's model is fetched
 * (network needed), then a picture of a sentence in it is read back.
 */
@RunWith(AndroidJUnit4::class)
class OcrLanguagesDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = OcrPreferences(context)
    private val models = OcrModels(context)
    private val reader = TextReader(context, models, prefs)

    // These tests run in the app's own storage: leave its language choice as it was.
    private var before: Set<String>? = null
    @Before fun remember() = runBlocking { before = prefs.languages.first() }
    @After fun restore() = runBlocking { before?.let { prefs.setLanguages(it) }; Unit }

    private fun picture(lines: List<String>): Uri {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 56f }
        val bmp = Bitmap.createBitmap(1400, 120 + lines.size * 90, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            lines.forEachIndexed { i, l -> drawText(l, 40f, 100f + i * 90, paint) }
        }
        val f = File(context.cacheDir, "ocr-test-${System.nanoTime()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.fromFile(f)
    }

    private fun read(on: Set<String>, lines: List<String>): String = runBlocking {
        prefs.setLanguages(on)
        for (code in on) assertTrue("model for $code didn't arrive", models.fetch(OcrLanguages.of(code)!!))
        reader.refresh()
        val text = reader.readImage(picture(lines)).text
        Log.i("OcrDeviceTest", "$on → $text")
        text
    }

    private fun check(code: String, word: String, vararg lines: String) {
        val text = read(setOf(code), lines.toList())
        assertTrue("$code: expected \"$word\" in \"$text\"", text.contains(word))
    }

    @Test fun malayalam() = check("ml", "മലയാളം", "മലയാളം ഭാഷ കേരളത്തിൽ", "നാളെ രാവിലെ പത്തു മണിക്ക്")
    @Test fun tamil() = check("ta", "தமிழ்", "தமிழ் மொழி மிகவும் பழமையானது")
    @Test fun telugu() = check("te", "తెలుగు", "తెలుగు భాష చాలా అందమైనది")
    @Test fun kannada() = check("kn", "ಕನ್ನಡ", "ಕನ್ನಡ ಭಾಷೆ ಕರ್ನಾಟಕದಲ್ಲಿ")
    @Test fun bengali() = check("bn", "বাংলা", "বাংলা ভাষা খুব সুন্দর")
    @Test fun russian() = check("ru", "Привет", "Привет, мир! Встреча завтра", "в десять часов утра")
    @Test fun arabic() = check("ar", "مرحبا", "مرحبا بكم في المدينة")
    @Test fun hindi() = check("hi", "भारत", "हिन्दी भारत की भाषा है", "कल सुबह दस बजे मिलेंगे")
    @Test fun chinese() = check("zh", "天气", "今天天气很好", "我们去公园散步")
    @Test fun japanese() = check("ja", "東京", "東京はとても大きい都市です")
    @Test fun korean() = check("ko", "한국어", "한국어 공부는 재미있어요")
    @Test fun thai() = check("th", "ภาษา", "ภาษาไทยสวยงามมาก")

    /** Short labels are kept, not thrown out for being few letters. */
    @Test fun a_short_chinese_label() = check("zh", "天气", "天气")
    @Test fun a_short_russian_word() = check("ru", "Привет", "Привет")

    /** A page in two scripts keeps both. */
    @Test fun hindi_and_chinese_on_one_page_keep_both() {
        val text = read(setOf("hi", "zh"), listOf("हिन्दी भारत की भाषा है", "कल सुबह दस बजे मिलेंगे", "今天天气很好", "我们去公园散步"))
        assertTrue("got \"$text\"", text.contains("भारत") && text.contains("天气"))
    }

    @Test fun an_english_receipt_with_one_tamil_line_keeps_both() {
        val text = read(setOf("ta"), listOf("Grocery receipt total 420", "தமிழ் மொழி மிகவும் பழமையானது", "Thank you for shopping"))
        assertTrue("got \"$text\"", text.contains("Grocery") && text.contains("Thank") && text.contains("தமிழ்"))
    }

    /** All eight EXIF orientations, mirrored ones included, come out upright. */
    @Test fun every_exif_orientation_is_turned_upright() {
        // Upright: red top-left quarter, the rest blue. Stored pictures made pixel by pixel from the EXIF definitions.
        val w = 60; val h = 30
        fun upright(x: Int, y: Int) = if (x < w / 2 && y < h / 2) Color.RED else Color.BLUE
        val stored: Map<Int, Triple<Int, Int, (Int, Int) -> Int>> = mapOf(
            ExifInterface.ORIENTATION_NORMAL to Triple(w, h) { x, y -> upright(x, y) },
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to Triple(w, h) { x, y -> upright(w - 1 - x, y) },
            ExifInterface.ORIENTATION_ROTATE_180 to Triple(w, h) { x, y -> upright(w - 1 - x, h - 1 - y) },
            ExifInterface.ORIENTATION_FLIP_VERTICAL to Triple(w, h) { x, y -> upright(x, h - 1 - y) },
            ExifInterface.ORIENTATION_TRANSPOSE to Triple(h, w) { x, y -> upright(y, x) },
            ExifInterface.ORIENTATION_ROTATE_90 to Triple(h, w) { x, y -> upright(w - 1 - y, x) }, // stored turned left; shown turned right
            ExifInterface.ORIENTATION_TRANSVERSE to Triple(h, w) { x, y -> upright(w - 1 - y, h - 1 - x) },
            ExifInterface.ORIENTATION_ROTATE_270 to Triple(h, w) { x, y -> upright(y, h - 1 - x) },
        )
        for ((orientation, spec) in stored) {
            val (sw, sh, pixel) = spec
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            for (x in 0 until sw) for (y in 0 until sh) bmp.setPixel(x, y, pixel(x, y))
            val f = File(context.cacheDir, "exif-$orientation.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bmp.recycle()
            ExifInterface(f.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val loaded = OcrImageLoader.loadBitmap(context, Uri.fromFile(f))!!
            fun isRed(x: Int, y: Int) = loaded.getPixel(x, y).let { Color.red(it) > Color.blue(it) }
            assertTrue("orientation $orientation: size ${loaded.width}x${loaded.height}", loaded.width == w && loaded.height == h)
            assertTrue("orientation $orientation: top-left", isRed(5, 5))
            assertTrue("orientation $orientation: others", !isRed(w - 5, 5) && !isRed(5, h - 5) && !isRed(w - 5, h - 5))
            loaded.recycle()
        }
    }

    /** A picture in a script none of the turned-on languages read must not come out in theirs. */
    @Test fun an_unknown_script_isnt_read_as_one_turned_on() {
        val on = setOf("ml", "ru", "ta")
        for (lines in listOf(
            listOf("हिन्दी भारत की भाषा है", "कल सुबह दस बजे मिलेंगे"),
            listOf("今天天气很好", "我们去公园散步"),
            listOf("ภาษาไทยสวยงามมาก"),
            listOf("Grocery receipt total 420", "Thank you for shopping")
        )) {
            val text = read(on, lines)
            val wrong = text.codePoints().filter {
                Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.TAMIL)
            }.count()
            assertTrue("$lines came out as \"$text\"", wrong == 0L)
        }
    }

    /** With several languages on, each picture comes out in its own script, and English stays English. */
    @Test fun several_on_at_once_each_picture_reads_in_its_own_script() {
        val on = setOf("ml", "ru", "hi")
        assertTrue(read(on, listOf("മലയാളം ഭാഷ കേരളത്തിൽ", "നാളെ രാവിലെ")).contains("മലയാളം"))
        assertTrue(read(on, listOf("Привет, мир! Встреча завтра")).contains("Привет"))
        val english = read(on, listOf("Grocery receipt total 420", "Thank you for shopping"))
        assertTrue("English came out as \"$english\"", english.contains("Grocery") && english.contains("Thank"))
    }
}
