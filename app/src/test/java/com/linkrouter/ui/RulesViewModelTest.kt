package com.linkrouter.ui

import android.content.Context
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linkrouter.AppContainer
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.browsers.BrowserRegistry
import com.linkrouter.rules.MatchType
import com.linkrouter.rules.OpenMode
import com.linkrouter.rules.RedirectFormatRepository
import com.linkrouter.rules.Rule
import com.linkrouter.rules.RuleRepository
import com.linkrouter.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private class RoomlessDb : com.linkrouter.rules.LinkRouterDatabase() {
        override fun ruleDao(): com.linkrouter.rules.RuleDao = NoopDao
        override fun redirectFormatDao(): com.linkrouter.rules.RedirectFormatDao = NoopFormatDao
        override fun shortenerHostDao(): com.linkrouter.rules.ShortenerHostDao = NoopShortenerHostDao
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

        private object NoopShortenerHostDao : com.linkrouter.rules.ShortenerHostDao {
            override fun observeAll(): Flow<List<com.linkrouter.rules.ShortenerHostEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): Flow<List<com.linkrouter.rules.ShortenerHostEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun all(): List<com.linkrouter.rules.ShortenerHostEntity> = emptyList()
            override suspend fun allEnabled(): List<com.linkrouter.rules.ShortenerHostEntity> = emptyList()
            override suspend fun upsert(entity: com.linkrouter.rules.ShortenerHostEntity): Long = 0L
            override suspend fun update(entity: com.linkrouter.rules.ShortenerHostEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteNonBuiltIn() {}
            override suspend fun builtIns(): List<com.linkrouter.rules.ShortenerHostEntity> = emptyList()
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

    /** Emits a fixed rule set; [observeOrdered] overridden so no DAO/DB is needed. */
    private class FakeRuleRepository(private val rules: List<Rule>) : RuleRepository(RoomlessDb()) {
        override fun observeOrdered(): Flow<List<Rule>> = flowOf(rules)
    }

    /** No real PackageManager discovery; browsers are set via [BrowserRegistry.setBrowsersForTest]. */
    private class TestRegistry(context: Context) : BrowserRegistry(context) {
        override fun refresh() { /* no-op: discovery is driven manually in tests */ }
    }

    private class NoopFormatRepository : RedirectFormatRepository(RoomlessDb())

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

    @Before
    fun setUp() {
        AppContainer.ruleRepository = FakeRuleRepository(listOf(rule("org.example.browser")))
        AppContainer.redirectFormatRepository = NoopFormatRepository()
        AppContainer.browserRegistry = TestRegistry(context())
        AppContainer.settings = SettingsStore(context())
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
}
