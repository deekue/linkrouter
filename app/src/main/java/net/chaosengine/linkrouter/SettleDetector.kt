package net.chaosengine.linkrouter

import java.net.URI

/**
 * Pure-Kotlin "has the page settled on a final URL?" detector (M7 WebView
 * fallback, DESIGN.md section 6 / D9).
 *
 * This file must not import any `android.*` classes so the settle logic is
 * unit-testable on the plain JVM (Robolectric's WebView is unreliable for
 * driving a real settle, so the decision lives here).
 *
 * Model: feed it navigation transitions from the WebView
 * ([onPageStarted]/[onPageFinished]). A page is **settled** when it finished
 * loading and then stayed put for a grace period — i.e. no new navigation
 * began within [graceMillis] after the last finish. The final [settledUrl] is
 * the last page that finished and remained stable.
 *
 * Time is injected via [clock] so tests are deterministic; the detector never
 * calls [System.currentTimeMillis] itself.
 *
 * Guard: a settled URL whose scheme is not http/https is treated as NOT
 * settled (returns null) — a shortener never legitimately resolves to a
 * non-web scheme (D6: never silently pretend).
 */
class SettleDetector(private val graceMillis: Long = 1500L, private val clock: () -> Long = { System.currentTimeMillis() }) {

    private var lastStartedUrl: String? = null
    private var lastStartedAt: Long = 0L
    private var lastFinishedUrl: String? = null
    private var lastFinishedAt: Long = 0L

    /** The stable final URL, or null when nothing has settled yet. */
    val settledUrl: String?
        get() {
            val candidate = lastFinishedUrl ?: return null
            if (schemeOf(candidate) !in WEB_SCHEMES) return null
            val now = clock()
            val stableSince = lastFinishedAt
            if (now - stableSince < graceMillis) return null
            // A navigation that began after the finish invalidates the settle.
            if (lastStartedAt > lastFinishedAt) return null
            return candidate
        }

    val isSettled: Boolean
        get() = settledUrl != null

    fun onPageStarted(url: String) {
        val now = clock()
        lastStartedUrl = url
        lastStartedAt = now
    }

    fun onPageFinished(url: String) {
        val now = clock()
        lastFinishedUrl = url
        lastFinishedAt = now
    }

    fun reset() {
        lastStartedUrl = null
        lastStartedAt = 0L
        lastFinishedUrl = null
        lastFinishedAt = 0L
    }

    private fun schemeOf(url: String): String =
        try {
            URI(url).scheme?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https")
    }
}
