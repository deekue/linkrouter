package com.linkrouter.rules

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed query-param-filter store. Mirrors [ShortenerHostRepository]:
 * ordered CRUD + an `enabled()` stream. List order is the user-visible priority
 * (top = highest). Built-in rows are non-deletable (deleting redirects to
 * disabling) and only their `enabled` flag is user-editable.
 */
open class QueryParamFilterRepository(private val db: LinkRouterDatabase) {

    private val dao = db.queryParamFilterDao()

    /** All filters in user-visible order (top first). */
    fun observeAll(): Flow<List<QueryParamFilter>> =
        dao.observeAll().map { list -> list.map { it.toQueryParamFilter() } }

    /** Only enabled filters, in priority order, for the dispatcher. */
    fun enabled(): Flow<List<QueryParamFilter>> =
        dao.observeEnabled().map { list -> list.map { it.toQueryParamFilter() } }

    suspend fun all(): List<QueryParamFilter> = dao.all().map { it.toQueryParamFilter() }

    open suspend fun allEnabled(): List<QueryParamFilter> = dao.allEnabled().map { it.toQueryParamFilter() }

    suspend fun count(): Int = dao.count()

    /** Insert a new filter (id must be 0). Returns the new id. */
    suspend fun insert(filter: QueryParamFilter): Long {
        val maxPriority = dao.all().maxOfOrNull { it.priority } ?: 0
        return dao.upsert(
            QueryParamFilterEntity.fromQueryParamFilter(
                filter.copy(id = 0L, priority = maxPriority + 1)
            )
        )
    }

    /** Update an existing filter. Built-ins may only be re-enabled, not re-targeted. */
    suspend fun update(filter: QueryParamFilter) {
        val current = dao.all().firstOrNull { it.id == filter.id } ?: return
        if (current.isBuiltIn) {
            // Only the enabled flag is user-editable on a built-in.
            dao.update(QueryParamFilterEntity.fromQueryParamFilter(current.toQueryParamFilter().copy(enabled = filter.enabled)))
            return
        }
        dao.update(QueryParamFilterEntity.fromQueryParamFilter(filter))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        dao.update(QueryParamFilterEntity.fromQueryParamFilter(current.toQueryParamFilter().copy(enabled = enabled)))
    }

    /**
     * Delete a filter. Built-in rows are refused: deleting a built-in is
     * redirected to disabling it.
     */
    suspend fun delete(id: Long) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        if (current.isBuiltIn) {
            dao.update(QueryParamFilterEntity.fromQueryParamFilter(current.toQueryParamFilter().copy(enabled = false)))
        } else {
            dao.deleteById(id)
        }
    }
}
