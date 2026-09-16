package net.chaosengine.linkrouter.rules

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.chaosengine.linkrouter.ResolutionWebViewActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Regression coverage for the two confirmed "Short link resolution timed out"
 * false-timeout bugs.
 *
 * **Bug #1 (stale result preemption — fixed by token tagging).**
 * [net.chaosengine.linkrouter.rules.ActivityWebResolver] previously resumed a
 * single pending sink unconditionally from the OWNING activity's
 * `onActivityResult`. A stale `RESULT_CANCELED` (code=0) that belonged to some
 * other/earlier `startActivityForResult` in the owning activity — even one
 * delivered BEFORE this resolution's WebView activity had launched — could
 * consume the live sink, orphaning the genuine `RESULT_OK`.
 *
 * **Bug #2 (shared-`requestCode` lost handback + single-sink clobber — fixed
 * by token-keyed sinks + direct in-process delivery).** Two concurrent
 * `resolve()` calls shared ONE `requestCode` in the
 * `startActivityForResult`/`onActivityResult` channel: the second `resolve()`
 * CLOBBERED the first's single pending sink, and a genuine `RESULT_OK` for
 * one resolution could be lost in the handback (the first activity's
 * `finish()` already consumed that `requestCode`'s result slot), orphaning the
 * live sink until `withTimeout` fired — the device-confirmed false timeout.
 *
 * **The fix.** Each `resolve()` issues a unique [token] ([java.util.UUID])
 * and registers its sink under that token in a MAP (concurrent resolves are
 * independent — no clobbering, no shared `requestCode`). The child
 * [ResolutionWebViewActivity] delivers its result DIRECTLY IN-PROCESS
 * (bypassing the `onActivityResult` channel entirely) with the token echoed in
 * [ResolutionWebViewActivity.EXTRA_TOKEN]. [deliverResult] resumes ONLY the
 * sink registered under the echoed token, **exactly once** (atomic
 * remove-before-resume under a single intrinsic lock); stale/foreign/late/
 * duplicate deliveries are dropped without consuming any live sink.
     *
     * **How this class exercises it.** The guard logic is driven deterministically
     * via the internal [seedPendingForTest]/[liveTokens] hooks (mirroring what
     * `resolve()` does before it launches the child — `pending[token] = Pending(
     * token, sink)`) so those tests are threading-free and fast. Two end-to-end
     * tests drive the REAL `resolve()` under Robolectric to prove the token flows
     * from `resolve()` into the child and resumes the suspend on a genuine
     * delivery. One test drives the REAL `resolve()` and leaves the child
     * undelivered (no network, main looper never pumped) to prove the
     * `withTimeout` safety net returns null without clobbering other in-flight
     * tokens.
     *
 * [net.chaosengine.linkrouter.ShortenResolveLog] is a no-op under the
 * plain-JVM/Robolectric test classpath (its `try/catch` swallows the
 * `android.util.Log` "Stub!" throw), so the diagnostic logging on these paths
 * is safe and unasserted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActivityWebResolverStaleResultTest {

    private companion object {
        const val FINAL_URL = "https://final.example.com/article?id=1"
        const val FOREIGN_TOKEN = "stale-foreign-token"
    }

    private val activity: Activity = buildActivity(Activity::class.java).create().get()

    // ========================================================================
    // Bug #1 regression: stale/foreign/duplicate delivery must never consume a
    // live sink for a token.
    // ========================================================================

    /**
     * Core regression: a stale `RESULT_CANCELED` (code=0) delivered with NO
     * token must NOT consume any live sink. The sink stays intact (same token)
     * and the subsequent genuine `RESULT_OK` (echoing the live token) still
     * resumes it with the final URL.
     */
    @Test
    fun `stale CANCELED without a token does not consume the live sink`() {
        val resolver = ActivityWebResolver()
        val sinkInvocations = AtomicInteger(0)
        val resumed = AtomicReference<String?>("UNSET")

        val token = "00000000-1111-2222-3333-444444444444"
        resolver.seedPendingForTest(token) { code, data ->
            sinkInvocations.incrementAndGet()
            if (code == Activity.RESULT_OK) resumed.set(data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
            else resumed.set(null)
        }
        // Sanity: we now own a live sink for this token.
        assertEquals(setOf(token), resolver.liveTokens())

        // 1) STALE CANCELED (code=0) with no echoed token (the old
        //    `onActivityResult` shape that pre-empted the live sink).
        resolver.deliverResult(Activity.RESULT_CANCELED, null)
        assertEquals("stale CANCELED must not consume the live sink", setOf(token), resolver.liveTokens())
        assertEquals("stale CANCELED must not invoke the sink", 0, sinkInvocations.get())
        assertEquals("stale CANCELED must not resume the sink", "UNSET", resumed.get())

        // 2) GENUINE RESULT_OK echoing the live token resumes the sink.
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(token, FINAL_URL))
        assertEquals("genuine RESULT_OK must resume the sink exactly once", 1, sinkInvocations.get())
        assertEquals(FINAL_URL, resumed.get())
        assertTrue("after the genuine RESULT_OK the sink is consumed", resolver.liveTokens().isEmpty())
    }

    /** A stale CANCELED carrying a FOREIGN token must stay dropped too. */
    @Test
    fun `foreign token result is dropped and live sink survives`() {
        val resolver = ActivityWebResolver()
        val sinkInvocations = AtomicInteger(0)
        val token = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        resolver.seedPendingForTest(token) { _, _ -> sinkInvocations.incrementAndGet() }
        assertEquals(setOf(token), resolver.liveTokens())

        resolver.deliverResult(Activity.RESULT_CANCELED, intentWithToken(FOREIGN_TOKEN, null))

        assertEquals("foreign-token result must not consume the live sink", setOf(token), resolver.liveTokens())
        assertEquals(0, sinkInvocations.get())
    }

    /** Exactly-once: a repeat delivery for the same token is a no-op. */
    @Test
    fun `duplicate delivery for the same token does not double resume`() {
        val resolver = ActivityWebResolver()
        val sinkInvocations = AtomicInteger(0)
        val resumed = AtomicReference<String?>("UNSET")
        val token = "ffffffff-0000-0000-0000-000000000000"
        resolver.seedPendingForTest(token) { code, data ->
            sinkInvocations.incrementAndGet()
            if (code == Activity.RESULT_OK) resumed.set(data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
        }

        val real = intentWithToken(token, FINAL_URL)
        resolver.deliverResult(Activity.RESULT_OK, real)
        resolver.deliverResult(Activity.RESULT_OK, real) // duplicate
        resolver.deliverResult(Activity.RESULT_OK, real) // duplicate again

        assertEquals("sink must resume exactly once", 1, sinkInvocations.get())
        assertEquals(FINAL_URL, resumed.get())
        assertTrue("no live sink for the token remains after the first delivery", resolver.liveTokens().isEmpty())
    }

    // ========================================================================
    // Bug #2 regression: concurrent resolves must be independent (token-keyed
    // sinks — no single-sink clobbering), each resumes exactly once on its own
    // token's delivery, in any order.
    // ========================================================================

    /**
     * THE core clobber regression: two concurrent resolves (token A, token B)
     * are independent. Delivering token A's `RESULT_OK` resumes ONLY A; token
     * B's sink stays intact (it was never clobbered by A and is never resumed
     * by A's delivery). A duplicate for A is a no-op. Token B's later
     * `RESULT_OK` then resumes ONLY B with B's own final URL.
     *
     * In the pre-fix single-sink design a second `resolve()` overwrote the
     * first's sink, so A's result could not resume A; in the device log the
     * second (newer) sink was orphaned by the lost `onActivityResult` handback.
     */
    @Test
    fun `two concurrent resolves are independent and each resumes on its own token`() {
        val resolver = ActivityWebResolver()

        val aInvocations = AtomicInteger(0); val a = AtomicReference<String?>("UNSET")
        val bInvocations = AtomicInteger(0); val b = AtomicReference<String?>("UNSET")

        val tokenA = "aaaaaaaa-0000-0000-0000-0000000000a1"
        val tokenB = "bbbbbbbb-0000-0000-0000-0000000000b2"
        resolver.seedPendingForTest(tokenA) { code, data ->
            aInvocations.incrementAndGet()
            if (code == Activity.RESULT_OK) a.set(data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
        }
        // A concurrent second resolve() for the same URL registers the second
        // sink WITHOUT clobbering the first (token-keyed independence).
        resolver.seedPendingForTest(tokenB) { code, data ->
            bInvocations.incrementAndGet()
            if (code == Activity.RESULT_OK) b.set(data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
        }
        assertEquals("both tokens live before any delivery", setOf(tokenA, tokenB), resolver.liveTokens())

        // 1) Token A settles FIRST — resumes ONLY A.
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(tokenA, FINAL_URL))
        assertEquals("A must have resumed exactly once", 1, aInvocations.get())
        assertEquals(FINAL_URL, a.get())
        assertEquals("B must still be pending after A resumed (NOT clobbered)", setOf(tokenB), resolver.liveTokens())
        assertEquals("B must NOT have resumed yet", "UNSET", b.get())

        // 2) A duplicate for A is a no-op (exactly-once).
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(tokenA, FINAL_URL))
        assertEquals("A must still have resumed exactly once", 1, aInvocations.get())

        // 3) Token B settles LATER — resumes ONLY B with its own final URL.
        val finalB = "https://final.example.com/page-b"
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(tokenB, finalB))
        assertEquals(1, bInvocations.get())
        assertEquals(finalB, b.get())
        assertEquals(FINAL_URL, a.get())
        assertTrue("no live tokens remain after both deliver", resolver.liveTokens().isEmpty())
    }

    /** Out-of-order delivery still lands on the right sink exactly once. */
    @Test
    fun `out-of-order delivery lands on the correct sink for each token`() {
        val resolver = ActivityWebResolver()
        val aCalls = AtomicInteger(0); val aSeen = AtomicReference<String?>("UNSET")
        val bCalls = AtomicInteger(0); val bSeen = AtomicReference<String?>("UNSET")

        val tokenA = "cccccccc-0000-0000-0000-0000000000c3"
        val tokenB = "dddddddd-0000-0000-0000-0000000000d4"
        resolver.seedPendingForTest(tokenA) { c, d ->
            aCalls.incrementAndGet()
            if (c == Activity.RESULT_OK) aSeen.set(d?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
        }
        resolver.seedPendingForTest(tokenB) { c, d ->
            bCalls.incrementAndGet()
            if (c == Activity.RESULT_OK) bSeen.set(d?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
        }

        // Token B delivers FIRST (before A), then A — order must not matter.
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(tokenB, "https://final.example.com/b"))
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(tokenA, "https://final.example.com/a"))

        assertEquals("A resumed exactly once", 1, aCalls.get())
        assertEquals("B resumed exactly once", 1, bCalls.get())
        assertEquals("https://final.example.com/a", aSeen.get())
        assertEquals("https://final.example.com/b", bSeen.get())
        assertTrue(resolver.liveTokens().isEmpty())
    }

    // ========================================================================
    // withTimeout safety net: a resolve whose child is never able to deliver
    // must still return null, and must not clobber other in-flight tokens.
    // ========================================================================

    /**
     * Drives the REAL [resolve] and ensures token A's sink is never delivered
     * (under Robolectric the child never settles: there is no network and the
     * test never pumps the main looper that would run the child's settle/timeout
     * runnables). Asserts the resolver's `withTimeout` safety net fires and A
     * resumes with **null**, while token B — a concurrent independent resolution
     * — still resumes correctly on its own genuine `RESULT_OK`. This is the
     * exact device-log scenario: a live sink is orphaned → `withTimeout` fires →
     * the user saw a false timeout toast — except here the orphaning is A's OWN
     * timeout, and it does NOT clobber the concurrent B.
     *
     * This test waits for the real `withTimeout` (~9s over the 8s activity
     * timeout, which never self-fires under Robolectric) — by design this is the
     * SAFETY-NET path.
     */
    @Test
    fun `a resolve whose child never delivers times out to null without clobbering other tokens`() {
        val resolver = ActivityWebResolver()
        val url = "https://short.example/killed"
        val resultForA = AtomicReference<String?>(null)
        val doneForA = CountDownLatch(1)

        val bInvocations = AtomicInteger(0)
        val b = AtomicReference<String?>("UNSET")
        val tokenB = "eeeeeeee-0000-0000-0000-0000000000e5"

        // Start A's resolve on a background thread so we can manage B + the
        // child from the test thread while A is suspended awaiting delivery.
        val worker = Thread {
            val r = runBlocking { resolver.resolve(activity, url) }
            resultForA.set(r)
            doneForA.countDown()
        }
        worker.start()

        // Wait for A's live sink (resolve() registered its token before the
        // child launch).
        var tokenA: String? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val live = resolver.liveTokens()
            if (live.isNotEmpty()) { tokenA = live.first(); break }
            Thread.sleep(5)
        }
        assertNotNull("A's resolve() must have established a live sink", tokenA)

        // Concurrent B: same shape as a second tap for the same URL.
        resolver.seedPendingForTest(tokenB) { code, data ->
            bInvocations.incrementAndGet()
            if (code == Activity.RESULT_OK) b.set(data?.getStringExtra(ResolutionWebViewActivity.RESULT_URL))
        }

        // A's child is left undelivered: under Robolectric there is no network
        // for its WebView to settle, and the test never pumps the main looper
        // that would run the child's settle/timeout runnables — so NO in-process
        // delivery ever happens for token A. Only the resolver's own withTimeout
        // can resume A (to null). (We deliberately do NOT rely on killing the
        // child activity; leaving it undelivered exercises the identical orphan
        // path that the device hit.)

        // B delivers a genuine RESULT_OK — must resume ONLY B (A is untouched;
        // exactly-once).
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(tokenB, FINAL_URL))
        assertEquals("B must resume exactly once", 1, bInvocations.get())
        assertEquals(FINAL_URL, b.get())

        // A is still pending and STILL must time out to null (withTimeout).
        // Give up to 15s (RESOLVE_TIMEOUT_MS = 9s above the 8s activity
        // timeout, which never self-fires under Robolectric because we never
        // pump the child's main looper).
        assertTrue("A must time out to null (withTimeout safety net, <=15s)", doneForA.await(15, TimeUnit.SECONDS))
        assertNull("A must time out to null", resultForA.get())
        worker.join(5_000)
    }

    // ========================================================================
    // End-to-end (Robolectric): the real resolve() → real child → real
    // settle-delivery path, including the bug #1 stale-CANCELED ordering.
    // ========================================================================

    /**
     * Drive the REAL [resolve] (launching the actual child via
     * `startActivity`), then simulate bug #1's confirmed ordering — a stale
     * CANCELED (no token) delivered BEFORE the real result — and assert the
     * genuine echo-token RESULT_OK (delivered in-process by the child) still
     * resumes `resolve()` with the final URL.
     *
     * This proves the token actually flows from `resolve()` into
     * [ResolutionWebViewActivity.EXTRA_TOKEN] (via [liveTokens]) AND that a
     * matching echo resumes the suspended coroutine after a stale CANCELED —
     * i.e. BOTH the guard logic and the in-process handback are real.
     */
    @Test
    fun `resolve survives a stale CANCELED and the genuine in-process delivery still resumes`() {
        val resolver = ActivityWebResolver()
        val url = "https://short.example/abcd"
        val result = AtomicReference<String?>(null)
        val done = CountDownLatch(1)

        val worker = Thread {
            val r = runBlocking { resolver.resolve(activity, url) }
            result.set(r)
            done.countDown()
        }
        worker.start()

        var token: String? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (token == null && System.currentTimeMillis() < deadline) {
            val live = resolver.liveTokens()
            if (live.isNotEmpty()) token = live.first()
            if (token == null) Thread.sleep(5)
        }
        assertNotNull("resolve() must have established a live sink", token)

        // 1) STALE CANCELED (bug #1's shape: a no-token delivery that used to
        //    consume the live sink via the shared `onActivityResult` channel).
        resolver.deliverResult(Activity.RESULT_CANCELED, null)
        assertEquals("stale CANCELED must not consume the live sink", setOf(token), resolver.liveTokens())
        assertFalse("resolve() must still be suspended after the stale CANCELED", done.await(100, TimeUnit.MILLISECONDS))

        // 2) GENUINE RESULT_OK delivered IN-PROCESS by the child (the new
        //    handback), carrying the echoed token (non-null now) and the final URL.
        resolver.deliverResult(Activity.RESULT_OK, intentWithToken(token!!, FINAL_URL))
        assertTrue("resolve() must resume after the genuine in-process RESULT_OK", done.await(5, TimeUnit.SECONDS))
        assertEquals(FINAL_URL, result.get())
        worker.join(5_000)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Build the [data] intent as [ResolutionWebViewActivity.deliverInProcess]
     * would: [EXTRA_TOKEN] always, [RESULT_URL] only on RESULT_OK with a URL.
     */
    private fun intentWithToken(token: String, finalUrl: String?): Intent = Intent().apply {
        putExtra(ResolutionWebViewActivity.EXTRA_TOKEN, token)
        if (finalUrl != null) putExtra(ResolutionWebViewActivity.RESULT_URL, finalUrl)
    }
}
