package net.chaosengine.linkrouter.browsers

/**
 * Sentinel "target" for the built-in "Stop" action.
 *
 * Unlike [WebViewTarget] (which opens the URL in-app), the Stop target does
 * NOT open the URL anywhere — when a rule with this target matches, the
 * dispatcher only shows a toast notifying that the URL is being stopped.
 *
 * We use a fake package name so that the existing rule model (which stores
 * `targetPackage` as a String) can represent it without a schema change.
 *
 * The package name is deliberately a non-resolvable value so that
 * [BrowserRegistry.resolveTarget] returns null for it — the dispatcher
 * intercepts Stop rules *before* consulting the registry.
 */
object StopTarget {

    /** Sentinel package name — NOT a real installed app. */
    const val PACKAGE = "net.chaosengine.linkrouter.stop"

    /** Human-readable label shown in the UI. */
    const val LABEL = "Stop"

    /** True when [packageName] refers to the built-in Stop target. */
    fun isStop(packageName: String): Boolean =
        packageName == PACKAGE

    /** Synthesized [BrowserInfo] for the rule editor / row display. */
    val browserInfo: BrowserInfo
        get() = BrowserInfo(
            packageName = PACKAGE,
            label = LABEL,
            activity = null,
            icon = null,
            isPrivateCapable = false, // never opens anything — no private-window concept
        )
}
