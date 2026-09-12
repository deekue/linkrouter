package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [HostRewriter] (Host Rewrites P0+P1): the canonical
 * HOST_SWAP examples verbatim, the canonical PATH_PREFIX_REWRITE examples
 * (example 3, www-preserved prefix, empty path), the preservation matrix —
 * query/fragment fidelity, trailing slash, empty path, non-implicit port
 * carry-over, userinfo drop, IDN normalization — and the never-touch
 * guarantees (non-web URL, non-match, disabled rule, malformed rule). The
 * PATH_PREFIX_REWRITE without preserveHostInPath still leaves the path
 * unchanged. Mirrors [QueryParamStripperTest] style.
 */
class HostRewriterTest {

    private fun rewrite(
        matchHost: String = "x.com",
        matchType: RewriteMatchType = RewriteMatchType.EXACT_HOST,
        kind: RewriteKind = RewriteKind.HOST_SWAP,
        targetHost: String = "twitter.com",
        preserveHostInPath: Boolean = false,
        enabled: Boolean = true,
        isBuiltIn: Boolean = false,
        id: Long = 0L,
    ) = HostRewrite(
        id = id,
        matchHost = matchHost,
        matchType = matchType,
        kind = kind,
        targetHost = targetHost,
        preserveHostInPath = preserveHostInPath,
        enabled = enabled,
        priority = 0,
        isBuiltIn = isBuiltIn,
    )

    // --- canonical examples -----------------------------------------------

    @Test
    fun example1_x_com_to_twitter_com_exact() {
        val url = "https://x.com/jack/status/123?f=tw"
        assertEquals(
            "https://twitter.com/jack/status/123?f=tw",
            HostRewriter.rewrite(url, listOf(rewrite(targetHost = "twitter.com"))),
        )
    }

