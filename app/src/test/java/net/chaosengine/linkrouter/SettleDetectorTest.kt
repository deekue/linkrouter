package net.chaosengine.linkrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SettleDetector] (M7, DESIGN.md 6 / D9). No Android, no
 * Robolectric, no real WebView — the settle decision is deterministic via an
 * injected clock.
 */
class SettleDetectorTest {

    private companion object {
        const val GRACE = 1000L
        const val T0 = 5_000L
    }

    /** A controllable clock: starts at [T0]; [advance] moves it forward. */
    private class FakeClock(var now: Long = T0) {
        fun advance(ms: Long) { now += ms }
    }

    private fun detector(clock: FakeClock, grace: Long = GRACE) =
        SettleDetector(graceMillis = grace, clock = { clock.now })

    @Test
    fun `settles after a page finishes and stays put for the grace period`() {
        val clock = FakeClock()
        val d = detector(clock)

        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("https://final.example/page")
        // Immediately after finishing, the grace window has not elapsed.
        assertNull(d.settledUrl)
        assertFalse(d.isSettled)

        clock.advance(GRACE)
        assertEquals("https://final.example/page", d.settledUrl)
        assertTrue(d.isSettled)
    }

    @Test
    fun `does not settle when a new navigation begins within the grace window`() {
        val clock = FakeClock()
        val d = detector(clock)

        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("https://mid.example/1")
        clock.advance(GRACE / 2)
        // A second navigation starts before the grace window closes.
        d.onPageStarted("https://mid.example/2")

        // Still not settled even well past the grace window (navigation moved on).
        clock.advance(GRACE * 10)
        assertNull(d.settledUrl)
        assertFalse(d.isSettled)
    }

    @Test
    fun `settles on the last stable page after a chain of navigations`() {
        val clock = FakeClock()
        val d = detector(clock)

        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("https://a.example/1")
        d.onPageStarted("https://b.example/2")
        d.onPageFinished("https://b.example/2")

        clock.advance(GRACE)
        assertEquals("https://b.example/2", d.settledUrl)
    }

    @Test
    fun `does not settle on a non http scheme`() {
        val clock = FakeClock()
        val d = detector(clock)

        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("about:blank")
        clock.advance(GRACE * 10)
        assertNull(d.settledUrl)
        assertFalse(d.isSettled)
    }

    @Test
    fun `does not settle on a custom (non web) scheme`() {
        val clock = FakeClock()
        val d = detector(clock)

        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("tel:1234567890")
        clock.advance(GRACE * 10)
        assertNull(d.settledUrl)
    }

    @Test
    fun `reset clears settled state`() {
        val clock = FakeClock()
        val d = detector(clock)

        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("https://final.example/page")
        clock.advance(GRACE)
        assertTrue(d.isSettled)

        d.reset()
        assertFalse(d.isSettled)
        assertNull(d.settledUrl)
    }

    @Test
    fun `is deterministic via injected clock (no wall clock)`() {
        // Same event sequence, identical injected clock → identical outcome.
        fun run(): String? {
            val clock = FakeClock()
            val d = detector(clock)
            d.onPageStarted("https://t.co/abc")
            d.onPageFinished("https://final.example/page")
            clock.advance(GRACE)
            return d.settledUrl
        }
        assertEquals(run(), run())
        assertEquals("https://final.example/page", run())
    }

    @Test
    fun `not settled before any page has finished`() {
        val clock = FakeClock()
        val d = detector(clock)
        d.onPageStarted("https://t.co/abc")
        clock.advance(GRACE * 10)
        assertNull(d.settledUrl)
    }

    @Test
    fun `boundary settles exactly at the grace edge`() {
        val clock = FakeClock()
        val d = detector(clock)
        d.onPageStarted("https://t.co/abc")
        d.onPageFinished("https://final.example/page")
        clock.advance(GRACE - 1)
        assertNull("just before grace is not settled", d.settledUrl)
        clock.advance(1)
        assertEquals("at grace is settled", "https://final.example/page", d.settledUrl)
    }
}
