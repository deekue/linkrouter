package net.chaosengine.linkrouter

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [BrowserChooserActivity] (DESIGN.md section 9):
 * a missing EXTRA_URI finishes without launching anything, while a valid URI
 * shows the chooser (not finishing). The actual browser launch is exercised
 * end-to-end by [DispatcherActivityTest] via the fallback path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BrowserChooserActivityTest {

    private fun context() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `missing EXTRA_URI finishes without launching anything`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))
        val activity = buildActivity(BrowserChooserActivity::class.java, intent).create().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("missing URI must finish", activity.isFinishing)
        // No activity should have been started (no browser, no chooser loop).
        var count = 0
        while (shadowOf(activity).nextStartedActivity != null) count++
        assertTrue("no activity should be started on missing URI", count == 0)
    }

    @Test
    fun `valid EXTRA_URI shows the chooser (not finishing)`() {
        val uri = Uri.parse("https://example.com/page")
        val intent = Intent(Intent.ACTION_VIEW, uri).putExtra(LinkRouter.EXTRA_URI, uri)
        val activity = buildActivity(BrowserChooserActivity::class.java, intent).create().get()
        shadowOf(Looper.getMainLooper()).idle()
        // A valid URI must present the chooser, not immediately finish.
        assertTrue("valid URI must show the chooser (not finish)", !activity.isFinishing)
    }
}
