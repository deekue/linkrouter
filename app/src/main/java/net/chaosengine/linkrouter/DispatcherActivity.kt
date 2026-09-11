package net.chaosengine.linkrouter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import net.chaosengine.linkrouter.BuildConfig
import net.chaosengine.linkrouter.browsers.BrowserInfo
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.browsers.StrategyTable
import net.chaosengine.linkrouter.browsers.WebViewTarget
import net.chaosengine.linkrouter.fallback.FallbackHandler
import net.chaosengine.linkrouter.rules.QueryParamStripper
import net.chaosengine.linkrouter.rules.RedirectResolver
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.rules.RuleEngine
import net.chaosengine.linkrouter.rules.RuleRepository
import net.chaosengine.linkrouter.rules.ShortenerMatcher
import net.chaosengine.linkrouter.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent ACTION_VIEW interceptor (DESIGN.md section 6). Matched rules
 * launch the target browser — honoring the per-rule private flag — then
 * finish(). Everything else degrades to the configured fallback.
 */
class DispatcherActivity : Activity() {

    private lateinit var repo: RuleRepository
    private lateinit var redirectRepo: net.chaosengine.linkrouter.rules.RedirectFormatRepository
    private lateinit var shortenerRepo: net.chaosengine.linkrouter.rules.ShortenerHostRepository
    private lateinit var paramFilterRepo: net.chaosengine.linkrouter.rules.QueryParamFilterRepository
    private lateinit var registry: BrowserRegistry
    private lateinit var settings: SettingsStore
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val TAG = "DispatcherActivity"

        /**
         * Nested shortener resolution bound (redirector → shortener → final):
         * at most ONE shortener resolution may follow a redirector unwrap.
         * Enforced structurally (no loop construct) by the `finalUrl == null`
         * guard (a successful incoming shortener is never routed through the
         * nested path) plus the `unwrapped != matchCandidate` guard (each
         * shortener chain is separately bounded by [ShortenerResolver.MAX_HOPS]
         * and its `seen` set). No unbounded redirector↔shortener alternation.
         */
        private const val MAX_NESTED_DEPTH = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = AppContainer.get(this).ruleRepository
        redirectRepo = AppContainer.get(this).redirectFormatRepository
        shortenerRepo = AppContainer.get(this).shortenerHostRepository
        paramFilterRepo = AppContainer.get(this).queryParamFilterRepository
        registry = AppContainer.get(this).browserRegistry
        settings = AppContainer.get(this).settings

        val original: Uri = intent?.data ?: run {
            finish()
            return
        }
        val parsed = RuleEngine.normalize(original.toString())
        if (parsed == null) {
            // Non-web scheme (mailto:, etc.) — ignore (DESIGN.md 12).
            finish()
            return
        }

        // 1. LOOP GUARD (DESIGN.md section 2) — never re-match an already-handled URL.
        // If the user picked us from the chooser, just finish silently.
        // Re-showing the chooser would create an infinite loop.
        val handled = intent?.getBooleanExtra(LinkRouter.EXTRA_HANDLED, false) == true
        if (RuleEngine.isLoopGuard(handled, original.toString())) {
            finish()
            return
        }

