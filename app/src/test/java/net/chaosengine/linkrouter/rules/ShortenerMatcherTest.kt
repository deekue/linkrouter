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
}
