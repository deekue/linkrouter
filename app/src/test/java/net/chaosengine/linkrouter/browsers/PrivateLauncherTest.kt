package net.chaosengine.linkrouter.browsers

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.chaosengine.linkrouter.WebViewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

/**
 * Regression coverage for private-mode behaviour (DESIGN.md section 7).
 *
 * 1. "opens the browser but not the URL" — launches must be package-pinned,
 *    never component-pinned (the MAIN/LAUNCHER icon activity does not interpret
 *    ACTION_VIEW data).
 *
 * 2. 3-tier capability model (D6: never silently pretend to be private):
 *    - REAL    — verified on-device private window (Firefox today).
 *    - ATTEMPT — best-effort vendor extra (none today: Chrome's extra drops the URL).
 *    - NONE    — no private mechanism; warn + open normally (Chrome today).
 *
 * 3. On-device results (2026-09-06):
 *    - Firefox `private_browsing_mode` extra → URL opens in a genuine private
 *      window → REAL.
 *    - Chrome `EXTRA_OPEN_NEW_INCOGNITO_TAB` extra → opens a BLANK incognito
 *      tab and DROPS the URL → must NOT be attached → NONE.
 *    - The `about:privatebrowsing`-then-refire trick was falsified on-device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrivateLauncherTest {

    // --- Test fixtures ---

    private val firefox = BrowserInfo(
        packageName = "org.mozilla.firefox",
        label = "Firefox",
        activity = "org.mozilla.firefox.Start",
        icon = null,
        isPrivateCapable = true, // REAL tier
    )

    private val chrome = BrowserInfo(
        packageName = "com.android.chrome",
        label = "Chrome",
        activity = "com.android.chrome.Main",
        icon = null,
        isPrivateCapable = false, // NONE tier (incognito extra drops the URL)
    )

    private val samsung = BrowserInfo(
        packageName = "com.sec.android.app.sbrowser",
        label = "Samsung Internet",
        activity = "com.sec.android.app.sbrowser.BrowserActivity",
        icon = null,
        isPrivateCapable = false, // NONE tier
    )

    private val url = Uri.parse("https://example.com/page")

    private lateinit var activity: android.app.Activity

    @Before
    fun setUp() {
        activity = buildActivity(android.app.Activity::class.java).get()
    }

    private fun launchedIntents(): List<Intent> {
        val shadow = shadowOf(activity) as ShadowActivity
        val out = mutableListOf<Intent>()
        while (true) {
            val next = shadow.nextStartedActivity ?: break
            out.add(next)
        }
        return out
    }

    // --- Capability tier assertions ---

    @Test
    fun `firefox has REAL capability (verified on-device)`() {
        assertEquals(PrivateCapability.REAL, FirefoxPrivateLauncher.capability())
        assertTrue("Firefox IS verified real private", FirefoxPrivateLauncher.isRealPrivate())
        assertTrue(
            "StrategyTable must report Firefox as private-capable (REAL)",
            StrategyTable.isPrivateCapable("org.mozilla.firefox"),
        )
        assertTrue(
            "StrategyTable must report Firefox as REAL private",
            StrategyTable.isRealPrivate("org.mozilla.firefox"),
        )
    }

    @Test
    fun `chrome has NONE capability (incognito extra drops the URL)`() {
        assertEquals(PrivateCapability.NONE, ChromePrivateLauncher.capability())
        assertFalse("Chrome must not claim real private support", ChromePrivateLauncher.isRealPrivate())
        assertFalse(
            "StrategyTable must report Chrome as NOT private-capable (extra drops URL)",
            StrategyTable.isPrivateCapable("com.android.chrome"),
        )
        assertFalse(
            "StrategyTable must NOT report Chrome as REAL private",
            StrategyTable.isRealPrivate("com.android.chrome"),
        )
    }

    @Test
    fun `warn-normal launcher has NONE capability`() {
        assertEquals(PrivateCapability.NONE, WarnNormalLauncher.capability())
        assertFalse("WarnNormalLauncher must not claim real private support", WarnNormalLauncher.isRealPrivate())
        assertFalse(
            "StrategyTable must report unknown browser as NOT private-capable",
            StrategyTable.isPrivateCapable("com.sec.android.app.sbrowser"),
        )
        assertEquals(
            "StrategyTable capabilityFor unknown browser must be NONE",
            PrivateCapability.NONE,
            StrategyTable.capabilityFor("com.sec.android.app.sbrowser"),
        )
    }

    @Test
    fun `strategy table maps correct launcher per package`() {
        assertTrue(StrategyTable.launcherFor(firefox) === FirefoxPrivateLauncher)
        assertTrue(StrategyTable.launcherFor(chrome) === ChromePrivateLauncher)
        assertTrue(StrategyTable.launcherFor(samsung) === WarnNormalLauncher)
    }

    // --- Intent shape assertions (package-pinned, never component-pinned) ---

    @Test
    fun `firefox launch is package-pinned with private_browsing_mode extra`() {
        FirefoxPrivateLauncher.launch(activity, firefox, url)

        val intent = launchedIntents().single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.data)
        assertEquals("org.mozilla.firefox", intent.`package`)
        assertNull("component must be null so the real ACTION_VIEW handler gets the URL", intent.component)
        assertTrue(
            "Must attach private_browsing_mode=true extra",
            intent.getBooleanExtra("private_browsing_mode", false),
        )
    }

    @Test
    fun `chrome launch is package-pinned and attaches NO incognito extra`() {
        // EXTRA_OPEN_NEW_INCOGNITO_TAB opens a blank incognito tab and drops the
        // URL (verified on-device), so it must NOT be attached.
        ChromePrivateLauncher.launch(activity, chrome, url)

        val intent = launchedIntents().single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.data)
        assertEquals("com.android.chrome", intent.`package`)
        assertNull("component must be null so the real ACTION_VIEW handler gets the URL", intent.component)
        assertFalse(
            "Must NOT attach EXTRA_OPEN_NEW_INCOGNITO_TAB (it drops the URL)",
            intent.hasExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB"),
        )
    }

    @Test
    fun `warn-normal launcher is package-pinned with no private extra`() {
        WarnNormalLauncher.launch(activity, samsung, url)

        val intent = launchedIntents().single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.data)
        assertEquals("com.sec.android.app.sbrowser", intent.`package`)
        assertNull("component must be null", intent.component)
        // No private extra should be attached
        assertFalse(
            "Must NOT attach private_browsing_mode",
            intent.hasExtra("private_browsing_mode"),
        )
        assertFalse(
            "Must NOT attach incognito extra",
            intent.hasExtra("com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB"),
        )
    }

    // --- WebView target (in-app, REAL private) ---

    private val webview = WebViewTarget.browserInfo

    @Test
    fun `webview launcher has REAL capability (in-app, no cross-app leak)`() {
        assertEquals(PrivateCapability.REAL, WebViewLauncher.capability())
        assertTrue("WebView IS verified real private (in-app)", WebViewLauncher.isRealPrivate())
        assertTrue(
            "StrategyTable must report WebView as private-capable (REAL)",
            StrategyTable.isPrivateCapable(WebViewTarget.PACKAGE),
        )
        assertTrue(
            "StrategyTable must report WebView as REAL private",
            StrategyTable.isRealPrivate(WebViewTarget.PACKAGE),
        )
    }

    @Test
    fun `strategy table maps WebView package to WebViewLauncher`() {
        assertTrue(StrategyTable.launcherFor(webview) === WebViewLauncher)
    }

    @Test
    fun `webview launcher opens WebViewActivity with URL and private flag`() {
        WebViewLauncher.launch(activity, webview, url)

        val intent = launchedIntents().single()
        assertEquals(WebViewActivity::class.java.name, intent.component?.className)
        assertEquals(url.toString(), intent.getStringExtra(WebViewActivity.EXTRA_URL))
        assertTrue(
            "EXTRA_PRIVATE must be true (REAL private: data cleared on close)",
            intent.getBooleanExtra(WebViewActivity.EXTRA_PRIVATE, false),
        )
    }

    @Test
    fun `webview sentinel package is NOT a real installed app`() {
        assertFalse(
            "WebViewTarget.PACKAGE must not collide with a real package",
            WebViewTarget.PACKAGE.startsWith("com.android") ||
                WebViewTarget.PACKAGE.startsWith("org.mozilla"),
        )
        assertTrue(WebViewTarget.isWebView(WebViewTarget.PACKAGE))
        assertFalse(WebViewTarget.isWebView("org.mozilla.firefox"))
        assertFalse(WebViewTarget.isWebView("com.android.chrome"))
    }
}
