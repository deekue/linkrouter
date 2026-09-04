package com.linkrouter.rules

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed redirect-format store (generalizes the hardcoded `unwrapRedirect`).
 * Mirrors [RuleRepository]: ordered CRUD + an `enabled()` stream. List order is
 * the user-visible priority (top = highest). Built-in rows are non-deletable.
 */
open class RedirectFormatRepository(private val db: LinkRouterDatabase) {

    private val dao = db.redirectFormatDao()

    /** All formats in user-visible order (top first). */
    fun observeAll(): Flow<List<RedirectFormat>> =
        dao.observeAll().map { list -> list.map { it.toRedirectFormat() } }

    /** Only enabled formats, in priority order, for the [RedirectResolver]. */
    fun enabled(): Flow<List<RedirectFormat>> =
        dao.observeEnabled().map { list -> list.map { it.toRedirectFormat() } }

    suspend fun all(): List<RedirectFormat> = dao.all().map { it.toRedirectFormat() }

    open suspend fun allEnabled(): List<RedirectFormat> = dao.allEnabled().map { it.toRedirectFormat() }

    suspend fun count(): Int = dao.count()

    /** Insert a new format (id must be 0). Returns the new id. */
    suspend fun insert(format: RedirectFormat): Long {
        val maxPriority = dao.all().maxOfOrNull { it.priority } ?: 0
        return dao.upsert(
            RedirectFormatEntity.fromRedirectFormat(
                format.copy(id = 0L, priority = maxPriority + 1)
            )
        )
    }

    /** Update an existing format. Built-ins may only be re-enabled, not re-targeted. */
    suspend fun update(format: RedirectFormat) {
        val current = dao.all().firstOrNull { it.id == format.id } ?: return
        if (current.isBuiltIn) {
            // Only the enabled flag is user-editable on a built-in.
            dao.update(RedirectFormatEntity.fromRedirectFormat(current.toRedirectFormat().copy(enabled = format.enabled)))
            return
        }
        dao.update(RedirectFormatEntity.fromRedirectFormat(format))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        dao.update(RedirectFormatEntity.fromRedirectFormat(current.toRedirectFormat().copy(enabled = enabled)))
    }

    /**
     * Delete a format. Built-in rows are refused: deleting a built-in is
     * redirected to disabling it (use [resetBuiltIn] to restore it).
     */
    suspend fun delete(id: Long) {
        val current = dao.all().firstOrNull { it.id == id } ?: return
        if (current.isBuiltIn) {
            dao.update(RedirectFormatEntity.fromRedirectFormat(current.toRedirectFormat().copy(enabled = false)))
        } else {
            dao.deleteById(id)
        }
    }

    /** Restore the built-in Google format to its default values. */
    suspend fun resetBuiltIn() {
        val existing = dao.all().firstOrNull { it.isBuiltIn }
        if (existing == null) {
            dao.upsert(RedirectFormatEntity.fromRedirectFormat(RedirectFormat.BUILT_IN_GOOGLE))
        } else {
            dao.update(RedirectFormatEntity.fromRedirectFormat(RedirectFormat.BUILT_IN_GOOGLE.copy(id = existing.id)))
        }
    }

    /**
     * Replace the user's formats (import). Built-ins are preserved (or
     * re-seeded if absent); the import list must not contain built-ins.
     */
    suspend fun importAllFormats(formats: List<RedirectFormat>) = db.withTransaction {
        dao.deleteNonBuiltIn()
        val hasBuiltIn = dao.builtIns().isNotEmpty()
        if (!hasBuiltIn) {
            dao.upsert(RedirectFormatEntity.fromRedirectFormat(RedirectFormat.BUILT_IN_GOOGLE))
        }
        val userFormats = formats.filter { !it.isBuiltIn }
        userFormats.forEachIndexed { index, format ->
            val priority = userFormats.size - index
            dao.upsert(
                RedirectFormatEntity.fromRedirectFormat(
                    format.copy(id = 0L, isBuiltIn = false, priority = priority)
                )
            )
        }
    }
}
