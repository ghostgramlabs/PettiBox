package com.ghostgramlabs.pettibox.data.sync

import org.junit.Assert.assertEquals
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

    @Test fun an_unused_file_waits_30_days_before_it_goes() {
        val stored = mapOf("id-a" to "${sha('a')}.pdf", "id-b" to "${sha('b')}.jpg")
        val used = setOf(sha('b'))
        val first = DriveFileCleanup.plan(stored, used, emptyMap(), now = 100 * day)
        assertTrue(first.remove.isEmpty())
        assertEquals(mapOf(sha('a') to 100 * day), first.unusedSince)

        val early = DriveFileCleanup.plan(stored, used, first.unusedSince, now = 129 * day)
        assertTrue(early.remove.isEmpty())
        val due = DriveFileCleanup.plan(stored, used, first.unusedSince, now = 130 * day)
        assertEquals(listOf("id-a"), due.remove)
        assertTrue(due.unusedSince.isEmpty())
    }

    @Test fun a_file_used_again_starts_over_and_other_names_are_left_alone() {
        val stored = mapOf("id-a" to "${sha('a')}.pdf", "id-x" to "notes.txt", "id-c" to "${sha('c')}")
        val plan = DriveFileCleanup.plan(stored, setOf(sha('a')), mapOf(sha('a') to 0L, sha('c') to 0L), now = 90 * day)
        assertEquals(listOf("id-c"), plan.remove)
        assertTrue(plan.unusedSince.isEmpty())
        assertNull(DriveFileCleanup.shaOfName("notes.txt"))
    }

    @Test fun a_clock_set_back_never_brings_removal_forward() {
        val stored = mapOf("id-a" to "${sha('a')}.pdf")
        // First seen "in the future" (the clock was wrong then): counts from now instead.
        val plan = DriveFileCleanup.plan(stored, emptySet(), mapOf(sha('a') to 500 * day), now = 100 * day)
        assertTrue(plan.remove.isEmpty())
        assertEquals(100 * day, plan.unusedSince[sha('a')])
    }
}
