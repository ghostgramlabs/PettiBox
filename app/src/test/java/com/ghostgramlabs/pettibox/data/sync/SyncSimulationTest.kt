package com.ghostgramlabs.pettibox.data.sync

import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_FAV
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_NOTES
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_OCR
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TITLE
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TRASH
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.KIND_ITEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Many devices editing one library at random and syncing in random orders,
 * some uploads lost on the way, checked against an oracle that remembers
 * every write ever made:
 *
 * 1. Devices converge: after everyone has synced with everyone, every copy is
 *    identical and every phone shows the same library.
 * 2. Nothing is lost: every value anyone wrote is either still there (shown,
 *    or kept as the other version of a conflict) or was replaced by an edit
 *    whose device had seen it, or removed by a delete whose device had seen it.
 * 3. A save only leaves a phone through Recently deleted then a permanent
 *    removal; it is never dropped from a phone that still shows it live.
 */
class SyncSimulationTest {

    private class Device(val name: String, seed: Long) {
        val rnd = Random(seed)
        var wall = 1_000_000L + rnd.nextInt(5_000) // clocks disagree
        val clock = HybridClock(0) { wall }
        val meta = HashMap<String, SyncRecord>()
        val local = HashMap<String, LocalRecord>()
        var published: Map<String, SyncRecord> = emptyMap()
    }

    /** Every write: record id, field (or "tag:x"/"gone"), value, dot, what its device had seen. */
    private data class Write(val id: String, val what: String, val value: Any?, val dot: Dot, val seen: Ctx)

    private val writes = ArrayList<Write>()
    private var trace = false
    private fun log(msg: String) { if (trace) println(msg) }

    @Test fun `random edits and syncs always converge without losing data`() {
        val runs = (System.getProperty("syncSimRuns") ?: "1500").toInt()
        val only = System.getProperty("syncSimSeed")?.toLongOrNull()
        trace = only != null
        for (seed in (if (only != null) only..only else 1L..runs)) {
            writes.clear()
            try {
                runOnce(seed)
            } catch (e: Throwable) {
                throw AssertionError("seed $seed: ${e.message}", e)
            }
        }
    }

    private fun runOnce(seed: Long) {
        val rnd = Random(seed)
        val devices = (0 until 3 + rnd.nextInt(3)).map { Device("D$it", seed * 31 + it) }
        val ids = (0 until 4).map { "s$it" }
        repeat(60 + rnd.nextInt(60)) {
            val d = devices[rnd.nextInt(devices.size)]
            d.wall += rnd.nextInt(40)
            when (rnd.nextInt(10)) {
                in 0..5 -> localEdit(d, ids[rnd.nextInt(ids.size)])
                in 6..7 -> pull(d, devices[rnd.nextInt(devices.size)])
                else -> if (rnd.nextInt(4) != 0) publish(d) // sometimes the upload fails
            }
        }
        // Everyone syncs with everyone, twice, with no failures.
        repeat(2) {
            devices.forEach(::publish)
            for (d in devices) for (e in devices) pull(d, e)
        }
        devices.forEach(::publish)
        for (d in devices) for (e in devices) pull(d, e)

        // 1. Convergence.
        val reference = devices[0]
        for (d in devices.drop(1)) {
            assertEquals("copies differ between ${reference.name} and ${d.name}", reference.meta, d.meta)
            assertEquals("shown libraries differ", visible(reference), visible(d))
        }

        // 2. Nothing lost.
        val finalState = reference.meta
        val memo = HashMap<Write, Boolean>()
        for (w in writes) {
            if (w.what == "gone" || w.value == null && w.what.startsWith("tag:")) continue
            if (!accountedFor(w, finalState, memo)) {
                val r = finalState[w.id]
                fail("write $w lost; now ${r?.fields?.get(w.what)} tags=${r?.tags} gone=${r?.gone}")
            }
        }
    }

    /**
     * A write is accounted for when it is still in the final state, or was
     * removed for good by a device that had seen it, or was replaced by a
     * later write (or tag removal) whose device had seen it and which is in
     * turn accounted for.
     */
    private fun accountedFor(w: Write, state: Map<String, SyncRecord>, memo: HashMap<Write, Boolean>): Boolean {
        memo[w]?.let { return it }
        memo[w] = false // cycles are impossible (seen grows), but stay safe
        val r = state[w.id] ?: return false
        val ok = when {
            r.isGone && r.gone!!.covers(w.dot) -> true
            w.what.startsWith("tag:") ->
                r.tags[w.what.removePrefix("tag:")].orEmpty().any { it.dot == w.dot } ||
                    // Removed, or added again, by a device that had seen it.
                    writes.any { l ->
                        l.id == w.id && l.what == w.what && l.dot != w.dot && l.seen.covers(w.dot) &&
                            (l.value == null || accountedFor(l, state, memo))
                    }
            r.fields[w.what].orEmpty().any { it.dot == w.dot } -> true
            else -> writes.any { l ->
                l.id == w.id && l.what == w.what && l.dot != w.dot && l.seen.covers(w.dot) &&
                    accountedFor(l, state, memo)
            }
        }
        memo[w] = ok
        return ok
    }

