package com.linkrouter.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RedirectFormatDao {

    @Query("SELECT * FROM redirect_formats ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<RedirectFormatEntity>>

    @Query("SELECT * FROM redirect_formats WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    fun observeEnabled(): Flow<List<RedirectFormatEntity>>

    @Query("SELECT * FROM redirect_formats ORDER BY priority DESC, id ASC")
    suspend fun all(): List<RedirectFormatEntity>

    @Query("SELECT * FROM redirect_formats WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun allEnabled(): List<RedirectFormatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RedirectFormatEntity): Long

    @Update
    suspend fun update(entity: RedirectFormatEntity)

    @Query("DELETE FROM redirect_formats WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM redirect_formats")
    suspend fun deleteAll()

    @Query("DELETE FROM redirect_formats WHERE isBuiltIn = 0")
    suspend fun deleteNonBuiltIn()

    @Query("SELECT * FROM redirect_formats WHERE isBuiltIn = 1")
    suspend fun builtIns(): List<RedirectFormatEntity>

    @Query("SELECT COUNT(*) FROM redirect_formats")
    suspend fun count(): Int
}
