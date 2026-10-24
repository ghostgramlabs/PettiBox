package com.ghostgramlabs.pettibox.data.sync

import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_FAV
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_NOTES
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_OCR
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TITLE
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_TRASH
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.F_URL
import com.ghostgramlabs.pettibox.data.sync.SyncRecord.Companion.KIND_ITEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModelTest {

    private fun item(title: String, extra: Map<String, Any?> = emptyMap(), tags: Map<String, String> = emptyMap()) =
        LocalRecord(
            kind = KIND_ITEM, id = "s1", created = 100L,
            fields = mapOf(F_TITLE to title, F_URL to "https://a.example", F_NOTES to null, F_FAV to false, F_TRASH to null) + extra,
            tags = tags
        )

    private fun create(dev: String, ts: Long, local: LocalRecord) = SyncDiff.apply(null, local, Dot(dev, ts))!!

    // ── Field edits ──────────────────────────────────────────────────────

    @Test fun `unchanged local state writes nothing`() {
        val r = create("A", 1, item("Hello"))
        assertNull(SyncDiff.apply(r, item("Hello"), Dot("A", 2)))
    }

    @Test fun `edits to different fields on two devices both survive`() {
        val base = create("A", 1, item("Hello"))
        val onA = SyncDiff.apply(base, item("Hello there"), Dot("A", 5))!!
        val onB = SyncDiff.apply(base, item("Hello", mapOf(F_NOTES to "a note")), Dot("B", 6))!!
        val m = merge(onA, onB)
        assertEquals("Hello there", m.value(F_TITLE))
        assertEquals("a note", m.value(F_NOTES))
        assertFalse(m.hasConflict)
    }

    @Test fun `a favorite pin or reminder another device's copy cleared without seeing it stands`() {
        // Two phones holding the same save sync for the first time; B's copy was touched last.
        val onA = create("A", 5, item("Hello", mapOf(F_FAV to true, SyncRecord.F_PIN to true, SyncRecord.F_REMIND to 9_000L)))
        val onB = create("B", 6, item("Hello", mapOf(SyncRecord.F_PIN to false, SyncRecord.F_REMIND to null)))
        val m = merge(onA, onB)
        assertEquals(false, m.value(F_FAV))
        assertTrue(SyncMarks.unsettled(m, now = 1_000L))
        val settled = SyncMarks.settle(m, Dot("B", 7), now = 1_000L)
        assertEquals(true, settled.value(F_FAV))
        assertEquals(true, settled.value(SyncRecord.F_PIN))
        assertEquals(9_000L, settled.value(SyncRecord.F_REMIND))
        // Settled for everyone, whichever phone did it, and only once.
        for (other in listOf(onA, onB, m)) assertEquals(settled, merge(other, settled))
        assertFalse(SyncMarks.unsettled(settled, now = 1_000L))
        // A reminder whose time has passed stays cleared.
        assertNull(SyncMarks.settle(m, Dot("B", 7), now = 10_000L).value(SyncRecord.F_REMIND))
    }

    @Test fun `clearing a mark that was seen is not undone`() {
        val base = create("A", 1, item("Hello", mapOf(F_FAV to true)))
        val cleared = SyncDiff.apply(base, item("Hello"), Dot("B", 5))!!
        val m = merge(base, cleared)
        assertEquals(false, m.value(F_FAV))
        assertFalse(SyncMarks.unsettled(m, now = 0L))
        assertTrue(SyncMarks.settle(m, Dot("A", 9), now = 0L) === m)
    }

    @Test fun `settling marks never brings a deleted save back`() {
        val onA = create("A", 5, item("Hello", mapOf(F_FAV to true)))
        val onB = create("B", 6, item("Hello"))
        val trashed = SyncDiff.apply(merge(onA, onB), item("Hello", mapOf(F_TRASH to 50L)), Dot("B", 8))!!
        assertTrue(trashed.isTrashed)
        assertFalse(SyncMarks.unsettled(trashed, now = 0L))
    }

    @Test fun `same field edited on two devices keeps both values until someone picks`() {
        val base = create("A", 1, item("Hello"))
        val onA = SyncDiff.apply(base, item("From A"), Dot("A", 5))!!
        val onB = SyncDiff.apply(base, item("From B"), Dot("B", 6))!!
        val m = merge(onA, onB)
        assertEquals("From B", m.value(F_TITLE)) // newer is shown
        assertEquals(listOf("From A"), m.alternatives(F_TITLE).map { it.value })
        assertTrue(m.hasConflict)
        // Local still shows the shown value: that must not settle the conflict.
        assertNull(SyncDiff.apply(m, item("From B"), Dot("A", 9)))
        // Picking a version (any edit of the field) settles it, everywhere.
        val picked = SyncDiff.apply(m, item("From A"), Dot("A", 10))!!
        val elsewhere = merge(m, picked)
        assertEquals("From A", elsewhere.value(F_TITLE))
        assertFalse(elsewhere.hasConflict)
    }

    @Test fun `an edit made after seeing another replaces it without a conflict`() {
        val base = create("A", 1, item("Hello"))
        val onA = SyncDiff.apply(base, item("v2"), Dot("A", 5))!!
        val onB = SyncDiff.apply(merge(base, onA), item("v3"), Dot("B", 6))!!
        val m = merge(onA, onB)
        assertEquals("v3", m.value(F_TITLE))
        assertFalse(m.hasConflict)
    }

    @Test fun `a lagging clock still wins once it has seen the newer write`() {
        val clockB = HybridClock(0) { 10L } // B's wall clock is far behind
        val base = create("A", 1000, item("Hello"))
        val onA = SyncDiff.apply(base, item("from A"), Dot("A", 5000))!!
        clockB.observe(onA.maxTs)
        val onB = SyncDiff.apply(onA, item("from B later"), Dot("B", clockB.next()))!!
        val m = merge(onA, onB)
        assertEquals("from B later", m.value(F_TITLE))
        assertFalse(m.hasConflict)
    }

    // ── Tags ─────────────────────────────────────────────────────────────

    @Test fun `tags added on different devices are both kept`() {
        val base = create("A", 1, item("x"))
        val onA = SyncDiff.apply(base, item("x", tags = mapOf("red" to "Red")), Dot("A", 2))!!
        val onB = SyncDiff.apply(base, item("x", tags = mapOf("blue" to "Blue")), Dot("B", 3))!!
        assertEquals(setOf("red", "blue"), merge(onA, onB).presentTags().keys)
    }

    @Test fun `removing a tag elsewhere doesn't undo an add it hadn't seen`() {
        val base = create("A", 1, item("x", tags = mapOf("red" to "Red")))
        val removedOnA = SyncDiff.apply(base, item("x"), Dot("A", 2))!!
        val reAddedOnB = SyncDiff.apply(
            SyncWrite.removeTag(base, "red", Dot("B", 3)), item("x", tags = mapOf("red" to "Red")), Dot("B", 4)
        )!!
        assertEquals(setOf("red"), merge(removedOnA, reAddedOnB).presentTags().keys)
        // But a removal that had seen the tag removes it.
        assertEquals(emptySet<String>(), merge(base, removedOnA).presentTags().keys)
    }

    // ── Deletes ──────────────────────────────────────────────────────────

    @Test fun `a delete that saw everything stands`() {
        val base = create("A", 1, item("x"))
        val deleted = SyncDiff.apply(base, item("x", mapOf(F_TRASH to 50L)), Dot("A", 50))!!
        val m = merge(base, deleted)
        assertTrue(m.isTrashed)
        assertEquals(50L, m.trashedAt)
    }

    @Test fun `an edit the deleting device hadn't seen brings the save back`() {
        val base = create("A", 1, item("x"))
        val deleted = SyncDiff.apply(base, item("x", mapOf(F_TRASH to 50L)), Dot("A", 50))!!
        val edited = SyncDiff.apply(base, item("x edited"), Dot("B", 40))!!
        val m = merge(deleted, edited)
        assertFalse(m.isTrashed)
        assertEquals("x edited", m.value(F_TITLE))
    }

    @Test fun `recognized text arriving later doesn't undo a delete`() {
        val base = create("A", 1, item("x"))
        val deleted = SyncDiff.apply(base, item("x", mapOf(F_TRASH to 50L)), Dot("A", 50))!!
        val ocr = SyncWrite.field(base, F_OCR, "scanned words", Dot("B", 60))
        assertTrue(merge(deleted, ocr).isTrashed)
    }

    @Test fun `permanent removal with an unseen edit comes back whole`() {
        val base = create("A", 1, item("x", mapOf(F_NOTES to "keep me")))
        val gone = SyncDiff.apply(base, null, Dot("A", 70))!!
        assertTrue(gone.isGone)
        val edited = SyncDiff.apply(base, item("x edited", mapOf(F_NOTES to "keep me")), Dot("B", 60))!!
        val m = merge(gone, edited)
        assertFalse(m.isGone)
        assertEquals("x edited", m.value(F_TITLE))
        assertEquals("keep me", m.value(F_NOTES)) // nothing else is lost
        assertEquals("https://a.example", m.value(F_URL))
    }

    @Test fun `removed elsewhere while still in Recently deleted here stays quiet`() {
        val base = create("A", 1, item("x"))
        val gone = SyncWrite.gone(base, Dot("A", 70))
        assertNull(SyncDiff.apply(gone, item("x", mapOf(F_TRASH to 999L)), Dot("B", 80)))
    }

    @Test fun `restoring a removed save here brings it back everywhere`() {
        val base = create("A", 1, item("x"))
        val gone = SyncWrite.gone(base, Dot("A", 70))
        val restored = SyncDiff.apply(gone, item("x"), Dot("B", 80))!!
        assertFalse(merge(gone, restored).isGone)
    }

    // ── The same link saved on two devices ───────────────────────────────

    private fun link(id: String, dev: String, ts: Long, url: String, extra: Map<String, Any?> = emptyMap(), tags: Map<String, String> = emptyMap()) =
        SyncDiff.apply(
            null,
            LocalRecord(
                KIND_ITEM, id, ts,
                mapOf(F_TITLE to "T $id", F_URL to url, F_NOTES to null, F_FAV to false, F_TRASH to null, SyncRecord.F_TYPE to "LINK") + extra,
                tags
            ),
            Dot(dev, ts)
        )!!

    @Test fun `the same link saved on two devices folds into the older save`() {
        val phone = link("aaa", "P", 100, "https://example.com/x", mapOf(F_NOTES to "from phone", SyncRecord.F_CAT to "recipes"), mapOf("bread" to "Bread"))
        val chrome = link("bbb", "C", 200, "https://EXAMPLE.com/x", mapOf(F_NOTES to "from chrome", F_FAV to true), mapOf("later" to "Later"))
        val plan = SyncDedupe.plan(listOf(chrome, phone)) { com.ghostgramlabs.pettibox.data.util.TextUtils.urlDedupeKey(it) }
        assertEquals(1, plan.size)
        val (keep, others) = plan.single()
        assertEquals("aaa", keep.id)
        val (kept, removed) = SyncDedupe.fold(keep, others.single(), Dot("P", 300))
        assertEquals("from phone\n\nfrom chrome", kept.value(F_NOTES))
        assertEquals("recipes", kept.value(SyncRecord.F_CAT))
        assertEquals(true, kept.value(F_FAV))
        assertEquals(setOf("bread", "later"), kept.presentTags().keys)
        assertEquals("T aaa", kept.value(F_TITLE)) // the kept title isn't replaced
        assertTrue(removed.isGone)
        assertFalse(kept.hasConflict)
    }

    @Test fun `two devices folding at once agree`() {
        val a = link("aaa", "P", 100, "https://example.com/x", mapOf(F_NOTES to "one"))
        val b = link("bbb", "C", 200, "https://example.com/x", mapOf(F_NOTES to "two"))
        val (k1, r1) = SyncDedupe.fold(a, b, Dot("P", 300))
        val (k2, r2) = SyncDedupe.fold(a, b, Dot("C", 301))
        val k = merge(k1, k2)
        assertEquals("one\n\ntwo", k.value(F_NOTES))
        assertFalse(k.hasConflict) // same values from both: no "edited on 2 devices"
        assertTrue(merge(r1, r2).isGone)
    }

    @Test fun `two saves of a link made on one device are left alone`() {
        val a = link("aaa", "P", 100, "https://example.com/x")
        val b = link("bbb", "P", 200, "https://example.com/x")
        assertTrue(SyncDedupe.plan(listOf(a, b)) { it }.isEmpty())
    }

    @Test fun `a note already contained is not repeated, and a live copy brings a deleted one back`() {
        val a = SyncDiff.apply(link("aaa", "P", 100, "https://e.com", mapOf(F_NOTES to "long note here")),
            LocalRecord(KIND_ITEM, "aaa", 100, mapOf(F_TITLE to "T aaa", F_URL to "https://e.com", F_NOTES to "long note here",
                F_FAV to false, F_TRASH to 150L, SyncRecord.F_TYPE to "LINK")), Dot("P", 150))!!
        val b = link("bbb", "C", 200, "https://e.com", mapOf(F_NOTES to "note"))
        val (kept, _) = SyncDedupe.fold(a, b, Dot("C", 300))
        assertEquals("long note here", kept.value(F_NOTES))
        assertFalse(kept.isTrashed)
    }

    // ── Merge laws and the format ────────────────────────────────────────

    @Test fun `merge is commutative, associative and idempotent`() {
        val rnd = java.util.Random(7)
        repeat(2000) {
            val recs = (0 until 3).map { randomRecord(rnd) }
            val (a, b, c) = recs
            assertEquals(merge(a, b), merge(b, a))
            assertEquals(merge(merge(a, b), c), merge(a, merge(b, c)))
            assertEquals(merge(a, b), merge(merge(a, b), b))
        }
    }

    @Test fun `records survive a JSON round trip`() {
        val rnd = java.util.Random(11)
        repeat(500) {
            val r = randomRecord(rnd)
            assertEquals(r, SyncCodec.decodeString(SyncCodec.encodeString(r)))
        }
    }

    @Test fun `a broken file is rejected and a newer format is left alone`() {
        assertNull(SyncCodec.decodeShard("""{"format":99,"device":"x","records":[]}"""))
        listOf(
            "not json",
            """{"format":1,"records":[]}""",
            """{"format":1,"device":"x","records":[{"k":"item","id":"a"}]}""",
            """{"format":1,"device":"x","records":[{"k":"item","id":"a","vv":{},"f":{"title":[{"v":"x","t":"soon","d":"A"}]}}]}"""
        ).forEach { bad ->
            try {
                SyncCodec.decodeShard(bad)
                throw AssertionError("accepted: $bad")
            } catch (expected: SyncFormatException) {
            }
        }
        val ok = SyncCodec.encodeShard("dev1", "Pixel", 3, 5L, listOf(create("A", 1, item("x"))))
        assertNotNull(SyncCodec.decodeShard(ok))
    }

    @Test fun `shards spread records over 16 files`() {
        val seen = (0 until 2000).map { shardOf(com.ghostgramlabs.pettibox.data.local.newSyncId()) }.toSet()
        assertEquals((0..15).toSet(), seen)
        assertEquals(shardOf("books"), shardOf("books"))
    }

    /** A record built from a few random writes on random devices, then merged. */
    private fun randomRecord(rnd: java.util.Random): SyncRecord {
        val devs = listOf("A", "B", "C")
        var r = create("A", 1, item("t0"))
        repeat(rnd.nextInt(6)) { i ->
            val dot = Dot(devs[rnd.nextInt(3)], 10L + rnd.nextInt(50) * 3 + i)
            r = when (rnd.nextInt(6)) {
                0 -> SyncWrite.field(r, F_TITLE, "t${rnd.nextInt(4)}", dot)
                1 -> SyncWrite.field(r, F_NOTES, if (rnd.nextBoolean()) null else "n${rnd.nextInt(3)}", dot)
                2 -> listOf("Red", "Blue")[rnd.nextInt(2)].let { SyncWrite.addTag(r, it.lowercase(), it, dot) }
                3 -> SyncWrite.removeTag(r, listOf("red", "blue")[rnd.nextInt(2)], dot)
                4 -> SyncWrite.trash(r, if (rnd.nextBoolean()) dot.ts else null, dot)
                else -> SyncWrite.gone(r, dot)
            }
        }
        return r
    }
}
