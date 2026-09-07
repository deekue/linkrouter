package com.linkrouter.rules

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed shortener-host store. Mirrors [RedirectFormatRepository]: ordered
 * CRUD + an `enabled()` stream. List order is the user-visible priority (top =
 * highest). Built-in rows are non-deletable.
 */
open class ShortenerHostRepository(private val db: LinkRouterDatabase) {

    private val dao = db.shortenerHostDao()

    /** All hosts in user-visible order (top first). */
    fun observeAll(): Flow<List<ShortenerHost>> =
        dao.observeAll().map { list -> list.map { it.toShortenerHost() } }

    /** Only enabled hosts, in priority order, for the dispatcher. */
    fun enabled(): Flow<List<ShortenerHost>> =
        dao.observeEnabled().map { list -> list.map { it.toShortenerHost() } }

    suspend fun all(): List<ShortenerHost> = dao.all().map { it.toShortenerHost() }

    open suspend fun allEnabled(): List<ShortenerHost> = dao.allEnabled().map { it.toShortenerHost() }

    suspend fun count(): Int = dao.count()

    /** Insert a new host (id must be 0). Returns the new id. */
    suspend fun insert(host: ShortenerHost): Long {
        val maxPriority = dao.all().maxOfOrNull { it.priority } ?: 0
        return dao.upsert(
            ShortenerHostEntity.fromShortenerHost(
                host.copy(id = 0L, priority = maxPriority + 1)
            )
        )
    }

    /** Update an existing host. Built-ins may only be re-enabled, not re-targeted. */
    suspend fun update(host: ShortenerHost) {
        val current = dao.all().firstOrNull { it.id == host.id } ?: return
        if (current.isBuiltIn) {
            // Only the enabled flag is user-editable on a built-in.
            dao.update(ShortenerHostEntity.fromShortenerHost(current.toShortenerHost().copy(enabled = host.enabled)))
            return
        }
        dao.update(ShortenerHostEntity.fromShortenerHost(host))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        dao.update(ShortenerHostEntity.fromShortenerHost(current.toShortenerHost().copy(enabled = enabled)))
    }

    /**
     * Delete a host. Built-in rows are refused: deleting a built-in is
     * redirected to disabling it.
     */
    suspend fun delete(id: Long) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        if (current.isBuiltIn) {
            dao.update(ShortenerHostEntity.fromShortenerHost(current.toShortenerHost().copy(enabled = false)))
        } else {
            dao.deleteById(id)
        }
    }
}
