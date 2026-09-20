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
    fun importAll_import_priorityCollidesWithBuiltin_preservesBuiltins() = runBlocking {
        // Seed 5 built-ins. Sequential inserts get max+1 priorities, so these
        // land on priorities 1..5.
        repeat(5) { i -> repo.insert(rewrite("builtin-$i.com", isBuiltIn = true)) }
        assertEquals("precondition: 5 built-ins seeded", 5, repo.all().count { it.isBuiltIn })
        assertEquals("precondition: priorities 1..5", (1..5).toList(), repo.all().filter { it.isBuiltIn }.map { it.priority }.sorted())

        // Import 5 user rows. On the old code their auto-assigned priorities
        // (5..1) overlap the built-ins' and REPLACE deletes the built-ins.
        repo.importAll(
            listOf(
                rewrite("user-1.com", targetHost = "t1.com", enabled = true),
                rewrite("user-2.com", targetHost = "t2.com", enabled = false),
                rewrite("user-3.com", targetHost = "t3.com", enabled = true),
                rewrite("user-4.com", targetHost = "t4.com", enabled = false),
                rewrite("user-5.com", targetHost = "t5.com", enabled = true),
            )
        )

        val all = repo.all()
        // All 5 built-ins survive the collision, with their matchHosts intact.
        val builtins = all.filter { it.isBuiltIn }
        assertEquals("built-ins must survive priority collision", 5, builtins.size)
        assertEquals(
            (0..4).map { "builtin-$it.com" }.toSet(),
            builtins.map { it.matchHost }.toSet(),
        )
        // All 5 imported user rows are present.
        val users = all.filter { !it.isBuiltIn }
        assertEquals("imported user rows must be present", 5, users.size)
        assertEquals(
            (1..5).map { "user-$it.com" }.toSet(),
            users.map { it.matchHost }.toSet(),
        )
        // Priorities remain unique across the combined set (unique index holds).
        assertEquals(10, all.map { it.priority }.toSet().size)
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

    @Test
    fun importAll_userEntries_replaceUserRows_keepsBuiltins() = runBlocking {
        // Pre-existing state: one built-in (canonical seed) + one user rewrite.
        repo.insert(rewrite("builtin-nytimes.com", targetHost = "archive.md", isBuiltIn = true, enabled = false))
        repo.insert(rewrite("existing-user.com", enabled = true))
        assertEquals(1, repo.all().count { it.isBuiltIn })
        assertEquals(1, repo.all().count { !it.isBuiltIn })

        // Import two fresh user rewrites (the prior user row must be replaced).
        repo.importAll(
            listOf(
                rewrite("u1.com", targetHost = "t1.com", enabled = true),
                rewrite("u2.com", targetHost = "t2.com", enabled = false),
            )
        )

        val all = repo.all()
        val users = all.filter { !it.isBuiltIn }
        val builtins = all.filter { it.isBuiltIn }

        // Old user rewrite replaced; the two imported user rewrites are present in
        // list order (top of the list = highest priority = listed first by all()).
        assertEquals("imported user rows present", setOf("u1.com", "u2.com"), users.map { it.matchHost }.toSet())
        assertEquals("old user row is replaced", 0, users.count { it.matchHost == "existing-user.com" })
        assertEquals("list order is preserved (top first)", listOf("u1.com", "u2.com"), users.map { it.matchHost })
        users.forEach {
            assertTrue("imported user row must have a fresh, positive id", it.id > 0)
            assertEquals("imported user row is not a built-in", false, it.isBuiltIn)
        }

        // The seeded built-in survived the import, untouched.
        assertEquals("built-in must be preserved", 1, builtins.size)
        val b = builtins.single()
        assertEquals(true, b.isBuiltIn)
        assertEquals("builtin-nytimes.com", b.matchHost)
        assertEquals("archive.md", b.targetHost)
    }

    @Test
    fun importAll_builtinEntry_setsOnlyEnabled_noNewRow() = runBlocking {
        // Seed the canonical built-in rewrite under matchHost "nytimes.com" with a
        // distinctive target/kind and enabled=false.
        repo.insert(
            rewrite(
                matchHost = "nytimes.com",
                targetHost = "archive.md",
                kind = RewriteKind.HOST_SWAP,
                isBuiltIn = true,
                enabled = false,
            )
        )

        val before = repo.all().single { it.matchHost == "nytimes.com" }
        assertTrue("precondition: seeded built-in present", before.isBuiltIn)
        assertEquals(false, before.enabled)
        val beforeCount = repo.count()

        // Import a built-in entry with the SAME matchHost but a different
        // targetHost/kind/enabled. Only `enabled` may be applied.
        repo.importAll(
            listOf(
                rewrite(
                    matchHost = "nytimes.com",
                    targetHost = "imported-target.com",
                    kind = RewriteKind.PATH_PREFIX_REWRITE,
                    enabled = true,
                    isBuiltIn = true,
                ),
            )
        )

        val all = repo.all()
        val matched = all.single { it.matchHost == "nytimes.com" }

        assertEquals("no new row is created — total count unchanged", beforeCount, all.size)
        assertEquals("enabled flag is the only applied field", true, matched.enabled)
        assertEquals("id is unchanged", before.id, matched.id)
        assertEquals("targetHost is unchanged (imported target ignored)", "archive.md", matched.targetHost)
        assertEquals("kind is unchanged (imported kind ignored)", RewriteKind.HOST_SWAP, matched.kind)
    }

    @Test
    fun importAll_builtinEntry_noSeededMatch_ignored() = runBlocking {
        // Seed a built-in under a DIFFERENT matchHost, so the imported built-in
        // key matches nothing.
        repo.insert(rewrite("seeded-elsewhere.com", targetHost = "seed-target.com", isBuiltIn = true, enabled = false))
        val beforeCount = repo.count()

        // Import a built-in entry whose matchHost matches no seeded row.
        repo.importAll(
            listOf(
                rewrite("unseen.com", targetHost = "x.com", enabled = true, isBuiltIn = true),
            )
        )

        val all = repo.all()
        assertEquals(
            "imported built-in with no seeded match is ignored — no new row",
            0,
            all.count { it.matchHost == "unseen.com" },
        )
        assertEquals("imported built-in with no seeded match is ignored — total count unchanged", beforeCount, all.size)

        // The pre-existing (different-key) built-in survived.
        assertEquals("seeding built-in under a different matchHost is preserved", 1, all.count { it.isBuiltIn })
        assertEquals("seeded-elsewhere.com", all.single { it.isBuiltIn }.matchHost)
    }

    @Test
    fun all_includesBuiltinsAndUsers_preservesEnabled_forExport() = runBlocking {
        // Built-in seeded disabled (`enabled = false`); user rewrite enabled.
        repo.insert(rewrite("builtinmatch.com", isBuiltIn = true, enabled = false))
        repo.insert(rewrite("usermatch.com", enabled = true))

        val all = repo.all()

        assertEquals("a full list must include both rows", 2, all.size)
        val builtIn = all.single { it.isBuiltIn }
        assertEquals("builtinmatch.com", builtIn.matchHost)
        assertEquals(false, builtIn.enabled)
        val user = all.single { !it.isBuiltIn }
        assertEquals("usermatch.com", user.matchHost)
        assertEquals(true, user.enabled)
    }
}
