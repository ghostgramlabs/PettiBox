package com.ghostgramlabs.pettibox.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Where reading the text in one file stands, when that isn't simply "here
 * it is": waiting to be read, read but empty, or failed. No row means the
 * file's text is whatever the save holds. Local to this phone (not synced):
 * each phone reads its own copies.
 */
@Entity(
    tableName = "ocr_status",
    foreignKeys = [
        ForeignKey(entity = SaveItemEntity::class, parentColumns = ["id"], childColumns = ["item_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AttachmentEntity::class, parentColumns = ["id"], childColumns = ["attachment_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("item_id"), Index("attachment_id")]
)
data class OcrStatusEntity(
    /** "item:<id>" for a save's own file, "file:<id>" for one added to it. */
    @PrimaryKey @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "attachment_id") val attachmentId: Long?,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "pdf") val pdf: Boolean,
    /** One of [PENDING], [READING], [NO_TEXT], [UNREADABLE], [FAILED]. */
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    /** A PDF being read: the languages and page limit it's read under ([com.ghostgramlabs.pettibox.data.ocr.TextReader.configuration]). */
    @ColumnInfo(name = "config") val config: String? = null,
    /** A PDF being read: pages done, and the text (and dropped readings) so far, to carry on from there. */
    @ColumnInfo(name = "pages_done", defaultValue = "0") val pagesDone: Int = 0,
    @ColumnInfo(name = "partial_text") val partialText: String? = null,
    @ColumnInfo(name = "partial_replaced") val partialReplaced: String? = null
) {
    companion object {
        /** To be read (recorded with the file's arrival, so it survives the app being stopped). */
        const val PENDING = "pending"
        /** A PDF part read: carries on from [pagesDone] (a stopped job doesn't start again from page one). */
        const val READING = "reading"
        /** Read, and there's no text in it. */
        const val NO_TEXT = "no_text"
        /** The file couldn't be opened: missing, or not an image or PDF. Adding it again helps; retrying doesn't. */
        const val UNREADABLE = "unreadable"
        /** Reading kept failing (tried several times). */
        const val FAILED = "failed"

        fun sourceOf(itemId: Long, attachmentId: Long?) = if (attachmentId == null) "item:$itemId" else "file:$attachmentId"
    }
}

@Dao
interface OcrStatusDao {
    @Upsert
    suspend fun put(rows: List<OcrStatusEntity>)

    @Query("DELETE FROM ocr_status WHERE source = :source")
    suspend fun clear(source: String)

    /** Files waiting to be read, or part read (a PDF whose job stopped): to queue at app start. */
    @Query("SELECT * FROM ocr_status WHERE state IN ('pending', 'reading')")
    suspend fun pending(): List<OcrStatusEntity>

    @Query("SELECT * FROM ocr_status WHERE source = :source")
    suspend fun get(source: String): OcrStatusEntity?

    @Query("SELECT * FROM ocr_status WHERE item_id = :itemId")
    fun observeForItem(itemId: Long): Flow<List<OcrStatusEntity>>
}
