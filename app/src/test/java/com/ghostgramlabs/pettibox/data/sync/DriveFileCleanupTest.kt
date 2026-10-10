package com.ghostgramlabs.pettibox.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveFileCleanupTest {
    private val day = 24L * 60 * 60 * 1000
    private fun sha(c: Char) = c.toString().repeat(64)
    private val dot = Dot("aaaaaaaaaaaaaaaa", 5)

    private fun item(
        id: String,
        files: List<String> = emptyList(),
        main: List<String> = emptyList(),
        thumb: String? = null,
        trashed: Boolean = false,
        gone: Boolean = false
    ): SyncRecord {
        val fields = linkedMapOf<String, List<Head>>(SyncRecord.F_TITLE to listOf(Head("t", dot)))
        if (main.isNotEmpty()) fields[SyncRecord.F_MAIN] = main.mapIndexed { i, s -> Head(s, Dot("dev$i", 5)) }
        if (thumb != null) fields[SyncRecord.F_THUMB] = listOf(Head(thumb, dot))
        if (trashed) fields[SyncRecord.F_TRASH] = listOf(Head(1L, dot))
        return SyncRecord(
            kind = SyncRecord.KIND_ITEM, id = id, created = 1, vv = mapOf(dot.dev to dot.ts), fields = fields,
            files = files.associateWith { listOf(FileDot("PDF", 0, dot)) },
            gone = if (gone) mapOf(dot.dev to dot.ts) else null
        )
    }

    @Test fun files_main_files_pictures_and_both_versions_count_as_used() {
        val used = DriveFileCleanup.referenced(sequenceOf(
            item("1", files = listOf(sha('a'))),
            item("2", main = listOf(sha('b'), sha('c'))), // edited on 2 devices: both kept
            item("3", thumb = sha('d')),
            item("4", files = listOf(sha('e')), trashed = true), // Recently deleted still counts
            item("5", files = listOf(sha('f')), gone = true) // removed for good: doesn't
        ))
        assertEquals(setOf(sha('a'), sha('b'), sha('c'), sha('d'), sha('e')), used)
    }

    private fun plan(stored: Map<String, String>, removed: Map<String, String> = emptyMap(), used: Set<String> = emptySet(),
                     unused: Map<String, Long> = emptyMap(), held: Map<String, Long> = emptyMap(), now: Long) =
        DriveFileCleanup.plan(stored, removed, used, unused, held, now)

    @Test fun an_unused_file_waits_30_days_then_moves_to_files_removed() {
        val stored = mapOf("id-a" to "${sha('a')}.pdf", "id-b" to "${sha('b')}.jpg")
        val used = setOf(sha('b'))
        val first = plan(stored, used = used, now = 100 * day)
        assertTrue(first.quarantine.isEmpty())
        assertEquals(mapOf(sha('a') to 100 * day), first.unusedSince)
        assertTrue(plan(stored, used = used, unused = first.unusedSince, now = 129 * day).quarantine.isEmpty())
        val due = plan(stored, used = used, unused = first.unusedSince, now = 130 * day)
        assertEquals(listOf("id-a"), due.quarantine)
        assertEquals(mapOf(sha('a') to 130 * day), due.quarantinedAt)
        assertTrue(due.purge.isEmpty()) // nothing is deleted in the same step
    }

    @Test fun a_file_in_files_removed_is_deleted_only_after_30_more_unused_days() {
        val removed = mapOf("id-a" to "${sha('a')}.pdf")
        val early = plan(emptyMap(), removed, held = mapOf(sha('a') to 130 * day), now = 159 * day)
        assertTrue(early.purge.isEmpty())
        val due = plan(emptyMap(), removed, held = mapOf(sha('a') to 130 * day), now = 160 * day)
        assertEquals(listOf("id-a"), due.purge)
    }

    @Test fun a_file_used_again_while_in_files_removed_goes_straight_back() {
        val removed = mapOf("id-a" to "${sha('a')}.pdf")
        val p = plan(emptyMap(), removed, used = setOf(sha('a')), held = mapOf(sha('a') to 0L), now = 90 * day)
        assertEquals(listOf("id-a"), p.restore)
        assertTrue(p.purge.isEmpty())
        assertTrue(p.quarantinedAt.isEmpty())
    }

    @Test fun a_file_used_again_starts_over_and_other_names_are_left_alone() {
        val stored = mapOf("id-a" to "${sha('a')}.pdf", "id-x" to "notes.txt", "id-c" to sha('c'))
        val p = plan(stored, used = setOf(sha('a')), unused = mapOf(sha('a') to 0L, sha('c') to 0L), now = 90 * day)
        assertEquals(listOf("id-c"), p.quarantine)
        assertTrue(p.unusedSince.isEmpty())
        assertNull(DriveFileCleanup.shaOfName("notes.txt"))
    }

    @Test fun a_clock_set_back_never_brings_removal_forward() {
        val stored = mapOf("id-a" to "${sha('a')}.pdf")
        val p = plan(stored, unused = mapOf(sha('a') to 500 * day), now = 100 * day)
        assertTrue(p.quarantine.isEmpty())
        assertEquals(100 * day, p.unusedSince[sha('a')])
    }

    @Test fun a_device_copy_that_still_uses_a_file_keeps_it() {
        // Built from each device's own published copy: a device behind still lists it.
        val behind = DriveFileCleanup.publishedOf("m", listOf(item("1", files = listOf(sha('a')))))
        assertTrue(sha('a') in behind.refs)
        assertTrue("item:1" in behind.live)
        val upToDate = DriveFileCleanup.publishedOf("m", listOf(item("1", files = listOf(sha('a')), gone = true)))
        assertTrue(upToDate.refs.isEmpty())
        assertTrue(upToDate.live.isEmpty())
    }

    @Test fun a_deletion_is_stripped_only_when_settled_on_every_device_and_30_days_old() {
        val goneLong = item("1", files = listOf(sha('a')), gone = true)
        val at = goneLong.gone!!.values.max()
        assertTrue(DriveFileCleanup.compactable(goneLong, emptySet(), at + 30 * day))
        assertFalse(DriveFileCleanup.compactable(goneLong, emptySet(), at + 29 * day)) // too recent
        assertFalse(DriveFileCleanup.compactable(goneLong, setOf("item:1"), at + 90 * day)) // another device still holds it
        assertFalse(DriveFileCleanup.compactable(item("2"), emptySet(), at + 90 * day)) // not removed
        val bare = goneLong.copy(fields = emptyMap(), tags = emptyMap(), files = emptyMap())
        assertTrue(bare.isGone)
        assertFalse(DriveFileCleanup.compactable(bare, emptySet(), at + 90 * day)) // already bare
        // A device's full old copy merged with the bare marker stays removed (nothing comes back).
        val oldLiveCopy = item("1", files = listOf(sha('a')))
        assertTrue(merge(bare, oldLiveCopy).isGone)
        assertTrue(merge(oldLiveCopy, bare).isGone)
    }
}
