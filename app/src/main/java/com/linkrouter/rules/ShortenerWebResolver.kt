package com.linkrouter.rules

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.linkrouter.ResolutionWebViewActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * M7 WebView fallback (DESIGN.md section 6 / D9): resolve a shortener URL by
 * settling JS/Cloudflare/<meta refresh> interstitials in an ephemeral
 * resolution WebView, then returning the final URL.
 *
 * [ActivityWebResolver] is the production implementation (bridges
 * [ResolutionWebViewActivity] into a suspend call). Tests substitute a fake
 * whose [resolve] returns synchronously and whose [deliverResult] is a no-op.
 */
interface ShortenerWebResolver {
    /**
     * Resolve [url] by settling interstitials in an ephemeral WebView.
     * Returns the final URL on success, or **null** on failure/timeout (the
     * caller MUST degrade to the original URL — D6: never silently pretend).
     * Must not throw.
     */
    suspend fun resolve(context: Context, url: String): String?

    /**
     * Result-delivery hook for implementations that launch an [Activity] and
     * await its [Activity.RESULT_OK]/[Activity.RESULT_CANCELED] via
     * `startActivityForResult`. The OWNING activity's `onActivityResult`
     * forwards here to resume a pending [resolve].
     *
     * Default no-op: fakes (and any resolver that resolves without a child
     * activity) do not need it.
     */
    fun deliverResult(resultCode: Int, data: Intent?) {}
}

/**
 * Production [ShortenerWebResolver]: launches [ResolutionWebViewActivity] and
 * awaits its result on the main thread.
 *
 * Uses [Activity.startActivityForResult] + [suspendCancellableCoroutine].
 * (NOT `registerForActivityResult`, which is unavailable on a plain
 * [android.app.Activity] below API 34 and would throw if registered after the
 * activity is STARTED — the dispatcher invokes this after [onCreate] completes.)
 *
 * The resolution activity delivers its result via the OWNING activity's
 * [Activity.onActivityResult]; the dispatcher forwards it to [deliverResult],
 * which resumes the suspended [resolve].
 *
 * Runs on the main thread (it is launching an activity). On [Activity.RESULT_OK]
 * it returns the [ResolutionWebViewActivity.RESULT_URL]; on
 * [Activity.RESULT_CANCELED] / timeout / any exception it returns null so the
 * caller degrades to the original URL (D6).
 *
 * The overall timeout is enforced twice: the resolution activity has its own
 * [ResolutionWebViewActivity.DEFAULT_TIMEOUT_MS] (it always calls
 * `setResult`+`finish` by then), and [resolve] additionally wraps the suspend
 * call in [withTimeout] so it can never hang even if the child's result is lost.
 */
class ActivityWebResolver : ShortenerWebResolver {

    @Volatile
    private var pending: ((Int, Intent?) -> Unit)? = null

    override suspend fun resolve(context: Context, url: String): String? {
        val activity = context as? Activity ?: return null // D6: no activity → degrade.
        return try {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val sink: (Int, Intent?) -> Unit = { code, data ->
                        pending = null
                        // Skip resume if already cancelled (timeout/cancel).
                        if (!cont.isCancelled) {
                            val finalUrl = if (code == Activity.RESULT_OK) {
                                data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL)
                            } else {
                                null
                            }
                            cont.resume(finalUrl)
                        }
                    }
                    pending = sink
                    try {
                        activity.startActivityForResult(
                            Intent(activity, ResolutionWebViewActivity::class.java)
                                .putExtra(ResolutionWebViewActivity.EXTRA_URL, url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            REQUEST_RESOLUTION,
                        )
                    } catch (_: Exception) {
                        pending = null
                        if (!cont.isCancelled) cont.resume(null)
                    }
                }
            }
        } catch (_: Exception) {
            null // D6: never throw; degrade to the original URL.
        }
    }

    override fun deliverResult(resultCode: Int, data: Intent?) {
        val sink = pending
        pending = null
        sink?.invoke(resultCode, data)
    }

    private companion object {
        const val REQUEST_RESOLUTION = 0x4C52
        // Slightly above the activity's own timeout so the activity normally
        // reports first; the withTimeout is a safety net against a lost result.
        const val RESOLVE_TIMEOUT_MS = ResolutionWebViewActivity.DEFAULT_TIMEOUT_MS + 1_000L
    }
}
