package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for [QueryParamStripper] (DESIGN.md §6 / M9): global and
 * scoped stripping, case-insensitivity, ordering/encoding preservation, and
 * the never-touch guarantees (non-web urls, no query, no match, `__lr` guard).
 */
class QueryParamStripperTest {

    private fun filter(
        param: String,
        host: String? = null,
        enabled: Boolean = true,
    ) = QueryParamFilter(
        id = 0,
        name = param,
        host = host,
        param = param,
        enabled = enabled,
        priority = 0,
        isBuiltIn = false,
    )

    // --- global stripping ----------------------------------------------------

    @Test
    fun strips_single_global_param_and_keeps_the_rest() {
        val url = "https://example.com/page?utm_source=tw&id=5"
        assertEquals(
            "https://example.com/page?id=5",
            QueryParamStripper.strip(url, listOf(filter("utm_source"))),
        )
    }

    @Test
    fun strips_multiple_params_at_once() {
        val url = "https://example.com/page?utm_source=tw&gclid=x&id=5&msclkid=y"
        assertEquals(
            "https://example.com/page?id=5",
            QueryParamStripper.strip(
                url,
                listOf(filter("utm_source"), filter("gclid"), filter("msclkid")),
            ),
        )
    }

    @Test
    fun keeps_the_order_of_surviving_params() {
        val url = "https://example.com/p?b=2&a=1&gclid=x&c=3"
        assertEquals(
            "https://example.com/p?b=2&a=1&c=3",
            QueryParamStripper.strip(url, listOf(filter("gclid"))),
        )
    }

    @Test
    fun key_match_is_case_insensitive() {
        assertEquals(
            "https://example.com/p?id=5",
            QueryParamStripper.strip("https://example.com/p?UTM_SOURCE=x&id=5", listOf(filter("utm_source"))),
        )
    }

    // --- scoped (domain) stripping -------------------------------------------

    @Test
    fun scoped_filter_applies_to_subdomains() {
        val url = "https://www.tiktok.com/video?_t=8&x=1"
        assertEquals(
            "https://www.tiktok.com/video?x=1",
            QueryParamStripper.strip(url, listOf(filter("_t", host = "tiktok.com"))),
        )
    }

    @Test
    fun scoped_filter_does_not_apply_to_other_domains() {
        val url = "https://example.com/page?_t=8"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("_t", host = "tiktok.com"))))
    }

    @Test
    fun scoped_filter_rejects_lookalike_suffix_host() {
        // tiktok.com.evil.com is NOT a subdomain of tiktok.com.
        val url = "https://tiktok.com.evil.com/p?_t=8"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("_t", host = "tiktok.com"))))
    }

    // --- pair shapes ----------------------------------------------------------

    @Test
    fun bare_key_param_is_stripped_when_key_matches() {
        assertEquals(
            "https://example.com/p?keep=1",
            QueryParamStripper.strip("https://example.com/p?fbclid&keep=1", listOf(filter("fbclid"))),
        )
    }

    @Test
    fun empty_value_param_is_stripped() {
        assertEquals(
            "https://example.com/p?keep=1",
            QueryParamStripper.strip("https://example.com/p?gclid=&keep=1", listOf(filter("gclid"))),
        )
    }

    @Test
    fun all_params_stripped_drops_the_query_separator_entirely() {
        assertEquals(
            "https://example.com/p",
            QueryParamStripper.strip("https://example.com/p?gclid=x", listOf(filter("gclid"))),
        )
    }

    // --- never-touch guarantees ------------------------------------------------

    @Test
    fun url_without_query_is_unchanged() {
        val url = "https://example.com/page"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("utm_source"))))
    }

    @Test
    fun non_web_scheme_is_unchanged() {
        val url = "mailto:someone@example.com?utm_source=x"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("utm_source"))))
    }

    @Test
    fun empty_filter_list_is_unchanged() {
        val url = "https://example.com/page?utm_source=tw"
        assertEquals(url, QueryParamStripper.strip(url, emptyList()))
    }

    @Test
    fun disabled_filters_are_ignored() {
        val url = "https://example.com/page?utm_source=tw"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("utm_source", enabled = false))))
    }

    @Test
    fun no_matching_param_is_unchanged() {
        val url = "https://example.com/page?keep=1"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("utm_source"))))
    }

    @Test
    fun loop_guard_param_is_never_stripped_even_if_a_filter_named_it() {
        val url = "https://example.com/page?__lr=1&keep=1"
        assertEquals(url, QueryParamStripper.strip(url, listOf(filter("__lr"))))
    }

    // --- rebuild fidelity ------------------------------------------------------

    @Test
    fun fragment_is_preserved() {
        assertEquals(
            "https://example.com/p?keep=1#sec",
            QueryParamStripper.strip("https://example.com/p?keep=1&gclid=x#sec", listOf(filter("gclid"))),
        )
    }

    @Test
    fun raw_encoding_of_kept_params_is_preserved() {
        assertEquals(
            "https://example.com/p?keep=a%20b",
            QueryParamStripper.strip("https://example.com/p?keep=a%20b&gclid=x", listOf(filter("gclid"))),
        )
    }

    @Test
    fun http_scheme_is_preserved_in_output() {
        assertEquals(
            "http://example.com/p?a=1",
            QueryParamStripper.strip("http://example.com/p?a=1&gclid=x", listOf(filter("gclid"))),
        )
    }

    @Test
    fun authority_with_port_is_preserved() {
        assertEquals(
            "https://example.com:8080/p?a=1",
            QueryParamStripper.strip("https://example.com:8080/p?a=1&gclid=x", listOf(filter("gclid"))),
        )
    }

    @Test
    fun credentials_in_authority_are_preserved() {
        assertEquals(
            "https://user:pass@example.com/p?keep=1",
            QueryParamStripper.strip("https://user:pass@example.com/p?keep=1&gclid=x", listOf(filter("gclid"))),
        )
    }
}
