package net.chaosengine.linkrouter

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.chaosengine.linkrouter.browsers.BrowserInfo
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.browsers.WebViewTarget
import net.chaosengine.linkrouter.rules.OpenMode
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.rules.RuleRepository
import net.chaosengine.linkrouter.rules.ShortenerWebResolver
import net.chaosengine.linkrouter.settings.FallbackMode
import net.chaosengine.linkrouter.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.RedirectFormatEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.RedirectFormatEntity>> =
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
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.ShortenerHostEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.ShortenerHostEntity>> =
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
            override fun observeAll(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.QueryParamFilterEntity>> =
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
            override fun observeOrdered(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override fun observeEnabled(): kotlinx.coroutines.flow.Flow<List<net.chaosengine.linkrouter.rules.RuleEntity>> =
                kotlinx.coroutines.flow.emptyFlow()
            override suspend fun allOrdered(): List<net.chaosengine.linkrouter.rules.RuleEntity> = emptyList()
            override suspend fun upsert(entity: net.chaosengine.linkrouter.rules.RuleEntity) = 0L
            override suspend fun update(entity: net.chaosengine.linkrouter.rules.RuleEntity) {}
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

    private class FakeFormatRepository(private val formats: List<net.chaosengine.linkrouter.rules.RedirectFormat>) : net.chaosengine.linkrouter.rules.RedirectFormatRepository(RoomlessDb()) {
        override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.RedirectFormat> = formats
    }

    private class FakeShortenerRepo(private val hosts: List<net.chaosengine.linkrouter.rules.ShortenerHost>) : net.chaosengine.linkrouter.rules.ShortenerHostRepository(RoomlessDb()) {
        override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.ShortenerHost> = hosts
    }

    private class FakeParamFilterRepo(private val filters: List<net.chaosengine.linkrouter.rules.QueryParamFilter>) : net.chaosengine.linkrouter.rules.QueryParamFilterRepository(RoomlessDb()) {
        override suspend fun allEnabled(): List<net.chaosengine.linkrouter.rules.QueryParamFilter> = filters
    }

    /** Google redirect format fixture (reused across the nested-resolution tests). */
    private fun googleFormat(openReal: Boolean = false) =
        net.chaosengine.linkrouter.rules.RedirectFormat(
            id = net.chaosengine.linkrouter.rules.RedirectFormat.BUILT_IN_ID,
            name = "Google",
            pattern = "google.com/url",
            matchType = net.chaosengine.linkrouter.rules.MatchType.PATH_PREFIX,
            extractType = net.chaosengine.linkrouter.rules.ExtractType.QUERY_PARAM,
            extractTarget = "q",
            enabled = true,
            priority = 1000,
            isBuiltIn = true,
            openRealDestination = openReal,
        )

    /** Enabled bit.ly shortener host fixture (nested-resolution tests). */
    private val bitLy = net.chaosengine.linkrouter.rules.ShortenerHost(
        id = 42, name = "bit.ly", host = "bit.ly", enabled = true, isBuiltIn = true,
    )

    /**
     * M7 fake: resolves synchronously to a fixed final URL (or null) without
     * launching any activity. [calls] records each invocation so tests can assert
     * the web resolver was consulted (and only on the interstitial path).
     */
    private class FakeWebResolver(
        private val result: String?,
        val calls: MutableList<String> = mutableListOf(),
    ) : ShortenerWebResolver {
        override suspend fun resolve(context: android.content.Context, url: String): String? {
            calls.add(url)
            return result
        }
        // deliverResult is a no-op on the interface default.
    }

    // --- helpers ---

    private fun rule(pkg: String, mode: OpenMode = OpenMode.NORMAL) = Rule(
        id = 1,
        pattern = "example.com",
        matchType = net.chaosengine.linkrouter.rules.MatchType.EXACT_HOST,
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

    /** True when [intent] targets our in-app [BrowserChooserActivity] (the fallback chooser). */
    private fun isBrowserChooser(intent: Intent): Boolean =
        intent.component?.className == BrowserChooserActivity::class.java.name

    @Before
    fun setUp() {
        AppContainer.ruleRepository = FakeRepository(emptyList())
        AppContainer.redirectFormatRepository = FakeFormatRepository(emptyList())
        AppContainer.shortenerHostRepository = FakeShortenerRepo(emptyList())
        // M9 default: no filters → no stripping, so existing tests are unchanged.
        AppContainer.queryParamFilterRepository = FakeParamFilterRepo(emptyList())
        // Safe default: no-redirect fake fetcher so no test ever hits the real
        // network (no shortener host is enabled by default in the fake repo).
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { _ -> ShortenerResolver.HopResponse(200, null, "") }
        // M7 default: a null-returning fake so no existing test's behavior
        // changes (the web resolver is only consulted on the interstitial path).
        AppContainer.shortenerWebResolver = FakeWebResolver(null)
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
            net.chaosengine.linkrouter.rules.RedirectFormat(
                id = net.chaosengine.linkrouter.rules.RedirectFormat.BUILT_IN_ID,
                name = "Google",
                pattern = "google.com/url",
                matchType = net.chaosengine.linkrouter.rules.MatchType.PATH_PREFIX,
                extractType = net.chaosengine.linkrouter.rules.ExtractType.QUERY_PARAM,
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
            net.chaosengine.linkrouter.rules.RedirectFormat(
                id = net.chaosengine.linkrouter.rules.RedirectFormat.BUILT_IN_ID,
                name = "Google",
                pattern = "google.com/url",
                matchType = net.chaosengine.linkrouter.rules.MatchType.PATH_PREFIX,
                extractType = net.chaosengine.linkrouter.rules.ExtractType.QUERY_PARAM,
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
        assertTrue(isBrowserChooser(started))
    }

    @Test
    fun `enabled shortener resolves final url and launches it`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            if (url == "https://t.co/abc") ShortenerResolver.HopResponse(302, "https://example.com/page", "")
            else ShortenerResolver.HopResponse(200, null, "")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://t.co/abc")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // The FINAL url must be launched (not the t.co wrapper).
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `enabled shortener interstitial resolves via web resolver and launches the final url`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        // Fast path: a 200 page whose body triggers an INTERSTITIAL (JS redirect).
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { _ ->
            ShortenerResolver.HopResponse(200, null, "<html><script>window.location.replace('https://example.com/page')</script></html>")
        }
        val web = FakeWebResolver("https://example.com/page")
        AppContainer.shortenerWebResolver = web
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://t.co/abc")
        settle(activity)

        // The web resolver was consulted (escalated on Interstitial).
        assertTrue("web resolver should be consulted on interstitial", web.calls.isNotEmpty())
        assertEquals("https://t.co/abc", web.calls.first())

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // The FINAL url (returned by the web resolver) must be launched.
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `enabled shortener interstitial with web resolver failing degrades to original url`() {
        AppContainer.ruleRepository = FakeRepository(emptyList()) // no rule matches t.co
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        // Fast path: a 200 page whose body triggers an INTERSTITIAL (meta refresh).
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { _ ->
            ShortenerResolver.HopResponse(200, null, "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://example.com/page\"></head></html>")
        }
        // Web resolver fails → returns null → degrade to the original URL (D6).
        val web = FakeWebResolver(null)
        AppContainer.shortenerWebResolver = web
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://t.co/abc")
        settle(activity)

        // The web resolver was consulted, but it failed → no crash, no final URL.
        assertTrue("web resolver should be consulted on interstitial", web.calls.isNotEmpty())
        // Degraded to the ORIGINAL url via the normal (no-rule) path → chooser.
        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
        assertEquals(Uri.parse("https://t.co/abc"), started.getParcelableExtra(LinkRouter.EXTRA_URI))
        // AND the WebView settle failure is visible (never silent).
        val toast = lastToastText()
        assertNotNull("web resolver timeout must show a toast", toast)
        assertTrue(toast!!.contains("timed out"))
    }

    @Test
    fun `enabled shortener resolve failure degrades to original and toasts`() {
        // No rule (original t.co URL flows to the fallback path).
        AppContainer.ruleRepository = FakeRepository(emptyList())
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        // Fetcher throws → ShortenerResolver.resolve returns Result.Error.
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { throw RuntimeException("connection refused") }
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://t.co/abc")
        settle(activity)

        // D6: degraded to the ORIGINAL url → no-rule path → chooser.
        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
        assertEquals(Uri.parse("https://t.co/abc"), started.getParcelableExtra(LinkRouter.EXTRA_URI))
        // AND the failure is visible (never silent).
        val toast = lastToastText()
        assertNotNull("resolve failure must show a toast", toast)
        assertTrue(toast!!.contains("Couldn't resolve that short link"))
    }

    @Test
    fun `enabled shortener 3xx without location header degrades to original and toasts`() {
        AppContainer.ruleRepository = FakeRepository(emptyList())
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        // 302 with location == null → Result.Error (regression: previously could
        // be misclassified as Resolved via body inspection).
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { _ ->
            ShortenerResolver.HopResponse(302, null, "<html>302</html>")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://t.co/abc")
        settle(activity)

        // D6: degraded to the ORIGINAL url → no-rule path → chooser.
        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
        assertEquals(Uri.parse("https://t.co/abc"), started.getParcelableExtra(LinkRouter.EXTRA_URI))
        // AND the failure is visible (never silent).
        val toast = lastToastText()
        assertNotNull("3xx-without-Location failure must show a toast", toast)
        assertTrue(toast!!.contains("Location"))
    }

    // --- path-prefix shortener hosts (M8) ---

    @Test
    fun `path-prefix row matches subdomain with prefix and launches final url`() {
        // Rule targets the FINAL url's host (example.com), not the tiktok wrapper.
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(
                id = 27, name = "TikTok short links", host = "www.tiktok.com",
                pathPrefix = "/t/", enabled = true, isBuiltIn = true,
            )
        ))
        // Recording fetcher: proves the resolver was consulted (and only for
        // the incoming short link on a matching path).
        val fetchCalls = mutableListOf<String>()
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            fetchCalls.add(url)
            if (url == "https://vm.tiktok.com/t/ZG123/") ShortenerResolver.HopResponse(302, "https://example.com/page", "")
            else ShortenerResolver.HopResponse(200, null, "")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        // Subdomain (vm.) + path prefix /t/ → matches the www.tiktok.com + /t/ row.
        val activity = build("https://vm.tiktok.com/t/ZG123/")
        settle(activity)

        // The short link itself was resolved by the shortener fast path.
        // (The final URL may also be probed later by RedirectResolver for rule
        // matching — that's the pre-existing wrapper-resolution behavior.)
        assertTrue("resolver should be consulted on the short link",
            fetchCalls.firstOrNull() == "https://vm.tiktok.com/t/ZG123/")
        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // The FINAL url must be launched (not the tiktok wrapper).
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `path-prefix row does not match paths outside the prefix`() {
        // Rule does NOT match www.tiktok.com — only example.com — so if the
        // resolver were wrongly consulted the outcome would differ.
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(
                id = 27, name = "TikTok short links", host = "www.tiktok.com",
                pathPrefix = "/t/", enabled = true, isBuiltIn = true,
            )
        ))
        val fetchCalls = mutableListOf<String>()
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            fetchCalls.add(url)
            ShortenerResolver.HopResponse(302, "https://example.com/page", "")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        // /@user is NOT under /t/ → no shortener match, no network.
        val activity = build("https://www.tiktok.com/@user")
        settle(activity)

        assertEquals("resolver must not be consulted outside the prefix", emptyList<String>(), fetchCalls)
        // Original URL flows to rules (no match) → fallback chooser, no crash.
        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
        assertEquals(Uri.parse("https://www.tiktok.com/@user"), started.getParcelableExtra(LinkRouter.EXTRA_URI))
    }

    @Test
    fun `host-only row still matches any path on subdomains`() {
        // Regression: a null-prefix row keeps matching every path on the domain,
        // including subdomains, after the registrable-domain matcher went in.
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            if (url == "https://t.co/abc") ShortenerResolver.HopResponse(302, "https://example.com/page", "")
            else ShortenerResolver.HopResponse(200, null, "")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://t.co/abc")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        assertEquals(Uri.parse("https://example.com/page"), started.data)
    }

    // --- url param cleanup (M9) ---

    @Test
    fun `enabled param filter strips global tracking param from launched url`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.queryParamFilterRepository = FakeParamFilterRepo(listOf(
            net.chaosengine.linkrouter.rules.QueryParamFilter(
                id = -31, name = "utm_source (global)", host = null, param = "utm_source",
                enabled = true, priority = 1000, isBuiltIn = true,
            )
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://example.com/page?utm_source=news&id=42")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // utm_source stripped, id kept.
        assertEquals(Uri.parse("https://example.com/page?id=42"), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `scoped param filter strips on a matching domain`() {
        // Rule matches the launched host (www.tiktok.com); the scoped filter
        // (host = "tiktok.com") must apply to that subdomain.
        AppContainer.ruleRepository = FakeRepository(listOf(
            Rule(
                id = 1,
                pattern = "www.tiktok.com",
                matchType = net.chaosengine.linkrouter.rules.MatchType.EXACT_HOST,
                targetPackage = "org.example.browser",
                openMode = OpenMode.NORMAL,
                enabled = true,
                priority = 1,
            )
        ))
        AppContainer.queryParamFilterRepository = FakeParamFilterRepo(listOf(
            net.chaosengine.linkrouter.rules.QueryParamFilter(
                id = -44, name = "_t (TikTok)", host = "tiktok.com", param = "_t",
                enabled = true, priority = 1000, isBuiltIn = true,
            )
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://www.tiktok.com/video?_t=8&id=42")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals("org.example.browser", started.`package`)
        // _t stripped (subdomain of the scoped host), id kept.
        assertEquals(Uri.parse("https://www.tiktok.com/video?id=42"), started.data)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `scoped param filter does not strip on a different domain`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser")))
        AppContainer.queryParamFilterRepository = FakeParamFilterRepo(listOf(
            net.chaosengine.linkrouter.rules.QueryParamFilter(
                id = -44, name = "_t (TikTok)", host = "tiktok.com", param = "_t",
                enabled = true, priority = 1000, isBuiltIn = true,
            )
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://example.com/page?_t=8")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(Uri.parse("https://example.com/page?_t=8"), started.data)
    }

    @Test
    fun `uninstalled target browser falls back to chooser`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.missing.browser")))
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://example.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
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

    @Test
    fun `webview target opens WebViewActivity without consulting the registry`() {
        // Registry returns null (WebView is not an installed app), but the
        // dispatcher must intercept before the registry lookup.
        AppContainer.ruleRepository = FakeRepository(listOf(
            rule(net.chaosengine.linkrouter.browsers.WebViewTarget.PACKAGE, OpenMode.PRIVATE)
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://example.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(WebViewActivity::class.java.name, started.component?.className)
        assertEquals("https://example.com/page", started.getStringExtra(WebViewActivity.EXTRA_URL))
        assertTrue(
            "WebView private mode must set EXTRA_PRIVATE",
            started.getBooleanExtra(WebViewActivity.EXTRA_PRIVATE, false),
        )
    }

    @Test
    fun `webview target normal mode opens WebViewActivity without private flag`() {
        AppContainer.ruleRepository = FakeRepository(listOf(
            rule(net.chaosengine.linkrouter.browsers.WebViewTarget.PACKAGE, OpenMode.NORMAL)
        ))
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://example.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertEquals(WebViewActivity::class.java.name, started.component?.className)
        assertEquals("https://example.com/page", started.getStringExtra(WebViewActivity.EXTRA_URL))
        assertFalse(
            "WebView normal mode must NOT set EXTRA_PRIVATE",
            started.getBooleanExtra(WebViewActivity.EXTRA_PRIVATE, false),
        )
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
            packageName = "net.chaosengine.linkrouter"
            name = "net.chaosengine.linkrouter.DispatcherActivity"
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
        assertTrue(isBrowserChooser(started))
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
        assertTrue(isBrowserChooser(started))
    }

    @Test
    fun `fallback FALLBACK_BROWSER with no browser selected degrades to chooser`() {
        AppContainer.settings.setFallbackMode(FallbackMode.FALLBACK_BROWSER)
        AppContainer.settings.setFallbackBrowser(null)
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://other.com/page")
        settle(activity)

        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
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

    // --- nested shortener resolution ---

    @Test
    fun `redirector wrapping shortener resolves inner shortener and launches final url`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.redirectFormatRepository = FakeFormatRepository(listOf(googleFormat(openReal = false)))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(bitLy))
        val fetchCalls = mutableListOf<String>()
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            fetchCalls.add(url)
            when (url) {
                "https://bit.ly/adhdlist" -> ShortenerResolver.HopResponse(302, "https://example.com/page", "")
                else -> ShortenerResolver.HopResponse(200, null, "")
            }
        }
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://www.google.com/url?q=https%3A%2F%2Fbit.ly%2Fadhdlist")
        settle(activity)

        assertEquals(listOf("https://bit.ly/adhdlist", "https://example.com/page"), fetchCalls)
        val started = startedActivities(activity).single()
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertEquals("org.example.browser", started.`package`)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `redirector wrapping shortener interstitial resolves via web resolver`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.redirectFormatRepository = FakeFormatRepository(listOf(googleFormat(openReal = false)))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(bitLy))
        // Fast path on the INNER url: a 200 page whose body triggers an INTERSTITIAL.
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { _ ->
            ShortenerResolver.HopResponse(200, null, "<html><script>location.replace('https://example.com/page')</script></html>")
        }
        val web = FakeWebResolver("https://example.com/page")
        AppContainer.shortenerWebResolver = web
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val activity = build("https://www.google.com/url?q=https%3A%2F%2Fbit.ly%2Fadhdlist")
        settle(activity)

        // The web resolver was consulted with the INNER url (not the wrapper).
        assertTrue("web resolver should be consulted on interstitial", web.calls.isNotEmpty())
        assertEquals("https://bit.ly/adhdlist", web.calls.first())

        val started = startedActivities(activity).single()
        assertEquals(Uri.parse("https://example.com/page"), started.data)
        assertEquals("org.example.browser", started.`package`)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `redirector wrapping non-shortener does not trigger inner resolution`() {
        AppContainer.ruleRepository = FakeRepository(listOf(rule("org.example.browser"))) // pattern example.com
        AppContainer.redirectFormatRepository = FakeFormatRepository(listOf(googleFormat(openReal = false)))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(bitLy))
        val fetchCalls = mutableListOf<String>()
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            fetchCalls.add(url)
            ShortenerResolver.HopResponse(302, "https://example.com/elsewhere", "")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), browser("org.example.browser"))

        val wrapper = "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage"
        val activity = build(wrapper)
        settle(activity)

        // Destination example.com is NOT an enabled shortener → no inner resolution.
        assertEquals("resolver must not be consulted for non-shortener destinations", emptyList<String>(), fetchCalls)
        // Current redirector behavior retained (openRealDestination=false → wrapper).
        val started = startedActivities(activity).single()
        assertEquals(Uri.parse(wrapper), started.data)
        assertEquals("org.example.browser", started.`package`)
        assertTrue(started.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false))
    }

    @Test
    fun `redirector wrapping failing shortener degrades and toasts`() {
        AppContainer.ruleRepository = FakeRepository(emptyList()) // degrade to fallback
        AppContainer.redirectFormatRepository = FakeFormatRepository(listOf(googleFormat(openReal = false)))
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(bitLy))
        // INNER fetch: 302 to a non-http(s) target → Result.Rejected.
        val fetchCalls = mutableListOf<String>()
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            fetchCalls.add(url)
            if (url == "https://bit.ly/adhdlist") ShortenerResolver.HopResponse(302, "tel:+12345", "")
            else ShortenerResolver.HopResponse(200, null, "")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val wrapper = "https://www.google.com/url?q=https%3A%2F%2Fbit.ly%2Fadhdlist"
        val activity = build(wrapper)
        settle(activity)

        // The inner resolution WAS attempted and rejected…
        assertEquals(listOf("https://bit.ly/adhdlist"), fetchCalls)
        // The failure is visible (never silent).
        val toast = lastToastText()
        assertNotNull("inner resolve failure must show a toast", toast)
        assertTrue(toast!!.contains("non-http(s)"))
        // …and behavior degrades exactly like the current redirector path
        // (no rule match → chooser carrying the wrapper, openRealDestination=false).
        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
        assertEquals(Uri.parse(wrapper), started.getParcelableExtra(LinkRouter.EXTRA_URI))
    }

    @Test
    fun `incoming shortener failure is not re-fetched via nested path`() {
        AppContainer.ruleRepository = FakeRepository(emptyList()) // degrade to fallback
        AppContainer.redirectFormatRepository = FakeFormatRepository(emptyList()) // no formats
        AppContainer.shortenerHostRepository = FakeShortenerRepo(listOf(
            net.chaosengine.linkrouter.rules.ShortenerHost(id = 1, name = "t.co", host = "t.co", enabled = true, isBuiltIn = true)
        ))
        // Fetcher that throws → Result.Error. Count calls: the depth-cap guard
        // (finalUrl == null + unwrapped != matchCandidate) must prevent any
        // second fetch of the same failing shortener via the nested path.
        val fetchCalls = mutableListOf<String>()
        AppContainer.shortenerFetcher = ShortenerResolver.Fetcher { url ->
            fetchCalls.add(url)
            throw RuntimeException("connection refused")
        }
        AppContainer.browserRegistry = FakeRegistry(context(), null)

        val activity = build("https://t.co/abc")
        settle(activity)

        assertEquals("exactly ONE fetcher call (no double-resolution)", listOf("https://t.co/abc"), fetchCalls)
        // Exactly one failure toast (the incoming pass) — the nested path never
        // runs, so ShadowToast's latest toast is the single failure toast.
        val toast = lastToastText()
        assertNotNull("resolve failure must show a toast", toast)
        assertTrue(toast!!.contains("connection refused"))
        // Degraded to the original → chooser.
        val started = startedActivities(activity).single()
        assertTrue(isBrowserChooser(started))
        assertEquals(Uri.parse("https://t.co/abc"), started.getParcelableExtra(LinkRouter.EXTRA_URI))
    }
}
