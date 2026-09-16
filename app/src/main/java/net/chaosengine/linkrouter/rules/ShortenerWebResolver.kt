package net.chaosengine.linkrouter.rules

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import net.chaosengine.linkrouter.ResolutionWebViewActivity
import net.chaosengine.linkrouter.ShortenResolveLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
     * Result-delivery hook invoked **directly, in-process** by
     * [ResolutionWebViewActivity] on settle ([Activity.RESULT_OK] + final URL)
     * or timeout ([Activity.RESULT_CANCELED]), resuming the matching pending
     * [resolve]. [data] carries the echoed
     * [ResolutionWebViewActivity.EXTRA_TOKEN] (for [ActivityWebResolver]),
     * which keys its token-tagged sinks so that exactly ONE matching sink is
     * resumed and stale/foreign/late/duplicate deliveries are dropped.
     * Resolvers without an ownership concept (fakes, fast-path-only) ignore it.
     *
     * Default no-op: fakes (and any resolver that resolves without a child
     * activity) do not need it.
     */
    fun deliverResult(resultCode: Int, data: Intent?) {}
}

/**
 * Production [ShortenerWebResolver]: launches [ResolutionWebViewActivity] via
 * [Activity.startActivity] and awaits its result on the main thread.
 *
 * (NOT `registerForActivityResult`, which is unavailable on a plain
 * [android.app.Activity] below API 34 and would throw if registered after the
 * activity is STARTED — the dispatcher invokes this after [onCreate]
 * completes.)
 *
 * **Result handback is a DIRECT IN-PROCESS callback, not
 * `startActivityForResult`/`onActivityResult`.** Both classes live in the same
 * process, and the `onActivityResult` channel is fundamentally fragile here:
 * every resolution activity shared ONE `requestCode` with every other, so a
 * second concurrent resolution could clobber (or be clobbered by) the first's
 * result slot — the device-confirmed failure where a genuine `RESULT_OK` never
 * reached [deliverResult] and the live sink timed out. The activity now looks
 * up [AppContainer.shortenerWebResolver] — the SAME instance
 * [net.chaosengine.linkrouter.DispatcherActivity] invokes for [resolve] — and
 * calls [deliverResult] with the echoed [ResolutionWebViewActivity.EXTRA_TOKEN]
 * directly. There is no shared channel, so concurrent resolutions can no
 * longer lose each other's results.
 *
 * **Token-keyed sinks.** Each [resolve] issues a unique [token]
 * ([java.util.UUID]) and registers its sink under that token in a
 * [ConcurrentHashMap]. A concurrent `resolve()` for the same URL therefore
 * does NOT destroy a prior resolution's continuation: each resolves, and
 * times out, independently of the others.
 *
 * **Stale/duplicate-result guard (fix #1, preserved).** [deliverResult]
 * resumes ONLY the sink registered under the echoed [token], **exactly once**
 * (atomic remove-before-resume under a single intrinsic lock); stale/foreign
 * /late/duplicate deliveries are dropped safely, never consuming a live sink.
 *
 * Runs on the main thread (it is launching an activity). On
 * [Activity.RESULT_OK] it returns the [ResolutionWebViewActivity.RESULT_URL];
 * on [Activity.RESULT_CANCELED] / timeout / any exception it returns null so
 * the caller degrades to the original URL (D6).
 *
 * The overall timeout is enforced twice: the resolution activity has its own
 * [ResolutionWebViewActivity.DEFAULT_TIMEOUT_MS] (it self-delivers
 * RESULT_CANCELED + `finish` by then), and [resolve] additionally wraps the
 * suspend call in [withTimeout] — the safety net for an activity that dies
 * and never delivers at all.
 */
class ActivityWebResolver : ShortenerWebResolver {

    private class Pending(val token: String, val sink: (Int, Intent?) -> Unit)

    /**
     * Token-keyed pending sinks: concurrent resolves are independent (each has
     * its own sink; a second resolve() for the same URL does NOT clobber the
     * first). [deliverResult] atomically removes+invokes exactly one entry.
     */
    private val pending = ConcurrentHashMap<String, Pending>()

