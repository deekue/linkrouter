package com.linkrouter.browsers

/**
 * Sentinel "target" for the built-in in-app WebView (DESIGN.md section 7).
 *
 * The WebView is not a separate app — it is [WebViewActivity] inside LinkRouter.
 * We use a fake package name so that the existing rule model (which stores
 * `targetPackage` as a String) can represent it without a schema change.
 *
 * The package name is deliberately a non-resolvable value so that
 * [BrowserRegistry.resolveTarget] returns null for it — the dispatcher
 * intercepts WebView rules *before* consulting the registry.
 */
object WebViewTarget {

    /** Sentinel package name — NOT a real installed app. */
    const val PACKAGE = "com.linkrouter.webview"

    /** Human-readable label shown in the UI. */
    const val LABEL = "Built-in WebView"

    /** True when [packageName] refers to the in-app WebView target. */
    fun isWebView(packageName: String): Boolean =
        packageName == PACKAGE

    /** Synthetic [BrowserInfo] for the rule editor / row display. */
    val browserInfo: BrowserInfo
        get() = BrowserInfo(
            packageName = PACKAGE,
            label = LABEL,
            activity = null,
            icon = null,
            isPrivateCapable = true, // REAL — in-app, no cross-app leak
        )
}
