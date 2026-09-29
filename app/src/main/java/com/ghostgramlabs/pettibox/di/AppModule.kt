package com.ghostgramlabs.pettibox.di

import android.content.Context
import androidx.room.Room
import com.ghostgramlabs.pettibox.data.local.ALL_MIGRATIONS
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.ArticleDao
import com.ghostgramlabs.pettibox.data.local.AttachmentDao
import com.ghostgramlabs.pettibox.data.local.CategoryDao
import com.ghostgramlabs.pettibox.data.local.SaveDao
import com.ghostgramlabs.pettibox.data.local.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase {
        migrateLegacyDatabaseName(ctx)
        return Room.databaseBuilder(ctx, AppDatabase::class.java, "pettibox.db")
            .addMigrations(*ALL_MIGRATIONS)
            // Defensive: a future downgrade (e.g. an internal tester
            // reverting from a beta build) shouldn't crash on launch.
            // Destructive upgrade is NOT enabled — we still want an
            // exception if someone bumps the schema without a Migration.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    private fun migrateLegacyDatabaseName(ctx: Context) {
        val oldDb = ctx.getDatabasePath("savepetti.db")
        val newDb = ctx.getDatabasePath("pettibox.db")
        if (!oldDb.exists() || newDb.exists()) return

        newDb.parentFile?.mkdirs()
        // renameTo can fail (cross-filesystem mount, file lock) and returns
        // false rather than throwing. If we ignore the failure, Room opens
        // a fresh empty pettibox.db and the user appears to have lost
        // everything. Fall back to byte-copy + delete on failure.
        if (!safelyMoveDbFile(oldDb, newDb)) return

        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val oldSidecar = ctx.getDatabasePath("savepetti.db$suffix")
            if (oldSidecar.exists()) {
                safelyMoveDbFile(oldSidecar, ctx.getDatabasePath("pettibox.db$suffix"))
            }
        }
    }

    private fun safelyMoveDbFile(source: java.io.File, target: java.io.File): Boolean {
        if (source.renameTo(target)) return true
        // renameTo failed — try copy, then verify before deleting source so
        // a partial copy on a failing disk doesn't lose user data.
        return runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() == source.length()) {
                source.delete()
                true
            } else {
                target.delete()
                false
            }
        }.getOrDefault(false)
    }

    @Provides fun provideSaveDao(db: AppDatabase): SaveDao = db.saveDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideAttachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()
    @Provides fun provideTagDao(db: AppDatabase): TagDao = db.tagDao()
    @Provides fun provideArticleDao(db: AppDatabase): ArticleDao = db.articleDao()
}
