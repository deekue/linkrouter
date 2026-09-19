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
    ) = QueryParamFilter(
        id = 0,
        name = name,
        host = host,
        param = param,
        enabled = enabled,
        priority = 0,
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

        // Import a fresh set containing users + an (imported) built-in.
        repo.importAllFilters(
            listOf(
                filter("new-a_param", enabled = true),
                filter("new-b_param", host = "scop.example.com", enabled = false),
                filter("builtin_param", isBuiltIn = true, enabled = true), // imported built-in must be dropped
            )
        )

        val all = repo.all()
        // The old user filter is gone; the two new user filters remain.
        assertEquals("imported user rows", setOf("new-a_param", "new-b_param"), all.filter { !it.isBuiltIn }.map { it.param }.toSet())
        // Built-in survived and kept its seeded (disabled) state — the imported built-in was dropped.
        assertEquals("built-in must be preserved", 1, all.count { it.isBuiltIn })
        assertEquals(false, all.single { it.isBuiltIn }.enabled)
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
}
