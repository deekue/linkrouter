package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectResolverTest {

    private fun googleFormat() = RedirectFormat(
        id = RedirectFormat.BUILT_IN_ID,
        name = "Google",
        pattern = "google.com/url",
        matchType = MatchType.PATH_PREFIX,
        extractType = ExtractType.QUERY_PARAM,
        extractTarget = "q",
        enabled = true,
        priority = 1000,
        isBuiltIn = true,
    )

    @Test
    fun resolve_google_wrapper_extracts_q_param() {
        assertEquals(
            "https://example.com/page",
            RedirectResolver.resolve(
                "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage",
                listOf(googleFormat()),
            ),
        )
    }

    @Test
    fun resolve_www_tolerant() {
        assertEquals(
            "https://example.com/x",
            RedirectResolver.resolve(
                "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fx",
                listOf(googleFormat()),
            ),
        )
        assertEquals(
            "https://example.com/y",
            RedirectResolver.resolve(
                "https://google.com/url?q=https%3A%2F%2Fexample.com%2Fy",
                listOf(googleFormat()),
            ),
        )
    }

    @Test
    fun resolve_returns_null_when_no_format_matches() {
        assertNull(
            RedirectResolver.resolve(
                "https://other.com/foo?q=https%3A%2F%2Fexample.com",
                listOf(googleFormat()),
            ),
        )
    }

    @Test
    fun resolve_returns_null_when_param_missing() {
        assertNull(
            RedirectResolver.resolve(
                "https://google.com/url?x=1",
                listOf(googleFormat()),
            ),
        )
    }

    @Test
    fun resolve_returns_null_when_dest_not_http() {
        assertNull(
            RedirectResolver.resolve(
                "https://google.com/url?q=evil",
                listOf(googleFormat()),
            ),
        )
    }

    @Test
    fun resolve_base64_param() {
        val dest = "https://example.com/x"
        val b64 = java.util.Base64.getEncoder().encodeToString(dest.toByteArray())
        val fmt = RedirectFormat(
            id = 0,
            name = "B64",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.BASE64_PARAM,
            extractTarget = "d",
            enabled = true,
        )
        // BASE64_PARAM reads the value from the query string, so it must be a `?d=` param.
        assertEquals(dest, RedirectResolver.resolve("https://r.link/?d=$b64", listOf(fmt)))
    }

    @Test
    fun resolve_path_regex() {
        val fmt = RedirectFormat(
            id = 1,
            name = "PR",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.PATH_REGEX,
            extractTarget = "/go/(.*)",
            enabled = true,
        )
        assertEquals(
            "https://example.com",
            RedirectResolver.resolve("https://r.link/go/https://example.com", listOf(fmt)),
        )
    }

    @Test
    fun resolve_full_url_regex() {
        // regexCapture uses `Pattern.matcher(input).matches()` (FULL match), so the
        // target must describe the entire raw url, not just a substring of it.
        val target = """https?://r\.link\?redirect=1&url=(https?://[^&]+)"""
        val fmt = RedirectFormat(
            id = 1,
            name = "FU",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.FULL_URL_REGEX,
            extractTarget = target,
            enabled = true,
        )
        assertEquals(
            "https://example.com",
            RedirectResolver.resolve("https://r.link?redirect=1&url=https://example.com", listOf(fmt)),
        )
    }

    @Test
    fun resolve_priority_order_first_valid_wins() {
        val url = "https://r.link?a=https://example.com/a&b=https://example.com/b"
        val fmtA = RedirectFormat(
            id = 1,
            name = "A",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.QUERY_PARAM,
            extractTarget = "a",
            enabled = true,
        )
        val fmtB = RedirectFormat(
            id = 2,
            name = "B",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.QUERY_PARAM,
            extractTarget = "b",
            enabled = true,
        )
        assertEquals("https://example.com/a", RedirectResolver.resolve(url, listOf(fmtA, fmtB)))
        assertEquals("https://example.com/b", RedirectResolver.resolve(url, listOf(fmtB, fmtA)))
    }

    @Test
    fun resolve_disabled_format_skipped() {
        val fmt = RedirectFormat(
            id = 1,
            name = "Dis",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.QUERY_PARAM,
            extractTarget = "a",
            enabled = false,
        )
        assertNull(RedirectResolver.resolve("https://r.link?a=https://example.com", listOf(fmt)))
    }

    @Test
    fun resolve_never_throws_on_bad_regex() {
        val fmt = RedirectFormat(
            id = 1,
            name = "Bad",
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.PATH_REGEX,
            extractTarget = "(unclosed",
            enabled = true,
        )
        // Must not throw; an invalid regex simply yields no destination.
        val result = RedirectResolver.resolve("https://r.link/foo", listOf(fmt))
        assertNull(result)
    }

    // --- launchDestination (what actually gets launched) ---

    private val googleWrapper = "https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage"

    @Test
    fun launchDestination_flag_on_returns_extracted_destination() {
        val fmt = googleFormat().copy(openRealDestination = true)
        assertEquals("https://example.com/page", RedirectResolver.launchDestination(googleWrapper, listOf(fmt)))
    }

    @Test
    fun launchDestination_flag_off_returns_original_wrapper() {
        val fmt = googleFormat().copy(openRealDestination = false)
        assertEquals(googleWrapper, RedirectResolver.launchDestination(googleWrapper, listOf(fmt)))
    }

    @Test
    fun launchDestination_no_match_returns_original() {
        assertEquals(
            "https://other.com/foo?q=x",
            RedirectResolver.launchDestination("https://other.com/foo?q=x", listOf(googleFormat())),
        )
    }

    @Test
    fun launchDestination_winners_flag_decides() {
        val url = "https://r.link?a=https://example.com/a&b=https://example.com/b"
        fun fmt(id: Long, param: String, flag: Boolean) = RedirectFormat(
            id = id,
            name = param,
            pattern = "r.link",
            matchType = MatchType.EXACT_HOST,
            extractType = ExtractType.QUERY_PARAM,
            extractTarget = param,
            enabled = true,
            openRealDestination = flag,
        )
        // First (winning) format has the flag off → wrapper, even if the lower one is on.
        assertEquals(url, RedirectResolver.launchDestination(url, listOf(fmt(1, "a", false), fmt(2, "b", true))))
        // First (winning) format has the flag on → extracted destination.
        assertEquals(
            "https://example.com/a",
            RedirectResolver.launchDestination(url, listOf(fmt(1, "a", true), fmt(2, "b", false))),
        )
    }
}
