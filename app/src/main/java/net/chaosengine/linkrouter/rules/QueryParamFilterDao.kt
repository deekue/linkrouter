package net.chaosengine.linkrouter.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QueryParamFilterDao {

    @Query("SELECT * FROM query_param_filters ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<QueryParamFilterEntity>>

    @Query("SELECT * FROM query_param_filters WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    fun observeEnabled(): Flow<List<QueryParamFilterEntity>>

    @Query("SELECT * FROM query_param_filters ORDER BY priority DESC, id ASC")
    suspend fun all(): List<QueryParamFilterEntity>

    @Query("SELECT * FROM query_param_filters WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun allEnabled(): List<QueryParamFilterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: QueryParamFilterEntity): Long

    @Update
    suspend fun update(entity: QueryParamFilterEntity)

    @Query("DELETE FROM query_param_filters WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM query_param_filters WHERE isBuiltIn = 0")
    suspend fun deleteNonBuiltIn()

    @Query("SELECT * FROM query_param_filters WHERE isBuiltIn = 1")
    suspend fun builtIns(): List<QueryParamFilterEntity>

    @Query("SELECT COUNT(*) FROM query_param_filters")
    suspend fun count(): Int
}
