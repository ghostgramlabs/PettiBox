package com.ghostgramlabs.pettibox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    // ── Outbox (local changes) ───────────────────────────────────────────

    @Query("SELECT * FROM sync_outbox ORDER BY ts")
    suspend fun outbox(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observeOutboxCount(): Flow<Int>

    /** Clears a row only if nothing changed it since it was read (same ts). */
    @Query("DELETE FROM sync_outbox WHERE kind = :kind AND sync_id = :syncId AND ts = :ts")
    suspend fun clearOutbox(kind: String, syncId: String, ts: Long)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearAllOutbox()

    @Query("UPDATE OR REPLACE sync_outbox SET sync_id = :newId WHERE kind = 'item' AND sync_id = :oldId")
    suspend fun renameOutbox(oldId: String, newId: String)

    // ── Inbox (remote changes) ───────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putInbox(rows: List<SyncInboxEntity>)

    @Query("SELECT * FROM sync_inbox")
    suspend fun inbox(): List<SyncInboxEntity>

    @Query("SELECT COUNT(*) FROM sync_inbox")
    suspend fun inboxCount(): Int

    @Query("DELETE FROM sync_inbox")
    suspend fun clearInbox()

    // ── Meta (this device's merged copy) ─────────────────────────────────

    @Query("SELECT * FROM sync_meta WHERE kind = :kind AND sync_id = :syncId")
    suspend fun meta(kind: String, syncId: String): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta WHERE shard = :shard")
    suspend fun metaInShard(shard: Int): List<SyncMetaEntity>

    @Query("SELECT * FROM sync_meta WHERE kind = :kind")
    suspend fun metaOfKind(kind: String): List<SyncMetaEntity>

    @Query("SELECT COUNT(*) FROM sync_meta")
    suspend fun metaCount(): Int

    @Upsert
    suspend fun putMeta(rows: List<SyncMetaEntity>)

    @Query("DELETE FROM sync_meta WHERE kind = :kind AND sync_id = :syncId")
    suspend fun deleteMeta(kind: String, syncId: String)

    // ── Applying flag (silences the outbox triggers) ─────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun beginApplying(flag: SyncApplyingEntity = SyncApplyingEntity())

    @Query("DELETE FROM sync_applying")
    suspend fun endApplying()

    // ── Files ────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sync_files WHERE local_uri = :uri")
    suspend fun fileByUri(uri: String): SyncFileEntity?

    @Query("SELECT * FROM sync_files WHERE sha256 = :sha LIMIT 1")
    suspend fun fileBySha(sha: String): SyncFileEntity?

    @Upsert
    suspend fun putFile(file: SyncFileEntity)

    // ── Dirty shards ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markDirty(rows: List<SyncDirtyEntity>)

    @Query("SELECT shard FROM sync_dirty")
    suspend fun dirtyShards(): List<Int>

    @Query("DELETE FROM sync_dirty WHERE shard = :shard")
    suspend fun clearDirty(shard: Int)

    // ── Lookups into the library ─────────────────────────────────────────

    @Query("SELECT * FROM save_items WHERE sync_id = :syncId LIMIT 1")
    suspend fun itemBySyncId(syncId: String): SaveItemEntity?

    @Query("SELECT sync_id FROM save_items WHERE id = :id")
    suspend fun syncIdOf(id: Long): String?

    @Query("UPDATE save_items SET sync_id = :newId WHERE sync_id = :oldId")
    suspend fun renameItem(oldId: String, newId: String)

    @Query("SELECT sync_id FROM save_items")
    suspend fun allItemSyncIds(): List<String>
}