    /**
     * What a phone shows, minus one allowed difference: a save removed for
     * good on another device while it was live here sits in this phone's
     * Recently deleted for its own 30 days (rule: sync never permanently
     * deletes a live save). Every other save must match exactly.
     */
    private fun visible(d: Device): Map<String, LocalRecord> =
        d.local.filter { (id, rec) -> !(rec.trashed && d.meta[id]?.isGone == true) }.toSortedMap()

    private fun localEdit(d: Device, id: String) {
        val cur = d.local[id]
        val rnd = d.rnd
        val next: LocalRecord? = when {
            cur == null -> if (d.meta[id]?.isGone == true && rnd.nextBoolean()) null else LocalRecord(
                kind = KIND_ITEM, id = id, created = 1L,
                // Like LocalLibrary: every known field, null when empty.
                fields = mapOf(F_TITLE to "t-${d.name}-${d.wall}", F_NOTES to null, F_FAV to false, F_TRASH to null, F_OCR to null)
            )
            cur.trashed -> when (rnd.nextInt(3)) {
                0 -> null // delete for good
                1 -> cur.copy(fields = cur.fields + (F_TRASH to null)) // restore
                else -> cur
            }
            else -> when (rnd.nextInt(7)) {
                0 -> cur.copy(fields = cur.fields + (F_TITLE to "t-${d.name}-${d.wall}"))
                1 -> cur.copy(fields = cur.fields + (F_NOTES to "n-${d.name}-${d.wall}"))
                2 -> cur.copy(fields = cur.fields + (F_FAV to !(cur.fields[F_FAV] as Boolean)))
                3 -> rnd.nextInt(3).let { cur.copy(tags = cur.tags + ("t$it" to "T$it")) }
                4 -> if (cur.tags.isEmpty()) cur else cur.copy(tags = cur.tags - cur.tags.keys.first())
                5 -> cur.copy(fields = cur.fields + (F_TRASH to d.wall))
                else -> cur.copy(fields = cur.fields + (F_OCR to "scan-${d.wall}"))
            }
        }
        if (next == null) d.local.remove(id) else d.local[id] = next
        log("${d.name} edit $id -> ${next?.fields?.get(F_TRASH)?.let { "trash=$it" } ?: if (next == null) "REMOVED" else "live"} ${next?.fields}")
        commit(d, id)
    }

    /** The outbox step: fold local state into the merged copy, logging every write. */
    private fun commit(d: Device, id: String) {
        val before = d.meta[id]
        val dot = Dot(d.name, d.clock.next())
        val after = SyncDiff.apply(before, d.local[id], dot)
        log("   commit ${d.name} $id: ${if (after == null) "no change" else "gone=${after.isGone} trashed=${after.isTrashed} trashAt=${after.trashedAt}"} (before gone=${before?.isGone} trashed=${before?.isTrashed})")
        if (after == null) return
        d.meta[id] = after
        val seen = before?.vv ?: emptyMap()
        for ((field, heads) in after.fields) for (h in heads) if (h.dot == dot) {
            writes += Write(id, field, h.value, dot, seen)
        }
        for ((key, dots) in after.tags) for (t in dots) if (t.dot == dot) writes += Write(id, "tag:$key", t.name, dot, seen)
        // Tag removals and permanent removals are "writes" too: they supersede what was seen.
        for (key in before?.tags?.keys.orEmpty() - after.tags.keys) writes += Write(id, "tag:$key", null, dot, seen)
        if (after.gone != null && after.gone != before?.gone) writes += Write(id, "gone", null, dot, seen)
    }

    private fun publish(d: Device) {
        d.published = HashMap(d.meta)
    }

    /** d downloads e's last published copy, merges, and updates what its phone shows. */
    private fun pull(d: Device, e: Device) {
        if (d === e) return
        for ((id, theirs) in e.published) {
            val mine = d.meta[id]
            val merged = if (mine == null) theirs else merge(mine, theirs)
            d.clock.observe(merged.maxTs)
            if (merged == mine) continue
            d.meta[id] = merged
            applyToLocal(d, id, merged)
            log("   ${d.name} <- ${e.name} $id: gone=${merged.isGone} trashed=${merged.isTrashed} local=${d.local[id]?.fields?.get(F_TRASH)} exists=${d.local.containsKey(id)}")
        }
    }

    /** What LocalLibrary does: show the merged values, never dropping a live save. */
    private fun applyToLocal(d: Device, id: String, r: SyncRecord) {
        val cur = d.local[id]
        if (r.isGone) {
            when {
                cur == null -> {}
                cur.trashed -> d.local.remove(id) // already in Recently deleted: remove for good
                else -> d.local[id] = cur.copy(fields = cur.fields + (F_TRASH to d.wall)) // fresh 30 days
            }
            return
        }
        d.local[id] = LocalRecord(
            kind = r.kind, id = id, created = r.created,
            fields = mapOf(
                F_TITLE to r.value(F_TITLE), F_NOTES to r.value(F_NOTES), F_FAV to (r.value(F_FAV) ?: false),
                F_TRASH to r.trashedAt, F_OCR to r.value(F_OCR)
            ),
            tags = r.presentTags()
        )
        assertTrue(d.local[id]!!.fields[F_TITLE] != null)
    }
}
