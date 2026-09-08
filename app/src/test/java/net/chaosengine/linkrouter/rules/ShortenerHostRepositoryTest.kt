package net.chaosengine.linkrouter.rules

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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
class ShortenerHostRepositoryTest {

    private lateinit var db: LinkRouterDatabase
    private lateinit var repo: ShortenerHostRepository

    private fun host(
        host: String,
        name: String = "H",
        pathPrefix: String? = null,
        enabled: Boolean = false,
        isBuiltIn: Boolean = false,
    ) = ShortenerHost(
        id = 0,
        name = name,
        host = host,
        pathPrefix = pathPrefix,
        enabled = enabled,
        priority = 0,
        isBuiltIn = isBuiltIn,
    )

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LinkRouterDatabase::class.java).build()
        repo = ShortenerHostRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_returns_distinct_non_zero_ids() = runBlocking {
        val id1 = repo.insert(host("t.co"))
        val id2 = repo.insert(host("bit.ly"))

        assertTrue("host ids must be non-zero", id1 > 0 && id2 > 0)
        assertTrue("host ids must be distinct", id1 != id2)
    }

    @Test
    fun all_and_observeAll_order_by_priority_then_id() = runBlocking {
        val first = repo.insert(host("first.com"))
        val second = repo.insert(host("second.com"))

        // Later insert gets the higher auto-assigned priority.
        val all = repo.all()
        assertEquals(2, all.size)
        assertEquals(listOf(second, first), all.map { it.id })

        val observed = repo.observeAll().first()
        assertEquals(listOf(second, first), observed.map { it.id })
    }

    @Test
    fun allEnabled_returns_only_enabled() = runBlocking {
        repo.insert(host("enabled.com", enabled = true))
        repo.insert(host("disabled.com", enabled = false))

        val enabled = repo.allEnabled()
        assertEquals(1, enabled.size)
        assertEquals("enabled.com", enabled.single().host)
    }

    @Test
    fun setEnabled_toggles() = runBlocking {
        val id = repo.insert(host("t.co"))

        repo.setEnabled(id, true)
        assertEquals(true, repo.all().single { it.id == id }.enabled)

        repo.setEnabled(id, false)
        assertEquals(false, repo.all().single { it.id == id }.enabled)
    }

    @Test
    fun delete_user_host_removes_it() = runBlocking {
        val idA = repo.insert(host("a.com"))
        repo.insert(host("b.com"))

        repo.delete(idA)

        val all = repo.all()
        assertEquals(1, all.size)
        assertEquals("b.com", all.single().host)
    }

    @Test
    fun delete_builtin_disables_it_not_removes_it() = runBlocking {
        repo.insert(host("t.co", isBuiltIn = true))

        val builtInId = repo.all().single { it.isBuiltIn }.id
        repo.delete(builtInId)

        val all = repo.all()
        assertEquals("built-in must not be deletable", 1, all.count { it.isBuiltIn })
        val b = all.single { it.isBuiltIn }
        assertEquals(false, b.enabled)
    }

    @Test
    fun update_builtin_only_changes_enabled() = runBlocking {
        val builtInId = repo.insert(host("t.co", isBuiltIn = true))
        val current = repo.all().single { it.isBuiltIn }

        repo.update(current.copy(enabled = true, host = "hacked.com"))

        val b = repo.all().single { it.isBuiltIn }
        assertEquals("only the enabled flag is editable on a built-in", "t.co", b.host)
        assertEquals(true, b.enabled)
        assertEquals(builtInId, b.id)
    }

    @Test
    fun insert_with_pathPrefix_round_trips() = runBlocking {
        val id = repo.insert(host("www.tiktok.com", pathPrefix = "/t/"))

        val h = repo.all().single { it.id == id }
        assertEquals("/t/", h.pathPrefix)
    }

    @Test
    fun insert_without_pathPrefix_defaults_to_null() = runBlocking {
        val id = repo.insert(host("t.co"))

        val h = repo.all().single { it.id == id }
        assertEquals(null, h.pathPrefix)
    }

    @Test
    fun update_builtin_preserves_pathPrefix() = runBlocking {
        val builtInId = repo.insert(host("www.tiktok.com", pathPrefix = "/t/", isBuiltIn = true))
        val current = repo.all().single { it.isBuiltIn }

        repo.update(current.copy(enabled = true, pathPrefix = "/x/"))

        val b = repo.all().single { it.isBuiltIn }
        assertEquals("built-in prefix is not editable", "/t/", b.pathPrefix)
        assertEquals(true, b.enabled)
        assertEquals(builtInId, b.id)
    }

    @Test
    fun count_reflects_rows() = runBlocking {
        assertEquals(0, repo.count())

        repo.insert(host("a.com"))
        repo.insert(host("b.com"))
        assertEquals(2, repo.count())

        val id = repo.all().first { it.host == "a.com" }.id
        repo.delete(id)
        assertEquals(1, repo.count())
    }

    @Test
    fun importAllHosts_replacesUserRows_preservesBuiltIns() = runBlocking {
        // Pre-existing state: one user host + one built-in.
        repo.insert(host("old-user.com", enabled = true))
        repo.insert(host("t.co", isBuiltIn = true, enabled = false))

        // Import a fresh set containing user + a (non-canonical) built-in.
        repo.importAllHosts(
            listOf(
                host("new-a.com", enabled = true),
                host("new-b.com", pathPrefix = "/x/", enabled = false),
                host("t.co", isBuiltIn = true, enabled = true), // imported built-in must be dropped
            )
        )

        val all = repo.all()
        // The old user host is gone; the two new user hosts remain.
        assertEquals("imported user rows", setOf("new-a.com", "new-b.com"), all.filter { !it.isBuiltIn }.map { it.host }.toSet())
        // Built-in survived and kept its seeded (disabled) state — the imported built-in was dropped.
        assertEquals("built-in must be preserved", 1, all.count { it.isBuiltIn })
        assertEquals(false, all.single { it.isBuiltIn }.enabled)
        // Imported user rows are not flagged built-in.
        assertTrue(all.none { !it.isBuiltIn && it.isBuiltIn })
    }

    @Test
    fun importAllHosts_emptyList_clearsUserRows_keepsBuiltIns() = runBlocking {
        repo.insert(host("user.com", enabled = true))
        repo.insert(host("t.co", isBuiltIn = true))

        repo.importAllHosts(emptyList())

        val all = repo.all()
        assertEquals("no user rows after empty import", 0, all.count { !it.isBuiltIn })
        assertEquals("built-in survives", 1, all.count { it.isBuiltIn })
    }
}
