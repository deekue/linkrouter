package net.chaosengine.linkrouter.ui

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.chaosengine.linkrouter.AppContainer
import net.chaosengine.linkrouter.browsers.BrowserInfo
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.rules.ExtractType
import net.chaosengine.linkrouter.rules.HostRewrite
import net.chaosengine.linkrouter.rules.HostRewriteRepository
import net.chaosengine.linkrouter.rules.MatchType
import net.chaosengine.linkrouter.rules.OpenMode
import net.chaosengine.linkrouter.rules.QueryParamFilter
import net.chaosengine.linkrouter.rules.QueryParamFilterRepository
import net.chaosengine.linkrouter.rules.RedirectFormat
import net.chaosengine.linkrouter.rules.RedirectFormatRepository
import net.chaosengine.linkrouter.rules.RewriteKind
import net.chaosengine.linkrouter.rules.RewriteMatchType
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.rules.RuleRepository
import net.chaosengine.linkrouter.rules.ShortenerHost
import net.chaosengine.linkrouter.rules.ShortenerHostRepository
import net.chaosengine.linkrouter.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for [RulesViewModel] row building.
 *
 * The critical case: on a cold start the rules Room Flow emits *before* the
 * async browser discovery ([BrowserRegistry.refresh]) has populated the
 * [BrowserRegistry.browsers] StateFlow. The fix [kotlinx.coroutines.flow.combine]s
 * the two so rows rebuild when browsers arrives late. This test asserts that a
 * browser that was "uninstalled" (null) in the initial snapshot becomes
 * resolved once the registry is populated — proving the rows are reactive to
 * late browser discovery and not a one-shot snapshot.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RulesViewModelTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun app(): android.app.Application = RuntimeEnvironment.getApplication()

    /** No-op Room db so [FakeRuleRepository] can be constructed without a database. */
    private class RoomlessDb : net.chaosengine.linkrouter.rules.LinkRouterDatabase() {
        override fun ruleDao(): net.chaosengine.linkrouter.rules.RuleDao = NoopDao
        override fun redirectFormatDao(): net.chaosengine.linkrouter.rules.RedirectFormatDao = NoopFormatDao
        override fun shortenerHostDao(): net.chaosengine.linkrouter.rules.ShortenerHostDao = NoopShortenerHostDao
        override fun queryParamFilterDao(): net.chaosengine.linkrouter.rules.QueryParamFilterDao = NoopQueryParamFilterDao
        override fun hostRewriteDao(): net.chaosengine.linkrouter.rules.HostRewriteDao = NoopHostRewriteDao
        override fun clearAllTables() {}
        override fun createInvalidationTracker(): androidx.room.InvalidationTracker =
            androidx.room.InvalidationTracker(this, "rules")
        override fun createOpenHelper(
            configuration: androidx.room.DatabaseConfiguration,
        ): androidx.sqlite.db.SupportSQLiteOpenHelper =
            throw UnsupportedOperationException("not used by tests")

        private object NoopFormatDao : net.chaosengine.linkrouter.rules.RedirectFormatDao {
            override fun observeAll(): Flow<List<net.chaosengine.linkrouter.rules.RedirectFormatEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<net.chaosengine.linkrouter.rules.RedirectFormatEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun all(): List<net.chaosengine.linkrouter.rules.RedirectFormatEntity> = emptyList()
            override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.RedirectFormatEntity> = emptyList()
            override suspend fun upsert(entity: net.chaosengine.linkrouter.rules.RedirectFormatEntity): Long = 0L
            override suspend fun update(entity: net.chaosengine.linkrouter.rules.RedirectFormatEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteAll() {}
            override suspend fun deleteNonBuiltIn() {}
            override suspend fun builtIns(): List<net.chaosengine.linkrouter.rules.RedirectFormatEntity> = emptyList()
            override suspend fun count(): Int = 0
        }

        private object NoopShortenerHostDao : net.chaosengine.linkrouter.rules.ShortenerHostDao {
            override fun observeAll(): Flow<List<net.chaosengine.linkrouter.rules.ShortenerHostEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<net.chaosengine.linkrouter.rules.ShortenerHostEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun all(): List<net.chaosengine.linkrouter.rules.ShortenerHostEntity> = emptyList()
            override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.ShortenerHostEntity> = emptyList()
            override suspend fun upsert(entity: net.chaosengine.linkrouter.rules.ShortenerHostEntity): Long = 0L
            override suspend fun update(entity: net.chaosengine.linkrouter.rules.ShortenerHostEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteNonBuiltIn() {}
            override suspend fun builtIns(): List<net.chaosengine.linkrouter.rules.ShortenerHostEntity> = emptyList()
            override suspend fun count(): Int = 0
        }

        private object NoopQueryParamFilterDao : net.chaosengine.linkrouter.rules.QueryParamFilterDao {
            override fun observeAll(): Flow<List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun all(): List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity> = emptyList()
            override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity> = emptyList()
            override suspend fun upsert(entity: net.chaosengine.linkrouter.rules.QueryParamFilterEntity): Long = 0L
            override suspend fun update(entity: net.chaosengine.linkrouter.rules.QueryParamFilterEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteNonBuiltIn() {}
            override suspend fun builtIns(): List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity> = emptyList()
            override suspend fun count(): Int = 0
        }

        private object NoopHostRewriteDao : net.chaosengine.linkrouter.rules.HostRewriteDao {
            override fun observeAll(): Flow<List<net.chaosengine.linkrouter.rules.HostRewriteEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<net.chaosengine.linkrouter.rules.HostRewriteEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun all(): List<net.chaosengine.linkrouter.rules.HostRewriteEntity> = emptyList()
            override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.HostRewriteEntity> = emptyList()
            override suspend fun upsert(entity: net.chaosengine.linkrouter.rules.HostRewriteEntity): Long = 0L
            override suspend fun update(entity: net.chaosengine.linkrouter.rules.HostRewriteEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteNonBuiltIn() {}
            override suspend fun builtIns(): List<net.chaosengine.linkrouter.rules.HostRewriteEntity> = emptyList()
            override suspend fun count(): Int = 0
        }

        private object NoopDao : net.chaosengine.linkrouter.rules.RuleDao {
            override fun observeOrdered(): Flow<List<net.chaosengine.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<net.chaosengine.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun allOrdered(): List<net.chaosengine.linkrouter.rules.RuleEntity> = emptyList()
            override suspend fun upsert(entity: net.chaosengine.linkrouter.rules.RuleEntity) = 0L
            override suspend fun update(entity: net.chaosengine.linkrouter.rules.RuleEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteAll() {}
            override suspend fun count() = 0
        }
    }

    /** Emits a fixed rule set; [observeOrdered] overridden so no DAO/DB is needed. */
    private class FakeRuleRepository(private val rules: List<Rule>) : RuleRepository(RoomlessDb()) {
        override fun observeOrdered(): Flow<List<Rule>> = flowOf(rules)
    }

    /** No real PackageManager discovery; browsers are set via [BrowserRegistry.setBrowsersForTest]. */
    private class TestRegistry(context: Context) : BrowserRegistry(context) {
        override fun refresh() { /* no-op: discovery is driven manually in tests */ }
    }

    private class NoopFormatRepository : RedirectFormatRepository(RoomlessDb())

    private class NoopShortenerHostRepository : ShortenerHostRepository(RoomlessDb())

    private class NoopQueryParamFilterRepository : QueryParamFilterRepository(RoomlessDb())

    private class NoopHostRewriteRepository : HostRewriteRepository(RoomlessDb())

    private fun rule(pkg: String) = Rule(
        id = 1,
        pattern = "example.com",
        matchType = MatchType.EXACT_HOST,
        targetPackage = pkg,
        openMode = OpenMode.NORMAL,
        enabled = true,
        priority = 1,
    )

    private fun browser(pkg: String) = BrowserInfo(pkg, "Test Browser", null, null, false)

    /** Drain the paused main looper so viewModelScope coroutines run to completion. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private lateinit var db: net.chaosengine.linkrouter.rules.LinkRouterDatabase

    @Before
    fun setUp() {
        // Real in-memory Room db backing the four sub-section repos so the
        // export tests can seed a built-in + a user row and read [all()] back.
        // (The base-rule repo stays a Roomless fake: those tests only exercise
        // row rebuilding, not rules content.)
        val ctx: Context = context()
        db = Room.inMemoryDatabaseBuilder(ctx, net.chaosengine.linkrouter.rules.LinkRouterDatabase::class.java).build()

        AppContainer.ruleRepository = FakeRuleRepository(listOf(rule("org.example.browser")))
        AppContainer.redirectFormatRepository = RedirectFormatRepository(db)
        AppContainer.shortenerHostRepository = ShortenerHostRepository(db)
        AppContainer.queryParamFilterRepository = QueryParamFilterRepository(db)
        AppContainer.hostRewriteRepository = HostRewriteRepository(db)
        AppContainer.browserRegistry = TestRegistry(ctx)
        AppContainer.settings = SettingsStore(ctx)

        runBlocking { seedBuiltInAndUserRows() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Seed each sub-section with one built-in and one user row. */
    private suspend fun seedBuiltInAndUserRows() {
        val fmtRepo = AppContainer.redirectFormatRepository
        fmtRepo.resetBuiltIn() // built-in Google format (isBuiltIn = true)
        fmtRepo.insert(
            RedirectFormat(
                id = 0, name = "UserFmt", pattern = "userfmt.com", matchType = MatchType.EXACT_HOST,
                extractType = ExtractType.QUERY_PARAM, extractTarget = "q",
                enabled = true, priority = 0, isBuiltIn = false,
            )
        )

        AppContainer.shortenerHostRepository.insert(
            ShortenerHost(id = 0, name = "BuiltinHost", host = "builtin.sh", enabled = false, isBuiltIn = true)
        )
        AppContainer.shortenerHostRepository.insert(
            ShortenerHost(id = 0, name = "UserHost", host = "user.sh", enabled = true, isBuiltIn = false)
        )

        AppContainer.queryParamFilterRepository.insert(
            QueryParamFilter(id = 0, name = "builtin_param", host = null, param = "builtin_param", enabled = true, isBuiltIn = true)
        )
        AppContainer.queryParamFilterRepository.insert(
            QueryParamFilter(id = 0, name = "user_param", host = null, param = "user_param", enabled = true, isBuiltIn = false)
        )

        AppContainer.hostRewriteRepository.insert(
            HostRewrite(id = 0, matchHost = "builtinmatch.com", matchType = RewriteMatchType.EXACT_HOST, kind = RewriteKind.HOST_SWAP, targetHost = "to1.com", isBuiltIn = true)
        )
        AppContainer.hostRewriteRepository.insert(
            HostRewrite(id = 0, matchHost = "usermatch.com", matchType = RewriteMatchType.EXACT_HOST, kind = RewriteKind.HOST_SWAP, targetHost = "to2.com", isBuiltIn = false)
        )
    }

    @Test
    fun `rows rebuild when browsers arrive late`() {
        val registry = AppContainer.browserRegistry as TestRegistry
        val vm = RulesViewModel(app())

        // Cold start: browsers is still empty (discovery "in flight"), rules already emitted.
        settle()
        val initial = vm.rows.single()
        assertEquals("org.example.browser", initial.rule.targetPackage)
        assertNull("browser must be null before discovery completes", initial.browser)

        // Discovery completes: the registry is now populated. The combine must
        // re-emit and rebuild rows so the rule resolves to the installed browser.
        registry.setBrowsersForTest(listOf(browser("org.example.browser")))
        settle()

        val rebuilt = vm.rows.single()
        assertNotNull("browser must resolve after late discovery", rebuilt.browser)
        assertEquals("org.example.browser", rebuilt.browser?.packageName)
    }

    @Test
    fun `rule for a package that is never installed stays uninstalled`() {
        val vm = RulesViewModel(app())
        settle()

        // Populate with a *different* browser; the rule's target is still absent.
        (AppContainer.browserRegistry as TestRegistry).setBrowsersForTest(listOf(browser("org.other.browser")))
        settle()

        assertNull(vm.rows.single().browser)
    }

    // --- Export excludes built-ins -------------------------------------------------
    // Every sub-section (formats / query-param filters / shortener hosts /
    // host rewrites) stores built-in rows alongside the user's. The four export
    // functions must exclude built-ins from the serialized set while keeping all
    // user rows. `exportRules` (base rules) is deliberately untouched — Rules
    // have no `isBuiltIn` flag.

    @Test
    fun `exportFormats_excludes_builtins_includes_users`() = runBlocking {
        val vm = RulesViewModel(app())

        val exported = vm.exportFormats()

        assertEquals(1, exported.size)
        val row = exported.single()
        assertEquals(false, row.isBuiltIn)
        assertEquals("userfmt.com", row.pattern)
    }

    @Test
    fun `exportQueryParamFilters_excludes_builtins_includes_users`() = runBlocking {
        val vm = RulesViewModel(app())

        val exported = vm.exportQueryParamFilters()

        assertEquals(1, exported.size)
        val row = exported.single()
        assertEquals(false, row.isBuiltIn)
        assertEquals("user_param", row.param)
    }

    @Test
    fun `exportShortenerHosts_excludes_builtins_includes_users`() = runBlocking {
        val vm = RulesViewModel(app())

        val exported = vm.exportShortenerHosts()

        assertEquals(1, exported.size)
        val row = exported.single()
        assertEquals(false, row.isBuiltIn)
        assertEquals("user.sh", row.host)
    }

    @Test
    fun `exportHostRewrites_excludes_builtins_includes_users`() = runBlocking {
        val vm = RulesViewModel(app())

        val exported = vm.exportHostRewrites()

        assertEquals(1, exported.size)
        val row = exported.single()
        assertEquals(false, row.isBuiltIn)
        assertEquals("usermatch.com", row.matchHost)
    }
}
