package net.chaosengine.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.chaosengine.linkrouter.WebViewActivity

/**
 * In-app WebView strategy (DESIGN.md section 7).
 *
 * Opens the URL inside [WebViewActivity] — a WebView embedded in LinkRouter
 * itself. Because the content never leaves our process, this is a **REAL**
 * private strategy: the page never crosses app boundaries, and
 * [WebViewActivity] clears cookies, cache and web storage on close (D6
 * satisfied without a warning).
 *
 * This launcher is only invoked from the PRIVATE branch of the dispatcher,
 * so [WebViewActivity.EXTRA_PRIVATE] is always true here.
 */
object WebViewLauncher : PrivateLauncher {

    override fun launch(context: Context, browser: BrowserInfo, uri: Uri) {
        context.startActivity(
            Intent(context, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, uri.toString())
                .putExtra(WebViewActivity.EXTRA_PRIVATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun isRealPrivate(): Boolean = true

    override fun capability(): PrivateCapability = PrivateCapability.REAL
}
