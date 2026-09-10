package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortenerMatcherTest {

    private fun host(
        host: String,
        pathPrefix: String? = null,
        name: String = "H",
    ) = ShortenerHost(
        id = 0,
        name = name,
        host = host,
        pathPrefix = pathPrefix,
        enabled = true,
        priority = 0,
        isBuiltIn = false,
    )

    // --- domain matching ---------------------------------------------------

    @Test
    fun domain_matches_bare_host_against_www_stored() {
        assertTrue(ShortenerMatcher.domainMatches("tiktok.com", "www.tiktok.com"))
    }

    @Test
    fun domain_matches_subdomain_against_www_stored() {
        assertTrue(ShortenerMatcher.domainMatches("vm.tiktok.com", "www.tiktok.com"))
    }

    @Test
    fun domain_matches_mobile_subdomain_of_facebook() {
        assertTrue(ShortenerMatcher.domainMatches("m.facebook.com", "www.facebook.com"))
    }

    @Test
    fun domain_rejects_similar_but_different_host() {
        assertFalse(ShortenerMatcher.domainMatches("not-tiktok.com", "www.tiktok.com"))
    }

    @Test
    fun domain_matches_www_incoming_against_bare_stored() {
        // www is stripped on BOTH sides, so www.t.co == t.co.
        assertTrue(ShortenerMatcher.domainMatches("www.t.co", "t.co"))
        assertTrue(ShortenerMatcher.domainMatches("www.t.co", "www.t.co"))
    }

    @Test
    fun domain_matches_subdomain_against_bare_stored() {
        // Subdomains are intentionally matched (e.g. vm.tiktok.com vs www.tiktok.com).
        assertTrue(ShortenerMatcher.domainMatches("sub.t.co", "t.co"))
        assertTrue(ShortenerMatcher.domainMatches("www.sub.t.co", "t.co"))
    }

    @Test
    fun domain_rejects_bare_host_against_subdomain_stored() {
        // "t.co" is not a subdomain of "sub.t.co" (anchored on a dot).
        assertFalse(ShortenerMatcher.domainMatches("t.co", "sub.t.co"))
    }

    @Test
    fun domain_rejects_lookalike_suffix() {
        // endsWith must be anchored on a dot: "tiktok.com.evil.com" is not a
        // subdomain of "tiktok.com" (and vice versa).
        assertFalse(ShortenerMatcher.domainMatches("tiktok.com.evil.com", "tiktok.com"))
    }

    // --- path-prefix matching ----------------------------------------------

    @Test
    fun prefix_matches_short_link_path() {
        assertTrue(ShortenerMatcher.matches("www.tiktok.com", "/t/XYZ123", host("www.tiktok.com", "/t/")))
    }

    @Test
    fun prefix_rejects_other_paths() {
        assertFalse(ShortenerMatcher.matches("www.tiktok.com", "/embed/foo", host("www.tiktok.com", "/t/")))
        assertFalse(ShortenerMatcher.matches("www.tiktok.com", "/", host("www.tiktok.com", "/t/")))
    }

    @Test
    fun prefix_is_boundary_anchored() {
        // "/t/" must NOT match "/to/".
        assertFalse(ShortenerMatcher.matches("www.tiktok.com", "/to/abc", host("www.tiktok.com", "/t/")))
    }

    @Test
    fun prefix_is_case_insensitive_on_incoming_path() {
        assertTrue(ShortenerMatcher.matches("www.tiktok.com", "/T/abc", host("www.tiktok.com", "/t/")))
    }

    @Test
    fun prefix_without_leading_slash_is_normalized() {
        val h = host("www.tiktok.com", pathPrefix = "t/")
        assertTrue(ShortenerMatcher.matches("www.tiktok.com", "/t/abc", h))
    }

    @Test
    fun host_only_row_matches_any_path() {
        val h = host("t.co", pathPrefix = null)
        assertTrue(ShortenerMatcher.matches("t.co", "/", h))
        assertTrue(ShortenerMatcher.matches("t.co", "/abc/def", h))
        assertTrue(ShortenerMatcher.matches("www.t.co", "/ABC", h))
    }

    @Test
    fun non_matching_domain_rejects_even_if_path_fits() {
        val h = host("www.tiktok.com", "/t/")
        assertFalse(ShortenerMatcher.matches("evil.com", "/t/abc", h))
    }

    @Test
    fun empty_root_path_matches_host_only_row_but_not_prefixed_row() {
        // https://t.co/ normalizes to path "/": a host-only (null prefix) row
        // matches any path including "/", but a prefixed row does not.
        assertTrue(ShortenerMatcher.matches("t.co", "/", host("t.co")))
        assertFalse(ShortenerMatcher.matches("t.co", "/", host("t.co", "/t/")))
    }

    @Test
    fun path_prefix_row_matches_www_subdomain_and_prefix_path() {
        // Built-in shape: www.tiktok.com + /t/ must match its own www host.
        val h = host("www.tiktok.com", "/t/")
        assertTrue(ShortenerMatcher.matches("www.tiktok.com", "/t/ZG123/", h))
    }

    @Test
    fun path_prefix_requires_prefix_even_on_www_incoming_host() {
        val h = host("www.tiktok.com", "/t/")
        // Same host, but a path outside the prefix is rejected.
        assertFalse(ShortenerMatcher.matches("www.tiktok.com", "/video", h))
    }
}
