package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [HostRewriteValidator] (Host Rewrites P1). Structural
 * emptiness is blocking ([Result.Invalid]); the loop-generating
 * PATH_PREFIX_REWRITE case and any SUBDOMAIN scope produce warnings
 * ([Result.Valid.warnings]) rather than blocks. Mirrors
 * [RedirectFormatValidatorTest] style.
 */
class HostRewriteValidatorTest {

    private fun validate(
        matchHost: String = "nytimes.com",
        matchType: RewriteMatchType = RewriteMatchType.EXACT_HOST,
        kind: RewriteKind = RewriteKind.PATH_PREFIX_REWRITE,
        targetHost: String = "archive.md",
        preserveHostInPath: Boolean = true,
    ) = HostRewriteValidator.validate(matchHost, matchType, kind, targetHost, preserveHostInPath)

    // --- blocking (structural) -------------------------------------------

    @Test
    fun empty_match_host_is_invalid() {
        assertTrue(validate(matchHost = "") is HostRewriteValidator.Result.Invalid)
        // A host with no usable form (only slashes) is also invalid.
        assertTrue(validate(matchHost = "//") is HostRewriteValidator.Result.Invalid)
    }

    @Test
    fun empty_target_host_is_invalid() {
        assertTrue(validate(targetHost = "") is HostRewriteValidator.Result.Invalid)
    }

    // --- clean case -------------------------------------------------------

    @Test
    fun clean_rule_is_valid_with_no_warnings() {
        val result = validate(matchHost = "nytimes.com", targetHost = "archive.md")
        assertTrue(result is HostRewriteValidator.Result.Valid)
        val valid = result as HostRewriteValidator.Result.Valid
        assertEquals("nytimes.com", valid.normalizedMatchHost)
        assertEquals("archive.md", valid.normalizedTargetHost)
        assertTrue(valid.warnings.isEmpty())
    }

    // --- P1: loop-generating warning --------------------------------------

    @Test
    fun path_prefix_rewrite_target_equals_match_base_warns() {
        // targetHost base == matchHost base → would produce archive.md/archive.md/...
        val result = validate(matchHost = "archive.md", targetHost = "archive.md")
        assertTrue(result is HostRewriteValidator.Result.Valid)
        val valid = result as HostRewriteValidator.Result.Valid
        assertTrue(
            "expected a loop-generating warning, got ${valid.warnings}",
            valid.warnings.any { "archive.md" in it || "loop" in it.lowercase() || "..." in it },
        )
    }

    @Test
    fun loop_warning_is_www_tolerant() {
        // www. must not hide a loop-generating match: target base == match base.
        val result = validate(matchHost = "www.archive.md", targetHost = "archive.md")
        assertTrue(result is HostRewriteValidator.Result.Valid)
        val valid = result as HostRewriteValidator.Result.Valid
        assertTrue(
            "expected a loop-generating warning, got ${valid.warnings}",
            valid.warnings.any { "..." in it || "loop" in it.lowercase() },
        )
    }

    @Test
    fun distinct_target_does_not_warn() {
        val result = validate(matchHost = "nytimes.com", targetHost = "archive.md")
        val valid = result as HostRewriteValidator.Result.Valid
        assertTrue(valid.warnings.isEmpty())
    }

    // --- P1: SUBDOMAIN warning --------------------------------------------

    @Test
    fun subdomain_match_type_warns() {
        val result = validate(
            matchHost = "tiktok.com",
            matchType = RewriteMatchType.SUBDOMAIN,
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
        )
        assertTrue(result is HostRewriteValidator.Result.Valid)
        val valid = result as HostRewriteValidator.Result.Valid
        assertTrue(
            "expected a SUBDOMAIN warning, got ${valid.warnings}",
            valid.warnings.any { it.contains("SUBDOMAIN") || it.contains("subdomain") },
        )
    }

    // --- warning is additive and non-blocking ------------------------------

    @Test
    fun warnings_do_not_block_the_rule() {
        // SUBDOMAIN + loop-generating: both warn, still Valid (non-blocking).
        val result = validate(
            matchHost = "archive.md",
            matchType = RewriteMatchType.SUBDOMAIN,
            kind = RewriteKind.PATH_PREFIX_REWRITE,
            targetHost = "archive.md",
        )
        assertTrue(result is HostRewriteValidator.Result.Valid)
        val valid = result as HostRewriteValidator.Result.Valid
        assertTrue(valid.warnings.size >= 2)
    }
}
