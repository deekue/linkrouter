package com.linkrouter

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.browsers.BrowserRegistry
import com.linkrouter.rules.OpenMode
import com.linkrouter.rules.Rule
import com.linkrouter.rules.RuleRepository
import com.linkrouter.settings.FallbackMode
import com.linkrouter.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowToast

/**
 * Robolectric coverage for [DispatcherActivity] (DESIGN.md section 6):
 * loop guard, rule match + launch, uninstalled fallback, private warn,
 * non-web scheme, and all four fallback modes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DispatcherActivityTest {

    // --- fakes ---

    /** No-op Room db so [FakeRepository] can be constructed without a database. */
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
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.linkrouter.rules.RedirectFormatEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): kotlinx.coroutines.flow.Flow<List<com.linkrouter.rules.RedirectFormatEntity>> =
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
            override fun observeOrdered(): kotlinx.coroutines.flow.Flow<List<com.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): kotlinx.coroutines.flow.Flow<List<com.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun allOrdered(): List<com.linkrouter.rules.RuleEntity> = emptyList()
            override suspend fun upsert(entity: com.linkrouter.rules.RuleEntity) = 0L
            override suspend fun update(entity: com.linkrouter.rules.RuleEntity) {}
            override suspend fun deleteById(id: Long) {}
            override suspend fun deleteAll() {}
            override suspend fun count() = 0
        }
    }

    private class FakeRepository(private val rules: List<Rule>) : RuleRepository(RoomlessDb()) {
        override suspend fun all(): List<Rule> = rules
    }

    private class FakeRegistry(
        context: android.content.Context,
        private val installed: BrowserInfo?,
    ) : BrowserRegistry(context) {
        override fun installed(packageName: String): BrowserInfo? = installed
        override fun resolveTarget(packageName: String): BrowserInfo? = installed
        override fun targetIntent(uri: Uri, packageName: String, activity: String?): Intent? {
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
            if (activity != null) i.component = android.content.ComponentName.unflattenFromString(activity)
            return i
        }
    }

    private class FakeFormatRepository(private val formats: List<com.linkrouter.rules.RedirectFormat>) : com.linkrouter.rules.RedirectFormatRepository(RoomlessDb()) {
        override suspend fun allEnabled(): List<com.linkrouter.rules.RedirectFormat> = formats
    }

    // --- helpers ---

    private fun rule(pkg: String, mode: OpenMode = OpenMode.NORMAL) = Rule(
        id = 1,
        pattern = "example.com",
        matchType = com.linkrouter.rules.MatchType.EXACT_HOST,
        targetPackage = pkg,
        openMode = mode,
        enabled = true,
        priority = 1,
    )

    private fun browser(pkg: String) = BrowserInfo(pkg, "Test Browser", null, null, false)

    private fun context(): android.content.Context =
        org.robolectric.RuntimeEnvironment.getApplication()

    private fun newSettings(): SettingsStore =
        SettingsStore(context()).also { it.setFallbackMode(FallbackMode.CHOOSER) }

    private fun build(url: String, extraHandled: Boolean = false): DispatcherActivity {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (extraHandled) putExtra(LinkRouter.EXTRA_HANDLED, true)
        }
        return buildActivity(DispatcherActivity::class.java, intent).create().get()
    }

    /** Let the async dispatch (which hops to IO) finish, then the activity calls finish(). */
    private fun settle(activity: DispatcherActivity) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            try { Thread.sleep(5) } catch (_: InterruptedException) {}
        }
    }

    private fun startedActivities(activity: DispatcherActivity): List<Intent> {
        val shadow = shadowOf(activity) as ShadowActivity
        val out = mutableListOf<Intent>()
        while (true) {
            val next = shadow.nextStartedActivity ?: break
            out.add(next)
        }
        return out
    }

    private fun lastToastText(): String? = ShadowToast.getTextOfLatestToast()?.toString()

    @Before
    fun setUp() {
        AppContainer.ruleRepository = FakeRepository(emptyList())
        AppContainer.redirectFormatRepository = FakeFormatRepository(emptyList())
        AppContainer.browserRegistry = FakeRegistry(context(), null)
        AppContainer.settings = newSettings()
    }

    @After
    fun tearDown() {
        ShadowToast.reset()
    }

    // --- basic dispatch ---

    @Test
    fun `matched rule launches target browser with loop guard extra`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://example.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertEquals("org.example.browser", started.`package`)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `google wrapper resolves to destination rule and launches the original wrapper`() {
        // rule targets the DESTINATION host, not the wrapper host:
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.redirectFormatRepository = FakeFormatRepository(listOf(
            com.linkrouter.rules.RedirectFormat(
                id = com.linkrouter.rules.RedirectFormat.BUILT_IN_ID,
                name = "Google",
                pattern = "google.com/url",
                matchType = com.linkrouter.rules.MatchType.PATH_PREFIX,
                extractType = com.linkrouter.rules.ExtractType.QUERY_PARAM,
                extractTarget = "q",
                enabled = true,
                priority = 1000,
                isBuiltIn = true,
            )
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val wrapper = "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage"
        val activity = build(wrapper)
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // The ORIGINAL wrapper must be launched (not the resolved destination).
        assertEquals(Uri.parse(wrapper), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `google wrapper with openRealDestination launches the real destination`() {
        // rule targets the DESTINATION host, not the wrapper host:
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.redirectFormatRepository = FakeFormatRepository(listOf(
            com.linkrouter.rules.RedirectFormat(
                id = com.linkrouter.rules.RedirectFormat.BUILT_IN_ID,
                name = "Google",
                pattern = "google.com/url",
                matchType = com.linkrouter.rules.MatchType.PATH_PREFIX,
                extractType = com.linkrouter.rules.ExtractType.QUERY_PARAM,
                extractTarget = "q",
                enabled = true,
                priority = 1000,
                isBuiltIn = true,
                openRealDestination = true,
            )
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val wrapper = "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage"
        val activity = build(wrapper)
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // The REAL destination must be launched (not the original wrapper).
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `unmatched url falls back to chooser`() {
        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `uninstalled target browser falls back to chooser`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.missing.browser")))
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://example.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `non web scheme is ignored and finishes`() {
        val activity = build("mailto:someone@example.com")
        settle(activity)
        assertTrue(activity.isFinishing)
        assertTrue(startedActivities(activity).isEmpty())
    }

    // --- loop guard ---

    @Test
    fun `already handled url finishes silently even if a rule matches`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://example.com/page", extraHandled = true)
        settle(activity)

        // Loop guard must NOT re-show the chooser (that would loop forever).
        // It finishes silently instead — no activity is started.
        assertTrue(activity.isFinishing)
        assertTrue("loop guard must start no activity (no chooser re-show)",
            startedActivities(activity).isEmpty())
    }

    @Test
    fun `loop guard query param finishes silently`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://example.com/page?__lr=1")
        settle(activity)

        // Loop guard must NOT re-show the chooser (that would loop forever).
        // It finishes silently instead — no activity is started.
        assertTrue(activity.isFinishing)
        assertTrue("loop guard must start no activity (no chooser re-show)",
            startedActivities(activity).isEmpty())
    }

    // --- private mode ---

    @Test
    fun `private rule on non private browser warns and opens normally`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser", OpenMode.PRIVATE)))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://example.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        val toast = lastToastText()
        assertNotNull("a one-time warning toast is expected", toast)
        assertTrue(toast!!.contains("Test Browser"))
    }

    // --- fallback modes ---

    @Test
    fun `fallback BLOCK shows toast and starts nothing`() {
        AppContainer.settings.setFallbackMode(FallbackMode.BLOCK)

        val activity = build("https://other.com/page")
        settle(activity)

        assertTrue(startedActivities(activity).isEmpty())
        val toast = lastToastText()
        assertNotNull(toast)
        assertTrue(toast!!.contains("blocked"))
    }

    @Test
    fun `fallback OS_DEFAULT launches ACTION_VIEW directly`() {
        AppContainer.settings.setFallbackMode(FallbackMode.OS_DEFAULT)

        // Stub the OS default browser as a real (non-self) package, as on a
        // device where LinkRouter is not the system default browser.
        val default = Intent(Intent.ACTION_VIEW, Uri.parse("https://other.com/page"))
        val activityInfo = android.content.pm.ActivityInfo().apply {
            packageName = "org.example.browser"
            name = "org.example.browser.ViewActivity"
            enabled = true
            exported = true
        }
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            this.activityInfo = activityInfo
        }
        shadowOf(context().packageManager).addResolveInfoForIntent(default, resolveInfo)

        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertNull(started.component)
    }

    @Test
    fun `fallback OS_DEFAULT resolving to self degrades to chooser (no infinite loop)`() {
        AppContainer.settings.setFallbackMode(FallbackMode.OS_DEFAULT)

        // Simulate LinkRouter being the OS default browser
        // ACTION_VIEW resolves to our own package.
        val self = Intent(Intent.ACTION_VIEW, Uri.parse("https://other.com/page"))
        val activityInfo = android.content.pm.ActivityInfo().apply {
            packageName = "com.linkrouter"
            name = "com.linkrouter.DispatcherActivity"
            enabled = true
            exported = true
        }
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            this.activityInfo = activityInfo
        }
        shadowOf(context().packageManager).addResolveInfoForIntent(self, resolveInfo)

        val activity = build("https://other.com/page")
        settle(activity)

        // Must hand off to the chooser instead of re-launching ourselves.
        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `fallback FALLBACK_BROWSER launches the chosen browser`() {
        AppContainer.settings.setFallbackMode(FallbackMode.FALLBACK_BROWSER)
        AppContainer.settings.setFallbackBrowser("org.example.browser")
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `fallback FALLBACK_BROWSER with uninstalled browser degrades to chooser`() {
        AppContainer.settings.setFallbackMode(FallbackMode.FALLBACK_BROWSER)
        AppContainer.settings.setFallbackBrowser("org.missing.browser")
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `fallback FALLBACK_BROWSER with no browser selected degrades to chooser`() {
        AppContainer.settings.setFallbackMode(FallbackMode.FALLBACK_BROWSER)
        AppContainer.settings.setFallbackBrowser(null)
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `fallback ASK_REMEMBER launches remembered package when resolvable`() {
        AppContainer.settings.setFallbackMode(FallbackMode.ASK_REMEMBER)
        AppContainer.settings.setRememberedPackage("org.example.browser")
        // Make the remembered package resolvable so the remembered branch is taken.
        val target = Intent(Intent.ACTION_VIEW, Uri.parse("https://other.com/page"))
            .setPackage("org.example.browser")
        val activityInfo = android.content.pm.ActivityInfo().apply {
            packageName = "org.example.browser"
            name = "org.example.browser.ViewActivity"
            enabled = true
            exported = true
        }
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            this.activityInfo = activityInfo
        }
        shadowOf(context().packageManager).addResolveInfoForIntent(target, resolveInfo)

        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
    }
}
