package com.ghostgramlabs.pettibox.data.article

import android.content.Context
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
import com.ghostgramlabs.pettibox.data.local.ArticleCopyEntity
import com.ghostgramlabs.pettibox.data.local.ArticleCopyStatus
import com.ghostgramlabs.pettibox.data.local.ArticleDao
import com.ghostgramlabs.pettibox.data.local.SaveDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Makes (or refreshes) the offline copy for one link save. Runs only with a
 * network connection, so a link saved offline gets its copy once the phone
 * is back online. A refresh that fails keeps the copy the user already has.
 */
@HiltWorker
class ArticleCopyWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val saveDao: SaveDao,
    private val articleDao: ArticleDao,
    private val extractor: ArticleExtractor
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val saveId = inputData.getLong(KEY_SAVE_ID, -1L)
        if (saveId <= 0L) return Result.failure()
        val url = saveDao.getById(saveId)?.url ?: return Result.success()
        val existing = articleDao.get(saveId)
        val hasCopy = existing?.status == ArticleCopyStatus.READY.name

        val next: ArticleCopyEntity? = when (val outcome = extractor.extract(url)) {
            is ArticleExtractor.Outcome.Article -> ArticleCopyEntity(
                saveId = saveId,
                status = ArticleCopyStatus.READY.name,
                html = outcome.html,
                textContent = outcome.text,
                byline = outcome.byline,
                wordCount = outcome.wordCount
            )
            ArticleExtractor.Outcome.Unsupported ->
                if (hasCopy) null else ArticleCopyEntity(saveId, ArticleCopyStatus.UNSUPPORTED.name)
            is ArticleExtractor.Outcome.Failed ->
                if (hasCopy) null else failed(saveId, outcome.reason)
            ArticleExtractor.Outcome.TryLater -> {
                if (runAttemptCount < MAX_ATTEMPTS - 1) return Result.retry()
                if (hasCopy) null else failed(saveId, "Couldn't reach the page. Check your connection and try again.")
            }
        }
        // The save may have been deleted while we were downloading; the
        // foreign key then rejects the write, which is exactly right.
        next?.let { runCatching { articleDao.upsert(it) } }
        return Result.success()
    }

    private fun failed(saveId: Long, reason: String) =
        ArticleCopyEntity(saveId, ArticleCopyStatus.FAILED.name, failureReason = reason)

    companion object {
        const val TAG = "pettibox_article_copies"
        private const val KEY_SAVE_ID = "save_id"
        private const val MAX_ATTEMPTS = 3

        /**
         * [replace] restarts work already queued for this save (an explicit
         * Retry/Update tap); otherwise an in-flight copy is left alone.
         */
        fun enqueue(context: Context, saveId: Long, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ArticleCopyWorker>()
                .setInputData(workDataOf(KEY_SAVE_ID to saveId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "article_copy_$saveId",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }
}
