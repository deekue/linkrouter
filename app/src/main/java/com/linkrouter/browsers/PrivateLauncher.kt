package com.linkrouter.browsers

import android.content.Context
import android.net.Uri

/**
 * Private-window capability tier (DESIGN.md section 7).
 *
 * Android has **no official cross-browser "open private" intent**, so this is
 * a best-effort, per-browser strategy keyed by package name.
 *
 *  - [REAL]    — a strategy is verified (on a real device) to actually open a
 *                private window. Today: Firefox and the in-app WebView.
 *  - [ATTEMPT] — a best-effort vendor extra/flag is attached that *may* open a
 *                private window. Unverified: if the browser ignores it, the URL
 *                simply opens normally. We warn the user we cannot guarantee
 *                privacy (D6: never silently pretend to be private).
 *  - [NONE]    — no private mechanism; the URL opens normally (warn per D6).
 */
enum class PrivateCapability { REAL, ATTEMPT, NONE }

/**
 * Pluggable private/incognito strategy (DESIGN.md section 7).
 */
interface PrivateLauncher {
    fun launch(context: Context, browser: BrowserInfo, uri: Uri)

    /** True only for [PrivateCapability.REAL] — a verified private window. */
    fun isRealPrivate(): Boolean

    /** The capability tier this launcher provides. */
    fun capability(): PrivateCapability
}
