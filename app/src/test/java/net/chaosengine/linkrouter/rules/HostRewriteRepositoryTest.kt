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
class HostRewriteRepositoryTest {

    private lateinit var db: LinkRouterDatabase
    private lateinit var repo: HostRewriteRepository

    private fun rewrite(
        matchHost: String,
        targetHost: String = "target.com",
        matchType: RewriteMatchType = RewriteMatchType.EXACT_HOST,
        kind: RewriteKind = RewriteKind.HOST_SWAP,
        enabled: Boolean = false,
        isBuiltIn: Boolean = false,
    ) = HostRewrite(
        id = 0,
        matchHost = matchHost,
        matchType = matchType,
        kind = kind,
        targetHost = targetHost,
        preserveHostInPath = false,
        enabled = enabled,
        priority = 0,
        isBuiltIn = isBuiltIn,
    )

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LinkRouterDatabase::class.java).build()
        repo = HostRewriteRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_returns_distinct_non_zero_ids() = runBlocking {
        val id1 = repo.insert(rewrite("a.com"))
        val id2 = repo.insert(rewrite("b.com"))

        assertTrue("rewrite ids must be non-zero", id1 > 0 && id2 > 0)
        assertTrue("rewrite ids must be distinct", id1 != id2)
    }

    @Test
    fun all_and_observeAll_order_by_priority_then_id() = runBlocking {
        val first = repo.insert(rewrite("first.com"))
        val second = repo.insert(rewrite("second.com"))

        // Later insert gets the higher auto-assigned priority.
        val all = repo.all()
        assertEquals(2, all.size)
        assertEquals(listOf(second, first), all.map { it.id })

        val observed = repo.observeAll().first()
        assertEquals(listOf(second, first), observed.map { it.id })
    }

    @Test
    fun allEnabled_returns_only_enabled() = runBlocking {
        repo.insert(rewrite("enabled.com", enabled = true))
        repo.insert(rewrite("disabled.com", enabled = false))

        val enabled = repo.allEnabled()
        assertEquals(1, enabled.size)
        assertEquals("enabled.com", enabled.single().matchHost)
    }

    @Test
    fun setEnabled_toggles() = runBlocking {
        val id = repo.insert(rewrite("a.com"))

        repo.setEnabled(id, true)
        assertEquals(true, repo.all().single { it.id == id }.enabled)

        repo.setEnabled(id, false)
        assertEquals(false, repo.all().single { it.id == id }.enabled)
    }

    @Test
    fun delete_user_rewrite_removes_it() = runBlocking {
        val idA = repo.insert(rewrite("a.com"))
        repo.insert(rewrite("b.com"))

        repo.delete(idA)

        val all = repo.all()
        assertEquals(1, all.size)
        assertEquals("b.com", all.single().matchHost)
    }

    @Test
    fun delete_builtin_disables_it_not_removes_it() = runBlocking {
        repo.insert(rewrite("builtin.com", isBuiltIn = true))

        val builtInId = repo.all().single { it.isBuiltIn }.id
        repo.delete(builtInId)

        val all = repo.all()
        assertEquals("built-in must not be deletable", 1, all.count { it.isBuiltIn })
        val b = all.single { it.isBuiltIn }
        assertEquals(false, b.enabled)
    }

    @Test
    fun update_retains_builtin_flag() = runBlocking {
        val builtInId = repo.insert(rewrite("builtin.com", isBuiltIn = true))
        val current = repo.all().single { it.isBuiltIn }

        repo.update(current.copy(targetHost = "retarget.com"))

        val b = repo.all().single { it.isBuiltIn }
        assertEquals("re-targeting is allowed", "retarget.com", b.targetHost)
        assertEquals(true, b.isBuiltIn)
        assertEquals(builtInId, b.id)
    }

    @Test
    fun count_reflects_rows() = runBlocking {
        assertEquals(0, repo.count())

        repo.insert(rewrite("a.com"))
        repo.insert(rewrite("b.com"))
        assertEquals(2, repo.count())

        val id = repo.all().first { it.matchHost == "a.com" }.id
        repo.delete(id)
        assertEquals(1, repo.count())
    }

    @Test
    fun importAll_drops_importedBuiltins_and_replacesUserRows() = runBlocking {
        // Pre-existing state: one user rewrite that the import should replace.
        repo.insert(rewrite("old-user.com", enabled = true))

        // Import a fresh set containing user rows + an (imported) built-in.
        // The built-in entry is non-canonical and must be dropped on import.
        repo.importAll(
            listOf(
                rewrite("new-a.com", targetHost = "a-target.com", enabled = true),
                rewrite("new-b.com", targetHost = "b-target.com", enabled = false),
                rewrite("builtin.com", isBuiltIn = true, enabled = true), // imported built-in must be dropped
            )
        )

        val all = repo.all()
        // The old user rewrite is gone; the two new user rewrites remain.
        assertEquals("imported user rows", setOf("new-a.com", "new-b.com"), all.map { it.matchHost }.toSet())
        // The imported built-in entry was dropped — no built-in rows at all.
        assertEquals("imported built-in must be dropped", 0, all.count { it.isBuiltIn })
    }

    @Test
    fun importAll_emptyList_clearsUserRows_keepsBuiltIns() = runBlocking {
        repo.insert(rewrite("user.com", enabled = true))
        repo.insert(rewrite("builtin.com", isBuiltIn = true))

        repo.importAll(emptyList())

        val all = repo.all()
        assertEquals("no user rows after empty import", 0, all.count { !it.isBuiltIn })
        assertEquals("built-in survives", 1, all.count { it.isBuiltIn })
    }
}
