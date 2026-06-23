package com.raban.etabli.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY `order`")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session ORDER BY startedAtEpoch DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE completed = 1 AND kind = 'focus' AND startedAtEpoch >= :sinceEpoch")
    fun observeCompletedFocusSince(sinceEpoch: Long): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM template_entry")
    fun observeAll(): Flow<List<TemplateEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TemplateEntryEntity)

    @Query("DELETE FROM template_entry WHERE weekday = :weekday AND categoryID = :categoryID")
    suspend fun delete(weekday: Int, categoryID: String)
}
