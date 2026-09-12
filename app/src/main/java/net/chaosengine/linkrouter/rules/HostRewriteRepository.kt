package net.chaosengine.linkrouter.rules

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed host-rewrite store. Mirrors [QueryParamFilterRepository]:
 * ordered CRUD + an `enabled()` stream. List order is the user-visible
 * priority (top = highest). Built-in rows are non-deletable (deleting
 * redirects to disabling); their target/enable state is user-editable (the
 * user may enable, toggle, or re-target a seeded example — just never remove
 * it, keeping the seeds recoverable).
 */
open class HostRewriteRepository(private val db: LinkRouterDatabase) {

    private val dao = db.hostRewriteDao()

    /** All rewrites in user-visible order (top first). */
    fun observeAll(): Flow<List<HostRewrite>> =
        dao.observeAll().map { list -> list.map { it.toHostRewrite() } }

    /** Only enabled rewrites, in priority order, for the [HostRewriter]. */
    fun enabled(): Flow<List<HostRewrite>> =
        dao.observeEnabled().map { list -> list.map { it.toHostRewrite() } }

    suspend fun all(): List<HostRewrite> = dao.all().map { it.toHostRewrite() }

    open suspend fun allEnabled(): List<HostRewrite> = dao.allEnabled().map { it.toHostRewrite() }

    suspend fun count(): Int = dao.count()

    /** Insert a new rewrite (id must be 0). Returns the new id. */
    suspend fun insert(rw: HostRewrite): Long {
        val maxPriority = dao.all().maxOfOrNull { it.priority } ?: 0
        return dao.upsert(
            HostRewriteEntity.fromHostRewrite(
                rw.copy(id = 0L, priority = maxPriority + 1)
            )
        )
    }

    /** Update an existing rewrite. */
    suspend fun update(rw: HostRewrite) {
        val current = dao.all().firstOrNull { it.id == rw.id } ?: return
        // Built-in: the user may re-target but never change the isBuiltIn flag
        // or id; keep the seed recoverable.
        dao.update(HostRewriteEntity.fromHostRewrite(rw.copy(isBuiltIn = current.isBuiltIn)))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        dao.update(HostRewriteEntity.fromHostRewrite(current.toHostRewrite().copy(enabled = enabled)))
    }

    /**
     * Delete a rewrite. Built-in rows are refused: deleting a built-in is
     * redirected to disabling it.
     */
    suspend fun delete(id: Long) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        if (current.isBuiltIn) {
            dao.update(HostRewriteEntity.fromHostRewrite(current.toHostRewrite().copy(enabled = false)))
        } else {
            dao.deleteById(id)
        }
    }

    /** Reorder to a new ordered list of ids (top first). */
    suspend fun reorder(newTopFirstOrder: List<Long>) {
        val current = dao.all()
        val byId = current.associateBy { it.id }
        val n = newTopFirstOrder.size
        // (id, final priority): the top row gets the highest priority.
        val finals: List<Pair<Long, Int>> = newTopFirstOrder.withIndex()
            .mapNotNull { (index, id) -> byId[id]?.let { id to (n - index) } }
        // Preserve any ids not in the new order (defensive): keep them below the
        // re-ordered rows so their priorities never collide with the finals.
        val known = newTopFirstOrder.toSet()
        val leftovers = current.filter { it.id !in known }
        val allFinals = finals + leftovers.withIndex().map { (index, e) -> e.id to (n + 1 + index) }
        val maxPriority = current.maxOfOrNull { it.priority } ?: 0
        val needsTemp = allFinals.any { (id, finalP) -> byId[id]?.priority != finalP }

        // HostRewriteEntity enforces a UNIQUE index on `priority`. Writing the
        // final priorities one row at a time can collide with a *different* row
        // that still holds the slot being moved onto it (trips the unique index
        // and crashes). When anything actually moves, first push every changing
        // row to a slot strictly above every current priority (guaranteed
        // distinct and non-clashing), then set the real priorities.
        db.withTransaction {
            if (needsTemp) {
                allFinals.withIndex().forEach { (shift, idAndPriority) ->
                    byId[idAndPriority.first]?.let { dao.update(it.copy(priority = maxPriority + 1 + shift)) }
                }
            }
            allFinals.forEach { (id, finalP) ->
                byId[id]?.let { dao.update(it.copy(priority = finalP)) }
            }
        }
    }

    /** Replace the user's rewrites (import). Built-in rows are preserved. */
    suspend fun importAll(rewrites: List<HostRewrite>) = db.withTransaction {
        dao.deleteNonBuiltIn()
        val userRewrites = rewrites.filter { !it.isBuiltIn }
        userRewrites.forEachIndexed { index, rw ->
            dao.upsert(
                HostRewriteEntity.fromHostRewrite(
                    rw.copy(id = 0L, isBuiltIn = false, priority = userRewrites.size - index)
                )
            )
        }
    }
}
