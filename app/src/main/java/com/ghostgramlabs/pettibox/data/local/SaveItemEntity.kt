package com.ghostgramlabs.pettibox.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tags live in their own [TagEntity] / [ItemTagCrossRef] tables now — there
 * is intentionally no `tags` column here. Tag filtering becomes an indexed
 * JOIN, and aggregating known tags is O(unique tags) instead of scanning every
 * row.
 *
 * Composite indexes match the hot read paths: category drill-in sorts by
 * `created_at DESC` within a category; source-filtered browses by `created_at`
 * within a source app. Single-column indexes on the boolean flags keep
 * favorites/pinned queries cheap.
 */
@Entity(
    tableName = "save_items",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("category_id"),
        Index("created_at"),
        Index("source_app"),
        Index("content_type"),
        Index("is_favorite"),
        Index("is_pinned"),
        Index("opened_at"),
        Index("is_archived"),
        Index("is_pending_delete"),
        Index("remind_at"),
        Index(value = ["category_id", "created_at"], name = "idx_save_items_cat_time"),
        Index(value = ["source_app", "created_at"], name = "idx_save_items_src_time")
    ]
)
data class SaveItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "url") val url: String? = null,
    @ColumnInfo(name = "local_uri") val localUri: String? = null,
    @ColumnInfo(name = "thumbnail_uri") val thumbnailUri: String? = null,
    @ColumnInfo(name = "content_type") val contentType: String,
    @ColumnInfo(name = "source_app") val sourceApp: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "notes") val notes: String? = null,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    // Soft-deleted ("I'm done with this") items still exist for search and
    // restore, but disappear from Home and the default Browse view.
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    // Set while an actual delete is staged in the UI (Undo snackbar
    // window). Hides the row from every listing query — including
    // Archive — without losing it from the DB so Undo can clear the
    // flag. App cold-start sweeps anything left in this state.
    @ColumnInfo(name = "is_pending_delete") val isPendingDelete: Boolean = false,
    // When the row entered Recently deleted (is_pending_delete = 1). Rows
    // are kept 30 days so a delete can be undone long after the snackbar.
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    // Epoch millis at which the user wants a reminder notification. Null
    // means no reminder pending. The notification clears this back to null
    // when it fires.
    @ColumnInfo(name = "remind_at") val remindAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "opened_at") val openedAt: Long? = null
)
