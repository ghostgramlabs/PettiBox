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
 * A round is not one transaction across devices: steps that finished stay
 * done (local changes recorded, some files sent) and every step is safe to
 * repeat, so a failure part-way is finished by the next round. Each local
 * step is all-or-nothing on its own. Rounds never overlap (one lock per engine).
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

    /** Tests only: runs just before incoming changes are applied, to stand in for an edit at that moment. */
    @androidx.annotation.VisibleForTesting
    internal var beforeApply: suspend () -> Unit = {}

    suspend fun round(now: Long = System.currentTimeMillis()): SyncOutcome = lock.withLock {
        val started = System.currentTimeMillis()
        try {
            val outcome = roundLocked(now)
            // A warning this round recorded (a skipped file, a newer app, no
            // safety copy) stays on show although the round itself succeeded.
            val warned = prefs.status.first().let { it.lastError.isNotBlank() && it.lastErrorAt >= started }
            when (outcome) {
                is SyncOutcome.Ok -> prefs.recordSuccess(keepError = warned)
                is SyncOutcome.NeedsReview -> prefs.recordSuccess(keepError = warned)
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
                // Which step, and Drive's own code, so a problem can be told apart from the next.
                val step = prefs.status.first().progress.removeSuffix("…").lowercase().ifBlank { "connecting" }
                Log.w(TAG, "sync round failed while $step", e)
                val what = if (e.status == 0) "Couldn't reach Google Drive" else "Google Drive answered with an error (${e.status})"
                val msg = "$what while ${step.replace("checking google drive", "checking for changes")}." +
                    if (e.transient) " Trying again by itself shortly." else ""
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

    /** The synced data was deleted from Drive on another device: stop here; the library stays. */
    private suspend fun stopForReset(): SyncOutcome {
        prefs.setEnabled(false)
        prefs.resetForDisable()
        prefs.recordError("Sync was turned off because the synced data was deleted from Google Drive on another device. Everything on this phone is still here; turn sync on again to start over.")
        // Last: stopping cancels background sync work, possibly this very round.
        hooks.stopSync()
        return SyncOutcome.ResetElsewhere
    }

    /**
     * Before writing records stamped by [clock]: saves a ceiling above every
     * stamp they can carry, so a phone that dies before the round's end
     * never starts again below a stamp it already wrote (which would make its
     * next edit lose to its own older one).
     */
    private suspend fun reserve(clock: HybridClock, count: Int) {
        val ceiling = maxOf(clock.current, System.currentTimeMillis()) + count + CLOCK_MARGIN_MS
        prefs.saveClock(ceiling)
        // Enforced, not assumed: however long the writes take, no stamp passes it.
        clock.ceiling = ceiling
    }

    private suspend fun reconnect() {
        prefs.setNeedsReconnect(true)
        prefs.recordError("Google Drive needs reconnecting")
    }

    private suspend fun roundLocked(now: Long): SyncOutcome {
        if (!prefs.isEnabled()) return SyncOutcome.Off
        // Until this round ends well, no earlier round vouches for which shared files are unused.
        prefs.setCleanRoundAt(0L)
        val session = transport.open() ?: return SyncOutcome.NeedsReconnect
        // This library syncs with one Google account. If Drive is now signed
        // in as another (switched in Backup settings, say), touch nothing.
        val account = session.accountEmail() ?: throw DriveSyncException(503, "Google Drive didn't say which account this is")
        val owner = prefs.status.first().accountEmail
        if (owner.isNotBlank() && !account.equals(owner, ignoreCase = true)) {
            return SyncOutcome.Failed(
                "Google Drive is signed in as $account, but this library syncs with $owner. Switch back to $owner, or turn sync off and on again to sync with $account.",
                retry = false
            )
        }
        if (owner.isBlank()) prefs.setAccountEmail(account)
        // Follow-up work (reminders, link pictures) a round applied but didn't get to finish.
        prefs.pendingFollowUps().takeIf { it.isNotEmpty() }?.let { ids ->
            hooks.afterApply(library.followUpsFor(ids), now)
            prefs.setPendingFollowUps(emptyList())
        }
        val (me, copied) = prefs.ownDeviceId()
        if (copied) {
            // Moved here from another phone: publish everything under this
            // phone's new id; the old phone keeps its own.
            syncDao.markDirty((0 until 16).map { SyncDirtyEntity(it) })
        }
        val clock = HybridClock(prefs.clock())
        indexChoices()

        // Before this device's first sync touches anything, keep a copy. If
        // that fails (no space, say) it's tried again next round; sync goes on,
        // since it never deletes anything.
        if (!prefs.firstBackupDone()) {
            if (hooks.safetyBackup()) prefs.markFirstBackupDone()
            else prefs.recordError("Couldn't save a safety copy of your library before syncing. It will be tried again next time.")
        }

        prefs.setProgress("Checking Google Drive…")
        // Deleted from Google Drive on some device since sync was turned on
        // here? Then stop, rather than upload everything again.
        val marker = session.resetMarker() ?: ""
        val known = prefs.knownReset()
        if (known == null) {
            prefs.setKnownReset(marker)
        } else if (marker != known) {
            return stopForReset()
        }
        val epoch = known ?: marker
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
            if (shard.device != dev || shard.shard != DriveSyncClient.shardOfName(f.name)) {
                damaged++
                continue
            }
            // Every record must be in the file its id belongs to.
            if (shard.records.any { shardOf(it.id) != shard.shard }) {
                damaged++
                continue
            }
            // Written before the last "Delete synced data" by a device that
            // hadn't noticed yet: not part of this library any more.
            if (shard.epoch != epoch) {
                newSeen[f.name] = f.md5
                continue
            }
            devices[dev] = shard.name to f.modifiedAt
            syncDao.putInbox(shard.records.map { SyncInboxEntity(it.kind, it.id, dev, SyncCodec.encodeString(it)) })
            newSeen[f.name] = f.md5
        }
        if (devices.isNotEmpty()) prefs.rememberDevices(devices)
        // This phone's own files that Drive no longer has (removed by hand,
        // say): send them again. A reset was ruled out above.
        val missing = syncDao.shardsInUse().filter { ours[DriveSyncClient.shardName(me, it)] == null }
        if (missing.isNotEmpty()) syncDao.markDirty(missing.map { SyncDirtyEntity(it) })

        val remote = remoteRecords()
        // A device's very first sync only: later duplicates are folded instead
        // (foldDuplicates), the same way on every device and the extension.
        if (prefs.status.first().lastSyncAt == 0L) adoptMatchingSaves(remote)
        commitLocal(me, clock, remote.filter { it.kind == KIND_COLLECTION }.mapTo(HashSet()) { it.id })

        val decision = prefs.takeReviewDecision()
        beforeApply()
        val applied = applyInbox(remote, me, clock, now, decision)
        if (applied < 0) {
            // Held back for review: still send this device's own changes.
            if (uploadDirty(session, me, ours, now, epoch) < 0) return stopForReset()
            prefs.saveClock(clock.current)
            return SyncOutcome.NeedsReview(-applied)
        }
        prefs.saveSeenMd5(newSeen)
        prefs.setPendingReview(0)
        settleMarks(me, clock, now)
        foldDuplicates(me, clock, now)

        // Edits made while the round ran are back in the outbox; send them too.
        commitLocal(me, clock)
        val sent = uploadDirty(session, me, ours, now, epoch)
        if (sent < 0) return stopForReset()
        prefs.saveClock(clock.current)
        // Only a round that saw every device's copy may tell which shared files are unused.
        prefs.setCleanRoundAt(if (damaged == 0 && unreadable == 0) now else 0L)
        hooks.syncFiles(prefs.status.first().filesOnWifiOnly)
        if (damaged > 0) {
            prefs.recordError("Skipped $damaged damaged file(s) from another device; nothing from them was applied")
        } else if (unreadable > 0) {
            prefs.recordError("Another device uses a newer version of PettiBox. Update this app to see everything it saved.")
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

    /**
     * Folds queued local changes into this device's merged copy.
     * [remoteCollections]: collections other devices sent this round.
     */
    private suspend fun commitLocal(me: String, clock: HybridClock, remoteCollections: Set<String> = emptySet()) {
        val rows = syncDao.outbox()
        if (rows.isEmpty()) return
        prefs.setProgress("Preparing your changes…")
        for (chunk in rows.chunked(200)) {
          reserve(clock, chunk.size)
          db.withTransaction {
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
                    if (local == null) null
                    // A starter this phone just seeded and nobody touched, that
                    // other devices already have: take theirs (renamed, new
                    // icon, or deleted) instead of sending the defaults back.
                    else if (row.kind == KIND_COLLECTION && row.syncId in remoteCollections && library.isUntouchedStarter(row.syncId)) null
                    else {
                        val ts = library.editedAt(row.kind, row.syncId)?.takeIf { it > 0 }
                            ?.coerceAtMost(System.currentTimeMillis()) ?: clock.next()
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
        val removing = ArrayList<String>()
        for (r in remote) {
            clock.observe(r.maxTs)
            val meta = syncDao.meta(r.kind, r.id)?.let { decodeOrNull(it.recordJson) }
            val merged = meta?.let { merge(it, r) } ?: r
            if (merged == meta) continue
            if (r.kind == KIND_ITEM && wouldRemove(r.id, merged)) removing += r.id
            changes += meta to merged
        }
        if (changes.isEmpty()) {
            syncDao.clearInbox()
            return 0
        }
        val removals = removing.size
        // Only what looks like a wipe: deleting a handful of saves on another
        // phone is everyday tidying (they wait in Recently deleted here anyway),
        // and holding it back would pause every other change too.
        val big = removals > MASS_LIMIT || (removals >= MASS_MIN && removals * 2 > syncDao.liveCount())
        // A choice holds only for the saves it was shown for: if more
        // removals arrived since, ask again.
        val key = removing.sorted().joinToString(",")
        val choice = decision?.takeIf { prefs.reviewKey() == key }
        if (big && choice == null) {
            prefs.setReviewKey(key)
            prefs.setPendingReview(removals)
            return -removals
        }
        if (big && !hooks.safetyBackup()) {
            // No safety copy, no mass removal: ask again once there's room.
            prefs.recordError("Couldn't save a safety copy before removing saves, so nothing was removed. Free some space and choose again.")
            prefs.setPendingReview(removals)
            return -removals
        }
        reserve(clock, changes.size * 3)
        // Remembered before applying, so reminders and link pictures for
        // these saves are still set up if the app stops right after.
        prefs.setPendingFollowUps(changes.map { it.second }.filter { it.kind == KIND_ITEM }.map { it.id })

        val followUps = ArrayList<LocalLibrary.Applied>()
        db.withTransaction {
            syncDao.beginApplying()
            val metas = ArrayList<SyncMetaEntity>()
            val dirty = HashSet<Int>()
            for ((before, mergedIn) in changes) {
                var merged = mergedIn
                // Edited here since this round gathered local changes: record
                // that edit now, on top of what it was based on, and merge it
                // in, so the copy from another device can't overwrite it.
                syncDao.outboxRow(merged.kind, merged.id)?.let { row ->
                    // No local copy means it was removed for good here: that
                    // removal is recorded too (it only wins over writes it saw).
                    val local = library.snapshot(merged.kind, merged.id, before)
                    val mine = if (before == null && local == null) null else SyncDiff.apply(before, local, Dot(me, clock.next()))
                    if (mine != null) merged = merge(mine, merged)
                    syncDao.clearOutbox(row.kind, row.syncId, row.ts)
                }
                // Revived by this phone's own edit over a stripped deletion: refill what it lacks.
                val gone = merged.gone
                if (gone != null && !merged.isGone && before != null && !before.isGone &&
                    merged.userDotsOf(me).any { !gone.covers(it) }
                ) {
                    library.snapshot(merged.kind, merged.id, before)?.let { merged = refill(merged, it, Dot(me, clock.next())) }
                }
                if (choice == REVIEW_KEEP && merged.kind == KIND_ITEM && wouldRemove(merged.id, merged)) {
                    // "Keep mine": undo the removal for every device by writing
                    // this phone's state back as a new edit.
                    val local = library.snapshot(KIND_ITEM, merged.id, merged)
                    merged = SyncDiff.apply(merged, local, Dot(me, clock.next())) ?: merged
                }
                // Marked on one device, unknowingly cleared by another's copy: the mark stands.
                if (SyncMarks.unsettled(merged, now)) merged = SyncMarks.settle(merged, Dot(me, clock.next()), now)
                val shard = shardOf(merged.id)
                metas += SyncMetaEntity(merged.kind, merged.id, shard, SyncCodec.encodeString(merged))
                if (merged != before) dirty += shard
                followUps += library.apply(merged, now)
            }
            syncDao.putMeta(metas)
            // Each device's copy is 16 files, read as they come: a save can
            // arrive before its collection. Once the collection is here, file
            // the saves (and sub-collections) that were waiting for it.
            val arrived = changes.map { it.second }.filter { it.kind == KIND_COLLECTION && !it.isGone }.map { it.id }.toSet()
            if (arrived.isNotEmpty()) linkWaiting(arrived)
            if (dirty.isNotEmpty()) syncDao.markDirty(dirty.map { SyncDirtyEntity(it) })
            syncDao.clearInbox()
            syncDao.endApplying()
            noteChoices(metas)
        }
        hooks.afterApply(followUps, now)
        prefs.setPendingFollowUps(emptyList())
        return changes.size
    }

    /**
     * Once, for copies merged before marks were settled as they arrive (see
     * [SyncMarks]): favorites, pins and reminders another phone's copy
     * cleared come back. A save edited here this very moment waits for the
     * next round, so that edit isn't written over.
     */
    private suspend fun settleMarks(me: String, clock: HybridClock, now: Long) {
        if (prefs.marksSettled()) return
        val unsettled = syncDao.metaOfKind(KIND_ITEM).mapNotNull { decodeOrNull(it.recordJson) }
            .filter { SyncMarks.unsettled(it, now) }
        var waiting = false
        if (unsettled.isNotEmpty()) {
            val followUps = ArrayList<LocalLibrary.Applied>()
            reserve(clock, unsettled.size)
            prefs.setPendingFollowUps(unsettled.map { it.id })
            db.withTransaction {
                syncDao.beginApplying()
                val metas = ArrayList<SyncMetaEntity>()
                for (r in unsettled) {
                    if (syncDao.outboxRow(KIND_ITEM, r.id) != null) {
                        waiting = true
                        continue
                    }
                    val settled = SyncMarks.settle(r, Dot(me, clock.next()), now)
                    metas += SyncMetaEntity(KIND_ITEM, settled.id, shardOf(settled.id), SyncCodec.encodeString(settled))
                    followUps += library.apply(settled, now)
                }
                syncDao.putMeta(metas)
                syncDao.markDirty(metas.map { SyncDirtyEntity(it.shard) }.distinct())
                syncDao.endApplying()
            }
            hooks.afterApply(followUps, now)
            prefs.setPendingFollowUps(emptyList())
        }
        if (!waiting) prefs.markMarksSettled()
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
        reserve(clock, plan.size)
        prefs.setPendingFollowUps(plan.flatMap { (keep, others) -> listOf(keep.id) + others.map { it.id } })
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
        prefs.setPendingFollowUps(emptyList())
    }

    /** Live here now, and the merged state would take it off the shelf. */
    private suspend fun wouldRemove(syncId: String, merged: SyncRecord): Boolean {
        val row = syncDao.itemBySyncId(syncId) ?: return false
        if (row.isPendingDelete || row.isArchived) return false
        return merged.isGone || merged.isTrashed || merged.value(F_ARCH) == true
    }

    /** Uploads this device's files that changed. Returns how many. */
    /**
     * Sends this phone's changed files, stamped with the reset [epoch]. The
     * marker is checked again before each one, so a "Delete synced data" made
     * meanwhile isn't undone (returns -1 then); readers skip files from
     * another epoch, which covers the moment between check and send.
     */
    private suspend fun uploadDirty(session: SyncTransport.Session, me: String, ours: Map<String, String>, now: Long, epoch: String): Int {
        val dirty = syncDao.dirtyShards()
        if (dirty.isEmpty()) return 0
        prefs.setProgress("Sending your changes…")
        val name = prefs.deviceName()
        var sent = 0
        for (shard in dirty.sorted()) {
            if ((session.resetMarker() ?: "") != epoch) return -1
            val records = syncDao.metaInShard(shard).mapNotNull { decodeOrNull(it.recordJson) }
            val fileName = DriveSyncClient.shardName(me, shard)
            session.write(fileName, SyncCodec.encodeShard(me, name, shard, now, records, epoch), ours[fileName])
            syncDao.clearDirty(shard)
            sent++
        }
        return sent
    }

    private fun decodeOrNull(json: String): SyncRecord? = runCatching { SyncCodec.decodeString(json) }.getOrNull()

    /** Files saves and sub-collections into the collections in [arrived], where their synced copy says so. */
    private suspend fun linkWaiting(arrived: Set<String>) {
        for (row in syncDao.metaOfKind(KIND_ITEM)) {
            val r = decodeOrNull(row.recordJson) ?: continue
            val cat = r.value(SyncRecord.F_CAT) as? String ?: continue
            if (cat in arrived && !r.isGone) syncDao.linkCategory(r.id, cat)
        }
        for (row in syncDao.metaOfKind(KIND_COLLECTION)) {
            val r = decodeOrNull(row.recordJson) ?: continue
            val parent = r.value(SyncRecord.F_PARENT) as? String ?: continue
            if (parent in arrived && !r.isGone) syncDao.linkParent(r.id, parent)
        }
    }

    /**
     * Strips records removed for good long ago to their bare markers, so a
     * deleted save's title, note and link no longer sit in anyone's Drive.
     * Only those no other device's copy still holds as not removed (see
     * [DriveFileCleanup.compactable]); [published] is every device file now
     * in Drive. The marker (its clock and what the deletion saw) stays, so
     * the save can never come back from an old copy.
     */
    suspend fun compact(published: Map<String, DriveFileCleanup.Published>, now: Long = System.currentTimeMillis()): Int = lock.withLock {
        if (!prefs.isEnabled()) return@withLock 0
        val me = prefs.deviceId()
        val holding = HashMap<Int, MutableSet<String>>()
        for ((name, p) in published) {
            val dev = DriveSyncClient.deviceOf(name) ?: continue
            if (dev == me) continue
            val n = DriveSyncClient.shardOfName(name) ?: continue
            holding.getOrPut(n) { HashSet() } += p.live
        }
        val rows = (syncDao.metaOfKind(KIND_ITEM) + syncDao.metaOfKind(KIND_COLLECTION)).mapNotNull { row ->
            val r = decodeOrNull(row.recordJson) ?: return@mapNotNull null
            if (!DriveFileCleanup.compactable(r, holding[row.shard].orEmpty(), now)) return@mapNotNull null
            SyncMetaEntity(r.kind, r.id, row.shard, SyncCodec.encodeString(r.copy(fields = emptyMap(), tags = emptyMap(), files = emptyMap())))
        }
        if (rows.isEmpty()) return@withLock 0
        db.withTransaction {
            syncDao.putMeta(rows)
            syncDao.markDirty(rows.map { SyncDirtyEntity(it.shard) }.distinct())
        }
        rows.size
    }

    /**
     * A save this phone held, brought back by an edit made here without
     * knowing it was deleted for good elsewhere, after that deletion was
     * stripped to its marker: what the marker no longer carries (title, link,
     * tags, files) is written again from this phone's own copy, so the save
     * comes back whole. Nothing that is there is overwritten.
     */
    private fun refill(merged: SyncRecord, local: LocalRecord, dot: Dot): SyncRecord {
        var r = merged
        for ((name, v) in local.fields) {
            if (v != null && r.fields[name].isNullOrEmpty()) r = SyncWrite.field(r, name, v, dot)
        }
        if (r.presentTags().isEmpty()) for ((key, name) in local.tags) r = SyncWrite.addTag(r, key, name, dot)
        if (r.presentFiles().isEmpty()) for ((sha, f) in local.files) r = SyncWrite.addFile(r, sha, f.first, f.second, dot)
        return r
    }

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
        clock.observe(r.maxTs)
        val next = SyncWrite.field(r, field, r.value(field), Dot(prefs.deviceId(), clock.next()))
        val row = SyncMetaEntity(KIND_ITEM, syncId, shardOf(syncId), SyncCodec.encodeString(next))
        prefs.saveClock(clock.current) // before the write: the clock never falls behind a saved stamp
        db.withTransaction {
            syncDao.putMeta(listOf(row))
            syncDao.markDirty(listOf(SyncDirtyEntity(shardOf(syncId))))
        }
        noteChoices(listOf(row))
    }

    companion object {
        private const val TAG = "PettiBoxSync"

        /** More removals than this in one round wait for the user. */
        const val MASS_LIMIT = 25
        /** In a small library, at least this many removals, taking more than half of it, wait too. */
        const val MASS_MIN = 10
        /** Headroom in a saved clock ceiling, so stamps can follow the wall clock for a while (the ceiling is enforced). */
        const val CLOCK_MARGIN_MS = 60_000L

        const val REVIEW_APPLY = "apply"
        const val REVIEW_KEEP = "keep"
    }
}
