package com.linkrouter.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linkrouter.AppContainer
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.browsers.BrowserRegistry
import com.linkrouter.rules.MatchType
import com.linkrouter.rules.OpenMode
import com.linkrouter.rules.RedirectFormatRepository
import com.linkrouter.rules.Rule
import com.linkrouter.rules.RuleRepository
import com.linkrouter.settings.FallbackMode
import com.linkrouter.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (instrumentation) coverage for [SettingsScreen] async recomposition,
 * specifically the private [FallbackBrowserPicker] reachable only through the
 * public screen.
 *
 * Locks in that the picker's button label — which falls back to the raw
 * `selected` package when the browser list is still empty — is recomposed to the
 * resolved human label once [BrowserRegistry] discovery completes late.
 *
 * Drives state changes with the Compose test `mainClock` (no Robolectric).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreenAsyncRecompositionTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        resetContainer()
    }

    @Test
    fun fallback_browser_picker_recomposes_when_browsers_arrive_late() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

        // Pre-inject fakes BEFORE constructing the ViewModel so that
        // AppContainer.get(app) does not lazily build a real Room DB.
        AppContainer.ruleRepository = FakeRuleRepository(emptyList())
        AppContainer.redirectFormatRepository = NoopFormatRepository()
        AppContainer.browserRegistry = TestRegistry(context)
        AppContainer.settings = SettingsStore(context)

        // The picker only renders in FALLBACK_BROWSER mode with a non-null
        // selected package; seed both so the button shows the raw package label.
        val settings = AppContainer.settings
        settings.setFallbackMode(FallbackMode.FALLBACK_BROWSER)
        settings.setFallbackBrowser("org.example.browser")

        val app = context.applicationContext as Application
        val vm = RulesViewModel(app)

        composeRule.setContent {
            SettingsScreen(
                vm = vm,
                onBack = {},
                onOpenDefaultBrowserPrompt = {},
            )
        }

        // Cold start: browsers list is empty and selected is set, so the picker
        // button label is the raw package name.
        composeRule.waitForIdle()
        composeRule.onNodeWithText("org.example.browser").assertIsDisplayed()

        // Browser discovery completes late: the picker must re-compose the button
        // label from the raw package to the resolved human label.
        (AppContainer.browserRegistry as TestRegistry)
            .setBrowsersForTest(listOf(browser("org.example.browser")))
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // Resolved label shows; the raw package label is gone.
        composeRule.onNodeWithText("Test Browser").assertIsDisplayed()
        composeRule.onNodeWithText("org.example.browser").assertDoesNotExist()
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
        for (name in listOf("ruleRepository", "redirectFormatRepository", "browserRegistry", "settings")) {
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
    private class RoomlessDb : com.linkrouter.rules.LinkRouterDatabase() {
        override fun ruleDao(): com.linkrouter.rules.RuleDao = NoopDao
        override fun redirectFormatDao(): com.linkrouter.rules.RedirectFormatDao = NoopFormatDao
        override fun clearAllTables() {}
        override fun createInvalidationTracker(): androidx.room.InvalidationTracker =
            androidx.room.InvalidationTracker(this, "rules")
        override fun createOpenHelper(
            configuration: androidx.room.DatabaseConfiguration,
        ): androidx.sqlite.db.SupportSQLiteOpenHelper =
            throw UnsupportedOperationException("not used by tests")

        private object NoopFormatDao : com.linkrouter.rules.RedirectFormatDao {
            override fun observeAll(): Flow<List<com.linkrouter.rules.RedirectFormatEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<com.linkrouter.rules.RedirectFormatEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun all(): List<com.linkrouter.rules.RedirectFormatEntity> = emptyList()
            override suspend fun allEnabled(): List<com.linkrouter.rules.RedirectFormatEntity> = emptyList()
            override suspend fun upsert(entity: com.linkrouter.rules.RedirectFormatEntity): Long = 0L
            override suspend fun update(entity: com.linkrouter.rules.RedirectFormatEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteAll() {}
            override suspend fun deleteNonBuiltIn() {}
            override suspend fun builtIns(): List<com.linkrouter.rules.RedirectFormatEntity> = emptyList()
            override suspend fun count(): Int = 0
        }

        private object NoopDao : com.linkrouter.rules.RuleDao {
            override fun observeOrdered(): Flow<List<com.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<com.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun allOrdered(): List<com.linkrouter.rules.RuleEntity> = emptyList()
            override suspend fun upsert(entity: com.linkrouter.rules.RuleEntity) = 0L
            override suspend fun update(entity: com.linkrouter.rules.RuleEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteAll() {}
            override suspend fun count() = 0
        }
    }
}
