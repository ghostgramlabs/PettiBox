package com.ghostgramlabs.pettibox.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.SyncDao
import com.ghostgramlabs.pettibox.data.local.SyncDirtyEntity
import com.ghostgramlabs.pettibox.data.local.SyncInboxEntity
import com.ghostgramlabs.pettibox.data.local.SyncMetaEntity
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_ARCH
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TITLE
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TYPE
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_URL
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.KIND_COLLECTION
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.KIND_ITEM
import com.ghostgramlabs.pettibox.data.util.TextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncOutcome {
    /** Up to date. [received] records changed here, [sent] files uploaded, [damaged] files skipped. */
    data class Ok(val received: Int, val sent: Int, val damaged: Int = 0) : SyncOutcome
    data object Off : SyncOutcome
    data object NeedsReconnect : SyncOutcome
    /** Someone deleted the synced data from Drive; sync is now off here (the library stays). */
    data object ResetElsewhere : SyncOutcome
    /** Another device wants to remove or archive [count] saves; waiting for the user. */
    data class NeedsReview(val count: Int) : SyncOutcome
    data class Failed(val message: String, val retry: Boolean) : SyncOutcome
}

/**
 * One sync round:
 *
 * 1. Download other devices' files that changed (md5) into the inbox.
 * 2. First sync only: a save here that matches one in Drive takes its id, so
 *    it isn't duplicated.
 * 3. Fold this device's queued changes (the outbox) into its merged copy.
 * 4. Merge the inbox in and show the result, in one transaction. A round that
 *    would remove or archive many saves waits for the user instead.
 * 5. Upload this device's files that changed.
 *
 * Failure anywhere leaves both sides as they were; the next round picks up.
 * Rounds never overlap (one lock per engine).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: AppDatabase,
    private val syncDao: SyncDao,
    private val prefs: SyncPreferences,
    private val library: LocalLibrary,
    private val transport: SyncTransport,
    private val hooks: SyncHooks
) {
    private val lock = Mutex()

    suspend fun round(now: Long = System.currentTimeMillis()): SyncOutcome = lock.withLock {
        try {
            val outcome = roundLocked(now)
            when (outcome) {
                is SyncOutcome.Ok -> prefs.recordSuccess(keepError = outcome.damaged > 0)
                is SyncOutcome.NeedsReview -> prefs.recordSuccess()
                is SyncOutcome.Failed -> prefs.recordError(outcome.message)
                SyncOutcome.NeedsReconnect -> reconnect()
                SyncOutcome.ResetElsewhere -> {}
                SyncOutcome.Off -> {}
            }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveSyncException) {
            if (e.needsReconnect) {
                reconnect()
                SyncOutcome.NeedsReconnect
            } else {
                val msg = if (e.status == 0) "Couldn't reach Google Drive" else "Google Drive had a problem (${e.status})"
                prefs.recordError(msg)
                SyncOutcome.Failed(msg, retry = e.transient)
            }
        } catch (e: Exception) {
            // Never let sync take the app down; say what happened instead.
            Log.w(TAG, "sync round failed", e)
            val msg = "Sync stopped: ${e.message ?: e.javaClass.simpleName}"
            prefs.recordError(msg)
            SyncOutcome.Failed(msg, retry = true)
        } finally {
            prefs.setProgress("")
        }
    }

    private suspend fun reconnect() {
        prefs.setNeedsReconnect(true)
        prefs.recordError("Google Drive needs reconnecting")
    }

    private suspend fun roundLocked(now: Long): SyncOutcome {
        if (!prefs.isEnabled()) return SyncOutcome.Off
        val session = transport.open() ?: return SyncOutcome.NeedsReconnect
        val me = prefs.deviceId()
        val clock = HybridClock(prefs.clock())
        indexChoices()

        // Before this device's first sync touches anything, keep a copy.
        if (!prefs.firstBackupDone()) {
            hooks.safetyBackup()
            prefs.markFirstBackupDone()
        }

        prefs.setProgress("Checking Google Drive…")
        // Deleted from Google Drive on some device since sync was turned on
        // here? Then stop, rather than upload everything again.
        val marker = session.resetMarker() ?: ""
        val known = prefs.knownReset()
        if (known == null) {
            prefs.setKnownReset(marker)
        } else if (marker != known) {
            prefs.setEnabled(false)
            prefs.resetForDisable()
            prefs.recordError("Sync was turned off because the synced data was deleted from Google Drive on another device. Everything on this phone is still here; turn sync on again to start over.")
            // Last: stopping cancels background sync work, possibly this very round.
            hooks.stopSync()
            return SyncOutcome.ResetElsewhere
        }
        val files = session.list()
        val seen = prefs.seenMd5()
        val newSeen = HashMap(seen)
        val ours = HashMap<String, String>() // our file name -> its id
        var damaged = 0
        var unreadable = 0 // written by a newer app: fine, but this round didn't see all of it
        val devices = LinkedHashMap<String, Pair<String, Long>>()
        for (f in files) {
            val dev = DriveSyncClient.deviceOf(f.name) ?: continue
            if (dev == me) {
                ours[f.name] = f.id
                continue
            }
            if (f.md5.isNotBlank() && seen[f.name] == f.md5) continue
            prefs.setProgress("Receiving changes…")
            val decoded = try {
                SyncCodec.decodeShard(session.read(f), now)
            } catch (e: SyncFormatException) {
                // Skipped whole and reported; never part-applied. Not marked
                // as read, so a fixed copy is picked up next time.
                Log.w(TAG, "skipping ${f.name}: ${e.message}")
                damaged++
                continue
            }
            if (decoded == null) {
                unreadable++ // a newer app's format: leave it for a newer app
                continue
            }
            val shard = decoded
            if (shard.device != dev) {
                damaged++
                continue
            }
            devices[dev] = shard.name to f.modifiedAt
            syncDao.putInbox(shard.records.map { SyncInboxEntity(it.kind, it.id, dev, SyncCodec.encodeString(it)) })
            newSeen[f.name] = f.md5
        }
        if (devices.isNotEmpty()) prefs.rememberDevices(devices)

        val remote = remoteRecords()
        // A device's very first sync only: later duplicates are folded instead
        // (foldDuplicates), the same way on every device and the extension.
        if (prefs.status.first().lastSyncAt == 0L) adoptMatchingSaves(remote)
        commitLocal(me, clock)

        val decision = prefs.takeReviewDecision()
        val applied = applyInbox(remote, me, clock, now, decision)
        if (applied < 0) {
            // Held back for review: still send this device's own changes.
            uploadDirty(session, me, ours, now)
            prefs.saveClock(clock.current)
            prefs.setCleanRoundAt(0L)
            return SyncOutcome.NeedsReview(-applied)
        }
        prefs.saveSeenMd5(newSeen)
        prefs.setPendingReview(0)
        foldDuplicates(me, clock, now)

        // Edits made while the round ran are back in the outbox; send them too.
        commitLocal(me, clock)
        val sent = uploadDirty(session, me, ours, now)
        prefs.saveClock(clock.current)
        session.accountEmail()?.let { prefs.setAccountEmail(it) }
        // Only a round that saw every device's copy may tell which shared files are unused.
        prefs.setCleanRoundAt(if (damaged == 0 && unreadable == 0) now else 0L)
        hooks.syncFiles(prefs.status.first().filesOnWifiOnly)
        if (damaged > 0) {
            prefs.recordError("Skipped $damaged damaged file(s) from another device; nothing from them was applied")
        }
        return SyncOutcome.Ok(applied, sent, damaged)
    }

    /** Inbox rows merged per record (one row per device), collections first. */
    private suspend fun remoteRecords(): List<SyncRecord> {
        val merged = LinkedHashMap<Pair<String, String>, SyncRecord>()
        for (row in syncDao.inbox()) {
            val r = decodeOrNull(row.recordJson) ?: continue
            val key = r.kind to r.id
            merged[key] = merged[key]?.let { merge(it, r) } ?: r
        }
        return merged.values.sortedBy { if (it.kind == KIND_COLLECTION) 0 else 1 }
    }

    /**
     * First sync of saves already here: one that matches a save in Drive
     * (same link, or same type, creation time and title) takes the Drive
     * save's id, so the two merge instead of showing twice.
     */
    private suspend fun adoptMatchingSaves(remote: List<SyncRecord>) {
        if (remote.none { it.kind == KIND_ITEM }) return
        val unsynced = syncDao.unsyncedItems()
        if (unsynced.isEmpty()) return
        val byKey = HashMap<String, String>()
        for (r in remote) {
            if (r.kind != KIND_ITEM || r.isGone) continue
            if (syncDao.itemBySyncId(r.id) != null) continue
            byKey.putIfAbsent(
                dedupeKey(r.value(F_URL) as? String, r.value(F_TYPE) as? String, r.created, r.value(F_TITLE) as? String),
                r.id
            )
        }
        if (byKey.isEmpty()) return
        db.withTransaction {
            syncDao.beginApplying()
            for (s in unsynced) {
                val remoteId = byKey.remove(dedupeKey(s.url, s.contentType, s.createdAt, s.title)) ?: continue
                syncDao.renameItem(s.syncId, remoteId)
                syncDao.renameOutbox(s.syncId, remoteId)
            }
            syncDao.endApplying()
        }
    }

    private fun dedupeKey(url: String?, type: String?, created: Long, title: String?): String =
        url?.trim()?.takeIf { it.isNotEmpty() }?.let { "u:" + TextUtils.urlDedupeKey(it) } ?: "f:$type|$created|$title"

    /** Folds queued local changes into this device's merged copy. */
    private suspend fun commitLocal(me: String, clock: HybridClock) {
        val rows = syncDao.outbox()
        if (rows.isEmpty()) return
        prefs.setProgress("Preparing your changes…")
        for (chunk in rows.chunked(200)) db.withTransaction {
            val dirty = HashSet<Int>()
            val metas = ArrayList<SyncMetaEntity>()
            for (row in chunk) {
                val meta = syncDao.meta(row.kind, row.syncId)?.let { decodeOrNull(it.recordJson) }
                val local = library.snapshot(row.kind, row.syncId, meta)
                val next = if (meta == null) {
                    // First time this record is synced from here: stamp it with
                    // when it was last edited, not now, so an old copy doesn't
                    // win over newer edits made elsewhere (it merges as a
                    // concurrent version instead).
                    if (local == null) null else {
                        val ts = library.editedAt(row.kind, row.syncId)?.takeIf { it > 0 } ?: clock.next()
                        clock.observe(ts)
                        SyncDiff.apply(null, local, Dot(me, ts))
                    }
                } else {
                    SyncDiff.apply(meta, local, Dot(me, clock.next()))
                }
                if (next != null) {
                    val shard = shardOf(next.id)
                    metas += SyncMetaEntity(next.kind, next.id, shard, SyncCodec.encodeString(next))
                    dirty += shard
                }
                syncDao.clearOutbox(row.kind, row.syncId, row.ts)
            }
            if (metas.isNotEmpty()) syncDao.putMeta(metas)
            if (dirty.isNotEmpty()) syncDao.markDirty(dirty.map { SyncDirtyEntity(it) })
            noteChoices(metas)
        }
    }

    /**
     * Merges [remote] into this device's copy and shows it, all in one
     * transaction. Returns how many records changed here, or minus the
     * number of saves waiting for review when the mass-change guard holds
     * the round back.
     */
    private suspend fun applyInbox(
        remote: List<SyncRecord>, me: String, clock: HybridClock, now: Long, decision: String?
    ): Int {
        if (remote.isEmpty()) {
            syncDao.clearInbox() // only rows that couldn't be read, if any
            return 0
        }
        prefs.setProgress("Updating your library…")
        val changes = ArrayList<Pair<SyncRecord?, SyncRecord>>()
        var removals = 0
        for (r in remote) {
            clock.observe(r.maxTs)
            val meta = syncDao.meta(r.kind, r.id)?.let { decodeOrNull(it.recordJson) }
            val merged = meta?.let { merge(it, r) } ?: r
            if (merged == meta) continue
            if (r.kind == KIND_ITEM && wouldRemove(r.id, merged)) removals++
            changes += meta to merged
        }
        if (changes.isEmpty()) {
            syncDao.clearInbox()
            return 0
        }
        val big = removals > MASS_LIMIT || (removals >= 5 && removals * 10 > syncDao.liveCount())
        if (big && decision == null) {
            prefs.setPendingReview(removals)
            return -removals
        }
        if (big) hooks.safetyBackup()

        val followUps = ArrayList<LocalLibrary.Applied>()
        db.withTransaction {
            syncDao.beginApplying()
            val metas = ArrayList<SyncMetaEntity>()
            val dirty = HashSet<Int>()
            for ((before, mergedIn) in changes) {
                var merged = mergedIn
                if (decision == REVIEW_KEEP && merged.kind == KIND_ITEM && wouldRemove(merged.id, merged)) {
                    // "Keep mine": undo the removal for every device by writing
                    // this phone's state back as a new edit.
                    val local = library.snapshot(KIND_ITEM, merged.id, merged)
                    merged = SyncDiff.apply(merged, local, Dot(me, clock.next())) ?: merged
                }
                val shard = shardOf(merged.id)
                metas += SyncMetaEntity(merged.kind, merged.id, shard, SyncCodec.encodeString(merged))
                if (merged != before) dirty += shard
                followUps += library.apply(merged, now)
            }
            syncDao.putMeta(metas)
            if (dirty.isNotEmpty()) syncDao.markDirty(dirty.map { SyncDirtyEntity(it) })
            syncDao.clearInbox()
            syncDao.endApplying()
            noteChoices(metas)
        }
        hooks.afterApply(followUps, now)
        return changes.size
    }

    /**
     * The same link saved on two devices before they synced: fold the newer
     * save into the older (see [SyncDedupe]). The other one goes to Recently
     * deleted here; every device does the same, so they agree.
     */
    private suspend fun foldDuplicates(me: String, clock: HybridClock, now: Long) {
        val groups = syncDao.linkIndex().groupBy { TextUtils.urlDedupeKey(it.url) }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return
        val candidates = groups.flatten().mapNotNull { link ->
            syncDao.meta(KIND_ITEM, link.syncId)?.let { decodeOrNull(it.recordJson) }
        }
        val plan = SyncDedupe.plan(candidates, TextUtils::urlDedupeKey)
        if (plan.isEmpty()) return
        val followUps = ArrayList<LocalLibrary.Applied>()
        db.withTransaction {
            syncDao.beginApplying()
            val metas = ArrayList<SyncMetaEntity>()
            for ((keep0, others) in plan) {
                val dot = Dot(me, clock.next())
                var keep = keep0
                for (other in others) {
                    val (k, removed) = SyncDedupe.fold(keep, other, dot)
                    keep = k
                    metas += SyncMetaEntity(KIND_ITEM, removed.id, shardOf(removed.id), SyncCodec.encodeString(removed))
                    followUps += library.apply(removed, now)
                }
                metas += SyncMetaEntity(KIND_ITEM, keep.id, shardOf(keep.id), SyncCodec.encodeString(keep))
                followUps += library.apply(keep, now)
            }
            syncDao.putMeta(metas)
            syncDao.markDirty(metas.map { SyncDirtyEntity(it.shard) }.distinct())
            syncDao.endApplying()
            noteChoices(metas)
        }
        hooks.afterApply(followUps, now)
    }

    /** Live here now, and the merged state would take it off the shelf. */
    private suspend fun wouldRemove(syncId: String, merged: SyncRecord): Boolean {
        val row = syncDao.itemBySyncId(syncId) ?: return false
        if (row.isPendingDelete || row.isArchived) return false
        return merged.isGone || merged.isTrashed || merged.value(F_ARCH) == true
    }

    /** Uploads this device's files that changed. Returns how many. */
    private suspend fun uploadDirty(session: SyncTransport.Session, me: String, ours: Map<String, String>, now: Long): Int {
        val dirty = syncDao.dirtyShards()
        if (dirty.isEmpty()) return 0
        prefs.setProgress("Sending your changes…")
        val name = prefs.deviceName()
        var sent = 0
        for (shard in dirty.sorted()) {
            val records = syncDao.metaInShard(shard).mapNotNull { decodeOrNull(it.recordJson) }
            val fileName = DriveSyncClient.shardName(me, shard)
            session.write(fileName, SyncCodec.encodeShard(me, name, shard, now, records), ours[fileName])
            syncDao.clearDirty(shard)
            sent++
        }
        return sent
    }

    private fun decodeOrNull(json: String): SyncRecord? = runCatching { SyncCodec.decodeString(json) }.getOrNull()

    /** Keeps the "needs a choice" list (the pulsing saves) in step with records just written. */
    private suspend fun noteChoices(written: Collection<SyncMetaEntity>) {
        val items = written.filter { it.kind == KIND_ITEM }
        if (items.isEmpty()) return
        val (need, fine) = items.partition { decodeOrNull(it.recordJson)?.needsChoice == true }
        prefs.updateNeedingChoice(add = need.map { it.syncId }, remove = fine.map { it.syncId })
    }

    /** Once, for copies synced before the list existed. */
    private suspend fun indexChoices() {
        if (prefs.needingChoiceIndexed()) return
        noteChoices(syncDao.metaOfKind(KIND_ITEM))
        prefs.markNeedingChoiceIndexed()
    }

    /**
     * Settles a field edited on two devices by keeping the value shown now.
     * (Picking the other value is an ordinary edit of the save.) Written as
     * a new edit so every device drops the other version.
     */
    suspend fun keepShown(syncId: String, field: String) = lock.withLock {
        val r = syncDao.meta(KIND_ITEM, syncId)?.let { decodeOrNull(it.recordJson) } ?: return@withLock
        if (r.alternatives(field).isEmpty()) return@withLock
        val clock = HybridClock(prefs.clock())
        val next = SyncWrite.field(r, field, r.value(field), Dot(prefs.deviceId(), clock.next()))
        val row = SyncMetaEntity(KIND_ITEM, syncId, shardOf(syncId), SyncCodec.encodeString(next))
        db.withTransaction {
            syncDao.putMeta(listOf(row))
            syncDao.markDirty(listOf(SyncDirtyEntity(shardOf(syncId))))
        }
        noteChoices(listOf(row))
        prefs.saveClock(clock.current)
    }

    companion object {
        private const val TAG = "PettiBoxSync"

        /** More removals than this in one round wait for the user. */
        const val MASS_LIMIT = 25

        const val REVIEW_APPLY = "apply"
        const val REVIEW_KEEP = "keep"
    }
}
