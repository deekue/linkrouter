package com.linkrouter.rules

/**
 * How the real destination is extracted from a redirect-wrapper URL
 * (DESIGN.md: generalizes the hardcoded `unwrapRedirect`).
 *
 * QUERY_PARAM     extractTarget = query-param name (e.g. "q"); value is URL-decoded.
 * PATH_REGEX      extractTarget = regex over the path; first capture group (else group 0).
 * FULL_URL_REGEX  extractTarget = regex over the RAW url; first capture group (else group 0).
 * BASE64_PARAM    extractTarget = query-param name; value is URL-decoded then Base64-decoded.
 */
enum class ExtractType { QUERY_PARAM, PATH_REGEX, FULL_URL_REGEX, BASE64_PARAM }

/**
 * A user-managed redirect-unwrapping format. Mirrors [Rule] (the domain model)
 * end-to-end: a [pattern] identifies the WRAPPER (host[/path]) using the same
 * [MatchType] semantics as rules, and [extractType]/[extractTarget] describe how
 * to recover the real destination from a matching wrapper.
 */
data class RedirectFormat(
    val id: Long,
    val name: String,
    val pattern: String,
    val matchType: MatchType,
    val extractType: ExtractType,
    val extractTarget: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val isBuiltIn: Boolean = false,
) {
    companion object {
        /**
         * The built-in Google redirect format (`google.com/url?q=<dest>`).
         * Seeded on every open (fresh install + migrated) and restorable via
         * `resetBuiltIn`. id is the fixed -1 used by the idempotent seed so it
         * never collides with auto-generated positive ids.
         */
        const val BUILT_IN_ID: Long = -1L
        val BUILT_IN_GOOGLE: RedirectFormat = RedirectFormat(
            id = BUILT_IN_ID,
            name = "Google",
            pattern = "google.com/url",
            matchType = MatchType.PATH_PREFIX,
            extractType = ExtractType.QUERY_PARAM,
            extractTarget = "q",
            enabled = true,
            priority = 1000,
            isBuiltIn = true,
        )
    }
}
