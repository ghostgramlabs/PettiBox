package com.ghostgramlabs.pettibox.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {

    /** Status only — the detail screen never needs the article body to draw its card. */
    @Query(
        """
        SELECT save_id, status, word_count, fetched_at, failure_reason
        FROM article_copies WHERE save_id = :saveId
        """
    )
    fun observeSummary(saveId: Long): Flow<ArticleCopySummary?>

    @Query("SELECT * FROM article_copies WHERE save_id = :saveId")
    suspend fun get(saveId: Long): ArticleCopyEntity?

    // @Upsert, not REPLACE: REPLACE deletes the old row without firing
    // delete triggers, which would leave the FTS index out of sync.
    @Upsert
    suspend fun upsert(copy: ArticleCopyEntity)

    @Upsert
    suspend fun upsertAll(copies: List<ArticleCopyEntity>)

    @Query("DELETE FROM article_copies")
    suspend fun deleteAll()

    @Query(
        """
        SELECT COUNT(*) AS count,
               COALESCE(SUM(LENGTH(html) + LENGTH(text_content)), 0) AS bytes
        FROM article_copies WHERE status = 'READY'
        """
    )
    fun observeStats(): Flow<ArticleStats>

    /** Live link saves with no copy row at all — candidates for "download for existing links". */
    @Query(
        """
        SELECT s.id, s.url FROM save_items s
        LEFT JOIN article_copies a ON a.save_id = s.id
        WHERE s.content_type = 'LINK' AND s.url IS NOT NULL AND s.url != ''
          AND s.is_pending_delete = 0 AND a.save_id IS NULL
        """
    )
    suspend fun linksWithoutCopy(): List<LinkRef>

    @Query(
        """
        SELECT COUNT(*) FROM save_items s
        LEFT JOIN article_copies a ON a.save_id = s.id
        WHERE s.content_type = 'LINK' AND s.url IS NOT NULL AND s.url != ''
          AND s.is_pending_delete = 0 AND a.save_id IS NULL
        """
    )
    fun observeLinksWithoutCopy(): Flow<Int>

    /** Ids only: backup streams copies one at a time instead of loading them all. */
    @Query("SELECT save_id FROM article_copies WHERE status = 'READY'")
    suspend fun readyIds(): List<Long>
}

data class ArticleStats(val count: Int, val bytes: Long)
data class LinkRef(val id: Long, val url: String)

data class ArticleCopySummary(
    @androidx.room.ColumnInfo(name = "save_id") val saveId: Long,
    @androidx.room.ColumnInfo(name = "status") val status: String,
    @androidx.room.ColumnInfo(name = "word_count") val wordCount: Int,
    @androidx.room.ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @androidx.room.ColumnInfo(name = "failure_reason") val failureReason: String?
)
