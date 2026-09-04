package com.linkrouter.rules

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RedirectFormatRepositoryTest {

    private lateinit var db: LinkRouterDatabase
    private lateinit var repo: RedirectFormatRepository

    private fun fmt(pattern: String = "example.com", name: String = "F", enabled: Boolean = true) =
        RedirectFormat(
            id = 0,
            name = name,
            pattern = pattern,
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.QUERY_PARAM,
            extractTarget = "q",
            enabled = enabled,
            priority = 0,
            isBuiltIn = false,
        )

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LinkRouterDatabase::class.java).build()
        repo = RedirectFormatRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fresh_install_seed_via_resetBuiltIn_creates_one_built_in() = runBlocking {
        repo.resetBuiltIn()

        val all = repo.all()
        val builtIns = all.filter { it.isBuiltIn }
        assertEquals(1, builtIns.size)
        val b = builtIns.single()
        assertEquals("Google", b.name)
        assertEquals("google.com/url", b.pattern)
        assertEquals("q", b.extractTarget)
        assertEquals(1000, b.priority)
        assertEquals(MatchType.PATH_PREFIX, b.matchType)
        assertEquals(ExtractType.QUERY_PARAM, b.extractType)
    }

    @Test
    fun seed_is_idempotent() = runBlocking {
        repo.resetBuiltIn()
        repo.resetBuiltIn()

        val all = repo.all()
        assertEquals(1, all.count { it.isBuiltIn })
        // The seeded built-in is the only row (fresh in-memory db).
        assertEquals(1, repo.count())
        assertEquals(1, all.size)
    }

    @Test
    fun inserting_user_format_does_not_clobber_builtin() = runBlocking {
        repo.resetBuiltIn()
        val id1 = repo.insert(fmt("a.com"))
        val id2 = repo.insert(fmt("b.com"))

        assertTrue("user format ids must be distinct", id1 != id2)
        assertTrue("user format ids must be non-zero", id1 > 0 && id2 > 0)

        val all = repo.all()
        assertEquals(3, all.size)
        assertEquals(1, all.count { it.isBuiltIn })
        assertEquals(setOf("a.com", "b.com"), all.filter { !it.isBuiltIn }.map { it.pattern }.toSet())
    }

    @Test
    fun delete_user_format_removes_only_it() = runBlocking {
        repo.resetBuiltIn()
        val idA = repo.insert(fmt("a.com"))
        repo.insert(fmt("b.com"))

        repo.delete(idA)

        val all = repo.all()
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.isBuiltIn })
        val users = all.filter { !it.isBuiltIn }.map { it.pattern }
        assertTrue("a.com removed", !users.contains("a.com"))
        assertTrue("b.com kept", users.contains("b.com"))
    }

    @Test
    fun delete_builtin_refused_disables_it() = runBlocking {
        repo.resetBuiltIn()
        val builtinId = repo.all().single { it.isBuiltIn }.id

        repo.delete(builtinId)

        val all = repo.all()
        assertEquals("built-in must not be deletable", 1, all.count { it.isBuiltIn })
        val b = all.single { it.isBuiltIn }
        assertEquals(false, b.enabled)
    }

    @Test
    fun setEnabled_builtin() = runBlocking {
        repo.resetBuiltIn()
        val builtinId = repo.all().single { it.isBuiltIn }.id

        repo.setEnabled(builtinId, false)
        assertEquals(false, repo.all().single { it.isBuiltIn }.enabled)

        repo.setEnabled(builtinId, true)
        assertEquals(true, repo.all().single { it.isBuiltIn }.enabled)
    }

    @Test
    fun update_builtin_only_changes_enabled() = runBlocking {
        repo.resetBuiltIn()
        val builtIn = repo.all().single { it.isBuiltIn }

        repo.update(builtIn.copy(enabled = false, pattern = "hacked.com"))

        val b = repo.all().single { it.isBuiltIn }
        assertEquals("only the enabled flag is editable on a built-in", "google.com/url", b.pattern)
        assertEquals(false, b.enabled)
    }

    @Test
    fun update_user_format_full() = runBlocking {
        val id = repo.insert(fmt("a.com"))
        val current = repo.all().single { it.id == id }

        repo.update(current.copy(pattern = "a2.com", extractTarget = "url"))

        val updated = repo.all().single { it.id == id }
        assertEquals("a2.com", updated.pattern)
        assertEquals("url", updated.extractTarget)
    }

    @Test
    fun importAllFormats_replaces_user_formats_keeps_builtin() = runBlocking {
        repo.resetBuiltIn()
        repo.insert(fmt("old1.com"))
        repo.insert(fmt("old2.com"))

        repo.importAllFormats(listOf(fmt("x.com"), fmt("y.com")))

        val all = repo.all()
        assertEquals(3, all.size)
        assertEquals(1, all.count { it.isBuiltIn })
        val users = all.filter { !it.isBuiltIn }.map { it.pattern }.toSet()
        assertEquals(setOf("x.com", "y.com"), users)
        // Fresh auto ids, non-zero.
        assertTrue(all.filter { !it.isBuiltIn }.all { it.id > 0 })
    }

    @Test
    fun importAllFormats_filters_out_builtin_entries_in_import() = runBlocking {
        repo.resetBuiltIn()
        repo.importAllFormats(
            listOf(
                fmt("x.com"),
                RedirectFormat(
                    id = -1,
                    name = "Google",
                    pattern = "google.com/url",
                    matchType = MatchType.PATH_PREFIX,
                    extractType = ExtractType.QUERY_PARAM,
                    extractTarget = "q",
                    enabled = true,
                    priority = 1,
                    isBuiltIn = true,
                ),
            ),
        )

        val all = repo.all()
        assertEquals("import's built-in must be filtered, not duplicated", 1, all.count { it.isBuiltIn })
        val users = all.filter { !it.isBuiltIn }.map { it.pattern }.toSet()
        assertEquals(setOf("x.com"), users)
    }

    @Test
    fun allEnabled_returns_only_enabled() = runBlocking {
        repo.resetBuiltIn() // built-in is enabled by default
        repo.insert(fmt("enabled.com", enabled = true))
        repo.insert(fmt("disabled.com", enabled = false))

        val enabled = repo.allEnabled()
        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
        assertEquals(setOf("google.com/url", "enabled.com"), enabled.map { it.pattern }.toSet())
    }

    @Test
    fun count_reflects_rows() = runBlocking {
        repo.resetBuiltIn()
        repo.insert(fmt("a.com"))
        repo.insert(fmt("b.com"))

        assertEquals(3, repo.count())
    }
}
