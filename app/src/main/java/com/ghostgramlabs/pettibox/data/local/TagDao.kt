package com.ghostgramlabs.pettibox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT id FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findIdByName(name: String): Long?

    /**
     * Idempotent get-or-create. Returns the tag id whether the tag was just
     * inserted or already existed.
     */
    @Transaction
    open suspend fun upsert(name: String): Long {
        val clean = name.trim().trimStart('#').trim()
        if (clean.isBlank()) return -1L
        findIdByName(clean)?.let { return it }
        val id = insertTag(TagEntity(name = clean))
        if (id == -1L) return findIdByName(clean) ?: -1L
        return id
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun link(ref: ItemTagCrossRef)

    @Query("DELETE FROM item_tags WHERE item_id = :itemId AND tag_id = :tagId")
    abstract suspend fun unlink(itemId: Long, tagId: Long)

    @Query("DELETE FROM item_tags WHERE item_id = :itemId")
    abstract suspend fun unlinkAll(itemId: Long)

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN item_tags it ON it.tag_id = t.id
        WHERE it.item_id = :itemId
        ORDER BY t.name COLLATE NOCASE ASC
        """
    )
    abstract fun observeTagsForItem(itemId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN item_tags it ON it.tag_id = t.id
        WHERE it.item_id = :itemId
        ORDER BY t.name COLLATE NOCASE ASC
        """
    )
    abstract suspend fun tagsForItem(itemId: Long): List<TagEntity>

    /**
     * Top tags by usage. O(unique tags) — not O(items) — because we count via
     * the join table, never materializing item rows.
     */
    @Query(
        """
        SELECT t.id AS id, t.name AS name, COUNT(it.tag_id) AS count
        FROM tags t
        LEFT JOIN item_tags it ON it.tag_id = t.id
        GROUP BY t.id
        HAVING count > 0
        ORDER BY count DESC, t.name COLLATE NOCASE ASC
        LIMIT :limit
        """
    )
    abstract fun observeTopTags(limit: Int = 20): Flow<List<TagWithCount>>

    @Query(
        """
        SELECT s.id FROM save_items s
        JOIN item_tags it ON it.item_id = s.id
        JOIN tags t ON t.id = it.tag_id
        WHERE t.name = :name COLLATE NOCASE
        """
    )
    abstract suspend fun itemIdsForTag(name: String): List<Long>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    abstract suspend fun allForExport(): List<TagEntity>

    @Query("SELECT * FROM item_tags ORDER BY item_id ASC, tag_id ASC")
    abstract suspend fun linksForExport(): List<ItemTagCrossRef>
}

data class TagWithCount(val id: Long, val name: String, val count: Int)
