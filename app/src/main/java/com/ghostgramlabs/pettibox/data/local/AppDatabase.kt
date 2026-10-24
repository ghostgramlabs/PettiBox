package com.ghostgramlabs.pettibox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema v11 — ocr_status keeps a PDF's progress (pages read, text so far)
 * so a stopped reading carries on. See [MIGRATION_10_11].
 *
 * v10 — ocr_status: where reading each file's text stands (waiting,
 * no text, failed). See [MIGRATION_9_10].
 *
 * v9 — both search indexes use the unicode61 tokenizer (words in
 * every script, not just A–Z). See [MIGRATION_8_9].
 *
 * v8 — save_items.sync_id and the sync_* tables behind "Sync between
 * devices". See [MIGRATION_7_8]. (v7 added categories.is_locked.) (v6 swapped the Beauty starter's emoji; v5 added
 * save_items.deleted_at for the 30-day Recently deleted bin; v4 added
 * offline article copies.)
 */
@Database(
    entities = [
        SaveItemEntity::class,
        SaveItemFts::class,
        CategoryEntity::class,
        AttachmentEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        ArticleCopyEntity::class,
        ArticleCopyFts::class,
        SyncOutboxEntity::class,
        SyncInboxEntity::class,
        SyncMetaEntity::class,
        SyncApplyingEntity::class,
        SyncFileEntity::class,
        SyncDirtyEntity::class,
        OcrStatusEntity::class
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saveDao(): SaveDao
    abstract fun categoryDao(): CategoryDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
    abstract fun syncDao(): SyncDao
    abstract fun ocrStatusDao(): OcrStatusDao
}