    override suspend fun resolve(context: Context, url: String): String? {
        val activity = context as? Activity ?: return null // D6: no activity → degrade.
        val startMs = SystemClock.elapsedRealtime()
        return try {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val token = UUID.randomUUID().toString()
                    val pendingCount = pending.size
                    ShortenResolveLog.i(
                        "web-resolve start url=$url token=${token.take(8)} (timeout=${RESOLVE_TIMEOUT_MS}ms)" +
                            (if (pendingCount == 0) "" else
                                " [$pendingCount other resolve(s) still pending — token-keyed, independent]")
                    )
                    val sink: (Int, Intent?) -> Unit = { code, data ->
                        // Invoked at most once (atomic remove in deliverResult /
                        // launch-failure path); no double-resume is possible.
                        if (!cont.isCancelled) {
                            val finalUrl = if (code == Activity.RESULT_OK) {
                                data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL)
                            } else {
                                null
                            }
                            ShortenResolveLog.i(
                                "web-resolve sink resumed: token=${token.take(8)} code=$code " +
                                "(RESULT_OK=${code == Activity.RESULT_OK}) " +
                                "elapsed=${SystemClock.elapsedRealtime() - startMs}ms " +
                                "finalUrl=${if (finalUrl != null) finalUrl else "<null>"}"
                            )
                            cont.resume(finalUrl)
                        } else {
                            ShortenResolveLog.w(
                                "web-resolve token=${token.take(8)} sink invoked but already " +
                                    "cancelled (timeout/cancel) — NOT resuming"
                            )
                        }
                    }
                    pending[token] = Pending(token, sink)
                    try {
                        // Plain startActivity: the result handback is direct
                        // in-process (see [deliverResult]); no requestCode is
                        // shared, so concurrent resolutions cannot clobber each
                        // other's result slots.
                        activity.startActivity(
                            Intent(activity, ResolutionWebViewActivity::class.java)
                                .putExtra(ResolutionWebViewActivity.EXTRA_URL, url)
                                .putExtra(ResolutionWebViewActivity.EXTRA_TOKEN, token)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                        // Launch failed → this token can never be delivered by
                        // an activity. Release THIS token's sink (exactly once)
                        // and resume it with a CANCELED so [resolve] returns null
                        // immediately (D6 degrade) instead of waiting for the
                        // withTimeout safety net. Other tokens are untouched.
                        val removed = synchronized(this) { pending.remove(token) }
                        ShortenResolveLog.w(
                            "web-resolve url=$url token=${token.take(8)}: startActivity threw — " +
                                "releasing sink and resuming with null (degrade)"
                        )
                        removed?.sink(Activity.RESULT_CANCELED, null)
                    }
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // The withTimeout safety net fired: the activity never delivered a
            // result (it died, or was never started). Only THIS token's
            // continuation is affected — it resumes below with null and is
            // cancelled, so a very-late delivery cannot resurrect it. Other
            // in-flight tokens are untouched (token-keyed independence).
            ShortenResolveLog.w(
                "web-resolve url=$url TIMED OUT after ${SystemClock.elapsedRealtime() - startMs}ms " +
                    "— sink never resumed (withTimeout fired) -> null (degrade)"
            )
            null // D6: never throw; degrade to the original URL.
        } catch (_: Exception) {
            ShortenResolveLog.w(
                "web-resolve url=$url: unexpected exception after ${SystemClock.elapsedRealtime() - startMs}ms → null (degrade)"
            )
            null // D6: never throw; degrade to the original URL.
        }
    }

    /**
     * Invoked DIRECTLY in-process by [ResolutionWebViewActivity] on settle
     * ([Activity.RESULT_OK]) or timeout ([Activity.RESULT_CANCELED]) — NOT via
     * `onActivityResult`.
     *
     * Atomically removes (under a single intrinsic lock) and then invokes the
     * single sink registered under the echoed [EXTRA_TOKEN] — exactly once.
     * Unknown/consumed/foreign tokens are safely ignored.
     */
    override fun deliverResult(resultCode: Int, data: Intent?) {
        val token = data?.getStringExtra(ResolutionWebViewActivity.EXTRA_TOKEN)
        val entry: Pending? = if (token == null) {
            ShortenResolveLog.w(
                "deliverResult code=$resultCode: no token in data — stale/legacy delivery DROPPED " +
                    "(liveTokens=${pending.keys.map { it.take(8) }})"
            )
            null
        } else {
            synchronized(this) {
                val e = pending.remove(token)
                if (e == null) {
                    ShortenResolveLog.w(
                        "deliverResult code=$resultCode: UNKNOWN/consumed token=${token.take(8)} DROPPED " +
                            "(liveTokens=${pending.keys.map { it.take(8) }})"
                    )
                }
                e
            }
        }
        // Invoke OUTSIDE the lock: resuming the continuation must not hold
        // this resolver's monitor.
        entry?.sink(resultCode, data)
    }

    /** Test hook: the set of currently pending (unconsumed) sink tokens. */
    internal fun liveTokens(): Set<String> = synchronized(this) { pending.keys.toSet() }

    /**
     * Test-only: seed [this] with a fresh pending sink under [token] so the
     * token-keyed guard logic in [deliverResult] (token match + exactly-once
     * remove + stale-drop) can be exercised deterministically, decoupled from
     * `startActivity` and Robolectric main-looper threading. Mirrors what
     * [resolve] does before it launches [ResolutionWebViewActivity]:
     * `pending[token] = Pending(token, sink)`.
     */
    internal fun seedPendingForTest(token: String, sink: (Int, Intent?) -> Unit) {
        check(token.isNotBlank()) { "token must be non-blank" }
        synchronized(this) {
            pending[token] = Pending(token, sink)
        }
    }

    private companion object {
        // Slightly above the activity's own timeout so the activity normally
        // reports first; the withTimeout is the safety net against a dead
        // activity that never delivers.
        const val RESOLVE_TIMEOUT_MS = ResolutionWebViewActivity.DEFAULT_TIMEOUT_MS + 1_000L
    }
}
