package com.ghostgramlabs.pettibox.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Loads an image for text recognition at a bounded resolution. Decoding at
 * full size makes a modern phone photo a 100–200 MB bitmap in our process,
 * which is an OOM on low-RAM devices. Text recognition gains nothing above
 * ~2K pixels, so oversized images are decoded with an `inSampleSize` that
 * caps the long edge, and turned upright by their EXIF rotation so sideways
 * photos still read correctly (Tesseract, unlike ML Kit, takes no rotation).
 */
object OcrImageLoader {

    /** Long-edge cap. Plenty for OCR; ML Kit's own minimum is far lower. */
    private const val MAX_DIMENSION = 2048

    /** An upright ARGB_8888 bitmap the caller recycles, or null if it can't be decoded. */
    fun loadBitmap(ctx: Context, uri: Uri): Bitmap? {
        val resolver = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)

        // Decoded at the largest halving still at least the cap; scaled down the rest of the way below.
        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= MAX_DIMENSION) sampleSize *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        // Upright, and within the size cap (sampling only halves, so it can leave up to twice the cap).
        val matrix = orientationMatrix(orientation)
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        val upright = if (matrix.isIdentity) bitmap else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
        // Tesseract reads only ARGB_8888; some decodes come back as another config.
        if (upright.config == Bitmap.Config.ARGB_8888) return upright
        return upright.copy(Bitmap.Config.ARGB_8888, false).also { upright.recycle() }
    }

    /**
     * The transform that turns a picture stored with this EXIF orientation
     * upright: all eight, the mirrored ones (as front cameras write) included.
     */
    internal fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> {}
        }
    }
}
