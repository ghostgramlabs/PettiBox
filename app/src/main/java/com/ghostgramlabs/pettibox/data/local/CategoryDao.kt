package com.ghostgramlabs.pettibox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(c: List<CategoryEntity>)

    @Update
    suspend fun update(c: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC, sort_order ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE ASC, sort_order ASC")
    suspend fun allForExport(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE parent_id = :parentId ORDER BY name COLLATE NOCASE ASC, sort_order ASC")
    fun observeChildrenOf(parentId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE is_locked = 1")
    fun observeLockedCount(): Flow<Int>

    @Query("SELECT COALESCE((SELECT is_locked FROM categories WHERE id = :id), 0)")
    fun observeIsLocked(id: String): Flow<Boolean>
}
