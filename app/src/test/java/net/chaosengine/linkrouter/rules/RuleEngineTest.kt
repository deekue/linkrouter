package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private fun rule(
        id: Long = 1,
        pattern: String,
        matchType: MatchType,
        enabled: Boolean = true,
        priority: Int = 0,
    ) = Rule(
        id = id,
        pattern = pattern,
        matchType = matchType,
        targetPackage = "org.example.browser",
        enabled = enabled,
        priority = priority,
    )

    // --- normalize ---

    @Test
    fun normalize_upgrades_http_to_https_and_strips_noise() {
        val p = RuleEngine.normalize("http://User:Pass@Example.COM:8080/a/b?x=1#frag")
        assertEquals("https", p?.scheme)
        assertEquals("example.com", p?.host)
        assertEquals(8080, p?.port)
        assertEquals("/a/b", p?.path)
        assertEquals("x=1", p?.query)
    }

    @Test
    fun normalize_root_path_when_no_path() {
        val p = RuleEngine.normalize("https://example.com")
        assertEquals("/", p?.path)
        assertNull(p?.query)
    }

    @Test
    fun normalize_rejects_non_web_schemes() {
        assertNull(RuleEngine.normalize("mailto:a@b.c"))
        assertNull(RuleEngine.normalize("not a url"))
    }

    @Test
    fun normalize_host_is_lowercase_and_idn() {
        assertEquals("example.com", RuleEngine.normalize("https://EXAMPLE.com/x")?.host)
    }

    // --- scoreRule ---

    @Test
    fun exact_host_scores_30() {
        val r = rule(pattern = "example.com", matchType = MatchType.EXACT_HOST)
        assertEquals(30, RuleEngine.scoreRule(r, RuleEngine.normalize("https://example.com/anything")!!))
        assertNull(RuleEngine.scoreRule(r, RuleEngine.normalize("https://other.com/")!!))
    }

    @Test
    fun path_prefix_scores_40() {
        val r = rule(pattern = "example.com/docs", matchType = MatchType.PATH_PREFIX)
        assertEquals(40, RuleEngine.scoreRule(r, RuleEngine.normalize("https://example.com/docs/guide")!!))
        assertNull(RuleEngine.scoreRule(r, RuleEngine.normalize("https://example.com/other")!!))
        assertNull(RuleEngine.scoreRule(r, RuleEngine.normalize("https://other.com/docs")!!))
    }

    @Test
    fun subdomain_scores_20_but_not_base() {
        val r = rule(pattern = "*.example.com", matchType = MatchType.SUBDOMAIN)
        assertEquals(20, RuleEngine.scoreRule(r, RuleEngine.normalize("https://sub.example.com/")!!))
        assertNull(RuleEngine.scoreRule(r, RuleEngine.normalize("https://example.com/")!!))
        assertNull(RuleEngine.scoreRule(r, RuleEngine.normalize("https://sub.other.com/")!!))
    }

    @Test
    fun regex_scores_10_full_match() {
        val r = rule(pattern = "example\\.com/old-.*", matchType = MatchType.REGEX)
        assertEquals(10, RuleEngine.scoreRule(r, RuleEngine.normalize("https://example.com/old-page")!!))
        assertNull(RuleEngine.scoreRule(r, RuleEngine.normalize("https://example.com/new-page")!!))
    }

    // --- resolve ---

    @Test
    fun resolve_empty_rules_returns_null() {
        assertNull(RuleEngine.resolve(emptyList(), "https://example.com/"))
    }

    @Test
    fun resolve_non_web_url_returns_null() {
        val r = rule(pattern = "example.com", matchType = MatchType.EXACT_HOST)
        assertNull(RuleEngine.resolve(listOf(r), "mailto:a@b.c"))
    }

    @Test
    fun resolve_higher_score_wins() {
        val exact = rule(id = 1, pattern = "example.com", matchType = MatchType.EXACT_HOST)
        val prefix = rule(id = 2, pattern = "example.com/docs", matchType = MatchType.PATH_PREFIX)
        val winner = RuleEngine.resolve(listOf(exact, prefix), "https://example.com/docs/x")
        assertEquals(prefix, winner)
    }

    @Test
    fun resolve_tie_broken_by_higher_priority() {
        // Both SUBDOMAIN rules score 20 for x.example.com — priority decides.
        val a = rule(id = 1, pattern = "*.example.com", matchType = MatchType.SUBDOMAIN, priority = 1)
        val b = rule(id = 2, pattern = "*.example.com", matchType = MatchType.SUBDOMAIN, priority = 9)
        assertEquals(
            20, RuleEngine.scoreRule(a, RuleEngine.normalize("https://x.example.com/")!!)
        )
        assertEquals(
            20, RuleEngine.scoreRule(b, RuleEngine.normalize("https://x.example.com/")!!)
        )
        val winner = RuleEngine.resolve(listOf(a, b), "https://x.example.com/")
        assertEquals(b, winner)
    }

    @Test
    fun resolve_disabled_rule_skipped() {
        val off = rule(pattern = "example.com", matchType = MatchType.EXACT_HOST, enabled = false)
        assertNull(RuleEngine.resolve(listOf(off), "https://example.com/"))
    }

    // --- loop guard ---

    @Test
    fun loop_guard_triggers_on_flag_or_param() {
        assertTrue(RuleEngine.isLoopGuard(true, "https://example.com/"))
        assertTrue(RuleEngine.isLoopGuard(false, "https://example.com/?__lr=1"))
        assertFalse(RuleEngine.isLoopGuard(false, "https://example.com/"))
    }

    // --- unwrapRedirect ---

    @Test
    fun unwrapRedirect_decodes_url_encoded_q_param() {
        // Normal case: the inner URL is percent-encoded in the q= param.
        val wrapper = "https://www.google.com/url?q=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fr%2F1%2F&source=chat&ust=123&usg=abc"
        assertEquals(
            "https://www.facebook.com/share/r/1/",
            RuleEngine.unwrapRedirect(wrapper),
        )
    }

    @Test
    fun unwrapRedirect_handles_plain_q_param() {
        // The q= value may arrive un-encoded (as in the on-device log).
        val wrapper = "https://www.google.com/url?q=https://www.facebook.com/share/r/1DBHjzmGnn/&source=chat&ust=1788259284891000&usg=AOvVaw3ZG1z40x7LIEkE5UsN3hvS"
        assertEquals(
            "https://www.facebook.com/share/r/1DBHjzmGnn/",
            RuleEngine.unwrapRedirect(wrapper),
        )
    }

    @Test
    fun unwrapRedirect_supports_bare_google_com_host() {
        val wrapper = "http://google.com/url?q=https%3A%2F%2Fexample.com%2F"
        assertEquals("https://example.com/", RuleEngine.unwrapRedirect(wrapper))
    }

    @Test
    fun unwrapRedirect_returns_null_for_non_google_host() {
        assertNull(RuleEngine.unwrapRedirect("https://example.com/url?q=https%3A%2F%2Ffacebook.com%2F"))
        assertNull(RuleEngine.unwrapRedirect("https://www.facebook.com/?q=1"))
    }

    @Test
    fun unwrapRedirect_returns_null_without_q_param() {
        assertNull(RuleEngine.unwrapRedirect("https://www.google.com/search?q=hi"))
        assertNull(RuleEngine.unwrapRedirect("https://www.google.com/url"))
    }

    @Test
    fun unwrapRedirect_returns_null_when_q_is_not_web_url() {
        assertNull(RuleEngine.unwrapRedirect("https://www.google.com/url?q=mailto%3Aa%40b.c"))
        assertNull(RuleEngine.unwrapRedirect("https://www.google.com/url?q=ftp%3A%2F%2Fexample.com%2F"))
    }

    @Test
    fun facebook_subdomain_rule_matches_google_redirect_wrapper() {
        // The on-device scenario: a Google redirect wrapper should still match
        // a rule written against the real destination.
        val rule = rule(pattern = "*.facebook.com", matchType = MatchType.SUBDOMAIN)
        val wrapper = "https://www.google.com/url?q=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fr%2F1%2F&source=chat"
        val matchParsed = RuleEngine.normalize(
            RuleEngine.unwrapRedirect(wrapper) ?: wrapper
        )!!
        assertEquals(20, RuleEngine.scoreRule(rule, matchParsed))
    }
}
