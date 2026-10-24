package com.ghostgramlabs.pettibox.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostgramlabs.pettibox.data.local.AppDatabase
import com.ghostgramlabs.pettibox.data.local.AttachmentEntity
import com.ghostgramlabs.pettibox.data.local.CategoryEntity
import com.ghostgramlabs.pettibox.data.local.ItemTagCrossRef
import com.ghostgramlabs.pettibox.data.local.SaveItemEntity
import com.ghostgramlabs.pettibox.data.local.SyncApplyingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The outbox triggers on Android's own SQLite: on only while sync is on, and catching every kind of write. */
@RunWith(AndroidJUnit4::class)
class SyncTablesTest {
    private lateinit var db: AppDatabase
    private lateinit var setup: SyncSetup

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        setup = SyncSetup(db)
    }

    @After fun close() = db.close()

    private fun save(title: String, updated: Long = 1_000L) = SaveItemEntity(
        title = title, contentType = "LINK", sourceApp = "WEB", url = "https://$title.example", updatedAt = updated
    )

    private suspend fun outbox() = db.syncDao().outbox().map { it.kind to it.syncId }.toSet()

    @Test fun nothingIsQueuedWhileSyncIsOff() = runBlocking {
        db.saveDao().insert(save("a"))
        db.categoryDao().upsert(CategoryEntity("c1", "C", "📦", 0))
        assertEquals(emptySet<Pair<String, String>>(), outbox())
        val triggers = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'sync_q_%'")
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, triggers)
    }

    @Test fun turningSyncOnQueuesTheWholeLibraryWithEachSavesOwnTime() = runBlocking {
        val a = db.saveDao().insert(save("a", updated = 111L))
        db.categoryDao().upsert(CategoryEntity("c1", "C", "📦", 0, createdAt = 222L))
        setup.enable()
        val rows = db.syncDao().outbox().associate { (it.kind to it.syncId) to it.ts }
        val syncId = db.syncDao().syncIdOf(a)!!
        assertEquals(111L, rows["item" to syncId])
        assertEquals(222L, rows["col" to "c1"])
    }

    @Test fun everyKindOfWriteQueuesItsRecordOnce() = runBlocking {
        setup.enable()
        db.syncDao().clearAllOutbox()
        val dao = db.saveDao()
        val id = dao.insert(save("a"))
        val syncId = db.syncDao().syncIdOf(id)!!
        assertEquals(setOf("item" to syncId), outbox())

        db.syncDao().clearAllOutbox()
        dao.setFavorite(id, true)
        assertEquals(setOf("item" to syncId), outbox())

        db.syncDao().clearAllOutbox()
        val tagId = db.tagDao().upsert("Red")
        db.tagDao().link(ItemTagCrossRef(id, tagId))
        assertEquals(setOf("item" to syncId), outbox())

        db.syncDao().clearAllOutbox()
        db.tagDao().unlink(id, tagId)
        assertEquals(setOf("item" to syncId), outbox())

        db.syncDao().clearAllOutbox()
        val attId = db.attachmentDao().insert(AttachmentEntity(itemId = id, uri = "file:///x.jpg", kind = "IMAGE"))
        db.attachmentDao().setOcrText(attId, "words")
        db.attachmentDao().delete(attId)
        assertEquals(setOf("item" to syncId), outbox())

        db.syncDao().clearAllOutbox()
        db.categoryDao().upsert(CategoryEntity("c1", "C", "📦", 0))
        db.categoryDao().update(CategoryEntity("c1", "Renamed", "📦", 0))
        assertEquals(setOf("col" to "c1"), outbox())
    }

    /** Regression: Room's UPDATE OR ABORT once turned the trigger's write into an abort, failing the edit. */
    @Test fun editingAnAlreadyQueuedSaveOrCollectionSucceeds() = runBlocking {
        setup.enable()
        val id = db.saveDao().insert(save("a"))
        db.categoryDao().upsert(CategoryEntity("c1", "C", "📦", 0))
        repeat(3) { n ->
            db.saveDao().update(db.saveDao().getById(id)!!.copy(title = "edit $n"))
            db.categoryDao().update(CategoryEntity("c1", "C$n", "📦", 0))
        }
        assertEquals("edit 2", db.saveDao().getById(id)!!.title)
        assertEquals(2, db.syncDao().outbox().size)
    }

    @Test fun deletingASaveWithTagsAndFilesQueuesItAndDoesNotFail() = runBlocking {
        setup.enable()
        val id = db.saveDao().insert(save("a"))
        val syncId = db.syncDao().syncIdOf(id)!!
        db.tagDao().link(ItemTagCrossRef(id, db.tagDao().upsert("Red")))
        db.attachmentDao().insert(AttachmentEntity(itemId = id, uri = "file:///x.jpg", kind = "IMAGE"))
        db.syncDao().clearAllOutbox()
        db.saveDao().delete(id) // cascades to tags and attachments
        assertEquals(setOf("item" to syncId), outbox())
    }

    @Test fun writesWhileApplyingAreNotQueued() = runBlocking {
        setup.enable()
        db.syncDao().clearAllOutbox()
        db.syncDao().beginApplying(SyncApplyingEntity())
        db.saveDao().insert(save("from another device"))
        db.syncDao().endApplying()
        assertEquals(emptySet<Pair<String, String>>(), outbox())
    }

    @Test fun clearingKeepsAChangeMadeAfterItWasRead() = runBlocking {
        setup.enable()
        db.syncDao().clearAllOutbox()
        val id = db.saveDao().insert(save("a"))
        val read = db.syncDao().outbox().single()
        Thread.sleep(5)
        db.saveDao().setPinned(id, true) // newer change while the round ran
        db.syncDao().clearOutbox(read.kind, read.syncId, read.ts)
        assertEquals(1, db.syncDao().outbox().size)
    }

    @Test fun turningSyncOffDropsTheTriggersAndTheQueue() = runBlocking {
        setup.enable()
        db.saveDao().insert(save("a"))
        setup.disable()
        assertTrue(db.syncDao().outbox().isEmpty())
        db.saveDao().insert(save("b"))
        assertTrue(db.syncDao().outbox().isEmpty())
    }

    @Test fun reconcileRepairsAHalfFinishedSwitch() = runBlocking {
        setup.enable()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER sync_q_item_upd") // as if killed midway
        setup.reconcile(enabled = true)
        db.syncDao().clearAllOutbox()
        val id = db.saveDao().insert(save("a"))
        db.syncDao().clearAllOutbox()
        db.saveDao().setFavorite(id, true)
        assertEquals(1, db.syncDao().outbox().size)
        setup.reconcile(enabled = false)
        db.saveDao().setFavorite(id, false)
        assertTrue(db.syncDao().outbox().isEmpty())
    }
}
