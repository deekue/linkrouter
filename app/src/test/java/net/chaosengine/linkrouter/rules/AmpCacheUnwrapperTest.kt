package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AmpCacheUnwrapper] — pure-JVM, no Android.
 * Table of structural cases: unwrap shape, protocol inference, host reading
 * from the path, query-param stripping, and every null-returning shape.
 */
class AmpCacheUnwrapperTest {

    // --- the common happy path (c/s/ => https origin) ----------------------

    @Test
    fun unwraps_content_dir_with_s_infix_to_https_origin() {
        assertEquals(
            "https://example.com/news/story?id=42",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/news/story?id=42"),
        )
    }

    @Test
    fun unwraps_www_host_from_path_verbatim() {
        assertEquals(
            "https://www.example.com/a.html?x=1&y=2",
            AmpCacheUnwrapper.unwrap("https://www-example-com.cdn.ampproject.org/c/s/www.example.com/a.html?x=1&y=2"),
        )
    }

    @Test
    fun unwraps_hyphenated_original_host() {
        // The host is read from the path, so the ambiguous `--` prefix is a
        // non-issue: foo--example-com (prefix) -> foo-example.com (origin).
        assertEquals(
            "https://foo-example.com/p",
            AmpCacheUnwrapper.unwrap("https://foo--example-com.cdn.ampproject.org/c/s/foo-example.com/p"),
        )
    }

    @Test
    fun unwraps_multi_subdomain_original_host() {
        assertEquals(
            "https://en-us.example.com/",
            AmpCacheUnwrapper.unwrap("https://en-us-example-com.cdn.ampproject.org/c/s/en-us.example.com/"),
        )
    }

    @Test
    fun unwraps_realistic_multi_segment_path() {
        assertEquals(
            "https://blog.example.com/2024/01/news/a-headline.html?utm_source=rss&page=2",
            AmpCacheUnwrapper.unwrap(
                "https://blog-example-com.cdn.ampproject.org/c/s/blog.example.com/2024/01/news/a-headline.html?utm_source=rss&page=2",
            ),
        )
    }

    @Test
    fun unwraps_content_dir_with_s_infix_and_root_path() {
        assertEquals(
            "https://example.com/",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/"),
        )
    }

    // --- protocol inference from the /s infix -------------------------------

    @Test
    fun no_s_infix_means_http_origin() {
        assertEquals(
            "http://example.com/",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/example.com/"),
        )
    }

    @Test
    fun no_s_infix_with_path_and_query() {
        assertEquals(
            "http://example.com/a/b.html?x=1",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/example.com/a/b.html?x=1"),
        )
    }

    // --- other served dirs ---------------------------------------------------

    @Test
    fun unwraps_viewer_dir() {
        assertEquals(
            "https://example.com/",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/v/s/example.com/"),
        )
    }

    @Test
    fun unwraps_wp_dir() {
        assertEquals(
            "https://example.com/p",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/wp/s/example.com/p"),
        )
    }

    @Test
    fun unwraps_cert_dir() {
        assertEquals(
            "https://example.com/",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/cert/s/example.com/"),
        )
    }

    // --- query-param handling ----------------------------------------------

    @Test
    fun strips_cache_only_params_keeps_real_ones_in_order() {
        assertEquals(
            "https://example.com/?b=2&a=1",
            AmpCacheUnwrapper.unwrap(
                "https://example-com.cdn.ampproject.org/c/s/example.com/?amp_latest_update_time=1719000000&b=2&amp_cache_update_time=1719000001&a=1",
            ),
        )
    }

    @Test
    fun strips_only_cache_params_leaves_no_query() {
        assertEquals(
            "https://example.com/",
            AmpCacheUnwrapper.unwrap(
                "https://example-com.cdn.ampproject.org/c/s/example.com/?amp_latest_update_time=1719000000",
            ),
        )
    }

    @Test
    fun strips_cache_params_in_middle_of_path_query() {
        assertEquals(
            "https://example.com/p?x=1",
            AmpCacheUnwrapper.unwrap(
                "https://example-com.cdn.ampproject.org/c/s/example.com/p?amp_cache_update_time=1&x=1",
            ),
        )
    }

    // --- path-segment fidelity (double slashes) ------------------------------

    @Test
    fun double_slash_in_path_is_preserved() {
        // /a//b is a different path than /a/b — empty segments must survive.
        assertEquals(
            "https://example.com/a//b",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/a//b"),
        )
    }

