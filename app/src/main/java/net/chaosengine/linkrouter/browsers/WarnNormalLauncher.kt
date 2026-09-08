package net.chaosengine.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Default strategy for browsers without a public private API (DESIGN.md 7, D6):
 * not truly private. Callers must warn (one-time toast) and open normally.
 */
object WarnNormalLauncher : PrivateLauncher {
    override fun launch(context: Context, browser: BrowserInfo, uri: Uri) {
        // Pin to the package, NOT to a component: `browser.activity` is the
        // MAIN/LAUNCHER (icon) activity, which does not interpret ACTION_VIEW
        // data. Pinning it would open the browser's home screen and drop the URL.
        // Let the resolver pick the real http/https handler (as targetIntent does).
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(browser.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun isRealPrivate(): Boolean = false

    override fun capability(): PrivateCapability = PrivateCapability.NONE
}
