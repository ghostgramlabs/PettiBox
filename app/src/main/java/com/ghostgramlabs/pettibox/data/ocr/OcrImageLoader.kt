package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage

/**
 * Loads an image for ML Kit at a bounded resolution. `InputImage.fromFilePath`
 * decodes at full size — a modern phone photo becomes a 100–200 MB ARGB bitmap
 * in our process, which is an OOM on low-RAM devices. Text recognition gains
 * nothing above ~2K pixels, so oversized images are decoded with an
 * `inSampleSize` that caps the long edge, and EXIF rotation is passed through
 * so sideways photos still read correctly.
 */
object OcrImageLoader {

    /** Long-edge cap. Plenty for OCR; ML Kit's own minimum is far lower. */
    private const val MAX_DIMENSION = 2048

    fun load(ctx: Context, uri: Uri): InputImage {
        val resolver = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

        // Small enough already (or bounds unreadable — let ML Kit's own
        // decoder have a go and fail there if it must).
        if (longEdge <= MAX_DIMENSION) return InputImage.fromFilePath(ctx, uri)

        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= MAX_DIMENSION) sampleSize *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return InputImage.fromFilePath(ctx, uri)

        // fromFilePath applies EXIF rotation itself; fromBitmap needs it
        // handed over or portrait photos OCR sideways and return garbage.
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180,
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0

        return InputImage.fromBitmap(bitmap, rotation)
    }
}
