package com.ghostgramlabs.pettibox.data.ocr

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: puts pictures of text in several scripts in the gallery, for
 * trying text recognition by hand (share them into PettiBox). Runs only when
 * asked: -Pandroid.testInstrumentationRunnerArguments.writeSamples=true
 */
@RunWith(AndroidJUnit4::class)
class SamplePicturesTool {
    /** Puts the "clip" argument on the clipboard, to paste text adb can't type (any non-ASCII). */
    @Test fun setClipboard() {
        val clip = InstrumentationRegistry.getArguments().getString("clip")
        assumeTrue(clip != null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("test", clip))
        }
    }

    @Test fun writeSamplePictures() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("writeSamples") == "true")
        val samples = mapOf(
            "sample-malayalam" to listOf("മലയാളം ഭാഷ കേരളത്തിൽ", "ഓണം സദ്യ നാളെ ഉച്ചയ്ക്ക്"),
            "sample-hindi" to listOf("हिन्दी भारत की भाषा है", "कल सुबह दस बजे मिलेंगे"),
            "sample-russian" to listOf("Привет, мир! Встреча завтра", "в десять часов утра"),
            "sample-chinese" to listOf("今天天气很好", "我们去公园散步"),
        )
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        for ((name, lines) in samples) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 56f }
            val bmp = Bitmap.createBitmap(1200, 120 + lines.size * 90, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                lines.forEachIndexed { i, l -> drawText(l, 40f, 100f + i * 90, paint) }
            }
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
            )!!
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
    }
}