        dispatch(original, parsed)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // M7 (D9): the ephemeral resolution WebView reports back here. Forward to
        // the web resolver so a pending ActivityWebResolver.resolve() can resume.
        // No-op when no resolution is in flight (fakes deliver synchronously).
        AppContainer.shortenerWebResolver.deliverResult(resultCode, data)
    }

    private fun dispatch(original: Uri, parsed: RuleEngine.ParsedUrl) {
        dispatchScope.launch {
            // 2. MATCH against enabled rules (in priority order), using the
            // user-managed redirect formats to resolve the wrapper to its
            // destination for RULE MATCHING only (we still launch the original).
            data class Loaded(
                val rules: List<Rule>,
                val formats: List<net.chaosengine.linkrouter.rules.RedirectFormat>,
                val shortenerHosts: List<net.chaosengine.linkrouter.rules.ShortenerHost>,
                val paramFilters: List<net.chaosengine.linkrouter.rules.QueryParamFilter>,
            )
            val (rules, formats, shortenerHosts, paramFilters) = withContext(Dispatchers.IO) {
                Loaded(
                    repo.all(),
                    redirectRepo.allEnabled(),
                    shortenerRepo.allEnabled(),
                    paramFilterRepo.allEnabled(),
                )
            }

            // D9 shortener resolution: if the incoming host is an enabled shortener host,
            // follow its redirects (pure-JVM fast path) to the final URL. On success we
            // BOTH match AND launch the final URL. Non-resolvable results degrade
            // gracefully (D6: never silently pretend).
            var finalUrl: String? = null
            var matchCandidate = original.toString()

            // 2a — INCOMING shortener (identical behavior to before, now via the
            // shared helper so the nested path below has identical semantics).
            val incoming = resolveShortener(matchCandidate, shortenerHosts)
            if (incoming != null) { finalUrl = incoming; matchCandidate = incoming }

            // 2b — NEW (nested shortener resolution): if the redirector's unwrapped
            // DESTINATION is itself an enabled shortener host, resolve it too.
            // Exactly ONE nesting level (MAX_NESTED_DEPTH): the finalUrl == null
            // guard means a successful incoming shortener is never re-fetched
            // through this path, and the unwrapped != matchCandidate guard means we
            // only resolve when a REAL unwrap happened (degrades to the redirector
            // destination's current behavior on inner failure — same as before).
            if (finalUrl == null) {
                @Suppress("DEPRECATION")
                val unwrapped = RedirectResolver.resolve(matchCandidate, formats)
                    ?: RuleEngine.unwrapRedirect(matchCandidate)
                if (unwrapped != null && unwrapped != matchCandidate) {
                    matchCandidate = unwrapped
                    val inner = resolveShortener(matchCandidate, shortenerHosts)
                    if (inner != null) finalUrl = inner
                }
            }

            val matchUrl = finalUrl ?: matchCandidate
            val matchParsed = RuleEngine.normalize(matchUrl) ?: parsed
            val rule: Rule? = RuleEngine.resolve(rules, matchParsed)

            // Launch the shortener's final URL when resolved; otherwise honor the existing
            // openRealDestination logic on the original wrapper.
            val launchUrl = finalUrl ?: RedirectResolver.launchDestination(original.toString(), formats)

            // M9: strip enabled tracking params from the URL we LAUNCH (never
            // from matchUrl — rule matching stays query-independent, DESIGN.md §6/M9).
            val launchUri = Uri.parse(QueryParamStripper.strip(launchUrl, paramFilters))

            // 3. RESOLVE TARGET + LAUNCH
            if (rule == null) {
                routeFallback(launchUri)
            } else if (WebViewTarget.isWebView(rule.targetPackage)) {
                // In-app WebView target — never consults the browser registry.
                // REAL private: the page stays in our process, no cross-app leak.
                if (rule.openMode == net.chaosengine.linkrouter.rules.OpenMode.PRIVATE) {
                    launchSafely(launchUri) {
                        net.chaosengine.linkrouter.browsers.WebViewLauncher.launch(this@DispatcherActivity, WebViewTarget.browserInfo, launchUri)
                    }
                } else {
                    launchSafely(launchUri) {
                        startActivity(
                            android.content.Intent(this@DispatcherActivity, WebViewActivity::class.java)
                                .putExtra(WebViewActivity.EXTRA_URL, launchUri.toString())
                                .putExtra(WebViewActivity.EXTRA_PRIVATE, false)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            } else {
                val target = registry.resolveTarget(rule.targetPackage)
                when {
                    target == null -> {
                        // Uninstalled target browser (DESIGN.md 8) → fallback.
                        routeFallback(launchUri)
                    }
                    rule.openMode == net.chaosengine.linkrouter.rules.OpenMode.PRIVATE -> {
                        val launcher = StrategyTable.launcherFor(target)
                        // D6: never silently pretend to be private. Warn once
                        // unless the strategy is verified to open a real
                        // private window.
                        when (launcher.capability()) {
                            net.chaosengine.linkrouter.browsers.PrivateCapability.REAL -> { /* verified; no warn */ }
                            net.chaosengine.linkrouter.browsers.PrivateCapability.ATTEMPT ->
                                if (settings.shouldWarnPrivate(target.packageName))
                                    toast(getString(R.string.private_attempted, target.label))
                            net.chaosengine.linkrouter.browsers.PrivateCapability.NONE ->
                                if (settings.shouldWarnPrivate(target.packageName))
                                    toast(getString(R.string.private_not_supported, target.label))
                        }
                        // Always launch via the strategy: ATTEMPT browsers get the
                        // best-effort private extra (harmless if the browser
                        // ignores it → URL opens normally); NONE opens normally.
                        // Both keep the URL deliverable (package-pinned, no
                        // component), so "opens but not the URL" cannot happen.
                        launchSafely(launchUri) { launcher.launch(this@DispatcherActivity, target, launchUri) }
                    }
                    else -> launchSafely(launchUri) { dispatchNormal(launchUri, target) }
                }
            }
            finish()
        }
    }

    /**
     * Resolve [url] through the shortener fast path (and, when the fast path
     * hits a JS/<meta refresh> interstitial it cannot settle, the ephemeral
     * resolution WebView). Returns the final URL on success, or null to
     * degrade (D6: never silently pretend).
     *
     * The INCOMING shortener ([resolveShortener] call #1 in [dispatch]) and the
     * DESTINATION-after-redirector-unwrap (call #2, the nested resolution)
     * share this exact helper so both passes have identical fast-path /
     * interstitial-escalation / degrade semantics. Each call is separately
     * bounded by [ShortenerResolver.MAX_HOPS] and its `seen` set; the overall
     * nesting bound is [MAX_NESTED_DEPTH].
     */
    private suspend fun resolveShortener(
        url: String,
        hosts: List<net.chaosengine.linkrouter.rules.ShortenerHost>,
    ): String? {
        if (hosts.isEmpty()) return null
        val parsed = RuleEngine.normalize(url) ?: return null  // non-http(s) → not a shortener
        if (!hosts.any { ShortenerMatcher.matches(parsed.host, parsed.path, it) }) return null

        val result = withContext(Dispatchers.IO) {
            ShortenerResolver.resolve(url, AppContainer.shortenerFetcher)
        }
        return when (result) {
            is ShortenerResolver.Result.Resolved -> {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "Shortener resolved $url -> ${result.finalUrl} (hops=${result.hops})")
                }
                result.finalUrl
            }
            // M7 (D9): a JS/<meta refresh>/Cloudflare interstitial the pure-JVM
            // fast path cannot settle → escalate to the ephemeral resolution
            // WebView, which returns the final URL. On failure/timeout it
            // returns null → finalUrl stays null → degrade (D6: never silently
            // pretend).
            is ShortenerResolver.Result.Interstitial -> {
                val web = withContext(Dispatchers.Main) {
                    AppContainer.shortenerWebResolver.resolve(this@DispatcherActivity, url)
                }
                if (web != null) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "Shortener resolved (WebView) $url -> $web (hops=${result.hops})")
                    }
                    web
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Shortener WebView settle failed/timed out for $url")
                    }
                    // Runs on the main thread (dispatchScope = Main); toast directly.
                    toast(getString(R.string.shortener_resolve_timeout))
                    null
                }
            }
            // Loop / MaxHops / Rejected / Error are genuine failures a WebView
            // won't safely fix — degrade (D6), with a visible toast so it is
            // never silent.
            is ShortenerResolver.Result.Error -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Shortener resolve failed for $url: ${result.message}")
                }
                toast(getString(R.string.shortener_resolve_failed, result.message))
                null
            }
            is ShortenerResolver.Result.Loop -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Shortener resolve failed for $url: redirect loop")
                }
                toast(getString(R.string.shortener_resolve_failed, "redirect loop"))
                null
            }
            is ShortenerResolver.Result.MaxHops -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Shortener resolve failed for $url: too many redirects")
                }
                toast(getString(R.string.shortener_resolve_failed, "too many redirects"))
                null
            }
            is ShortenerResolver.Result.Rejected -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Shortener resolve failed for $url: non-http(s) target ${result.url}")
                }
                toast(getString(R.string.shortener_resolve_failed, "non-http(s) target: ${result.url}"))
                null
            }
        }
    }

    /** Route to the configured fallback, resolving a specific browser if the user chose one. */
    private fun routeFallback(uri: Uri) {
        FallbackHandler.route(
            this,
            uri,
            settings,
            browserResolver = { pkg, u ->
                val browser = registry.installed(pkg)
                if (browser == null) null
                else registry.targetIntent(u, browser.packageName, browser.activity)
            },
        )
    }

    private fun launchSafely(uri: Uri, block: () -> Unit) {
        // Background-start guard (DESIGN.md 11): the coroutine may resume after
        // the activity is destroyed; don't attempt a background startActivity.
        if (!ActivityLaunchGuard.canStart(this)) return
        try {
            block()
        } catch (e: Exception) {
            if (ActivityLaunchGuard.canStart(this)) FallbackHandler.showChooser(this, uri)
        }
    }

    private fun dispatchNormal(uri: Uri, target: BrowserInfo) {
        val intent = registry.targetIntent(uri, target.packageName, target.activity)
        if (intent == null) {
            throw IllegalStateException("target not resolvable")
        }
        intent.putExtra(LinkRouter.EXTRA_HANDLED, true) // loop-guard marker
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
