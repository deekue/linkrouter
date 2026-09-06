package com.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Chrome strategy (DESIGN.md section 7).
 *
 * **Verified on-device (2026-09-06):** the folk extra
 * `com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB` DOES open
 * incognito — but it opens a **blank incognito tab and drops the URL**.
 * That breaks our core contract (the URL must be delivered), so we must NOT
 * attach it (D6 + D1: delivering the URL to the target browser is the point).
 *
 * Result: Chrome gets the NONE tier — open the URL normally (package-pinned)
 * and let the dispatcher warn the user that private browsing is not supported.
 *
 * TODO(verify-on-device): if a future Chrome build accepts the incognito extra
 * *together with* an ACTION_VIEW data URI, restore the extra and upgrade
 * [capability] to REAL (or at least ATTEMPT).
 */
object ChromePrivateLauncher : PrivateLauncher {

    override fun launch(context: Context, browser: BrowserInfo, uri: Uri) {
        // Pin to the package, NOT to a component: `browser.activity` is the
        // MAIN/LAUNCHER (icon) activity, which does not interpret ACTION_VIEW
        // data — pinning it opens the home screen and drops the URL. Let the
        // resolver pick the real http/https handler (as targetIntent does).
        //
        // Deliberately NO incognito extra: EXTRA_OPEN_NEW_INCOGNITO_TAB opens
        // a blank incognito tab and drops the URL (verified on-device).
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setPackage(browser.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun isRealPrivate(): Boolean = false

    override fun capability(): PrivateCapability = PrivateCapability.NONE
}
