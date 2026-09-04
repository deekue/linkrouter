package com.linkrouter.browsers

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Default strategy for browsers without a public private API (DESIGN.md 7, D6):
 * not truly private. Callers must warn (one-time toast) and open normally.
 */
object WarnNormalLauncher : PrivateLauncher {
    override fun launch(context: Context, browser: BrowserInfo, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(browser.packageName)
        browser.activity?.let { intent.component = android.content.ComponentName.unflattenFromString(it) }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun isRealPrivate(): Boolean = false
}
