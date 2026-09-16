package net.chaosengine.linkrouter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
 * **Result handback is a DIRECT IN-PROCESS call** to the resolver
 * ([deliverInProcess] → [AppContainer.shortenerWebResolver].deliverResult),
 * carrying [RESULT_URL] (success) or no URL (timeout) plus the echoed
 * [EXTRA_TOKEN]. It is NOT done via `startActivityForResult`/`onActivityResult`,
 * because every resolution instance shared ONE `requestCode` and a concurrent
 * resolution could clobber the first's result slot, losing a genuine success
 * (the device-confirmed false timeout). The activity still `finish()`es
 * afterwards; the owner [DispatcherActivity] finishes itself when its
 * `resolve()` resumes.
 *
 * Ephemeral privacy: on close (success or failure) [onDestroy] clears cookies,
 * HTTP cache and web storage and destroys the WebView, so the interstitial's
 * session cannot leak into a later real browsing session.
 */
class ResolutionWebViewActivity : Activity() {

    companion object {
        const val EXTRA_URL = "net.chaosengine.linkrouter.resolution.EXTRA_URL"
        const val RESULT_URL = "net.chaosengine.linkrouter.resolution.RESULT_URL"

        /**
         * Ownership token issued by [net.chaosengine.linkrouter.rules.ActivityWebResolver]
         * and echoed back into the in-process [deliverInProcess] call (both
         * success and timeout), so the resolver can match a delivered result to
         * the in-flight resolution and drop stale/foreign ones (stale-result
         * guard).
         */
        const val EXTRA_TOKEN = "net.chaosengine.linkrouter.resolution.EXTRA_TOKEN"

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

    // Ownership token echoed back into the result so the resolver can match it
    // to the in-flight resolution and drop stale/foreign results (stale-result
    // guard). Null when the token extra is absent (legacy callers / tests).
    private var token: String? = null

    /**
     * Direct in-process result handback to the owning [net.chaosengine.linkrouter.rules.ActivityWebResolver].
     *
     * Why not `startActivityForResult`/`onActivityResult`: the dispatcher and
     * every resolution activity shared ONE `requestCode` (0x4C52), so a second
     * concurrent resolution clobbered the first's result slot — a genuine
     * `RESULT_OK` could be lost in the handback and the live sink orphaned
     * (device-confirmed false timeout). Both classes live in this process, so
     * the activity calls the resolver directly, keyed by the echoed
     * [EXTRA_TOKEN], with no shared channel. The activity is still
     * `finish()`-ed afterwards (the owner dispatcher finishes itself on
     * resume of `resolve()`).
     */
    private fun deliverInProcess(code: Int, finalUrl: String?) {
        try {
            val data = Intent()
            token?.let { data.putExtra(EXTRA_TOKEN, it) }
            if (code == RESULT_OK && finalUrl != null) {
                data.putExtra(RESULT_URL, finalUrl)
            }
            AppContainer.shortenerWebResolver.deliverResult(code, data)
        } catch (e: Throwable) {
            // Best-effort: the resolver's [withTimeout] safety net will still
            // resume `resolve()` with null, so a failed handback degrades
            // (D6) rather than crashes.
            ShortenResolveLog.e("deliverInProcess code=$code finalUrl=$finalUrl threw ${e} — relying on the resolver's withTimeout safety net")
        }
    }

    // Diagnostic-only state (ShortenResolve tag): activity start time and the
    // last page that reached onPageFinished. Used solely for log lines.
    private var startEpochMs: Long = 0L
    private var anyPageFinished = false

    private fun elapsedMs(): Long = if (startEpochMs == 0L) -1L else SystemClock.elapsedRealtime() - startEpochMs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            ShortenResolveLog.w("resolution WebView onCreate: no URL extra -> finish() immediately")
            finish()
            return
        }
        token = intent?.getStringExtra(EXTRA_TOKEN)
        startEpochMs = SystemClock.elapsedRealtime()
        ShortenResolveLog.i(
            "resolution WebView onCreate: url=$url timeoutMs=$DEFAULT_TIMEOUT_MS graceMs=$GRACE_MS " +
            "settleCheckDelayMs=$SETTLE_CHECK_DELAY_MS"
        )

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
                    ShortenResolveLog.i("onPageStarted url=$url (elapsed=${elapsedMs()}ms)")
                    settleDetector.onPageStarted(url)
                    // A new navigation began → cancel any pending settle re-check.
                    mainHandler.removeCallbacks(grace)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    anyPageFinished = true
                    ShortenResolveLog.i("onPageFinished url=$url (elapsed=${elapsedMs()}ms)")
                    settleDetector.onPageFinished(url)
                    // Re-evaluate settle after the grace window: if the page stayed
                    // put (no new navigation), it is the final destination.
                    mainHandler.removeCallbacks(grace)
                    mainHandler.postDelayed(grace, SETTLE_CHECK_DELAY_MS)
                }

                // Diagnostic-only overrides: log resource/network failures to help
                // diagnose a resolution timeout (e.g. cleartext/SSL). NO
                // handler.cancel()/proceed() calls, so the WebView's existing
                // error handling / behavior is unchanged.
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    ShortenResolveLog.e(
                        "onReceivedError url=${request.url} " +
                        "(isForMainFrame=${request.isForMainFrame}) description=${error.description} " +
                        "elapsed=${elapsedMs()}ms"
                    )
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    ShortenResolveLog.e(
                        "onReceivedSslError url=${error.url} error=${error.toString()} (NOT cancelling) elapsed=${elapsedMs()}ms"
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    ShortenResolveLog.w(
                        "onReceivedHttpError url=${request.url} httpStatus=${errorResponse.statusCode} " +
                        "isForMainFrame=${request.isForMainFrame} elapsed=${elapsedMs()}ms"
                    )
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
                ShortenResolveLog.w(
                    "TIMEOUT fired: giving up after ${elapsedMs()}ms (expected=${DEFAULT_TIMEOUT_MS}ms) " +
                    "lastSettledCandidate=${detector?.settledUrl ?: "<none>"} anyPageFinished=$anyPageFinished " +
                    "-> RESULT_CANCELED (in-process delivery)"
                )
                deliverInProcess(RESULT_CANCELED, null)
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
            ShortenResolveLog.i("settle CHECK: SETTLED candidate=$finalUrl elapsed=${elapsedMs()}ms -> RESULT_OK (in-process delivery) + finish()")
            deliverInProcess(RESULT_OK, finalUrl)
            finish()
        } else {
            ShortenResolveLog.i("settle CHECK: not settled yet elapsed=${elapsedMs()}ms (waiting for grace window)")
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
