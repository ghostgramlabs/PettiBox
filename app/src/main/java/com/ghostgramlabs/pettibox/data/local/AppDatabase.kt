package com.ghostgramlabs.pettibox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema v4 — adds article_copies (offline reading copies of link saves)
 * and its FTS index. See [MIGRATION_3_4].
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
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saveDao(): SaveDao
    abstract fun categoryDao(): CategoryDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun tagDao(): TagDao
    abstract fun articleDao(): ArticleDao
}
