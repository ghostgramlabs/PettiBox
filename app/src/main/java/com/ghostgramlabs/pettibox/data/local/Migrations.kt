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

/** v6 → v7: is_locked on categories, for locked collections. Every collection starts unlocked. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE categories ADD COLUMN is_locked INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v7 → v8: "Sync between devices". Adds save_items.sync_id (backfilled with a
 * random id per save, then made unique) and the empty sync_* tables. Nothing
 * runs for users who never turn sync on: the outbox triggers are created by
 * SyncSetup only when they do. SQL is copied from schemas/8.json.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE save_items ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
        // Backfill before the unique index, or every existing row collides on ''.
        db.execSQL("UPDATE save_items SET sync_id = lower(hex(randomblob(16))) WHERE sync_id = ''")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_save_items_sync_id` ON `save_items` (`sync_id`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (`kind` TEXT NOT NULL, `sync_id` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, PRIMARY KEY(`kind`, `sync_id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_inbox` (`kind` TEXT NOT NULL, `sync_id` TEXT NOT NULL, " +
                "`device` TEXT NOT NULL, `record_json` TEXT NOT NULL, PRIMARY KEY(`kind`, `sync_id`, `device`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_meta` (`kind` TEXT NOT NULL, `sync_id` TEXT NOT NULL, " +
                "`shard` INTEGER NOT NULL, `record_json` TEXT NOT NULL, PRIMARY KEY(`kind`, `sync_id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_meta_shard` ON `sync_meta` (`shard`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_applying` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_files` (`local_uri` TEXT NOT NULL, `sha256` TEXT NOT NULL, " +
                "PRIMARY KEY(`local_uri`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_files_sha256` ON `sync_files` (`sha256`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_dirty` (`shard` INTEGER NOT NULL, PRIMARY KEY(`shard`))")
    }
}

/**
 * v8 → v9: both search indexes rebuilt with the unicode61 tokenizer, so
 * words in every script are lowercased and split properly (the old one
 * only folded A–Z: "Москва" didn't find "москва"). Each index is dropped
 * with its content-sync triggers, made again as schemas/9.json has it, and
 * refilled from its table. Nothing but the index changes.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        fun rebuild(fts: String, content: String, columns: List<String>, create: String) {
            for (t in listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")) {
                db.execSQL("DROP TRIGGER IF EXISTS room_fts_content_sync_${fts}_$t")
            }
            db.execSQL("DROP TABLE IF EXISTS `$fts`")
            db.execSQL(create)
            val cols = columns.joinToString(", ") { "`$it`" }
            val newCols = columns.joinToString(", ") { "NEW.`$it`" }
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_BEFORE_UPDATE BEFORE UPDATE ON `$content` " +
                    "BEGIN DELETE FROM `$fts` WHERE `docid`=OLD.`rowid`; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_BEFORE_DELETE BEFORE DELETE ON `$content` " +
                    "BEGIN DELETE FROM `$fts` WHERE `docid`=OLD.`rowid`; END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_AFTER_UPDATE AFTER UPDATE ON `$content` " +
                    "BEGIN INSERT INTO `$fts`(`docid`, $cols) VALUES (NEW.`rowid`, $newCols); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_${fts}_AFTER_INSERT AFTER INSERT ON `$content` " +
                    "BEGIN INSERT INTO `$fts`(`docid`, $cols) VALUES (NEW.`rowid`, $newCols); END"
            )
            db.execSQL("INSERT INTO `$fts`(`$fts`) VALUES('rebuild')")
        }
        rebuild(
            "save_items_fts", "save_items", listOf("title", "url", "notes", "ocr_text", "source_app"),
            "CREATE VIRTUAL TABLE IF NOT EXISTS `save_items_fts` USING FTS4(`title` TEXT NOT NULL, `url` TEXT, `notes` TEXT, " +
                "`ocr_text` TEXT, `source_app` TEXT NOT NULL, tokenize=unicode61, content=`save_items`)"
        )
        rebuild(
            "article_copies_fts", "article_copies", listOf("text_content"),
            "CREATE VIRTUAL TABLE IF NOT EXISTS `article_copies_fts` USING FTS4(`text_content` TEXT, tokenize=unicode61, content=`article_copies`)"
        )
    }
}

/** v9 → v10: ocr_status, where reading each file's text stands (SQL from schemas/10.json). Starts empty. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ocr_status` (`source` TEXT NOT NULL, `item_id` INTEGER NOT NULL, `attachment_id` INTEGER, " +
                "`uri` TEXT NOT NULL, `pdf` INTEGER NOT NULL, `state` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`source`), " +
                "FOREIGN KEY(`item_id`) REFERENCES `save_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`attachment_id`) REFERENCES `attachments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_status_item_id` ON `ocr_status` (`item_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_status_attachment_id` ON `ocr_status` (`attachment_id`)")
    }
}

/** v10 → v11: a PDF's reading progress on ocr_status (pages read and the text so far). */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ocr_status ADD COLUMN `config` TEXT")
        db.execSQL("ALTER TABLE ocr_status ADD COLUMN `pages_done` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ocr_status ADD COLUMN `partial_text` TEXT")
        db.execSQL("ALTER TABLE ocr_status ADD COLUMN `partial_replaced` TEXT")
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
)
