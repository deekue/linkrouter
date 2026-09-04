package com.linkrouter.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules ORDER BY priority DESC")
    fun observeOrdered(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority DESC")
    fun observeEnabled(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY priority DESC")
    suspend fun allOrdered(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RuleEntity): Long

    @Update
    suspend fun update(entity: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int
}
