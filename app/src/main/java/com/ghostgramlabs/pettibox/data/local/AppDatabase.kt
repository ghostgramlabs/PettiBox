package com.ghostgramlabs.pettibox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema v8 — save_items.sync_id and the sync_* tables behind "Sync between
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
        SyncDirtyEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saveDao(): SaveDao
    abstract fun categoryDao(): CategoryDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
    abstract fun syncDao(): SyncDao
}
