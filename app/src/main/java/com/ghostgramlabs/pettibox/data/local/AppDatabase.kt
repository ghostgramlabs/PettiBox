package com.ghostgramlabs.pettibox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema v5 — adds save_items.deleted_at for the 30-day Recently deleted
 * bin. See [MIGRATION_4_5]. (v4 added offline article copies.)
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
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saveDao(): SaveDao
    abstract fun categoryDao(): CategoryDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
}
