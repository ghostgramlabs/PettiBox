package com.ghostgramlabs.pettibox.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Hot read paths return [Flow] so the UI auto-refreshes on writes; large
 * browses return [PagingSource] so we never materialize more than the visible
 * window plus a small buffer.
 *
 * Every listing/count query filters `is_pending_delete = 0`. That flag is
 * set during the "Delete with Undo" snackbar window so the row is hidden
 * everywhere — including Archive — without losing it from the DB. The two
 * queries that intentionally bypass the filter are [observeById] and
 * [getById], so the Detail screen the user is currently looking at can
 * still render the item while the Undo snackbar is up. App start sweeps
 * any leftover pending-delete rows via [permanentlyDeletePending].
 */
@Dao
interface SaveDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SaveItemEntity): Long

    @Update
    suspend fun update(item: SaveItemEntity)

    @Query("DELETE FROM save_items WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Returns the row regardless of pending-delete state — read by repo
     * paths that need to inspect the entity before the actual delete.
     */
    @Query("SELECT * FROM save_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SaveItemEntity?

    /**
     * Most recent live save pointing at the exact same URL — backs the
     * "you already saved this" nudge on the Save sheet. Archived saves are
     * ignored so re-saving something you deliberately tucked away isn't
     * flagged as a duplicate.
     */
    @Query("SELECT * FROM save_items WHERE url = :url AND is_archived = 0 AND is_pending_delete = 0 ORDER BY created_at DESC LIMIT 1")
    suspend fun findByUrl(url: String): SaveItemEntity?

    /**
     * Identity fields of every kept row — archived included, since a
     * restore must not resurrect a copy of something the user tucked
     * away. Backs the duplicate-skip in backup restore.
     */
    @Query("SELECT url, content_type, created_at, title FROM save_items WHERE is_pending_delete = 0")
    suspend fun dedupeKeys(): List<SaveDedupeKey>

    /**
     * Every row referencing the category — archived and Undo-staged rows
     * included. This is the "is it safe to delete this collection without
     * unfiling anything" check, so it must not share the listing queries'
     * filters.
     */
    @Query("SELECT COUNT(*) FROM save_items WHERE category_id = :categoryId")
    suspend fun countAllForCategory(categoryId: String): Int

    /** Intentionally unfiltered — the Detail screen needs to keep showing the row during the Undo window. */
    @Query("SELECT * FROM save_items WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<SaveItemEntity?>

    @Query("SELECT * FROM save_items WHERE is_pending_delete = 0 ORDER BY created_at DESC LIMIT :limit")
    suspend fun browseForSearch(limit: Int = 200): List<SaveItemEntity>

    @Query("SELECT * FROM save_items WHERE is_pending_delete = 0 ORDER BY created_at DESC")
    suspend fun allForExport(): List<SaveItemEntity>

    @Query(
        """
        SELECT * FROM save_items
        WHERE content_type = 'IMAGE'
            AND local_uri IS NOT NULL
            AND (ocr_text IS NULL OR ocr_text = '')
            AND is_pending_delete = 0
            AND NOT EXISTS (
                SELECT 1 FROM attachments
                WHERE attachments.item_id = save_items.id
            )
        ORDER BY created_at DESC
        """
    )
    suspend fun imageItemsNeedingOcr(): List<SaveItemEntity>

    @Query(
        """
        SELECT * FROM save_items
        WHERE content_type = 'PDF'
            AND local_uri IS NOT NULL
            AND (ocr_text IS NULL OR ocr_text = '')
            AND is_pending_delete = 0
        ORDER BY created_at DESC
        """
    )
    suspend fun pdfItemsNeedingOcr(): List<SaveItemEntity>

    // ── Hot, capped browses (Home) ───────────────────────────────────────
    // Home views hide archived items. Search (FTS + browseForSearch) still
    // returns them so "I'm done with this" doesn't make a save unfindable.

    @Query("SELECT * FROM save_items WHERE is_archived = 0 AND is_pending_delete = 0 ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<SaveItemEntity>>

    @Query("SELECT * FROM save_items WHERE is_pinned = 1 AND is_archived = 0 AND is_pending_delete = 0 ORDER BY updated_at DESC LIMIT :limit")
    fun observePinned(limit: Int = 12): Flow<List<SaveItemEntity>>

    @Query("SELECT * FROM save_items WHERE is_favorite = 1 AND is_archived = 0 AND is_pending_delete = 0 ORDER BY created_at DESC LIMIT :limit")
    fun observeFavorites(limit: Int = 12): Flow<List<SaveItemEntity>>

    // ── Paged browses (Categories drill-in, future "all saves") ──────────

    @Query(
        """
        SELECT * FROM save_items
        WHERE category_id = :categoryId AND is_archived = :includeArchived AND is_pending_delete = 0
        ORDER BY is_pinned DESC,
            CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
            CASE WHEN :sort = 'UPDATED' THEN updated_at END DESC,
            CASE WHEN :sort = 'REMINDER' AND remind_at IS NULL THEN 1 ELSE 0 END ASC,
            CASE WHEN :sort = 'REMINDER' THEN remind_at END ASC,
            created_at DESC
        """
    )
    fun pagedByCategory(
        categoryId: String,
        includeArchived: Boolean = false,
        sort: String = "NEWEST"
    ): PagingSource<Int, SaveItemEntity>

    @Query(
        """
        SELECT * FROM save_items
        WHERE is_archived = :includeArchived AND is_pending_delete = 0
        ORDER BY is_pinned DESC,
            CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
            CASE WHEN :sort = 'UPDATED' THEN updated_at END DESC,
            CASE WHEN :sort = 'REMINDER' AND remind_at IS NULL THEN 1 ELSE 0 END ASC,
            CASE WHEN :sort = 'REMINDER' THEN remind_at END ASC,
            created_at DESC
        """
    )
    fun pagedAll(includeArchived: Boolean = false, sort: String = "NEWEST"): PagingSource<Int, SaveItemEntity>

    @Query(
        """
        SELECT * FROM save_items
        WHERE source_app = :sourceApp AND is_archived = 0 AND is_pending_delete = 0
        ORDER BY is_pinned DESC, created_at DESC
        """
    )
    fun pagedBySource(sourceApp: String): PagingSource<Int, SaveItemEntity>

    /** Every favorited live save — backs the dedicated Favorites destination. */
    @Query(
        """
        SELECT * FROM save_items
        WHERE is_favorite = 1 AND is_archived = 0 AND is_pending_delete = 0
        ORDER BY is_pinned DESC,
            CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
            CASE WHEN :sort = 'UPDATED' THEN updated_at END DESC,
            CASE WHEN :sort = 'REMINDER' AND remind_at IS NULL THEN 1 ELSE 0 END ASC,
            CASE WHEN :sort = 'REMINDER' THEN remind_at END ASC,
            created_at DESC
        """
    )
    fun pagedFavorites(sort: String = "NEWEST"): PagingSource<Int, SaveItemEntity>

    /**
     * Every archived save across all collections. Crucially this does not
     * filter by category_id, so an archived item that was never assigned
     * a collection is still reachable — the previous in-collection
     * archive toggle could only surface archived items whose collection
     * the user happened to drill into.
     */
    @Query(
        """
        SELECT * FROM save_items
        WHERE is_archived = 1 AND is_pending_delete = 0
        ORDER BY
            CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
            CASE WHEN :sort = 'NEWEST' THEN created_at END DESC,
            CASE WHEN :sort = 'REMINDER' AND remind_at IS NULL THEN 1 ELSE 0 END ASC,
            CASE WHEN :sort = 'REMINDER' THEN remind_at END ASC,
            updated_at DESC
        """
    )
    fun pagedArchived(sort: String = "UPDATED"): PagingSource<Int, SaveItemEntity>

    /** Live saves carrying a given tag (case-insensitive). */
    @Query(
        """
        SELECT s.* FROM save_items s
        JOIN item_tags it ON it.item_id = s.id
        JOIN tags t ON t.id = it.tag_id
        WHERE t.name = :name COLLATE NOCASE AND s.is_archived = 0 AND s.is_pending_delete = 0
        ORDER BY s.is_pinned DESC,
            CASE WHEN :sort = 'OLDEST' THEN s.created_at END ASC,
            CASE WHEN :sort = 'UPDATED' THEN s.updated_at END DESC,
            CASE WHEN :sort = 'REMINDER' AND s.remind_at IS NULL THEN 1 ELSE 0 END ASC,
            CASE WHEN :sort = 'REMINDER' THEN s.remind_at END ASC,
            s.created_at DESC
        """
    )
    fun pagedByTag(name: String, sort: String = "NEWEST"): PagingSource<Int, SaveItemEntity>

    @Query(
        """
        SELECT * FROM save_items
        WHERE remind_at IS NOT NULL AND remind_at > :now AND is_archived = 0 AND is_pending_delete = 0
        ORDER BY
            CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
            CASE WHEN :sort = 'UPDATED' THEN updated_at END DESC,
            CASE WHEN :sort = 'NEWEST' THEN created_at END DESC,
            remind_at ASC
        """
    )
    fun pagedUpcomingReminders(
        now: Long = System.currentTimeMillis(),
        sort: String = "REMINDER"
    ): PagingSource<Int, SaveItemEntity>

    // ── Aggregate queries: avoid loading rows just to count ───────────────
    // Counts also exclude archived so the user's home dashboard reflects
    // their "live" shelf size.

    @Query(
        """
        SELECT source_app AS source, COUNT(*) AS count
        FROM save_items
        WHERE is_archived = 0 AND is_pending_delete = 0
        GROUP BY source_app
        ORDER BY count DESC
        """
    )
    fun observeSourceCounts(): Flow<List<SourceCount>>

    @Query(
        """
        SELECT category_id AS categoryId, COUNT(*) AS count
        FROM save_items
        WHERE category_id IS NOT NULL AND is_archived = 0 AND is_pending_delete = 0
        GROUP BY category_id
        """
    )
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    /**
     * Collection ids ordered by the most recent (live) save assigned to them.
     * Backs the Save sheet ordering where the user's recently-used collections
     * float to the front of the chip row. Archived saves are excluded so a
     * collection you've stopped using doesn't hold its spot on stale rows. The
     * GROUP BY + MAX(created_at) rides the (category_id, created_at) index.
     */
    @Query(
        """
        SELECT category_id FROM save_items
        WHERE category_id IS NOT NULL AND is_archived = 0 AND is_pending_delete = 0
        GROUP BY category_id
        ORDER BY MAX(created_at) DESC
        LIMIT :limit
        """
    )
    fun observeRecentCategoryIds(limit: Int = 12): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM save_items WHERE is_archived = 0 AND is_pending_delete = 0")
    fun observeTotal(): Flow<Int>

    /** Count of archived items only — used to differentiate "fresh install" (0) from "you archived everything" (>0). */
    @Query("SELECT COUNT(*) FROM save_items WHERE is_archived = 1 AND is_pending_delete = 0")
    fun observeArchivedTotal(): Flow<Int>

    /** Count of live favorited items — drives the Favorites tile label in Browse. */
    @Query("SELECT COUNT(*) FROM save_items WHERE is_favorite = 1 AND is_archived = 0 AND is_pending_delete = 0")
    fun observeFavoriteTotal(): Flow<Int>

    // ── Reminders ─────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM save_items WHERE remind_at IS NOT NULL AND remind_at > :now AND is_archived = 0 AND is_pending_delete = 0")
    fun observeUpcomingReminderTotal(now: Long = System.currentTimeMillis()): Flow<Int>

    @Query("SELECT * FROM save_items WHERE remind_at IS NOT NULL AND remind_at <= :now AND is_archived = 0 AND is_pending_delete = 0 ORDER BY remind_at ASC")
    suspend fun dueReminders(now: Long = System.currentTimeMillis()): List<SaveItemEntity>

    /** All items with a pending reminder, regardless of whether it has fired yet. */
    @Query("SELECT * FROM save_items WHERE remind_at IS NOT NULL AND is_archived = 0 AND is_pending_delete = 0 ORDER BY remind_at ASC")
    suspend fun pendingReminders(): List<SaveItemEntity>

    // ── Mutations ────────────────────────────────────────────────────────

    @Query("UPDATE save_items SET is_favorite = :fav, updated_at = :ts WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE save_items SET is_pinned = :pin, updated_at = :ts WHERE id = :id")
    suspend fun setPinned(id: Long, pin: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE save_items SET is_archived = :archived, updated_at = :ts WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, ts: Long = System.currentTimeMillis())

    /**
     * Toggles the "Delete with Undo" staging flag. Keeping it separate
     * from [setArchived] preserves the user's original archived state —
     * Undo restores them to where they were, not to "archived".
     */
    @Query("UPDATE save_items SET is_pending_delete = :pending, updated_at = :ts WHERE id = :id")
    suspend fun setPendingDelete(id: Long, pending: Boolean, ts: Long = System.currentTimeMillis())

    /** Read attachment URIs for items left in the pending-delete state, before app-start cleanup. */
    @Query("SELECT a.uri FROM attachments a JOIN save_items s ON s.id = a.item_id WHERE s.is_pending_delete = 1")
    suspend fun urisForPendingDeletes(): List<String>

    /** Drops every row still in the pending-delete state. Called at app start. */
    @Query("DELETE FROM save_items WHERE is_pending_delete = 1")
    suspend fun permanentlyDeletePending()

    @Query("UPDATE save_items SET remind_at = :at, updated_at = :ts WHERE id = :id")
    suspend fun setRemindAt(id: Long, at: Long?, ts: Long = System.currentTimeMillis())

    @Query("UPDATE save_items SET opened_at = :ts WHERE id = :id")
    suspend fun touchOpened(id: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE save_items SET ocr_text = :text, updated_at = :ts WHERE id = :id")
    suspend fun setOcrText(id: Long, text: String, ts: Long = System.currentTimeMillis())

    /**
     * Append OCR text to a row atomically. Used when multiple OcrWorkers
     * race to merge attachment OCR into the parent item's ocr_text —
     * the previous read-modify-write at the worker level guaranteed
     * lost writes when attachments A and B finished simultaneously.
     * SQL `||` concatenation in a single UPDATE keeps each worker's
     * contribution.
     */
    @Query(
        """
        UPDATE save_items
        SET ocr_text = CASE
                WHEN ocr_text IS NULL OR ocr_text = '' THEN :text
                ELSE ocr_text || char(10) || char(10) || :text
            END,
            updated_at = :ts
        WHERE id = :id
        """
    )
    suspend fun appendOcrText(id: Long, text: String, ts: Long = System.currentTimeMillis())

    // ── FTS search ───────────────────────────────────────────────────────

    @Query(
        """
        SELECT s.* FROM save_items s
        JOIN save_items_fts ON save_items_fts.rowid = s.id
        WHERE save_items_fts MATCH :query AND s.is_pending_delete = 0
        ORDER BY s.created_at DESC
        LIMIT 200
        """
    )
    suspend fun search(query: String): List<SaveItemEntity>
}

data class SourceCount(val source: String, val count: Int)
data class CategoryCount(val categoryId: String, val count: Int)
