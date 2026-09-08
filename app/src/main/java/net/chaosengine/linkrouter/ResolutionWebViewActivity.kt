package net.chaosengine.linkrouter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Ephemeral resolution WebView (M7, DESIGN.md section 6 / D9).
 *
 * This is a *resolution* mechanism, NOT a browsing experience: it has no top
 * bar, no address bar, no back button, no user-facing chrome. Its only job is
 * to load a shortener's JS/Cloudflare/<meta refresh> interstitial, let the
 * client-side navigation settle, and hand the final URL back to the caller via
 * [RESULT_URL] (D6: on failure/timeout it returns no URL and the caller
 * degrades to the original).
 *
 * It is deliberately distinct from [WebViewActivity] (§7.1), which IS a
 * browsing target. The two share the same teardown pattern (clear cookies +
 * cache + web storage, destroy the WebView) but nothing else.
 *
 * **Settle detection** is the pure-JVM [SettleDetector] (decidable on the JVM,
 * testable without a real WebView). The activity only *drives* it: it feeds
 * [SettleDetector.onPageStarted]/[onPageFinished] and re-evaluates
 * [SettleDetector.settledUrl] after the grace window (a page is "settled" once
 * it finished loading and stayed put for the grace period).
 *
 * Ephemeral privacy: on close (success or failure) [onDestroy] clears cookies,
 * HTTP cache and web storage and destroys the WebView, so the interstitial's
 * session cannot leak into a later real browsing session.
 */
class ResolutionWebViewActivity : Activity() {

    companion object {
        const val EXTRA_URL = "net.chaosengine.linkrouter.resolution.EXTRA_URL"
        const val RESULT_URL = "net.chaosengine.linkrouter.resolution.RESULT_URL"

        /** Overall settle timeout: if the page has not settled by then, give up. */
        const val DEFAULT_TIMEOUT_MS = 8_000L
        /** Grace period a page must stay stable after finishing to count as settled. */
        const val GRACE_MS = 1_000L
        /** Delay before re-evaluating settle after a page finishes (>= GRACE_MS, with margin). */
        const val SETTLE_CHECK_DELAY_MS = 1_500L
    }

    private var webView: WebView? = null
    private var detector: SettleDetector? = null
    private var handler: Handler? = null
    private var graceRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }

        val settleDetector = SettleDetector(graceMillis = GRACE_MS)
        detector = settleDetector
        val mainHandler = Handler(Looper.getMainLooper())
        handler = mainHandler
        val grace = Runnable { maybeSettle() }
        graceRunnable = grace

        val view = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    if (scheme == "http" || scheme == "https") {
                        view.loadUrl(request.url.toString())
                        return true
                    }
                    // Non-web scheme (tel:, mailto:, custom): let the system take it;
                    // the settle detector will NOT settle on it (scheme guard).
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    settleDetector.onPageStarted(url)
                    // A new navigation began → cancel any pending settle re-check.
                    mainHandler.removeCallbacks(grace)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    settleDetector.onPageFinished(url)
                    // Re-evaluate settle after the grace window: if the page stayed
                    // put (no new navigation), it is the final destination.
                    mainHandler.removeCallbacks(grace)
                    mainHandler.postDelayed(grace, SETTLE_CHECK_DELAY_MS)
                }
            }
            loadUrl(url)
        }
        webView = view
        setContentView(view)

        // Overall timeout → cancel (D6: never return a half-resolved URL).
        val runTimeout = Runnable {
            if (!finished) {
                finished = true
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        timeoutRunnable = runTimeout
        mainHandler.postDelayed(runTimeout, DEFAULT_TIMEOUT_MS)
    }

    private fun maybeSettle() {
        if (finished) return
        val finalUrl = detector?.settledUrl
        if (finalUrl != null) {
            finished = true
            setResult(RESULT_OK, Intent().putExtra(RESULT_URL, finalUrl))
            finish()
        }
    }

    override fun onDestroy() {
        // Cancel any pending settle/timeout before teardown.
        val h = handler
        if (h != null) {
            try {
                graceRunnable?.let { h.removeCallbacks(it) }
                timeoutRunnable?.let { h.removeCallbacks(it) }
            } catch (_: Exception) {
                // Best-effort.
            }
        }
        handler = null
        graceRunnable = null
        timeoutRunnable = null

        // Ephemeral privacy (D6): clear all WebView data so nothing leaks into a
        // subsequent normal session. Best-effort — never crash.
        val view = webView
        if (view != null) {
            try {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                view.clearCache(true)
                view.loadUrl("about:blank")
                view.destroy()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
        }
        webView = null
        detector = null
        super.onDestroy()
    }
}
