package com.linkrouter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.browsers.BrowserRegistry
import com.linkrouter.browsers.StrategyTable
import com.linkrouter.fallback.FallbackHandler
import com.linkrouter.rules.RedirectResolver
import com.linkrouter.rules.Rule
import com.linkrouter.rules.RuleEngine
import com.linkrouter.rules.RuleRepository
import com.linkrouter.settings.SettingsStore
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
    private lateinit var redirectRepo: com.linkrouter.rules.RedirectFormatRepository
    private lateinit var registry: BrowserRegistry
    private lateinit var settings: SettingsStore
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = AppContainer.get(this).ruleRepository
        redirectRepo = AppContainer.get(this).redirectFormatRepository
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

    private fun dispatch(original: Uri, parsed: RuleEngine.ParsedUrl) {
        dispatchScope.launch {
            // 2. MATCH against enabled rules (in priority order), using the
            // user-managed redirect formats to resolve the wrapper to its
            // destination for RULE MATCHING only (we still launch the original).
            val (rules, formats) = withContext(Dispatchers.IO) {
                repo.all() to redirectRepo.allEnabled()
            }
            @Suppress("DEPRECATION")
            val matchUrl = RedirectResolver.resolve(original.toString(), formats)
                ?: RuleEngine.unwrapRedirect(original.toString())
                ?: original.toString()
            val matchParsed = RuleEngine.normalize(matchUrl) ?: parsed
            val rule: Rule? = RuleEngine.resolve(rules, matchParsed)

            // 3. RESOLVE TARGET + LAUNCH
            if (rule == null) {
                routeFallback(original)
            } else {
                val target = registry.resolveTarget(rule.targetPackage)
                when {
                    target == null -> {
                        // Uninstalled target browser (DESIGN.md 8) → fallback.
                        routeFallback(original)
                    }
                    rule.openMode == com.linkrouter.rules.OpenMode.PRIVATE -> {
                        val launcher = StrategyTable.launcherFor(target)
                        if (launcher.isRealPrivate()) {
                            launchSafely(original) { launcher.launch(this@DispatcherActivity, target, original) }
                        } else {
                            // D6: one-time warn, then open normally.
                            if (settings.shouldWarnPrivate(target.packageName)) {
                                toast(getString(R.string.private_not_supported, target.label))
                            }
                            launchSafely(original) { dispatchNormal(original, target) }
                        }
                    }
                    else -> launchSafely(original) { dispatchNormal(original, target) }
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
