package com.ghostgramlabs.pettibox.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * Offline reading copy of a link save: the page's article, extracted to
 * clean HTML + plain text. One row per save at most, keyed by the save's
 * id, and CASCADE-deleted with it.
 *
 * Lives in its own table rather than on save_items because an article is
 * tens of KB — every list and paging query selects `save_items.*`, and
 * dragging that text through them would make scrolling pay for reading.
 */
@Entity(
    tableName = "article_copies",
    foreignKeys = [
        ForeignKey(
            entity = SaveItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["save_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ArticleCopyEntity(
    @PrimaryKey @ColumnInfo(name = "save_id") val saveId: Long,
    /** An [ArticleCopyStatus] name. */
    @ColumnInfo(name = "status") val status: String,
    /** Sanitized article HTML (no scripts, images, or embeds). */
    @ColumnInfo(name = "html") val html: String? = null,
    @ColumnInfo(name = "text_content") val textContent: String? = null,
    @ColumnInfo(name = "byline") val byline: String? = null,
    @ColumnInfo(name = "word_count") val wordCount: Int = 0,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = System.currentTimeMillis(),
    /** User-facing reason when [status] is FAILED. */
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null
)

enum class ArticleCopyStatus {
    /** Queued or downloading; waits for a connection. */
    PENDING,
    READY,
    /** Tried and couldn't get an article (paywall, 404, not an article page). */
    FAILED,
    /** Sites that never yield a useful copy (video, social). Hidden in the UI. */
    UNSUPPORTED
}

/** Full-text index over article bodies, so search finds words inside saved articles. */
@Entity(tableName = "article_copies_fts")
@Fts4(contentEntity = ArticleCopyEntity::class)
data class ArticleCopyFts(
    @ColumnInfo(name = "text_content") val textContent: String?
)
