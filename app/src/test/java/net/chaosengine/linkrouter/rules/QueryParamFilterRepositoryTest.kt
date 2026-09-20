package net.chaosengine.linkrouter.rules

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueryParamFilterRepositoryTest {

    private lateinit var db: LinkRouterDatabase
    private lateinit var repo: QueryParamFilterRepository

    private fun filter(
        param: String,
        host: String? = null,
        name: String = "F",
        enabled: Boolean = false,
        isBuiltIn: Boolean = false,
        priority: Int = 0,
    ) = QueryParamFilter(
        id = 0,
        name = name,
        host = host,
        param = param,
        enabled = enabled,
        priority = priority,
        isBuiltIn = isBuiltIn,
    )

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LinkRouterDatabase::class.java).build()
        repo = QueryParamFilterRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_returns_distinct_non_zero_ids() = runBlocking {
        val id1 = repo.insert(filter("utm_source"))
        val id2 = repo.insert(filter("gclid"))

        assertTrue("filter ids must be non-zero", id1 > 0 && id2 > 0)
        assertTrue("filter ids must be distinct", id1 != id2)
    }

    @Test
    fun all_and_observeAll_order_by_priority_then_id() = runBlocking {
        val first = repo.insert(filter("utm_source"))
        val second = repo.insert(filter("gclid"))

        // Later insert gets the higher auto-assigned priority.
        val all = repo.all()
        assertEquals(2, all.size)
        assertEquals(listOf(second, first), all.map { it.id })

        val observed = repo.observeAll().first()
        assertEquals(listOf(second, first), observed.map { it.id })
    }

    @Test
    fun allEnabled_returns_only_enabled() = runBlocking {
        repo.insert(filter("enabled_param", enabled = true))
        repo.insert(filter("disabled_param", enabled = false))

        val enabled = repo.allEnabled()
        assertEquals(1, enabled.size)
        assertEquals("enabled_param", enabled.single().param)
    }

    @Test
    fun setEnabled_toggles() = runBlocking {
        val id = repo.insert(filter("utm_source"))

        repo.setEnabled(id, true)
        assertEquals(true, repo.all().single { it.id == id }.enabled)

        repo.setEnabled(id, false)
        assertEquals(false, repo.all().single { it.id == id }.enabled)
    }

    @Test
    fun delete_user_filter_removes_it() = runBlocking {
        val idA = repo.insert(filter("a_param"))
        repo.insert(filter("b_param"))

        repo.delete(idA)

        val all = repo.all()
        assertEquals(1, all.size)
        assertEquals("b_param", all.single().param)
    }

    @Test
    fun delete_builtin_disables_it_not_removes_it() = runBlocking {
        repo.insert(filter("utm_source", isBuiltIn = true))

        val builtInId = repo.all().single { it.isBuiltIn }.id
        repo.delete(builtInId)

        val all = repo.all()
        assertEquals("built-in must not be deletable", 1, all.count { it.isBuiltIn })
        val b = all.single { it.isBuiltIn }
        assertEquals(false, b.enabled)
    }

    @Test
    fun update_builtin_only_changes_enabled() = runBlocking {
        val builtInId = repo.insert(filter("utm_source", isBuiltIn = true))
        val current = repo.all().single { it.isBuiltIn }

        repo.update(current.copy(enabled = true, param = "hacked", host = "evil.com"))

        val b = repo.all().single { it.isBuiltIn }
        assertEquals("only the enabled flag is editable on a built-in", "utm_source", b.param)
        assertEquals("built-in host scope is not editable", null, b.host)
        assertEquals(true, b.enabled)
        assertEquals(builtInId, b.id)
    }

    @Test
    fun insert_global_filter_round_trips_with_null_host() = runBlocking {
        val id = repo.insert(filter("utm_source", host = null))

        val f = repo.all().single { it.id == id }
        assertNull("global filters must store a null host", f.host)
        assertEquals("utm_source", f.param)
    }

    @Test
    fun insert_scoped_filter_round_trips_with_host() = runBlocking {
        val id = repo.insert(filter("_t", host = "tiktok.com"))

        val f = repo.all().single { it.id == id }
        assertEquals("tiktok.com", f.host)
        assertEquals("_t", f.param)
    }

    @Test
    fun update_user_filter_changes_host_and_param() = runBlocking {
        val id = repo.insert(filter("utm_source"))
        val current = repo.all().single { it.id == id }

        repo.update(current.copy(param = "gclid", host = "example.com"))

        val f = repo.all().single { it.id == id }
        assertEquals("gclid", f.param)
        assertEquals("example.com", f.host)
    }

    @Test
    fun count_reflects_rows() = runBlocking {
        assertEquals(0, repo.count())

        repo.insert(filter("a_param"))
        repo.insert(filter("b_param"))
        assertEquals(2, repo.count())

        val id = repo.all().first { it.param == "a_param" }.id
        repo.delete(id)
        assertEquals(1, repo.count())
    }

    @Test
    fun importAllFilters_replacesUserRows_preservesBuiltIns() = runBlocking {
        // Pre-existing state: one user filter + one built-in.
        repo.insert(filter("old-user_param", enabled = true))
        repo.insert(filter("builtin_param", isBuiltIn = true, enabled = false))

        // Import a fresh set containing users + a built-in whose natural key
        // (param, host) matches the seeded one: only its `enabled` flag is applied.
        repo.importAllFilters(
            listOf(
                filter("new-a_param", enabled = true),
                filter("new-b_param", host = "scop.example.com", enabled = false),
                filter("builtin_param", isBuiltIn = true, enabled = true),
            )
        )

        val all = repo.all()
        // The old user filter is gone; the two new user filters remain.
        assertEquals("imported user rows", setOf("new-a_param", "new-b_param"), all.filter { !it.isBuiltIn }.map { it.param }.toSet())
        // The single built-in row survived the import; the import matched it by
        // natural key and applied its enabled flag.
        assertEquals("built-in must be preserved", 1, all.count { it.isBuiltIn })
        assertEquals("imported built-in enabled flag is applied", true, all.single { it.isBuiltIn }.enabled)
    }

    @Test
    fun importAllFilters_emptyList_clearsUserRows_keepsBuiltIns() = runBlocking {
        repo.insert(filter("user_param", enabled = true))
        repo.insert(filter("builtin_param", isBuiltIn = true))

        repo.importAllFilters(emptyList())

        val all = repo.all()
        assertEquals("no user rows after empty import", 0, all.count { !it.isBuiltIn })
        assertEquals("built-in survives", 1, all.count { it.isBuiltIn })
    }

    @Test
    fun importAll_userEntries_replaceUserRows_keepsBuiltins() = runBlocking {
        // Pre-existing state: one built-in (canonical seed) + one user row.
        repo.insert(filter("builtin_p", isBuiltIn = true, enabled = false))
        repo.insert(filter("existing-user_param", enabled = true))
        assertEquals(1, repo.all().count { it.isBuiltIn })
        assertEquals(1, repo.all().count { !it.isBuiltIn })

        // Import two fresh user entries (the prior user row must be replaced).
        repo.importAllFilters(
            listOf(
                filter("u1_param", enabled = true),
                filter("u2_param", enabled = false),
            )
        )

        val all = repo.all()
        val users = all.filter { !it.isBuiltIn }
        val builtins = all.filter { it.isBuiltIn }

        // Old user row replaced; the two imported user rows are present in list order
        // (top of the list = highest priority = listed first by all()).
        assertEquals("imported user rows present", setOf("u1_param", "u2_param"), users.map { it.param }.toSet())
        assertEquals("old user row is replaced", 0, users.count { it.param == "existing-user_param" })
        assertEquals("list order is preserved (top first)", listOf("u1_param", "u2_param"), users.map { it.param })
        users.forEach {
            assertTrue("imported user row must have a fresh, positive id", it.id > 0)
            assertEquals("imported user row is not a built-in", false, it.isBuiltIn)
        }

        // The seeded built-in survived the import, untouched.
        assertEquals("built-in must be preserved", 1, builtins.size)
        val b = builtins.single()
        assertEquals(true, b.isBuiltIn)
        assertEquals("builtin_p", b.param)
    }

    @Test
    fun importAll_builtinEntry_setsOnlyEnabled_noNewRow_noOtherFieldChange() = runBlocking {
        // Seed the canonical built-in row with a distinctive name, a real priority
        // (assigned by insert on an empty table => 1), and enabled=false.
        repo.insert(filter("_t", host = "tiktok.com", name = "seeded-name", isBuiltIn = true, enabled = false))

        val before = repo.all().single { it.param == "_t" && it.host == "tiktok.com" }
        assertTrue("precondition: seeded built-in present", before.isBuiltIn)
        assertEquals(false, before.enabled)
        assertEquals("seeded-name", before.name)
        val beforeCount = repo.count()

        // Import a built-in entry with the SAME natural key (param, host) but a
        // different name/priority/enabled. Only `enabled` may be applied.
        repo.importAllFilters(
            listOf(
                filter("_t", host = "tiktok.com", name = "imported-name", enabled = true, priority = 99, isBuiltIn = true),
            )
        )

        val all = repo.all()
        val matched = all.single { it.param == "_t" && it.host == "tiktok.com" }

        assertEquals("no new row is created — total count unchanged", beforeCount, all.size)
        assertEquals("enabled flag is the only applied field", true, matched.enabled)
        assertEquals("id is unchanged", before.id, matched.id)
        assertEquals("param is unchanged", before.param, matched.param)
        assertEquals("host is unchanged", before.host, matched.host)
        assertEquals("name is unchanged (imported name ignored)", "seeded-name", matched.name)
        assertEquals("priority is unchanged (imported priority ignored)", before.priority, matched.priority)
    }

    @Test
    fun importAll_builtinEntry_noSeededMatch_ignored() = runBlocking {
        // Seed a built-in under a DIFFERENT natural key, so the imported built-in
        // key matches nothing.
        repo.insert(filter("seeded_builtin_p", host = "elsewhere.com", isBuiltIn = true, enabled = false))
        val beforeCount = repo.count()

        // Import a built-in entry whose (param, host) matches no seeded row, plus a
        // user entry that must still be inserted.
        repo.importAllFilters(
            listOf(
                filter("_z", host = "unseen.com", name = "imported", enabled = true, isBuiltIn = true),
                filter("user1_param", enabled = true),
            )
        )

        val all = repo.all()
        assertEquals(
            "imported built-in with no seeded match is ignored — no new row",
            0,
            all.count { it.param == "_z" && it.host == "unseen.com" },
        )
        assertEquals("imported built-in with no seeded match is ignored — total count grew only by the user row", beforeCount + 1, all.size)

        // The user entry in the same import is still inserted correctly.
        val user = all.single { !it.isBuiltIn }
        assertEquals("user1_param", user.param)
        assertTrue("imported user row has a fresh, positive id", user.id > 0)

        // The pre-existing (different-key) built-in survived.
        assertEquals("seeding built-in under a different key is preserved", 1, all.count { it.isBuiltIn })
        assertEquals("seeded_builtin_p", all.single { it.isBuiltIn }.param)
    }

    @Test
    fun all_includesBuiltinsAndUsers_preservesEnabled_forExport() = runBlocking {
        repo.insert(filter("builtin_param", isBuiltIn = true, enabled = true))
        repo.insert(filter("user_param", enabled = true))

        val all = repo.all()

        assertEquals("a full list must include both rows", 2, all.size)
        val builtIn = all.single { it.isBuiltIn }
        assertEquals("builtin_param", builtIn.param)
        assertEquals(true, builtIn.enabled)
        val user = all.single { !it.isBuiltIn }
        assertEquals("user_param", user.param)
        assertEquals(true, user.enabled)
    }
}
