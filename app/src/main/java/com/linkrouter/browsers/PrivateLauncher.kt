package com.linkrouter.browsers

import android.content.Context
import android.net.Uri

/**
 * Pluggable private/incognito strategy (DESIGN.md section 7). Android has no
 * standard "open private" intent, so this is best-effort, keyed by package.
 */
interface PrivateLauncher {
    fun launch(context: Context, browser: BrowserInfo, uri: Uri)
    fun isRealPrivate(): Boolean
}
