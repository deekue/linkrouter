package net.chaosengine.linkrouter.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostRewriteDao {

    @Query("SELECT * FROM host_rewrites ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<HostRewriteEntity>>

    @Query("SELECT * FROM host_rewrites WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    fun observeEnabled(): Flow<List<HostRewriteEntity>>

    @Query("SELECT * FROM host_rewrites ORDER BY priority DESC, id ASC")
    suspend fun all(): List<HostRewriteEntity>

    @Query("SELECT * FROM host_rewrites WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun allEnabled(): List<HostRewriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HostRewriteEntity): Long

    @Update
    suspend fun update(entity: HostRewriteEntity)

    @Query("DELETE FROM host_rewrites WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM host_rewrites WHERE isBuiltIn = 0")
    suspend fun deleteNonBuiltIn()

    @Query("SELECT * FROM host_rewrites WHERE isBuiltIn = 1")
    suspend fun builtIns(): List<HostRewriteEntity>

    @Query("SELECT COUNT(*) FROM host_rewrites")
    suspend fun count(): Int
}
