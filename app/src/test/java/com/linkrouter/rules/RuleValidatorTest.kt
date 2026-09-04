package com.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleValidatorTest {

    // --- detectType ---

    @Test
    fun detectType_shapes() {
        assertEquals(MatchType.EXACT_HOST, RuleValidator.detectType("example.com"))
        assertEquals(MatchType.SUBDOMAIN, RuleValidator.detectType("*.example.com"))
        assertEquals(MatchType.PATH_PREFIX, RuleValidator.detectType("example.com/docs"))
        assertEquals(MatchType.REGEX, RuleValidator.detectType("example\\.com"))
        assertEquals(MatchType.REGEX, RuleValidator.detectType("example.com/(old|legacy)/.*"))
    }

    // --- validate ---

    @Test
    fun validate_empty_is_invalid() {
        assertTrue(RuleValidator.validate("   ") is RuleValidator.Result.Invalid)
    }

    @Test
    fun validate_invalid_regex_is_invalid() {
        assertTrue(
            RuleValidator.validate("example.com/(unclosed", MatchType.REGEX)
                is RuleValidator.Result.Invalid
        )
    }

    @Test
    fun validate_host_and_path() {
        val r = RuleValidator.validate("Example.com/Docs")
        val valid = r as RuleValidator.Result.Valid
        assertEquals(MatchType.PATH_PREFIX, valid.detectedType)
        assertEquals("example.com/Docs", valid.normalizedPattern)
    }

    @Test
    fun validate_subdomain_normalizes_base() {
        val r = RuleValidator.validate("*.EXAMPLE.com") as RuleValidator.Result.Valid
        assertEquals(MatchType.SUBDOMAIN, r.detectedType)
        assertEquals("*.example.com", r.normalizedPattern)
    }

    @Test
    fun validate_explicit_type_wins() {
        val r = RuleValidator.validate("example\\.com", MatchType.REGEX) as RuleValidator.Result.Valid
        assertEquals(MatchType.REGEX, r.detectedType)
    }

    // --- previewMatch ---

    @Test
    fun previewMatch_exact_host() {
        assertTrue(RuleValidator.previewMatch("example.com", MatchType.EXACT_HOST, "https://example.com/x"))
        assertFalse(RuleValidator.previewMatch("example.com", MatchType.EXACT_HOST, "https://other.com/"))
        assertFalse(RuleValidator.previewMatch("example.com", MatchType.EXACT_HOST, "mailto:a@b.c"))
    }

    @Test
    fun previewMatch_path_prefix() {
        assertTrue(RuleValidator.previewMatch("example.com/docs", MatchType.PATH_PREFIX, "https://example.com/docs/a"))
        assertFalse(RuleValidator.previewMatch("example.com/docs", MatchType.PATH_PREFIX, "https://example.com/other"))
    }

    @Test
    fun previewMatch_subdomain_excludes_base() {
        assertTrue(RuleValidator.previewMatch("*.example.com", MatchType.SUBDOMAIN, "https://a.example.com/"))
        assertFalse(RuleValidator.previewMatch("*.example.com", MatchType.SUBDOMAIN, "https://example.com/"))
    }

    // --- sampleUrls ---

    @Test
    fun sampleUrls_per_type() {
        assertEquals(3, RuleValidator.sampleUrls("example.com", MatchType.EXACT_HOST).size)
        val sub = RuleValidator.sampleUrls("*.example.com", MatchType.SUBDOMAIN)
        assertTrue(sub.contains("https://sub.example.com/"))
        assertTrue(sub.contains("https://a.b.example.com/x"))
        assertTrue(sub.contains("https://other.com/"))
        val prefix = RuleValidator.sampleUrls("example.com/docs", MatchType.PATH_PREFIX)
        assertTrue(prefix.contains("https://example.com/docs/page"))
    }

    @Test
    fun sampleUrls_empty_pattern() {
        assertTrue(RuleValidator.sampleUrls("", MatchType.EXACT_HOST).isEmpty())
    }
}
