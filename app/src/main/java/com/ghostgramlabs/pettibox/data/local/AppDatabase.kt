package com.ghostgramlabs.pettibox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema v6 — data-only bump that swaps the Beauty starter's emoji. See
 * [MIGRATION_5_6]. (v5 added save_items.deleted_at for the 30-day
 * Recently deleted bin; v4 added offline article copies.)
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
        ArticleCopyFts::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saveDao(): SaveDao
    abstract fun categoryDao(): CategoryDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
}
