package net.chaosengine.linkrouter.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.chaosengine.linkrouter.AppContainer
import net.chaosengine.linkrouter.browsers.BrowserInfo
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.rules.MatchType
import net.chaosengine.linkrouter.rules.OpenMode
import net.chaosengine.linkrouter.rules.QueryParamFilterRepository
import net.chaosengine.linkrouter.rules.RedirectFormatRepository
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.rules.RuleRepository
import net.chaosengine.linkrouter.rules.ShortenerHostRepository
import net.chaosengine.linkrouter.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (instrumentation) coverage for [RulesScreen] async recomposition.
 *
 * Locks in the fix where [RulesViewModel] *combines* the rules Room Flow with the
 * async browser-discovery StateFlow, so a rule whose target browser is still
 * "uninstalled" in the cold-start snapshot is rebuilt into the resolved (installed)
 * form once [BrowserRegistry] discovery completes late.
 *
 * Drives state changes with the Compose test `mainClock` (no Robolectric).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class RulesScreenAsyncRecompositionTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        resetContainer()
    }

    @Test
    fun ruleRow_recomposes_when_browsers_arrive_late() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

        // Pre-inject fakes BEFORE constructing the ViewModel so that
        // AppContainer.get(app) does not lazily build a real Room DB.
        AppContainer.ruleRepository = FakeRuleRepository(listOf(rule("org.example.browser")))
        AppContainer.redirectFormatRepository = NoopFormatRepository()
        // [RulesViewModel] reads these two eagerly in its constructor; they must
        // be initialized or construction throws UninitializedPropertyAccessException.
        AppContainer.shortenerHostRepository = ShortenerHostRepository(RoomlessDb())
        AppContainer.queryParamFilterRepository = QueryParamFilterRepository(RoomlessDb())
        AppContainer.browserRegistry = TestRegistry(context)
        AppContainer.settings = SettingsStore(context)

        val app = context.applicationContext as Application
        val vm = RulesViewModel(app)

        composeRule.setContent {
            RulesScreen(
                vm = vm,
                onOpenSettings = {},
                onOpenDefaultBrowserPrompt = {},
                onOpenRedirects = {},
                onOpenShorteners = {},
                onOpenParamFilters = {},
            )
        }

        // Cold start: the rules Flow has emitted but browser discovery is still
        // in flight (registry empty), so the single row renders as "uninstalled".
        composeRule.waitForIdle()
        composeRule.onNodeWithText("org.example.browser  ·  uninstalled").assertIsDisplayed()

        // Browser discovery completes late: the combine in RulesViewModel must
        // re-emit and rebuild rows, resolving the rule to the installed browser.
        (AppContainer.browserRegistry as TestRegistry)
            .setBrowsersForTest(listOf(browser("org.example.browser")))
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // Resolved label shows; the "uninstalled" sub-line is gone.
        composeRule.onNodeWithText("Test Browser", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("org.example.browser  ·  uninstalled").assertDoesNotExist()
    }

    // ---- Self-contained test helpers (each file compiles standalone) ----

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

    /** Unset the four DI singletons so they read as uninitialized again. */
    private fun resetContainer() {
        val c = AppContainer
        for (name in listOf(
            "ruleRepository", "redirectFormatRepository",
            "shortenerHostRepository", "queryParamFilterRepository",
            "browserRegistry", "settings",
        )) {
            val f = c.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(c, null)
        }
    }

    /** No real PackageManager discovery; browsers are set via [BrowserRegistry.setBrowsersForTest]. */
    private class TestRegistry(context: Context) : BrowserRegistry(context) {
        override fun refresh() { /* no-op: discovery is driven manually in tests */ }
    }

    /** Emits a fixed rule set; [RuleRepository.observeOrdered] overridden so no DAO/DB is needed. */
    private class FakeRuleRepository(private val rules: List<Rule>) : RuleRepository(RoomlessDb()) {
        override fun observeOrdered(): Flow<List<Rule>> = flowOf(rules)
    }

    private class NoopFormatRepository : RedirectFormatRepository(RoomlessDb())

    /** No-op Room db so [FakeRuleRepository]/[NoopFormatRepository] can be built without a database. */
    private class RoomlessDb : net.chaosengine.linkrouter.rules.LinkRouterDatabase() {
        override fun ruleDao(): net.chaosengine.linkrouter.rules.RuleDao = NoopDao
        override fun redirectFormatDao(): net.chaosengine.linkrouter.rules.RedirectFormatDao = NoopFormatDao
        override fun shortenerHostDao(): net.chaosengine.linkrouter.rules.ShortenerHostDao = NoopShortenerHostDao
        override fun queryParamFilterDao(): net.chaosengine.linkrouter.rules.QueryParamFilterDao = NoopQueryParamFilterDao
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
}
