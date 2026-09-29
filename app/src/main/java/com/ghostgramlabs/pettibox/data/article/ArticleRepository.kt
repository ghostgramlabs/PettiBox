package com.ghostgramlabs.pettibox.data.article

import android.content.Context
import com.ghostgramlabs.pettibox.data.local.ArticleCopyEntity
import com.ghostgramlabs.pettibox.data.local.ArticleCopyStatus
import com.ghostgramlabs.pettibox.data.local.ArticleCopySummary
import com.ghostgramlabs.pettibox.data.local.ArticleDao
import com.ghostgramlabs.pettibox.data.local.ArticleStats
import com.ghostgramlabs.pettibox.data.preferences.ArticlePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArticleRepository @Inject constructor(
    private val articleDao: ArticleDao,
    private val preferences: ArticlePreferences,
    @ApplicationContext private val context: Context
) {
    fun observeSummary(saveId: Long): Flow<ArticleCopySummary?> = articleDao.observeSummary(saveId)
    /** The full copy, loaded only when the reader opens. */
    suspend fun load(saveId: Long): ArticleCopyEntity? = runCatching { articleDao.get(saveId) }.getOrNull()
    fun observeStats(): Flow<ArticleStats> = articleDao.observeStats()
    fun observeLinksWithoutCopy(): Flow<Int> = articleDao.observeLinksWithoutCopy()

    /** Called after a new link save; respects the Settings switch. */
    suspend fun onLinkSaved(saveId: Long, url: String?) {
        if (!preferences.keepOfflineCopies.first()) return
        request(saveId, url, replace = false)
    }

    /**
     * Explicit request from the user (Save / Retry / Update on a save).
     * Works even with automatic copies switched off. A save that already
     * has a copy keeps showing it while the new one downloads.
     */
    suspend fun request(saveId: Long, url: String?, replace: Boolean = true) {
        if (!ArticleExtractor.isSupported(url)) {
            articleDao.upsert(ArticleCopyEntity(saveId, ArticleCopyStatus.UNSUPPORTED.name))
            return
        }
        if (articleDao.get(saveId)?.status != ArticleCopyStatus.READY.name) {
            articleDao.upsert(ArticleCopyEntity(saveId, ArticleCopyStatus.PENDING.name))
        }
        ArticleCopyWorker.enqueue(context, saveId, replace)
    }

    /** "Save copies for existing links": queues every link that has never had one. */
    suspend fun requestForExistingLinks(): Int {
        val links = articleDao.linksWithoutCopy()
        val (supported, unsupported) = links.partition { ArticleExtractor.isSupported(it.url) }
        articleDao.upsertAll(
            unsupported.map { ArticleCopyEntity(it.id, ArticleCopyStatus.UNSUPPORTED.name) } +
                supported.map { ArticleCopyEntity(it.id, ArticleCopyStatus.PENDING.name) }
        )
        supported.forEach { ArticleCopyWorker.enqueue(context, it.id) }
        return supported.size
    }

    suspend fun removeAll() {
        ArticleCopyWorker.cancelAll(context)
        articleDao.deleteAll()
    }
}
