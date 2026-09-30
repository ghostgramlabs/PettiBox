package com.ghostgramlabs.pettibox.data.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.util.AttachmentStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Copies a link's preview image (og:image / video thumbnail) onto the
 * phone. Saved as a web address it only showed while the image cache
 * happened to hold it, so offline — or weeks later — the card went blank.
 * Downscaled to a card-sized JPEG, so each is tens of KB.
 */
@HiltWorker
class ThumbnailWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val saveDao: SaveDao,
    private val attachmentStore: AttachmentStore
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val saveId = inputData.getLong(KEY_SAVE_ID, -1L)
        val remote = saveDao.getById(saveId)?.thumbnailUri
        if (remote == null || !remote.startsWith("http")) return Result.success()

        val bytes = try {
            download(remote)
        } catch (e: IOException) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        } ?: return Result.success()
        val jpeg = shrink(bytes) ?: return Result.success()
        val local = attachmentStore.saveBytes(jpeg, "jpg") ?: return Result.success()
        // Only swap if the save still points at the image we downloaded.
        if (saveDao.replaceThumbnail(saveId, remote, local) == 0) attachmentStore.deleteByUris(listOf(local))
        return Result.success()
    }

    private fun download(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) PettiBox")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun shrink(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = if (bitmap.width > TARGET_WIDTH) {
            Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, bitmap.height * TARGET_WIDTH / bitmap.width, true)
        } else bitmap
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        private const val KEY_SAVE_ID = "save_id"
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val TARGET_WIDTH = 720

        fun enqueue(context: Context, saveId: Long) {
            val request = OneTimeWorkRequestBuilder<ThumbnailWorker>()
                .setInputData(workDataOf(KEY_SAVE_ID to saveId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("thumbnail_$saveId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
