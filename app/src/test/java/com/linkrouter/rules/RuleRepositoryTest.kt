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

/**
 * Regression test for the "adding a second rule overwrites the first" bug.
 *
 * Root cause: [RuleEntity.id] is a single-column INTEGER primary key, which
 * SQLite treats as an alias for the rowid. `RuleRepository.insert()` always
 * forced `id = 0` and relied on `@Insert(onConflict = REPLACE)` to assign the
 * id, but Room generated an INSERT that explicitly bound `id = 0`. Every new
 * rule therefore wrote to rowid 0, and `INSERT OR REPLACE` deleted the
 * existing rule before reinserting the new one.
 *
 * Fix: `@PrimaryKey(autoGenerate = true)` so Room omits the id from the INSERT
 * and SQLite assigns each row a unique rowid. This test drives the real Room
 * database (not the fake DAO used elsewhere) to pin the behaviour.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RuleRepositoryTest {

    private lateinit var db: LinkRouterDatabase
    private lateinit var repo: RuleRepository

    private fun rule(pattern: String, pkg: String = "org.example.browser") = Rule(
        id = 0,
        pattern = pattern,
        matchType = MatchType.EXACT_HOST,
        targetPackage = pkg,
        openMode = OpenMode.NORMAL,
        enabled = true,
        priority = 0,
    )

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LinkRouterDatabase::class.java).build()
        repo = RuleRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `inserting a second rule does not overwrite the first`() = runBlocking {
        val id1 = repo.insert(rule("a.com"))
        val id2 = repo.insert(rule("b.com"))

        assertTrue("each rule must get a distinct id", id1 != id2)
        assertEquals(2, repo.count())

        val all = repo.all()
        assertEquals(2, all.size)
        assertEquals(setOf("a.com", "b.com"), all.map { it.pattern }.toSet())
        assertEquals("ids must be unique and non-zero", 2, all.map { it.id }.toSet().size)
        assertTrue("ids must not be 0", all.none { it.id == 0L })
        assertEquals("priorities must be unique", 2, all.map { it.priority }.toSet().size)
    }

    @Test
    fun `editing a rule updates in place without clobbering the other`() = runBlocking {
        val id1 = repo.insert(rule("a.com"))
        repo.insert(rule("b.com"))

        val target = repo.all().first { it.id == id1 }
        repo.update(target.copy(pattern = "a2.com"))

        assertEquals(2, repo.count())
        assertEquals(setOf("a2.com", "b.com"), repo.all().map { it.pattern }.toSet())
    }

    @Test
    fun `most recently added rule is highest priority (top of list)`() = runBlocking {
        repo.insert(rule("a.com"))
        repo.insert(rule("b.com"))

        val all = repo.all()
        assertEquals("b.com", all.first().pattern)
        assertEquals("a.com", all.last().pattern)
    }

    @Test
    fun `delete removes only the targeted rule`() = runBlocking {
        val id1 = repo.insert(rule("a.com"))
        val id2 = repo.insert(rule("b.com"))

        repo.delete(id1)

        assertEquals(1, repo.count())
        assertEquals(listOf("b.com"), repo.all().map { it.pattern })
        assertEquals(id2, repo.all().single().id)
    }
}
