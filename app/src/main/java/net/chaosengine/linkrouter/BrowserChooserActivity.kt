package net.chaosengine.linkrouter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import net.chaosengine.linkrouter.browsers.BrowserInfo
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.ui.BrowserChooserScreen

/**
 * In-app browser chooser (DESIGN.md section 9).
 *
 * The system `ACTION_CHOOSER` re-resolves the ACTION_VIEW intent and enumerates
 * *every* matching activity — including LinkRouter's own [DispatcherActivity],
 * the very activity that makes us the default browser. That puts LinkRouter in
 * its own chooser, and when it is the OS default browser it can be the only
 * option shown. There is no public intent extra that reliably excludes a
 * specific component (verified against AOSP: `EXTRA_CHOICES` does not exist,
 * `EXTRA_CHOOSER_TARGETS` takes `ChooserTarget[]`/max 2 and only *adds*
 * targets, `EXTRA_EXCLUDE_COMPONENTS` is declared but unconsumed).
 *
 * So instead of the framework chooser we present our own list, built from the
 * already-self-excluded [BrowserRegistry], giving the user exactly the other
 * installed browsers and nothing else.
 */
class BrowserChooserActivity : ComponentActivity() {

    private var registry: BrowserRegistry? = null

    /** Test-only: swap the registry before [onCreate] (see [AppContainer] pattern). */
    fun setRegistryForTest(reg: BrowserRegistry) {
        registry = reg
    }

    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val uri = intent?.getParcelableExtra<Uri>(LinkRouter.EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }

        val reg = registry ?: AppContainer.get(this).browserRegistry

        setContent {
            MaterialTheme {
                BrowserChooserScreen(
                    browsers = reg.browsers,
                    onPick = { launchBrowser(reg, it, uri) },
                )
            }
        }
    }

    private fun launchBrowser(reg: BrowserRegistry, browser: BrowserInfo, uri: Uri) {
        val intent = reg.targetIntent(uri, browser.packageName, browser.activity)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.open_failed, browser.label), Toast.LENGTH_SHORT).show()
            return
        }
        intent.putExtra(LinkRouter.EXTRA_HANDLED, true) // loop-guard marker
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.open_failed, browser.label), Toast.LENGTH_SHORT).show()
        }
    }
}
