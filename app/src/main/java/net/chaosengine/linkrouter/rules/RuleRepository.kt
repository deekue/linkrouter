package net.chaosengine.linkrouter.rules

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed rule store (DESIGN.md section 4). Exposes ordered CRUD and an
 * `enabledRules()` stream. List order is the user-visible priority
 * (top = highest).
 */
open class RuleRepository(private val db: LinkRouterDatabase) {

    private val dao = db.ruleDao()

    /** All rules in user-visible order (top first). */
    open fun observeOrdered(): Flow<List<Rule>> =
        dao.observeOrdered().map { list -> list.map { it.toRule() } }

    /** Only enabled rules, in priority order, for the RuleEngine. */
    fun enabledRules(): Flow<List<Rule>> =
        dao.observeEnabled().map { list -> list.map { it.toRule() } }

    open suspend fun all(): List<Rule> = dao.allOrdered().map { it.toRule() }

    suspend fun count(): Int = dao.count()

    /** Insert a new rule (id must be 0). Returns the new id. */
    suspend fun insert(rule: Rule): Long {
        val maxPriority = dao.allOrdered().maxOfOrNull { it.priority } ?: 0
        return dao.upsert(RuleEntity.fromRule(rule).copy(id = 0L, priority = maxPriority + 1))
    }

    /** Update an existing rule. */
    suspend fun update(rule: Rule) = dao.update(RuleEntity.fromRule(rule))

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = dao.allOrdered().firstOrNull { it.id == id } ?: return
        dao.update(RuleEntity.fromRule(current.toRule().copy(enabled = enabled)))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    /** Duplicate a rule directly below the original, same settings. */
    suspend fun duplicate(id: Long): Long {
        val rules = dao.allOrdered()
        val src = rules.firstOrNull { it.id == id } ?: return -1L
        // Insert at the same priority, shifting everything at/above down by one.
        val shifted = rules.map { r ->
            if (r.priority >= src.priority) r.copy(priority = r.priority + 1) else r
        }
        val copy = src.copy(id = 0L, priority = src.priority)
        return db.withTransaction {
            shifted.forEach { dao.update(it) }
            dao.upsert(copy)
        }
    }

    /** Reorder to a new ordered list of ids (top first). */
    suspend fun reorder(newTopFirstOrder: List<Long>) {
        val current = dao.allOrdered()
        val byId = current.associateBy { it.id }
        val n = newTopFirstOrder.size
        val updated = newTopFirstOrder.withIndex()
            .mapNotNull { (index, id) -> byId[id]?.copy(priority = n - index) }
        // Preserve any ids not in the new order (defensive).
        val known = newTopFirstOrder.toSet()
        val leftovers = current.filter { it.id !in known }
        db.withTransaction {
            (updated + leftovers).forEach { dao.update(it) }
        }
    }

    /** Replace the entire rule set (import). */
    suspend fun importAll(rules: List<Rule>) = db.withTransaction {
        dao.deleteAll()
        rules.forEachIndexed { index, rule ->
            dao.upsert(RuleEntity.fromRule(rule).copy(priority = rules.size - index))
        }
    }
}
