package com.ghostgramlabs.pettibox.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for the PettiBox Room database.
 *
 * v1 → v2 adds the archive flag and the reminder timestamp on save_items.
 * Indices match the ones declared on [SaveItemEntity] so Room's schema
 * validation succeeds — name format `index_<table>_<column>` is what
 * Room auto-generates when no explicit name is set on @Index.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE save_items ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE save_items ADD COLUMN remind_at INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_save_items_is_archived ON save_items(is_archived)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_save_items_remind_at ON save_items(remind_at)")
    }
}

/**
 * v2 → v3: adds is_pending_delete. Used as the staging flag for the
 * "Delete with Undo" flow so an in-flight delete is hidden from every
 * listing (including Archive). The Undo window is short (~5 s); on
 * app cold start anything still pending-delete is swept by
 * [com.ghostgramlabs.pettibox.PettiBoxApp] under the assumption that
 * the user (or a force-stop) committed to the delete.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE save_items ADD COLUMN is_pending_delete INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_save_items_is_pending_delete ON save_items(is_pending_delete)")
    }
}

/**
 * v3 → v4: offline article copies for link saves, plus their FTS index and
 * the content-sync triggers Room would create for a fresh install. SQL is
 * copied from schemas/4.json so Room's schema validation matches.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `article_copies` (`save_id` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`html` TEXT, `text_content` TEXT, `byline` TEXT, `word_count` INTEGER NOT NULL, " +
                "`fetched_at` INTEGER NOT NULL, `failure_reason` TEXT, PRIMARY KEY(`save_id`), " +
                "FOREIGN KEY(`save_id`) REFERENCES `save_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `article_copies_fts` USING FTS4(`text_content` TEXT, content=`article_copies`)"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_copies_fts_BEFORE_UPDATE BEFORE UPDATE ON `article_copies` " +
                "BEGIN DELETE FROM `article_copies_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_copies_fts_BEFORE_DELETE BEFORE DELETE ON `article_copies` " +
                "BEGIN DELETE FROM `article_copies_fts` WHERE `docid`=OLD.`rowid`; END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_copies_fts_AFTER_UPDATE AFTER UPDATE ON `article_copies` " +
                "BEGIN INSERT INTO `article_copies_fts`(`docid`, `text_content`) VALUES (NEW.`rowid`, NEW.`text_content`); END"
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_copies_fts_AFTER_INSERT AFTER INSERT ON `article_copies` " +
                "BEGIN INSERT INTO `article_copies_fts`(`docid`, `text_content`) VALUES (NEW.`rowid`, NEW.`text_content`); END"
        )
    }
}

/**
 * v4 → v5: deleted_at, turning the short Undo window into a 30-day
 * Recently deleted bin. Rows already staged for deletion start their
 * 30 days now rather than being dropped on the next launch.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE save_items ADD COLUMN deleted_at INTEGER")
        db.execSQL(
            "UPDATE save_items SET deleted_at = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE is_pending_delete = 1"
        )
    }
}

/**
 * v5 → v6: data only. The Beauty starter's emoji moved from lipstick to a
 * lotion bottle. Only rows still on the original lipstick change, so an
 * emoji the user picked themselves is left alone.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE categories SET emoji = ? WHERE id = 'beauty' AND emoji = ?",
            arrayOf<Any>("🧴", "💄")
        )
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
