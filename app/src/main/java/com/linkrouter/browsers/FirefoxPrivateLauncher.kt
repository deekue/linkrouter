package com.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Firefox private strategy (DESIGN.md section 7).
 *
 * Attach the `private_browsing_mode` intent extra that Mozilla's build honors
 * when the intent is pinned to the Firefox package (confirmed against Mozilla's
 * open-source repo — `HomeActivity`/`IntentReceiverActivity` — and issue #14499
 * "Allow third-party apps to open a tab in private browsing mode").
 *
 * **Verified on-device (2026-09-06):** the URL opens in a genuine private
 * window (purple shield in the URL bar). This is a REAL private strategy, so
 * we do NOT warn the user (D6 satisfied: we only claim private when it is
 * actually true).
 *
 * Note: the earlier `about:privatebrowsing`-then-refire trick was falsified on
 * device (the URL opened normally); the intent-extra approach is what works.
 */
object FirefoxPrivateLauncher : PrivateLauncher {

    /** Private-mode extra honored by Mozilla's build (unofficial but verified). */
    private const val EXTRA_PRIVATE_BROWSING_MODE = "private_browsing_mode"

    override fun launch(context: Context, browser: BrowserInfo, uri: Uri) {
        // Pin to the package, NOT to a component: `browser.activity` is the
        // MAIN/LAUNCHER (icon) activity, which does not interpret ACTION_VIEW
        // data — pinning it opens the home screen and drops the URL. Let the
        // resolver pick the real http/https handler (as targetIntent does).
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setPackage(browser.packageName)
                .putExtra(EXTRA_PRIVATE_BROWSING_MODE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun isRealPrivate(): Boolean = true

    override fun capability(): PrivateCapability = PrivateCapability.REAL
}