    @Test
    fun example2_www_tiktok_to_www_seetiktok_strict_www() {
        val url = "https://www.tiktok.com/@dude"
        val rule = rewrite(
            matchHost = "www.tiktok.com",
            matchType = RewriteMatchType.EXACT_WWW_HOST,
            targetHost = "www.seetiktok.com",
        )
        assertEquals(
            "https://www.seetiktok.com/@dude",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    // --- match-type discrimination ----------------------------------------

    @Test
    fun exact_host_is_www_tolerant_apex_and_www_both_match() {
        val rule = rewrite(matchHost = "x.com", targetHost = "twitter.com")
        assertEquals(
            "https://twitter.com/a",
            HostRewriter.rewrite("https://x.com/a", listOf(rule)),
        )
        assertEquals(
            "https://twitter.com/a",
            HostRewriter.rewrite("https://www.x.com/a", listOf(rule)),
        )
    }

    @Test
    fun exact_www_host_rejects_apex_when_www_is_significant() {
        val rule = rewrite(
            matchHost = "www.tiktok.com",
            matchType = RewriteMatchType.EXACT_WWW_HOST,
            targetHost = "www.seetiktok.com",
        )
        // Apex (no www.) does NOT match a strict www rule.
        assertNull(HostRewriter.applyOne("https://tiktok.com/@dude", rule))
        assertEquals(
            "https://tiktok.com/@dude",
            HostRewriter.rewrite("https://tiktok.com/@dude", listOf(rule)),
        )
    }

    @Test
    fun exact_www_rule_does_not_swallow_www_when_matching_apex() {
        // §8.7 (symmetric half): an EXACT_WWW_HOST rule stored for the APEX
        // (`tiktok.com`) is strict — a `www.tiktok.com` URL must NOT match it.
        val apexRule = rewrite(
            matchHost = "tiktok.com",
            matchType = RewriteMatchType.EXACT_WWW_HOST,
            targetHost = "archived.example",
        )
        assertNull(HostRewriter.applyOne("https://www.tiktok.com/@dude", apexRule))
        assertEquals(
            "https://www.tiktok.com/@dude",
            HostRewriter.rewrite("https://www.tiktok.com/@dude", listOf(apexRule)),
        )
        // …while the apex URL itself does match (the www. is significant and NOT
        // stripped for EXACT_WWW_HOST — in either direction).
        assertEquals(
            "https://archived.example/@dude",
            HostRewriter.rewrite("https://tiktok.com/@dude", listOf(apexRule)),
        )
    }

    @Test
    fun subdomain_matches_proper_subdomains_but_not_the_base() {
        val rule = rewrite(
            matchHost = "tiktok.com",
            matchType = RewriteMatchType.SUBDOMAIN,
            targetHost = "tiktok.com",
        )
        assertTrue(HostRewriter.matchesHost("v16-webapp2.tiktok.com", rule))
        assertTrue(HostRewriter.matchesHost("a.b.tiktok.com", rule))
        // The base host itself is not a *proper* subdomain.
        assertFalse(HostRewriter.matchesHost("tiktok.com", rule))
        // A lookalike suffix is not a subdomain either.
        assertFalse(HostRewriter.matchesHost("tiktok.com.evil.com", rule))
    }

    // --- never-touch guarantees ------------------------------------------

    @Test
    fun no_matching_rule_is_unchanged() {
        val url = "https://facebook.com/a"
        assertEquals(url, HostRewriter.rewrite(url, listOf(rewrite(targetHost = "twitter.com"))))
    }

    @Test
    fun non_web_scheme_is_unchanged() {
        val url = "mailto:someone@example.com?sub=x"
        assertEquals(url, HostRewriter.rewrite(url, listOf(rewrite())))
    }

    @Test
    fun empty_rule_list_is_unchanged() {
        val url = "https://x.com/a?b=1"
        assertEquals(url, HostRewriter.rewrite(url, emptyList()))
    }

    @Test
    fun disabled_rule_is_ignored() {
        val url = "https://x.com/a"
        assertEquals(url, HostRewriter.rewrite(url, listOf(rewrite(enabled = false))))
    }

    @Test
    fun relative_or_schemeless_url_is_unchanged() {
        val url = "/path/only"
        assertEquals(url, HostRewriter.rewrite(url, listOf(rewrite())))
    }

    // --- PATH_PREFIX_REWRITE: canonical examples --------------------------

    @Test
    fun example3_nytimes_to_archive_prefix_host_in_path() {
        val url = "https://nytimes.com/blahblah"
        val rule = rewrite(
            matchHost = "nytimes.com",
            matchType = RewriteMatchType.EXACT_HOST,
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = true,
        )
        assertEquals(
            "https://archive.md/nytimes.com/blahblah",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    @Test
    fun example3_www_host_is_preserved_in_prefix() {
        val url = "https://www.nytimes.com/blah"
        val rule = rewrite(
            matchHost = "nytimes.com",
            matchType = RewriteMatchType.EXACT_HOST, // www-tolerant
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = true,
        )
        // The prefix is the LITERAL incoming host — www. is NOT stripped.
        assertEquals(
            "https://archive.md/www.nytimes.com/blah",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    @Test
    fun path_prefix_rewrite_empty_path_becomes_prefix_plus_slash() {
        val url = "https://nytimes.com"
        val rule = rewrite(
            matchHost = "nytimes.com",
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = true,
        )
        // Documented/acceptable: empty path normalizes to '/' → archive.md/nytimes.com/
        assertEquals(
            "https://archive.md/nytimes.com/",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    @Test
    fun path_prefix_rewrite_preserves_query_fragment_and_encoding() {
        val url = "https://nytimes.com/blah?a=1%202&b=3#frag"
        val rule = rewrite(
            matchHost = "nytimes.com",
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = true,
        )
        assertEquals(
            "https://archive.md/nytimes.com/blah?a=1%202&b=3#frag",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    @Test
    fun path_prefix_rewrite_carries_over_non_implicit_port_and_drops_userinfo() {
        val url = "https://user:pass@nytimes.com:8080/blah"
        val rule = rewrite(
            matchHost = "nytimes.com",
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = true,
        )
        // Port (8080) carried over to target; userinfo dropped; host prefix is the
        // bare literal host (userinfo/port never part of the prefix).
        assertEquals(
            "https://archive.md:8080/nytimes.com/blah",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    @Test
    fun path_prefix_rewrite_without_preserve_flag_leaves_path_unchanged() {
        val url = "https://nytimes.com/blah"
        val rule = rewrite(
            matchHost = "nytimes.com",
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = false,
        )
        // Host still swaps; the path is rewritten (host-only match) but NOT prefixed.
        assertEquals(
            "https://archive.md/blah",
            HostRewriter.rewrite(url, listOf(rule)),
        )
    }

    @Test
    fun path_prefix_rewrite_no_match_is_unchanged() {
        val url = "https://other.com/blah"
        val rule = rewrite(
            matchHost = "nytimes.com",
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
            preserveHostInPath = true,
        )
        assertNull(HostRewriter.applyOne(url, rule))
        assertEquals(url, HostRewriter.rewrite(url, listOf(rule)))
    }

    // --- rebuild fidelity -------------------------------------------------

    @Test
    fun query_and_fragment_are_preserved() {
        val url = "https://x.com/p?q=1&f=2#frag"
        assertEquals(
            "https://twitter.com/p?q=1&f=2#frag",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun raw_query_encoding_is_preserved() {
        val url = "https://x.com/p?keep=a%20b&f=1"
        assertEquals(
            "https://twitter.com/p?keep=a%20b&f=1",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun valueless_query_params_and_fragment_are_kept_verbatim() {
        // The `q` param carries NO value (valueless pair) and the fragment is
        // empty — all must survive the host swap byte-for-byte.
        val url = "https://x.com/p?q&f=1#"
        assertEquals(
            "https://twitter.com/p?q&f=1#",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun trailing_slash_path_is_preserved_verbatim() {
        val url = "http://x.com/"
        assertEquals(
            "http://twitter.com/",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun empty_path_is_normalized_to_slash() {
        val url = "https://x.com?b=1"
        assertEquals(
            "https://twitter.com/?b=1",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun non_implicit_port_is_carried_over_to_target_q3() {
        val url = "https://x.com:8080/a"
        assertEquals(
            "https://twitter.com:8080/a",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun implicit_https_port_443_is_not_carried_over() {
        val url = "https://x.com:443/a"
        assertEquals(
            "https://twitter.com/a",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun http_scheme_is_preserved_in_output() {
        val url = "http://x.com/a"
        assertEquals(
            "http://twitter.com/a",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun userinfo_is_dropped_but_host_is_swapped() {
        val url = "https://user:pass@x.com/a?b=1#frag"
        assertEquals(
            "https://twitter.com/a?b=1#frag",
            HostRewriter.rewrite(url, listOf(rewrite())),
        )
    }

    @Test
    fun idn_host_is_normalized_to_ascii_form_for_matching() {
        // 例子.com IDN form; matching happens in ASCII (pnycode) form.
        val url = "https://例子.com/a"
        val rule = rewrite(matchHost = "例子.com", targetHost = "twitter.com")
        assertEquals(
            "https://twitter.com/a",
            HostRewriter.rewrite(url, listOf(rule)),
        )
        // The incoming IDN host matches the IDN-stored rule (both normalized).
        val incomingAscii = RuleEngine.normalizeHost("例子.com")
        assertTrue(HostRewriter.matchesHost(incomingAscii, rule))
    }

    @Test
    fun punycode_stored_rule_matches_idn_url_and_emits_ascii_target() {
        // Same IDN host, but the rule stores the matchHost in its ASCII
        // (punycode) form while the URL arrives in IDN form. Both sides
        // normalize to the same ASCII → the rule matches, and the ASCII
        // target is emitted (never the IDN form).
        val asciiMatchHost = RuleEngine.normalizeHost("例子.com")
        val rule = rewrite(matchHost = asciiMatchHost, targetHost = "twitter.com")
        assertEquals(
            "https://twitter.com/a",
            HostRewriter.rewrite("https://例子.com/a", listOf(rule)),
        )
    }

    // --- priority/precedence ---------------------------------------------

    @Test
    fun top_priority_rule_wins_for_the_same_host() {
        val url = "https://x.com/a"
        val top = rewrite(targetHost = "win.com", id = 1, enabled = true)
        val bottom = rewrite(targetHost = "lose.com", id = 2, enabled = true)
        // List order is priority: top = highest. Only the first match applies.
        assertEquals(
            "https://win.com/a",
            HostRewriter.rewrite(url, listOf(top, bottom)),
        )
        assertEquals(
            "https://lose.com/a",
            HostRewriter.rewrite(url, listOf(bottom, top)),
        )
    }

    @Test
    fun first_matching_enabled_rule_wins_disables_are_skipped() {
        val url = "https://x.com/a"
        val disabled = rewrite(targetHost = "skip.com", enabled = false)
        val active = rewrite(targetHost = "hit.com", enabled = true)
        assertEquals(
            "https://hit.com/a",
            HostRewriter.rewrite(url, listOf(disabled, active)),
        )
    }

    // --- applyOne contract -------------------------------------------------

    @Test
    fun applyOne_returns_null_on_non_match() {
        assertNull(HostRewriter.applyOne("https://other.com/a", rewrite()))
    }

    @Test
    fun applyOne_returns_null_for_non_web_url() {
        assertNull(HostRewriter.applyOne("mailto:a@b.com", rewrite()))
    }

    @Test
    fun preview_returns_url_unchanged_when_rule_does_not_match() {
        val url = "https://other.com/a"
        assertEquals(url, HostRewriter.preview(rewrite(), url))
    }

    // --- malformed stored rules (D6: never throws, never pretends) ----------

    @Test
    fun malformed_stored_rules_leave_input_unchanged_and_applyOne_returns_null() {
        // A rule with a blank match host can never match — input is untouched
        // (no crash, no empty-host rewrite) and applyOne reports "no match".
        val url = "https://x.com/a"
        val blankMatch = rewrite(matchHost = "")
        assertNull(HostRewriter.applyOne(url, blankMatch))
        assertEquals(url, HostRewriter.rewrite(url, listOf(blankMatch)))

        // A whitespace-only match host is likewise unusable.
        val whitespaceMatch = rewrite(matchHost = "   ")
        assertNull(HostRewriter.applyOne(url, whitespaceMatch))
        assertEquals(url, HostRewriter.rewrite(url, listOf(whitespaceMatch)))

        // A blank target host is a malformed stored rule: even when the host
        // matches, the rewrite is refused (never emits `https:///a`).
        val blankTarget = rewrite(targetHost = "")
        assertNull(HostRewriter.applyOne("https://x.com/a", blankTarget))
        assertEquals("https://x.com/a", HostRewriter.rewrite("https://x.com/a", listOf(blankTarget)))

        // A rule that stores an unusable target (no usable host form) is also
        // refused — input unchanged, never throws.
        val slashedTarget = rewrite(targetHost = "//")
        assertNull(HostRewriter.applyOne("https://x.com/a", slashedTarget))
        assertEquals("https://x.com/a", HostRewriter.rewrite("https://x.com/a", listOf(slashedTarget)))

        // Malformed rules mixed among healthy ones are skipped, not fatal:
        // the first WELL-FORMED matching rule still applies.
        val healthy = rewrite(targetHost = "twitter.com")
        assertEquals(
            "https://twitter.com/a",
            HostRewriter.rewrite("https://x.com/a", listOf(blankMatch, blankTarget, healthy)),
        )
    }
}
