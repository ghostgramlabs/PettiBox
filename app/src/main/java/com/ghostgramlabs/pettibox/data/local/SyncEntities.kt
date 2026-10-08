package com.ghostgramlabs.pettibox.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/*
 * Tables behind "Sync between devices". All empty, and untouched, unless the
 * user turns sync on; see SyncSetup for the triggers that feed the outbox.
 *
 * Kinds: "item" (a save, keyed by save_items.sync_id) and "col" (a
 * collection, keyed by categories.id).
 */

/** A random 128-bit id, 32 hex chars. The same shape the v8 migration backfills. */
fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")

/**
 * Local changes waiting to go out. Filled by SQLite triggers while sync is
 * on, so no write path can be missed; one row per record, holding the time
 * of its latest change.
 */
@Entity(tableName = "sync_outbox", primaryKeys = ["kind", "sync_id"])
data class SyncOutboxEntity(
    val kind: String,
    @ColumnInfo(name = "sync_id") val syncId: String,
    val ts: Long
)

/**
 * Records downloaded from other devices, waiting to be merged in. They are
 * applied in one transaction and then cleared, so a crash mid-sync never
 * leaves the library half-updated.
 */
@Entity(tableName = "sync_inbox", primaryKeys = ["kind", "sync_id", "device"])
data class SyncInboxEntity(
    val kind: String,
    @ColumnInfo(name = "sync_id") val syncId: String,
    val device: String,
    @ColumnInfo(name = "record_json") val recordJson: String
)

/**
 * This device's merged copy of every record, with per-field versions. What
 * the device publishes to Drive, and what local edits are compared against.
 */
@Entity(tableName = "sync_meta", primaryKeys = ["kind", "sync_id"], indices = [Index("shard")])
data class SyncMetaEntity(
    val kind: String,
    @ColumnInfo(name = "sync_id") val syncId: String,
    /** 0..15: which of this device's 16 Drive files the record lives in. */
    val shard: Int,
    @ColumnInfo(name = "record_json") val recordJson: String
)

/** Present (one row) only while remote changes are being applied; the outbox triggers check it. */
@Entity(tableName = "sync_applying")
data class SyncApplyingEntity(@PrimaryKey val id: Int = 1)

/** Content hash of each local file, so the same file is never uploaded or downloaded twice. */
@Entity(tableName = "sync_files", indices = [Index("sha256")])
data class SyncFileEntity(
    @PrimaryKey @ColumnInfo(name = "local_uri") val localUri: String,
    val sha256: String
)

/** Shards of this device's Drive copy that changed and still need uploading. */
@Entity(tableName = "sync_dirty")
data class SyncDirtyEntity(@PrimaryKey val shard: Int)
