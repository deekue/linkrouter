package com.linkrouter.rules

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

    private fun host(host: String, name: String = "H", enabled: Boolean = false, isBuiltIn: Boolean = false) =
        ShortenerHost(
            id = 0,
            name = name,
            host = host,
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
    fun count_reflects_rows() = runBlocking {
        assertEquals(0, repo.count())

        repo.insert(host("a.com"))
        repo.insert(host("b.com"))
        assertEquals(2, repo.count())

        val id = repo.all().first { it.host == "a.com" }.id
        repo.delete(id)
        assertEquals(1, repo.count())
    }
}
