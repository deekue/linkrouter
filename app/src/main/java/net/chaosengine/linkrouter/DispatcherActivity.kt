package net.chaosengine.linkrouter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
            // gracefully to the original URL (D6: never silently pretend).
                        var finalUrl: String? = null
            if (shortenerHosts.isNotEmpty()) {
                val isShortener = shortenerHosts.any {
                    ShortenerMatcher.matches(parsed.host, parsed.path, it)
                }
                if (isShortener) {
                    val result = withContext(Dispatchers.IO) {
                        ShortenerResolver.resolve(original.toString(), AppContainer.shortenerFetcher)
                    }
                    when (result) {
                        is ShortenerResolver.Result.Resolved -> {
                            finalUrl = result.finalUrl
                            Log.i(TAG, "Shortener resolved $original -> ${result.finalUrl} (hops=${result.hops})")
                        }
                        // M7 (D9): a JS/<meta refresh>/Cloudflare interstitial the
                        // pure-JVM fast path cannot settle → escalate to the
                        // ephemeral resolution WebView, which returns the final URL.
                        // On failure/timeout it returns null → finalUrl stays null →
                        // degrade to the original URL (D6: never silently pretend).
                        is ShortenerResolver.Result.Interstitial -> {
                            val web = withContext(Dispatchers.Main) {
                                AppContainer.shortenerWebResolver.resolve(this@DispatcherActivity, original.toString())
                            }
                            if (web != null) {
                                finalUrl = web
                                Log.i(TAG, "Shortener resolved (WebView) $original -> $web (hops=${result.hops})")
                            } else {
                                Log.w(TAG, "Shortener WebView settle failed/timed out for $original")
                                // Runs on the main thread (dispatchScope = Main); toast directly.
                                toast(getString(R.string.shortener_resolve_timeout))
                            }
                        }
                        // Loop / MaxHops / Rejected / Error are genuine failures a
                        // WebView won't safely fix — degrade to the original URL
                        // (D6), with a visible toast so it is never silent.
                        is ShortenerResolver.Result.Error -> {
                            Log.w(TAG, "Shortener resolve failed for $original: ${result.message}")
                            toast(getString(R.string.shortener_resolve_failed, result.message))
                        }
                        is ShortenerResolver.Result.Loop -> {
                            Log.w(TAG, "Shortener resolve failed for $original: redirect loop")
                            toast(getString(R.string.shortener_resolve_failed, "redirect loop"))
                        }
                        is ShortenerResolver.Result.MaxHops -> {
                            Log.w(TAG, "Shortener resolve failed for $original: too many redirects")
                            toast(getString(R.string.shortener_resolve_failed, "too many redirects"))
                        }
                        is ShortenerResolver.Result.Rejected -> {
                            Log.w(TAG, "Shortener resolve failed for $original: non-http(s) target ${result.url}")
                            toast(getString(R.string.shortener_resolve_failed, "non-http(s) target: ${result.url}"))
                        }
                    }
                }
            }

            @Suppress("DEPRECATION")
            val matchUrl = finalUrl
                ?: RedirectResolver.resolve(original.toString(), formats)
                ?: RuleEngine.unwrapRedirect(original.toString())
                ?: original.toString()
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
