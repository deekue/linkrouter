package com.linkrouter.rules

import java.net.IDN
import java.util.regex.PatternSyntaxException

/**
 * Pattern validation + type auto-detection + live match preview
 * (DESIGN.md section 5). Pure JVM — unit-testable.
 */
object RuleValidator {

    sealed class Result {
        data class Valid(val detectedType: MatchType, val normalizedPattern: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** Rejects empty patterns and malformed regex; auto-detects the match type. */
    fun validate(pattern: String, explicitType: MatchType? = null): Result {
        val p = pattern.trim()
        if (p.isEmpty()) return Result.Invalid("Pattern is empty")

        val detected = detectType(p)
        val type = explicitType ?: detected

        // Compile-check any pattern used with REGEX (and the auto-detected shape).
        if (type == MatchType.REGEX) {
            try {
                // Full match on host+path — must be a valid regex.
                java.util.regex.Pattern.compile(p)
            } catch (e: PatternSyntaxException) {
                return Result.Invalid("Invalid regex: ${e.description}")
            }
        }

        val normalized = when (type) {
            MatchType.REGEX -> p
            else -> {
                val host = p.substringBefore('/')
                if (type == MatchType.SUBDOMAIN) {
                    val base = if (host.startsWith("*.")) host.substring(2) else host
                    "*.${RuleEngine.normalizeHost(base)}"
                } else {
                    val path = if ('/' in p) p.substring(p.indexOf('/')) else ""
                    "${RuleEngine.normalizeHost(host)}$path"
                }
            }
        }
        return Result.Valid(type, normalized)
    }

    /**
     * Auto-detect the match type from the pattern shape (DESIGN.md section 5):
     * `*.host` → SUBDOMAIN, `host/path/` → PATH_PREFIX, `host` → EXACT_HOST.
     * Patterns that are clearly not host-shaped (contain regex metachars) → REGEX.
     */
    fun detectType(pattern: String): MatchType {
        val p = pattern.trim()
        if (p.isEmpty()) return MatchType.EXACT_HOST
        if (p.startsWith("*.")) return MatchType.SUBDOMAIN
        if (p.startsWith("/")) return MatchType.PATH_PREFIX
        if (looksLikeRegex(p)) return MatchType.REGEX
        val hostPart = p.substringBefore('/')
        val pathPart = if ('/' in p) p.substring(p.indexOf('/')) else ""
        return if (pathPart.isNotEmpty()) MatchType.PATH_PREFIX else MatchType.EXACT_HOST
    }

    private fun looksLikeRegex(p: String): Boolean {
        // Heuristic: regex metacharacters outside of a plain host/path shape.
        val hostPart = p.substringBefore('/')
        val pathPart = if ('/' in p) p.substring(p.indexOf('/')) else ""
        val regexMeta = Regex("[.\\[\\](){}^$+\\\\|*]")
        // A single dot in a host is a normal TLD separator — not regex by itself.
        val hostHasMeta = hostPart.filter { it != '.' }.any { it in "\\[\\](){}^$+\\|" }
        return hostHasMeta || regexMeta.containsMatchIn(pathPart)
    }

    /**
     * Live match preview (DESIGN.md section 10): does [url] match [pattern]
     * interpreted as [type]? Used by the editor as the user types.
     */
    fun previewMatch(pattern: String, type: MatchType, url: String): Boolean {
        val parsed = RuleEngine.normalize(url) ?: return false
        val rule = Rule(
            id = 0,
            pattern = pattern,
            matchType = type,
            targetPackage = "",
        )
        return RuleEngine.scoreRule(rule, parsed) != null
    }

    /**
     * Representative sample URLs for the editor preview, derived from the
     * pattern's host so the preview is meaningful.
     */
    fun sampleUrls(pattern: String, type: MatchType): List<String> {
        val p = pattern.trim()
        if (p.isEmpty()) return emptyList()
        val host = when {
            p.startsWith("*.") -> p.substring(2).substringBefore('/')
            else -> p.substringBefore('/')
        }
        val baseHost = RuleEngine.normalizeHost(host.ifEmpty { "example.com" })
        return when (type) {
            MatchType.EXACT_HOST ->
                listOf("https://$baseHost/", "https://$baseHost/page", "https://$baseHost/?utm=x")
            MatchType.SUBDOMAIN ->
                listOf("https://sub.$baseHost/", "https://a.b.$baseHost/x", "https://other.com/")
            MatchType.PATH_PREFIX -> {
                val path = if ('/' in p) p.substring(p.indexOf('/')) else "/"
                listOf("https://$baseHost$path/page", "https://$baseHost/", "https://other.com$path")
            }
            MatchType.REGEX ->
                listOf("https://$baseHost/", "https://$baseHost/anything", "https://other.com/")
        }
    }

    /** True when [pattern] (as [type]) would match the IDN form of the host. */
    fun isIdn(host: String): Boolean =
        try {
            IDN.toASCII(host) != host.lowercase()
        } catch (e: Exception) {
            false
        }
}
