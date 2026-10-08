package com.ghostgramlabs.pettibox.data.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and drops the triggers that feed sync_outbox. They exist only while
 * sync is on, so users who never turn it on pay nothing: no extra writes, no
 * rows, no background work.
 *
 * Every change to a save, its tags or files, or a collection queues that
 * record (one row each, newest time wins). Writes made while remote changes
 * are being applied (sync_applying has a row) are not queued, so they don't
 * bounce back out.
 *
 * The trigger body deletes then inserts rather than INSERT OR REPLACE: SQLite
 * applies the *outer* statement's conflict rule inside triggers, so under
 * Room's UPDATE OR ABORT a REPLACE would become ABORT and fail the user's
 * edit. A plain insert after the delete can't conflict under any rule. (Also
 * avoids UPSERT, which Android 7's SQLite lacks.)
 */
@Singleton
class SyncSetup @Inject constructor(private val db: AppDatabase) {

    /** Turns capture on and queues the whole library once; the first round compares it all. */
    suspend fun enable() = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        createTriggers(sql)
        // Everything goes in the outbox. A record this device has synced
        // before only writes what changed since (edits made while sync was
        // off); one it hasn't is merged in as new.
        sql.execSQL("INSERT OR REPLACE INTO sync_outbox(kind, sync_id, ts) SELECT 'item', sync_id, updated_at FROM save_items")
        sql.execSQL("INSERT OR REPLACE INTO sync_outbox(kind, sync_id, ts) SELECT 'col', id, created_at FROM categories")
        // Drive may have been emptied meanwhile (a reset, or this phone's
        // copy removed): send this phone's whole copy again.
        sql.execSQL("INSERT OR IGNORE INTO sync_dirty(shard) SELECT DISTINCT shard FROM sync_meta")
        // Removed for good while sync was off: still in the merged copy but not here.
        sql.execSQL(
            "INSERT OR REPLACE INTO sync_outbox(kind, sync_id, ts) SELECT kind, sync_id, $NOW FROM sync_meta m " +
                "WHERE (kind = 'item' AND NOT EXISTS (SELECT 1 FROM save_items s WHERE s.sync_id = m.sync_id)) " +
                "OR (kind = 'col' AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = m.sync_id))"
        )
    }

    /** Stops capture and clears what was waiting. The merged copy stays, so a later enable knows what was synced. */
    suspend fun disable() = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        dropTriggers(sql)
        sql.execSQL("DELETE FROM sync_outbox")
        sql.execSQL("DELETE FROM sync_inbox")
        sql.execSQL("DELETE FROM sync_applying")
    }

    /**
     * Makes the triggers match the setting. Run at app start, in case the app
     * was killed halfway through turning sync on or off.
     */
    suspend fun reconcile(enabled: Boolean) {
        val sql = db.openHelper.writableDatabase
        val present = sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync_q_%'")
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        when {
            enabled && present < TRIGGER_NAMES.size -> enable()
            !enabled && present > 0 -> disable()
        }
        // A crash mid-apply can't leave the flag behind (it's in the same
        // transaction), but clear it anyway: a stuck flag would silently
        // stop capture.
        sql.execSQL("DELETE FROM sync_applying")
    }

    private fun createTriggers(sql: SupportSQLiteDatabase) {
        fun queue(name: String, event: String, table: String, kind: String, idExpr: String, guard: String = "") {
            sql.execSQL(
                "CREATE TRIGGER IF NOT EXISTS $name AFTER $event ON $table " +
                    "WHEN NOT EXISTS (SELECT 1 FROM sync_applying)$guard " +
                    "BEGIN DELETE FROM sync_outbox WHERE kind = '$kind' AND sync_id = $idExpr; " +
                    "INSERT INTO sync_outbox(kind, sync_id, ts) VALUES ('$kind', $idExpr, $NOW); END"
            )
        }
        queue("sync_q_item_ins", "INSERT", "save_items", "item", "NEW.sync_id")
        queue("sync_q_item_upd", "UPDATE", "save_items", "item", "NEW.sync_id")
        queue("sync_q_item_del", "DELETE", "save_items", "item", "OLD.sync_id")
        // Tags and files belong to a save; skip rows whose save is already
        // gone (a cascade from deleting the save, which queued it already).
        val itemOf = { ref: String -> "(SELECT sync_id FROM save_items WHERE id = $ref.item_id)" }
        val hasItem = { ref: String -> " AND EXISTS (SELECT 1 FROM save_items WHERE id = $ref.item_id)" }
        queue("sync_q_tag_ins", "INSERT", "item_tags", "item", itemOf("NEW"), hasItem("NEW"))
        queue("sync_q_tag_del", "DELETE", "item_tags", "item", itemOf("OLD"), hasItem("OLD"))
        queue("sync_q_att_ins", "INSERT", "attachments", "item", itemOf("NEW"), hasItem("NEW"))
        queue("sync_q_att_upd", "UPDATE", "attachments", "item", itemOf("NEW"), hasItem("NEW"))
        queue("sync_q_att_del", "DELETE", "attachments", "item", itemOf("OLD"), hasItem("OLD"))
        queue("sync_q_col_ins", "INSERT", "categories", "col", "NEW.id")
        queue("sync_q_col_upd", "UPDATE", "categories", "col", "NEW.id")
        queue("sync_q_col_del", "DELETE", "categories", "col", "OLD.id")
    }

    private fun dropTriggers(sql: SupportSQLiteDatabase) {
        for (name in TRIGGER_NAMES) sql.execSQL("DROP TRIGGER IF EXISTS $name")
    }

    companion object {
        /** Current time in ms, in SQL. */
        private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

        val TRIGGER_NAMES = listOf(
            "sync_q_item_ins", "sync_q_item_upd", "sync_q_item_del",
            "sync_q_tag_ins", "sync_q_tag_del",
            "sync_q_att_ins", "sync_q_att_upd", "sync_q_att_del",
            "sync_q_col_ins", "sync_q_col_upd", "sync_q_col_del"
        )
    }
}
