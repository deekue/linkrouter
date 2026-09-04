package com.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.robolectric.shadows.ShadowPackageManager

/**
 * Regression test for browser discovery (DESIGN.md section 8) on the
 * Android 11+ package-visibility model.
 *
 * On a real device (reproduced on Android 17) an UNPINNED
 * queryIntentActivities(https ACTION_VIEW) returns only the default-browser
 * role holder (Chrome), while a PINNED per-package query finds the other
 * installed browsers. BrowserRegistry.discover() therefore falls back to a
 * pinned per-package scan so all installed browsers appear. This test pins a
 * browser's https ACTION_VIEW filter so the pinned scan is the path that
 * surfaces it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BrowserRegistryDiscoverTest {

    private val probe = Uri.parse("https://example.com/")

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun shadowPm() = shadowOf(context().packageManager)

    /** Register a fake browser package [pkg] with a launchable + https VIEW activity. */
    private fun addBrowser(pkg: String, activity: String) {
        shadowPm().addPackage(pkg)

        val activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = activity
            enabled = true
            exported = true
        }
        val resolveInfo = ResolveInfo().apply { this.activityInfo = activityInfo }

        // https ACTION_VIEW handler (what discover() pin-queries per package).
        shadowPm().addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, probe).setPackage(pkg),
            resolveInfo,
        )
        // Launchable (MAIN/LAUNCHER) activity so launchableActivity() resolves.
        shadowPm().addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            resolveInfo,
        )
    }

    private fun settle(registry: BrowserRegistry) {
        val deadline = System.currentTimeMillis() + 5_000
        while (registry.browsers.value.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            try { Thread.sleep(5) } catch (_: InterruptedException) {}
        }
    }

    @Before
    fun setUp() {
        ShadowPackageManager.reset()
    }

    @After
    fun tearDown() {
        ShadowPackageManager.reset()
    }

    @Test
    fun `discovery finds multiple installed browsers`() {
        addBrowser("org.mozilla.firefox", "org.mozilla.firefox.App")
        addBrowser("com.android.chrome", "com.android.chrome.Main")

        val registry = BrowserRegistry(context())
        registry.refresh()
        settle(registry)

        val found = registry.browsers.value.map { it.packageName }.toSet()
        assertEquals(setOf("org.mozilla.firefox", "com.android.chrome"), found)
        assertTrue("firefox must be installed", registry.installed("org.mozilla.firefox") != null)
        assertTrue("chrome must be installed", registry.installed("com.android.chrome") != null)
        registry.shutdown()
    }

    @Test
    fun `resolveTarget synchronously resolves an installed browser with an empty cache`() {
        val pkg = "org.mozilla.firefox"
        addBrowser(pkg, "org.mozilla.firefox.App")

        // Fresh process: the async refresh() has not populated the cache yet.
        // The cache write only happens on a Main-looper resume, which is paused
        // in Robolectric, so this is deterministically empty here.
        val registry = BrowserRegistry(context())
        assertTrue("cache must be empty before the async sweep completes",
            registry.browsers.value.isEmpty())

        // The dispatch path (DispatcherActivity) must resolve the target
        // synchronously via resolveTarget, rather than reading the empty cache
        // and returning null (which silently dropped the user's rule).
        val target = registry.resolveTarget(pkg)
        assertNotNull("resolveTarget must return the installed browser synchronously", target)
        assertEquals(pkg, target?.packageName)

        // The resolved target must also be resolvable to a real ACTION_VIEW intent.
        val resolved = target!!
        assertNotNull(
            "targetIntent must be resolvable for the resolved target",
            registry.targetIntent(Uri.parse("https://example.com/"), resolved.packageName, resolved.activity),
        )
        registry.shutdown()
    }

    @Test
    fun `resolveTarget returns null for an uninstalled package`() {
        val registry = BrowserRegistry(context())
        assertNull("an uninstalled package must not resolve to a target",
            registry.resolveTarget("org.does.not.exist"))
        registry.shutdown()
    }

    @Test
    fun `discovery excludes the app itself (loop guard)`() {
        addBrowser("com.example.otherbrowser", "com.example.otherbrowser.Main")

        val registry = BrowserRegistry(context())
        registry.refresh()
        settle(registry)

        val found = registry.browsers.value.map { it.packageName }
        assertTrue(found.contains("com.example.otherbrowser"))
        val self = context().packageName
        assertFalse("must never list our own package", found.contains(self))
        registry.shutdown()
    }
}
