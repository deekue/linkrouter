package net.chaosengine.linkrouter.rules

import java.util.regex.Pattern

/**
 * Pure JVM redirect-unwrapping core (no Android types) so it is unit-testable.
 * Generalizes the hardcoded [RuleEngine.unwrapRedirect] into data-driven,
 * user-managed [RedirectFormat]s: try each enabled format (in priority order)
 * and return the first VALID http(s) destination it extracts.
 *
 * All parsing/decoding is wrapped in try/catch — the dispatcher must NEVER
 * crash on a malformed format.
 */
object RedirectResolver {

    /** Try enabled formats in priority order; return the first VALID http(s) destination, else null. */
    fun resolve(incoming: String, formats: List<RedirectFormat>): String? {
        val parsed = RuleEngine.normalize(incoming) ?: return null
        for (fmt in formats) {
            if (!fmt.enabled) continue
            if (!matchesWrapper(fmt, parsed)) continue
            val dest = extract(fmt, incoming, parsed) ?: continue
            if (isValidDest(dest)) return dest.trim()
        }
        return null
    }

    /**
     * URL to actually launch for [incoming]: the extracted real destination if the
     * winning (first valid, priority-order) enabled format has openRealDestination
     * enabled, otherwise the original wrapper. Pure (no Android types), testable.
     */
    fun launchDestination(incoming: String, formats: List<RedirectFormat>): String {
        val parsed = RuleEngine.normalize(incoming) ?: return incoming
        for (fmt in formats) {
            if (!fmt.enabled) continue
            if (!matchesWrapper(fmt, parsed)) continue
            val dest = extract(fmt, incoming, parsed) ?: continue
            if (!isValidDest(dest)) continue
            // First valid extraction is the winner (same as resolve).
            return if (fmt.openRealDestination) dest.trim() else incoming
        }
        return incoming
    }

    /** Does [fmt]'s wrapper [pattern] match this already-normalized [parsed] URL? */
    private fun matchesWrapper(fmt: RedirectFormat, parsed: RuleEngine.ParsedUrl): Boolean {
        val host = parsed.host
        val path = parsed.path
        return when (fmt.matchType) {
            MatchType.EXACT_HOST -> hostMatches(patternHost(fmt.pattern), host)

            MatchType.PATH_PREFIX -> {
                val ph = patternHost(fmt.pattern)
                val pp = patternPath(fmt.pattern)
                hostMatches(ph, host) && path.startsWith(pp)
            }

            MatchType.SUBDOMAIN -> {
                val p = fmt.pattern.trim()
                if (p.startsWith("*.")) {
                    val base = RuleEngine.normalizeHost(p.substring(2))
                    base.isNotEmpty() && host.endsWith(".$base") && host != base
                } else {
                    false
                }
            }

            MatchType.REGEX -> {
                val full = host + path
                try {
                    full.matches(Regex(fmt.pattern))
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    /** Extract the destination from a matching wrapper, or null. */
    private fun extract(fmt: RedirectFormat, incoming: String, parsed: RuleEngine.ParsedUrl): String? =
        when (fmt.extractType) {
            ExtractType.QUERY_PARAM -> queryParamValue(fmt.extractTarget, parsed.query)
            ExtractType.BASE64_PARAM -> base64ParamValue(fmt.extractTarget, parsed.query)
            ExtractType.PATH_REGEX -> regexCapture(fmt.extractTarget, parsed.path)
            ExtractType.FULL_URL_REGEX -> regexCapture(fmt.extractTarget, incoming)
        }

    private fun queryParamValue(name: String, query: String?): String? {
        val n = name.trim()
        if (n.isEmpty() || query == null) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key == n) {
                val decoded = urlDecode(value).trim()
                if (decoded.isNotEmpty()) return decoded
            }
        }
        return null
    }

    private fun base64ParamValue(name: String, query: String?): String? {
        val n = name.trim()
        if (n.isEmpty() || query == null) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key == n) {
                val raw = value.trim()
                if (raw.isEmpty()) continue
                // The base64 may or may not be URL-encoded; try both, return the
                // first that decodes to a non-empty string.
                for (candidate in listOf(raw, urlDecode(raw)).distinct()) {
                    val c = candidate.trim()
                    if (c.isEmpty()) continue
                    try {
                        val decoded = String(java.util.Base64.getDecoder().decode(c))
                        if (decoded.isNotEmpty()) return decoded
                    } catch (e: Exception) {
                        // try the next candidate
                    }
                }
            }
        }
        return null
    }

    private fun regexCapture(pattern: String, input: String): String? {
        if (pattern.isEmpty() || input.isEmpty()) return null
        try {
            val m = Pattern.compile(pattern).matcher(input)
            if (m.matches()) {
                return if (m.groupCount() >= 1 && m.group(1) != null) m.group(1) else m.group(0)
            }
        } catch (e: Exception) {
            // invalid regex — ignore
        }
        return null
    }

    private fun isValidDest(dest: String): Boolean {
        val d = dest.trim()
        return d.startsWith("http://") || d.startsWith("https://")
    }

    private fun urlDecode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    /** Host part of a wrapper pattern (before the first '/'), trimmed. */
    private fun patternHost(pattern: String): String = pattern.trim().substringBefore('/')

    /** Path part of a wrapper pattern (from the first '/' onward), or '/' if none. */
    private fun patternPath(pattern: String): String {
        val i = pattern.indexOf('/')
        val p = if (i >= 0) pattern.substring(i) else "/"
        return if (p.isEmpty()) "/" else p
    }

    /** www-tolerant host equality: `google.com` matches `google.com` and `www.google.com`. */
    private fun hostMatches(patternHost: String, parsedHost: String): Boolean {
        val p = RuleEngine.normalizeHost(patternHost)
        val h = RuleEngine.normalizeHost(parsedHost)
        return stripWww(p) == stripWww(h)
    }

    private fun stripWww(host: String): String {
        val h = host.lowercase()
        return if (h.startsWith("www.")) h.substring(4) else h
    }
}
