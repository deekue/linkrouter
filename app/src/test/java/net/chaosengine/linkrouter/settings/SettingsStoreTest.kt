package net.chaosengine.linkrouter.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression coverage for [SettingsStore] persistence.
 *
 * The critical case: a *second* [SettingsStore] over the same context file
 * (simulating a process/app restart) must observe values written by the first.
 * In-process dispatch tests can't catch an uncommitted SharedPreferences edit
 * because the live StateFlow still reflects the write.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private fun context(): android.content.Context =
        org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `setFallbackBrowser survives a restart`() {
        val first = SettingsStore(context())
        first.setFallbackMode(FallbackMode.FALLBACK_BROWSER)
        first.setFallbackBrowser("org.mozilla.firefox")

        // Simulate a process restart: fresh instance reads the same prefs file.
        val second = SettingsStore(context())

        assertEquals(FallbackMode.FALLBACK_BROWSER, second.fallbackMode.value)
        assertEquals("org.mozilla.firefox", second.fallbackBrowser.value)
    }

    @Test
    fun `clearing fallbackBrowser survives a restart`() {
        val first = SettingsStore(context())
        first.setFallbackBrowser("org.mozilla.firefox")
        first.setFallbackBrowser(null)

        val second = SettingsStore(context())

        assertNull(second.fallbackBrowser.value)
    }

    @Test
    fun `setRememberedPackage survives a restart`() {
        val first = SettingsStore(context())
        first.setFallbackMode(FallbackMode.ASK_REMEMBER)
        first.setRememberedPackage("org.example.browser")

        val second = SettingsStore(context())

        assertEquals(FallbackMode.ASK_REMEMBER, second.fallbackMode.value)
        assertEquals("org.example.browser", second.rememberedPackage.value)
    }
}
