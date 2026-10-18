package com.ghostgramlabs.pettibox.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The latest migrations against the exported schemas: Room checks every table, index and trigger after each. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun from_v8_search_finds_any_script_and_reading_states_exist() {
        helper.createDatabase(DB, 8).apply {
            execSQL(
                "INSERT INTO save_items (id, title, content_type, source_app, created_at, updated_at, is_favorite, is_archived, is_pinned, " +
                    "is_pending_delete, sync_id) VALUES (1, 'Москва встреча', 'NOTE', 'TEST', 0, 0, 0, 0, 0, 0, 'abc')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 10, true, MIGRATION_8_9, MIGRATION_9_10)
        // The rebuilt index lowercases every script.
        db.query("SELECT rowid FROM save_items_fts WHERE save_items_fts MATCH 'москва*'").use {
            assertEquals(1, it.count)
        }
        db.query("SELECT COUNT(*) FROM ocr_status").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
