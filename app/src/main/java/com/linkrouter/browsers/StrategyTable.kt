package com.linkrouter.browsers

/**
 * package → private strategy (DESIGN.md section 7).
 *
 *  - Firefox — REAL: `private_browsing_mode` extra verified on-device to open
 *    a genuine private window.
 *  - WebView (in-app) — REAL: the page never leaves our process; cookies/cache
 *    are cleared on close.
 *  - Chrome — NONE: the folk incognito extra opens a blank tab and DROPS the
 *    URL (verified on-device), so we open normally and warn (D6).
 *  - Everything else — NONE: open normally + warn (D6).
 */
object StrategyTable {

    const val FIREFOX = "org.mozilla.firefox"
    const val CHROME = "com.android.chrome"

    fun launcherFor(browser: BrowserInfo): PrivateLauncher =
        when (browser.packageName) {
            FIREFOX -> FirefoxPrivateLauncher
            CHROME -> ChromePrivateLauncher
            WebViewTarget.PACKAGE -> WebViewLauncher
            else -> WarnNormalLauncher
        }

    /** Capability tier for a browser (drives the rule-editor badge + UX). */
    fun capabilityFor(packageName: String): PrivateCapability =
        launcherFor(BrowserInfo(packageName, "", null, null, false)).capability()

    /**
     * Cheap capability check for the rule editor badge (DESIGN.md 7/10).
     * @return true when the target can open a private window — either
     *         [PrivateCapability.REAL] (verified) or [PrivateCapability.ATTEMPT]
     *         (best-effort extra). NONE means "no private support".
     */
    fun isPrivateCapable(packageName: String): Boolean {
        val cap = capabilityFor(packageName)
        return cap == PrivateCapability.REAL || cap == PrivateCapability.ATTEMPT
    }

    /** @return true only when the strategy is verified to open a real private window. */
    fun isRealPrivate(packageName: String): Boolean =
        capabilityFor(packageName) == PrivateCapability.REAL
}
