package com.linkrouter.browsers

/**
 * package → private strategy (DESIGN.md section 7). Only Firefox has a real
 * private strategy today; everything else degrades to warn+normal (D6).
 *
 * Forward-looking: if the OS ships a system-level private-browsing toggle,
 * register it here as the top-priority strategy.
 */
object StrategyTable {

    const val FIREFOX = "org.mozilla.firefox"

    fun launcherFor(browser: BrowserInfo): PrivateLauncher =
        when (browser.packageName) {
            FIREFOX -> FirefoxPrivateLauncher
            else -> WarnNormalLauncher
        }

    /** Cheap capability check for the rule editor badge (DESIGN.md 7/10). */
    fun isPrivateCapable(packageName: String): Boolean =
        launcherFor(BrowserInfo(packageName, "", null, null, false)).isRealPrivate()
}
