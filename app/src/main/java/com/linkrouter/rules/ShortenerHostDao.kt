package com.linkrouter.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortenerHostDao {

    @Query("SELECT * FROM shortener_hosts ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<ShortenerHostEntity>>

    @Query("SELECT * FROM shortener_hosts WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    fun observeEnabled(): Flow<List<ShortenerHostEntity>>

    @Query("SELECT * FROM shortener_hosts ORDER BY priority DESC, id ASC")
    suspend fun all(): List<ShortenerHostEntity>

    @Query("SELECT * FROM shortener_hosts WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun allEnabled(): List<ShortenerHostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShortenerHostEntity): Long

    @Update
    suspend fun update(entity: ShortenerHostEntity)

    @Query("DELETE FROM shortener_hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM shortener_hosts WHERE isBuiltIn = 0")
    suspend fun deleteNonBuiltIn()

    @Query("SELECT * FROM shortener_hosts WHERE isBuiltIn = 1")
    suspend fun builtIns(): List<ShortenerHostEntity>

    @Query("SELECT COUNT(*) FROM shortener_hosts")
    suspend fun count(): Int
}
