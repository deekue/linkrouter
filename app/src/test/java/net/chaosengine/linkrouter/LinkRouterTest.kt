package net.chaosengine.linkrouter

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit coverage for [LinkRouter.sharedTextCandidates], including the
 * URI-typed `android.intent.extra.TEXT` edge case (some senders store a
 * `Uri`, not a `String`, in the extra — the String getter and its inverse
 * must both be tolerated without throwing).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LinkRouterTest {

    @Test
    fun stringExtraText_isReturnedAsFirstCandidate() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, "https://example.com/a")

        val candidates = LinkRouter.sharedTextCandidates(intent)

        assertEquals(listOf("https://example.com/a"), candidates)
    }

    @Test
    fun uriExtraText_isReturnedAsCandidate() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, Uri.parse("https://example.com/link"))

        val candidates = LinkRouter.sharedTextCandidates(intent)

        assertTrue(candidates.contains("https://example.com/link"))
    }

    @Test
    fun charSequenceArrayExtraText_isReturnedAsCandidates() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_TEXT, arrayOf("https://example.com/a", "https://example.com/b"))

        val candidates = LinkRouter.sharedTextCandidates(intent)

        assertEquals(listOf("https://example.com/a", "https://example.com/b"), candidates)
    }

    @Test
    fun stringsExtra_isReturnedAsCandidates() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(LinkRouter.EXTRA_STRINGS, arrayOf("https://example.com/s1", "https://example.com/s2"))

        val candidates = LinkRouter.sharedTextCandidates(intent)

        assertEquals(listOf("https://example.com/s1", "https://example.com/s2"), candidates)
    }

    @Test
    fun noExtraText_returnsNoCandidates() {
        val intent = Intent(Intent.ACTION_SEND)

        assertTrue(LinkRouter.sharedTextCandidates(intent).isEmpty())
    }

    @Test
    fun nullIntent_returnsEmptyList() {
        assertTrue(LinkRouter.sharedTextCandidates(null).isEmpty())
    }
}