    @Test
    fun triple_slash_in_path_is_preserved() {
        assertEquals(
            "https://example.com/a///b",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/a///b"),
        )
    }

    @Test
    fun double_slashes_in_multi_segment_path_are_preserved_with_query() {
        assertEquals(
            "https://example.com/a/b//c//d?x=1",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/a/b//c//d?x=1"),
        )
    }

    // --- case-insensitive scheme/dir slots -----------------------------------

    @Test
    fun uppercase_scheme_and_dirs_unwrap_with_host_case_preserved() {
        // Scheme is normalized to lowercase, structural dir/`s` slots are
        // case-insensitive, but the original host is read verbatim.
        assertEquals(
            "https://EXAMPLE.COM/",
            AmpCacheUnwrapper.unwrap("HTTPS://EXAMPLE-COM.CDN.AMPPROJECT.ORG/C/S/EXAMPLE.COM/"),
        )
    }

    // --- unusual-but-legal authorities and queries ---------------------------

    @Test
    fun cache_authority_with_port_still_unwraps() {
        assertEquals(
            "https://example.com/",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org:443/c/s/example.com/"),
        )
    }

    @Test
    fun duplicate_ampersand_in_query_is_normalized_to_single() {
        // rebuildQuery drops empty pairs, so `&&` collapses to a single `&`.
        assertEquals(
            "https://example.com/?x=1&y=2",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/?x=1&&y=2"),
        )
    }

    @Test
    fun origin_host_port_in_path_is_preserved() {
        // The original host (incl. a rare `:port`) is read verbatim from the
        // path — no port-stripping is applied to it.
        assertEquals(
            "https://example.com:8080/",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com:8080/"),
        )
    }

    @Test
    fun cache_only_param_without_value_is_stripped() {
        assertEquals(
            "https://example.com/?x=1",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/?amp_latest_update_time&x=1"),
        )
    }

    // --- Bing cache domain ---------------------------------------------------

    @Test
    fun unwraps_bing_cache_domain() {
        assertEquals(
            "https://example.com/n/story?ref=b",
            AmpCacheUnwrapper.unwrap("https://example-com.www.bing-amp.com/c/s/example.com/n/story?ref=b"),
        )
    }

    // --- null-returning shapes ------------------------------------------------

    @Test
    fun non_amp_url_returns_null() {
        assertNull(AmpCacheUnwrapper.unwrap("https://example.com/foo"))
        assertNull(AmpCacheUnwrapper.unwrap("http://example.com/foo"))
        assertNull(AmpCacheUnwrapper.unwrap("mailto:foo@example.com"))
        assertNull(AmpCacheUnwrapper.unwrap("not a url at all"))
    }

    @Test
    fun wrong_scheme_http_returns_null() {
        assertNull(AmpCacheUnwrapper.unwrap("http://example-com.cdn.ampproject.org/c/s/example.com/"))
    }

    @Test
    fun bare_cache_domain_no_prefix_returns_null() {
        assertNull(AmpCacheUnwrapper.unwrap("https://cdn.ampproject.org/c/s/example.com/"))
        assertNull(AmpCacheUnwrapper.unwrap("https://www.bing-amp.com/c/s/example.com/"))
    }

    @Test
    fun lookalike_domain_returns_null() {
        // Not a subdomain of a cache domain — endsWith must be dot-anchored.
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.evilcdn.ampproject.org/c/s/example.com/"))
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org.evil.com/c/s/example.com/"))
    }

    @Test
    fun missing_original_host_returns_null() {
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/"))
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s"))
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/"))
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c"))
    }

    @Test
    fun image_serving_dirs_return_null() {
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/ii/s/example.com/img.png"))
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/i/s/example.com/img.png"))
    }

    @Test
    fun unknown_serving_dir_returns_null() {
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/rts/example.com/"))
        assertNull(AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/other/example.com/"))
    }

    @Test
    fun fragment_is_ignored_when_present() {
        // Fragments are not part of AMP cache serving URLs; if one somehow
        // appears it is dropped, and the rest still unwraps.
        assertEquals(
            "https://example.com/p",
            AmpCacheUnwrapper.unwrap("https://example-com.cdn.ampproject.org/c/s/example.com/p#section"),
        )
    }

    @Test
    fun empty_string_returns_null() {
        assertNull(AmpCacheUnwrapper.unwrap(""))
    }
}
