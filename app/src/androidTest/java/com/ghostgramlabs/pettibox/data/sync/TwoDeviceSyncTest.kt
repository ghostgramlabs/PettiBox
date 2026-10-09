package com.ghostgramlabs.pettibox.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.ItemTagCrossRef
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Two phones, each with its own database and settings, syncing through an
 * in-memory stand-in for the Drive folder. Exercises the real engine, outbox
 * triggers, Room writes and merge rules end to end.
 */
@RunWith(AndroidJUnit4::class)
class TwoDeviceSyncTest {

    /** The Drive folder: file name -> (id, gzip bytes, md5). */
    private class FakeDrive : SyncTransport {
        val files = LinkedHashMap<String, Triple<String, String, String>>() // name -> (id, json, md5)
        var corrupt: String? = null
        var marker: String? = null

        override suspend fun open() = object : SyncTransport.Session {
            override suspend fun list() = files.map { (name, f) -> DriveSyncFile(f.first, name, f.third, 0L) }
            override suspend fun read(file: DriveSyncFile): String {
                if (file.name == corrupt) throw SyncFormatException("damaged")
                return files.getValue(file.name).second
            }
            override suspend fun write(name: String, json: String, existingId: String?) {
                val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(json.toByteArray()) } }.toByteArray()
                val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                files[name] = Triple(files[name]?.first ?: UUID.randomUUID().toString(), json, md5)
            }
            override suspend fun accountEmail() = "test@example.com"
            override suspend fun resetMarker() = marker
        }
    }

    private class NoHooks : SyncHooks {
        override suspend fun safetyBackup() {}
        override suspend fun afterApply(applied: List<LocalLibrary.Applied>, now: Long) {}
        override suspend fun syncFiles(wifiOnly: Boolean) {}
        var stopped = false
        override suspend fun stopSync() { stopped = true }
    }

    private class Phone(context: Context, name: String, drive: FakeDrive) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefs = SyncPreferences(context, "sync-test-$name-${UUID.randomUUID()}")
        val setup = SyncSetup(db)
        val library = LocalLibrary(context, db.saveDao(), db.categoryDao(), db.tagDao(), db.attachmentDao(), db.syncDao())
        val hooks = NoHooks()
        val engine = SyncEngine(db, db.syncDao(), prefs, library, drive, hooks)

        suspend fun turnOn() {
            prefs.setEnabled(true)
            setup.enable()
        }

        suspend fun sync(): SyncOutcome = engine.round()

        suspend fun saves(): List<SaveItemEntity> = all()
        suspend fun byUrl(url: String) = db.saveDao().findByUrl(url)
        /** Every save, Recently deleted included. */
        suspend fun all(): List<SaveItemEntity> = db.syncDao().allItemSyncIds().mapNotNull { db.syncDao().itemBySyncId(it) }
        suspend fun live() = all().filter { !it.isPendingDelete }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var drive: FakeDrive
    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before fun setUp() {
        drive = FakeDrive()
        a = Phone(context, "a", drive)
        b = Phone(context, "b", drive)
    }

    @After fun tearDown() {
        a.db.close(); b.db.close()
    }

    private fun link(title: String, url: String = "https://$title.example", updated: Long = System.currentTimeMillis()) =
        SaveItemEntity(title = title, url = url, contentType = "LINK", sourceApp = "WEB", updatedAt = updated, createdAt = updated)

    /** Both phones sync until nothing moves (in practice twice each). */
    private suspend fun syncBoth() {
        repeat(2) { assertTrue(a.sync() is SyncOutcome.Ok); assertTrue(b.sync() is SyncOutcome.Ok) }
    }

    @Test fun savesOnOnePhoneReachTheOther() = runBlocking {
        a.db.saveDao().insert(link("sourdough"))
        a.db.categoryDao().upsert(CategoryEntity("user_1", "Recipes", "🍞", 0xFFAA0000))
        a.turnOn(); b.turnOn()
        syncBoth()
        assertEquals(listOf("sourdough"), b.saves().map { it.title })
        assertEquals("Recipes", b.db.categoryDao().getById("user_1")?.name)
        // And back the other way.
        b.db.saveDao().insert(link("pasta"))
        syncBoth()
        assertEquals(setOf("sourdough", "pasta"), a.saves().map { it.title }.toSet())
    }

    @Test fun edits_to_different_fields_on_each_phone_both_survive() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        val onA = a.byUrl("https://x.example")!!
        val onB = b.byUrl("https://x.example")!!
        a.db.saveDao().update(onA.copy(title = "renamed on A", updatedAt = System.currentTimeMillis()))
        b.db.saveDao().update(onB.copy(notes = "note from B", updatedAt = System.currentTimeMillis()))
        val tag = b.db.tagDao().upsert("Bread")
        b.db.tagDao().link(ItemTagCrossRef(onB.id, tag))
        syncBoth()
        for (p in listOf(a, b)) {
            val s = p.byUrl("https://x.example")!!
            assertEquals("renamed on A", s.title)
            assertEquals("note from B", s.notes)
            assertEquals(listOf("Bread"), p.db.tagDao().tagsForItem(s.id).map { it.name })
        }
    }

    @Test fun the_same_field_edited_on_both_keeps_both_versions() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        a.db.saveDao().update(a.byUrl("https://x.example")!!.copy(title = "A's title"))
        Thread.sleep(5)
        b.db.saveDao().update(b.byUrl("https://x.example")!!.copy(title = "B's title"))
        syncBoth()
        val shownA = a.byUrl("https://x.example")!!.title
        assertEquals(shownA, b.byUrl("https://x.example")!!.title)
        val meta = SyncCodec.decodeString(a.db.syncDao().meta("item", a.byUrl("https://x.example")!!.syncId)!!.recordJson)
        assertTrue(meta.hasConflict)
        val all = (meta.alternatives(SyncRecord.F_TITLE).map { it.value } + meta.value(SyncRecord.F_TITLE)).toSet()
        assertEquals(setOf("A's title", "B's title"), all)
    }

    @Test fun a_choice_made_on_one_phone_is_not_asked_on_the_other() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        a.db.saveDao().update(a.byUrl("https://x.example")!!.copy(title = "A's title"))
        Thread.sleep(5)
        b.db.saveDao().update(b.byUrl("https://x.example")!!.copy(title = "B's title"))
        syncBoth()
        val id = a.byUrl("https://x.example")!!.syncId
        assertTrue(id in a.prefs.needingChoice.first())
        assertTrue(id in b.prefs.needingChoice.first())

        a.engine.keepShown(id, SyncRecord.F_TITLE)
        syncBoth()
        assertTrue(a.prefs.needingChoice.first().isEmpty())
        assertTrue(b.prefs.needingChoice.first().isEmpty())
        assertEquals(a.byUrl("https://x.example")!!.title, b.byUrl("https://x.example")!!.title)
    }

    @Test fun a_delete_moves_to_recently_deleted_on_the_other_phone() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        a.db.saveDao().setPendingDelete(a.byUrl("https://x.example")!!.id, true)
        syncBoth()
        val onB = b.saves().single()
        assertTrue(onB.isPendingDelete)
        // Deleting it for good on A never deletes it on B outright.
        a.db.saveDao().delete(a.saves().single().id)
        syncBoth()
        assertEquals(1, b.saves().size)
        assertTrue(b.saves().single().isPendingDelete)
    }

    @Test fun a_save_live_elsewhere_survives_a_permanent_removal() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        // A deletes for good while B, without knowing, edits it.
        a.db.saveDao().delete(a.saves().single().id)
        b.db.saveDao().update(b.saves().single().copy(notes = "still need this"))
        syncBoth()
        for (p in listOf(a, b)) {
            val s = p.saves().single()
            assertFalse(s.isPendingDelete)
            assertEquals("still need this", s.notes)
        }
    }

    @Test fun first_sync_with_overlapping_libraries_makes_no_duplicates() = runBlocking {
        a.db.saveDao().insert(link("shared", url = "https://shared.example", updated = 1_000L))
        b.db.saveDao().insert(link("shared", url = "https://SHARED.example", updated = 900L))
        b.db.saveDao().insert(link("only on b"))
        a.turnOn(); assertTrue(a.sync() is SyncOutcome.Ok)
        b.turnOn(); syncBoth()
        assertEquals(2, a.saves().size)
        assertEquals(2, b.saves().size)
        assertEquals(1, b.saves().count { it.url!!.lowercase() == "https://shared.example" })
    }

    @Test fun an_old_copy_joining_late_does_not_overwrite_newer_edits() = runBlocking {
        val now = System.currentTimeMillis()
        a.db.saveDao().insert(link("x", updated = now - 100_000))
        a.turnOn(); assertTrue(a.sync() is SyncOutcome.Ok)
        a.db.saveDao().update(a.saves().single().copy(title = "newest title"))
        assertTrue(a.sync() is SyncOutcome.Ok)
        // B restored an old backup of the same save (same sync id) and turns sync on.
        val old = a.saves().single().copy(id = 0, title = "old title", updatedAt = now - 100_000)
        b.db.saveDao().insert(old)
        b.turnOn(); syncBoth()
        assertEquals("newest title", a.saves().single().title)
        assertEquals("newest title", b.saves().single().title)
    }

    @Test fun many_removals_wait_for_review_and_keep_mine_restores_them() = runBlocking {
        repeat(30) { a.db.saveDao().insert(link("s$it")) }
        a.turnOn(); b.turnOn(); syncBoth()
        for (s in a.saves()) a.db.saveDao().setPendingDelete(s.id, true)
        assertTrue(a.sync() is SyncOutcome.Ok)
        val held = b.sync()
        assertTrue("expected review, got $held", held is SyncOutcome.NeedsReview)
        assertEquals(30, b.live().size) // nothing applied yet
        b.prefs.setReviewDecision(SyncEngine.REVIEW_KEEP)
        assertTrue(b.sync() is SyncOutcome.Ok)
        assertTrue(a.sync() is SyncOutcome.Ok)
        assertEquals(30, b.live().size)
        assertEquals(30, a.live().size) // restored for everyone
    }

    @Test fun a_damaged_file_is_skipped_and_nothing_from_it_applied() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); assertTrue(a.sync() is SyncOutcome.Ok)
        drive.corrupt = drive.files.keys.first()
        b.turnOn()
        val out = b.sync()
        assertTrue(out is SyncOutcome.Ok && out.damaged == 1)
        assertTrue(b.saves().isEmpty())
        drive.corrupt = null
        assertTrue(b.sync() is SyncOutcome.Ok)
        assertEquals(1, b.saves().size) // picked up once it's readable
    }

    @Test fun deleting_synced_data_elsewhere_stops_sync_here_and_keeps_the_library() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        // A deletes everything from Drive: files gone, marker left.
        drive.files.clear()
        drive.marker = "{\"resetAt\":1}"
        assertEquals(SyncOutcome.ResetElsewhere, b.sync())
        assertTrue(b.hooks.stopped)
        assertFalse(b.prefs.isEnabled())
        assertEquals(1, b.saves().size) // nothing on B was touched
        assertTrue(drive.files.isEmpty()) // and B didn't upload it all again
        // Turning sync back on later starts fresh and is not stopped again.
        b.prefs.clearKnownReset()
        b.turnOn()
        assertTrue(b.sync() is SyncOutcome.Ok)
        assertTrue(drive.files.isNotEmpty())
    }

    @Test fun the_same_link_saved_on_two_phones_before_syncing_becomes_one_save() = runBlocking {
        a.turnOn(); b.turnOn(); syncBoth()
        a.db.saveDao().insert(link("x", updated = 1_000L).copy(notes = "from A"))
        b.db.saveDao().insert(link("x", url = "https://X.example", updated = 2_000L).copy(notes = "from B"))
        repeat(2) { syncBoth() }
        for (p in listOf(a, b)) {
            assertEquals(1, p.live().size)
            assertEquals("from A\n\nfrom B", p.live().single().notes)
        }
        // The newer copy waits in Recently deleted on the phone that made it
        // (A never had it): nothing is lost.
        assertEquals(1, b.all().count { it.isPendingDelete })
        assertEquals(a.live().single().syncId, b.live().single().syncId)
    }

    @Test fun two_saves_of_a_link_on_one_phone_are_left_alone() = runBlocking {
        a.turnOn(); b.turnOn(); syncBoth()
        a.db.saveDao().insert(link("x", updated = 1_000L))
        a.db.saveDao().insert(link("x", updated = 2_000L))
        repeat(2) { syncBoth() }
        assertEquals(2, a.live().size)
        assertEquals(2, b.live().size)
    }

    @Test fun turning_sync_off_stops_sending() = runBlocking {
        a.db.saveDao().insert(link("x"))
        a.turnOn(); b.turnOn(); syncBoth()
        a.prefs.setEnabled(false); a.setup.disable()
        a.db.saveDao().insert(link("offline"))
        assertEquals(SyncOutcome.Off, a.sync())
        b.sync()
        assertEquals(1, b.saves().size)
        // Back on: what was added while off goes out.
        a.turnOn(); syncBoth()
        assertEquals(2, b.saves().size)
        assertNotNull(b.byUrl("https://offline.example"))
    }
}
