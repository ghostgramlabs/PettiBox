package com.ghostgramlabs.pettibox.data.metadata

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Gives a link a picture when the site doesn't offer one, so it can be
 * spotted by eye later. First retries the site's own preview image (the
 * save may have gone through before it loaded); failing that, renders the
 * page in an off-screen WebView on the phone and keeps the top of it as a
 * card-sized JPEG. Nothing leaves the phone except the page load itself.
 *
 * With [KEY_FORCE] (the Detail screen's "Use a page screenshot") it skips
 * the preview image and replaces whatever the card shows now.
 */
@HiltWorker
class PageSnapshotWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val saveDao: SaveDao,
    private val attachmentStore: AttachmentStore,
    private val metadataFetcher: MetadataFetcher
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val saveId = inputData.getLong(KEY_SAVE_ID, -1L)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val save = saveDao.getById(saveId) ?: return Result.success()
        val url = save.url?.takeIf { it.startsWith("http") } ?: return Result.success()
        val before = save.thumbnailUri
        if (!force && before != null) return Result.success()

        if (!force) {
            val image = metadataFetcher.fetch(url)?.imageUrl?.takeIf { it.startsWith("http") }
            if (image != null) {
                if (saveDao.swapThumbnail(saveId, null, image) > 0) ThumbnailWorker.enqueue(applicationContext, saveId)
                return Result.success()
            }
        }

        val shot = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { capture(url) }
        }
        if (shot == null) return if (runAttemptCount < 1) Result.retry() else Result.success()
        // A page that drew nothing (blocked, still blank, or a WebView that
        // couldn't render off-screen) is worse than the plain link card.
        if (looksBlank(shot)) return Result.success()

        val jpeg = withContext(Dispatchers.Default) {
            val card = Bitmap.createScaledBitmap(shot, TARGET_WIDTH, shot.height * TARGET_WIDTH / shot.width, true)
            ByteArrayOutputStream().use { out ->
                card.compress(Bitmap.CompressFormat.JPEG, 80, out)
                if (card !== shot) card.recycle()
                out.toByteArray()
            }
        }
        shot.recycle()
        val local = attachmentStore.saveBytes(jpeg, "jpg") ?: return Result.success()
        // Only swap if the card still shows what it did when we started.
        if (saveDao.swapThumbnail(saveId, before, local) == 0) {
            attachmentStore.deleteByUris(listOf(local))
        } else if (before != null) {
            attachmentStore.deleteByUris(listOf(before))
        }
        return Result.success()
    }

    /**
     * Loads [url] in a WebView that's never shown and draws its first
     * screenful into a bitmap. WebView lays pages out in dp, so the view is
     * [VIEWPORT_DP] wide in this phone's pixels to get the page's normal
     * phone layout; the crop is 4:3, a little taller than most preview images.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun capture(url: String): Bitmap? = suspendCancellableCoroutine { cont ->
        val web = WebView(applicationContext)
        val width = (VIEWPORT_DP * applicationContext.resources.displayMetrics.density).toInt()
        val height = width * 3 / 4
        web.settings.apply {
            javaScriptEnabled = true // most pages draw nothing without it
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setBackgroundColor(Color.WHITE)
        web.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        web.layout(0, 0, width, height)

        // Not View.post: a view that's never attached to a window holds its
        // posted callbacks until it is, so they'd never run.
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var pendingShot: Runnable? = null
        fun finish(bitmap: Bitmap?) {
            if (finished) return
            finished = true
            web.stopLoading()
            web.destroy()
            if (cont.isActive) cont.resume(bitmap)
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loaded: String?) {
                // Give late images, fonts, and cookie banners' fade-outs a
                // moment. A redirect finishes more than once; the last wins.
                pendingShot?.let(handler::removeCallbacks)
                pendingShot = Runnable {
                    val bitmap = runCatching {
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            view.draw(Canvas(it))
                        }
                    }.getOrNull()
                    finish(bitmap)
                }.also { handler.postDelayed(it, SETTLE_MS) }
            }
        }
        cont.invokeOnCancellation {
            handler.post {
                pendingShot?.let(handler::removeCallbacks)
                finish(null)
            }
        }
        web.loadUrl(url)
    }

    /** True when a coarse grid of pixels is all (nearly) one color. */
    private fun looksBlank(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(0, 0)
        for (gx in 1..8) for (gy in 1..8) {
            val p = bitmap.getPixel(bitmap.width * gx / 9, bitmap.height * gy / 9)
            if (colorDistance(p, first) > 24) return false
        }
        return true
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    companion object {
        private const val KEY_SAVE_ID = "save_id"
        private const val KEY_FORCE = "force"
        private const val VIEWPORT_DP = 412
        private const val TARGET_WIDTH = 720
        private const val SETTLE_MS = 1_500L
        private const val LOAD_TIMEOUT_MS = 30_000L

        /** After a link save with no preview image. Does nothing if one turns up first. */
        fun enqueue(context: Context, saveId: Long, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<PageSnapshotWorker>()
                .setInputData(workDataOf(KEY_SAVE_ID to saveId, KEY_FORCE to force))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "snapshot_$saveId",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
