package net.chaosengine.linkrouter.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.chaosengine.linkrouter.AppContainer
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.rules.LinkRouterDatabase
import net.chaosengine.linkrouter.rules.MatchType
import net.chaosengine.linkrouter.rules.OpenMode
import net.chaosengine.linkrouter.rules.QueryParamFilterRepository
import net.chaosengine.linkrouter.rules.RedirectFormatRepository
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.rules.RuleRepository
import net.chaosengine.linkrouter.rules.ShortenerHostRepository
import net.chaosengine.linkrouter.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end (Compose instrumentation) regression test for the
 * "tapping up/down on a rule crashes the app" bug.
 *
 * Drives the REAL tap → [RulesViewModel] → [RuleRepository] → Room path through
 * [RulesScreen], so the `RuleEntity.priority` UNIQUE index is actually
 * exercised on every move (the old row-at-a-time write tripped it and crashed).
 *
 * Setup: 3 rules inserted a, b, c → `insert()` assigns increasing priorities and
 * `allOrdered()` is `ORDER BY priority DESC`, so the initial top-first order is
 * c (top) > b (middle) > a (bottom).
 * The screen's up/down IconButtons carry stable testTags
 * `rule_<id>_moveUp` / `rule_<id>_moveDown`; the test targets each rule by its
 * actual id read from the database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class RulesScreenReorderTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private lateinit var repo: RuleRepository
    private lateinit var vm: RulesViewModel

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as Application

        // Real in-memory Room database + real [RuleRepository] (not a fake),
        // seeded with 3 rules in known priority order.
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context, LinkRouterDatabase::class.java,
        ).build()
        val repository = RuleRepository(db)
        runBlocking {
            repository.insert(rule("a.com"))   // top
            repository.insert(rule("b.com"))   // middle
            repository.insert(rule("c.com"))   // bottom
        }
        repo = repository
        appDb = db

        // Pre-inject the REAL graph BEFORE constructing the ViewModel so
        // AppContainer.get(app) does not lazily build the app-level on-disk DB.
        val c = AppContainer
        c.ruleRepository = repository
        c.redirectFormatRepository = RedirectFormatRepository(db)
        c.shortenerHostRepository = ShortenerHostRepository(db)
        c.queryParamFilterRepository = QueryParamFilterRepository(db)
        c.browserRegistry = NoopRegistry(context)
        c.settings = SettingsStore(context)

        vm = RulesViewModel(app)
    }

    @After
    fun tearDown() {
        appDb?.close()
        appDb = null
        resetContainer()
    }

    // ---- Tests ----

    // NOTE on ordering: `insert()` assigns INCREASING priorities (a=1, b=2, c=3)
    // and `allOrdered()` is `ORDER BY priority DESC`, so the initial top-first
    // order is [c.com, b.com, a.com] (c = top, a = bottom).
    @Test
    fun tap_down_on_top_rule_moves_it_below_second_and_priorities_stay_unique() = runBlocking {
        composeRule.setContent { rulesScreenContent(vm) }
        composeRule.waitForIdle()

        val c = repo.all().first().id    // top rule "c.com"
        tapDown(c)

        // c moved down one slot: order is now [b, c, a].
        assertEquals(listOf("b.com", "c.com", "a.com"), patterns())
        assertEquals("priorities must stay unique", 3, repo.all().map { it.priority }.toSet().size)
        assertEquals("no rows lost", 3, repo.all().size)
    }

    @Test
    fun tap_up_on_bottom_rule_moves_it_above_second_and_priorities_stay_unique() = runBlocking {
        composeRule.setContent { rulesScreenContent(vm) }
        composeRule.waitForIdle()

        val a = repo.all().last().id     // bottom rule "a.com"
        tapUp(a)

        // a moved up one slot: order is now [c, a, b].
        assertEquals(listOf("c.com", "a.com", "b.com"), patterns())
        assertEquals("priorities must stay unique", 3, repo.all().map { it.priority }.toSet().size)
        assertEquals("no rows lost", 3, repo.all().size)
    }

    @Test
    fun tap_up_on_top_rule_is_boundary_noop_order_unchanged() = runBlocking {
        composeRule.setContent { rulesScreenContent(vm) }
        composeRule.waitForIdle()

        val c = repo.all().first().id
        tapUp(c)     // already at top → moveRule() bails out; must not crash
        assertEquals(listOf("c.com", "b.com", "a.com"), patterns())
    }

    @Test
    fun tap_down_on_bottom_rule_is_boundary_noop_order_unchanged() = runBlocking {
        composeRule.setContent { rulesScreenContent(vm) }
        composeRule.waitForIdle()

        val a = repo.all().last().id
        tapDown(a)   // already at bottom → moveRule() bails out; must not crash
        assertEquals(listOf("c.com", "b.com", "a.com"), patterns())
    }

    @Test
    fun down_then_up_round_trip_restores_original_order() = runBlocking {
        composeRule.setContent { rulesScreenContent(vm) }
        composeRule.waitForIdle()

        val c = repo.all().first().id
        tapDown(c)                                   // [b, c, a]
        assertEquals(listOf("b.com", "c.com", "a.com"), patterns())
        tapUp(c)                                     // [c, b, a]
        assertEquals(listOf("c.com", "b.com", "a.com"), patterns())
        assertEquals("priorities must stay unique", 3, repo.all().map { it.priority }.toSet().size)
    }

    // ---- Helpers ----

    private suspend fun patterns(): List<String> = repo.all().map { it.pattern }

    private fun tapUp(ruleId: Long) {
        composeRule.onNodeWithTag("rule_${ruleId}_moveUp").performClick()
        composeRule.waitForIdle()
    }

    private fun tapDown(ruleId: Long) {
        composeRule.onNodeWithTag("rule_${ruleId}_moveDown").performClick()
        composeRule.waitForIdle()
    }

    /** [RulesScreen] with all its required callbacks stubbed to no-ops. */
    @Composable
    private fun rulesScreenContent(vm: RulesViewModel) {
        RulesScreen(
            vm = vm,
            onOpenSettings = {},
            onOpenDefaultBrowserPrompt = {},
            onOpenRedirects = {},
            onOpenShorteners = {},
            onOpenParamFilters = {},
        )
    }

    private fun rule(pattern: String) = Rule(
        id = 0,
        pattern = pattern,
        matchType = MatchType.EXACT_HOST,
        targetPackage = "org.example.browser",
        openMode = OpenMode.NORMAL,
        enabled = true,
        priority = 0,
    )

    /** No-op browser discovery (PackageManager sweep is not under test). */
    private class NoopRegistry(context: Context) : BrowserRegistry(context) {
        override fun refresh() { /* no-op: discovery is not under test */ }
    }

    /** Unset the six DI singletons so they read as uninitialized again. */
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

    companion object {
        private var appDb: LinkRouterDatabase? = null
    }
}
