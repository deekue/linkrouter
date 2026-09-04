package com.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Firefox true-private strategy (DESIGN.md section 7):
 * 1. Open `about:privatebrowsing` to spawn/activate a private window.
 * 2. Immediately re-fire the real URL at the same package — it lands in the
 *    active (private) window.
 */
object FirefoxPrivateLauncher : PrivateLauncher {

    private val PRIVATE_HOME = Uri.parse("about:privatebrowsing")

    override fun launch(context: Context, browser: BrowserInfo, uri: Uri) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, PRIVATE_HOME)
                    .setPackage(browser.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // fall through to normal launch below
        }
        context.startActivity(normalIntent(context, browser, uri))
    }

    override fun isRealPrivate(): Boolean = true

    internal fun normalIntent(context: Context, browser: BrowserInfo, uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(browser.packageName)
        browser.activity?.let { intent.component = android.content.ComponentName.unflattenFromString(it) }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
