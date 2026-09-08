package net.chaosengine.linkrouter.rules

import net.chaosengine.linkrouter.LinkRouter
import java.net.IDN
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Pure JVM matching core (no Android types) so it is unit-testable.
 *
 * Normalization: http -> https; strip credentials, query and fragment;
 * lowercase + IDN-normalize the host. See DESIGN.md section 5.
 */
object RuleEngine {

    data class ParsedUrl(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: String?,
    )

    private val SCHEME = Pattern.compile("^([Hh][Tt][Tt][Pp][Ss]?):\\/\\/(.+)$")
    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun regexFor(pattern: String): Regex =
        regexCache.computeIfAbsent(pattern) { Regex(it) }

    /** Returns null for non-web schemes (mailto:, etc.) which must be ignored. */
    fun normalize(url: String): ParsedUrl? {
        val m = SCHEME.matcher(url)
        if (!m.matches()) return null

        val rest = m.group(2) ?: return null
        var cut = rest.length
        for (i in rest.indices) {
            when (rest[i]) {
                '/', '?', '#' -> { cut = i; break }
            }
        }
        val authority = rest.substring(0, cut)
        var tail = rest.substring(cut)
        if (tail.isNotEmpty() && tail[0] != '/') tail = "/" + tail

        var fragment: String? = null
        val fragIdx = tail.indexOf('#')
        if (fragIdx >= 0) {
            fragment = tail.substring(fragIdx + 1)
            tail = tail.substring(0, fragIdx)
        }
        var query: String? = null
        val qIdx = tail.indexOf('?')
        if (qIdx >= 0) {
            query = tail.substring(qIdx + 1)
            tail = tail.substring(0, qIdx)
        }

        var auth = authority
        val at = auth.lastIndexOf('@')
        if (at >= 0) auth = auth.substring(at + 1)

        var host = auth
        var port = -1
        val colon = auth.indexOf(':')
        if (colon >= 0) {
            host = auth.substring(0, colon)
            port = auth.substring(colon + 1).toIntOrNull() ?: -1
        }
        host = normalizeHost(host)

        return ParsedUrl(
            scheme = "https",
            host = host,
            port = port,
            path = if (tail.isEmpty()) "/" else tail,
            query = query,
        )
    }

    fun normalizeHost(host: String): String {
        val h = host.lowercase().trim('/')
        if (h.isEmpty()) return h
        return try {
            IDN.toASCII(h)
        } catch (e: IllegalArgumentException) {
            h
        }
    }

    fun queryHasParam(query: String, name: String): Boolean {
        if (query.isEmpty()) return false
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key == name) return true
        }
        return false
    }

    /** DESIGN.md section 2: loop guard -- never re-match an already-handled URL. */
    fun isLoopGuard(handledExtra: Boolean, url: String): Boolean {
        if (handledExtra) return true
        val parsed = normalize(url) ?: return false
        return queryHasParam(parsed.query ?: "", LinkRouter.LOOP_GUARD_PARAM)
    }

    /**
     * Unwrap a known redirect-wrapper URL to its real destination, for rule
     * matching. When a user taps a link inside Google (search results, Gmail,
     * etc.) the default browser receives a `google.com/url?q=<dest>` redirect
     * URL, NOT the destination itself. Without unwrapping, a rule for the
     * destination (e.g. `*.facebook.com`) can never match the `google.com`
     * wrapper. Returns the decoded inner http(s) URL, or null if [url] is not
     * a recognized redirect wrapper. Pure (no Android types) so it is unit-testable.
     *
     * @suppress superseded by user-managed [RedirectResolver] formats; retained
     * as the zero-format safety fallback.
     */
    @Deprecated(
        message = "Use RedirectResolver.resolve(url, formats) with user-managed redirect formats.",
        replaceWith = ReplaceWith("RedirectResolver.resolve(url, formats)", "net.chaosengine.linkrouter.rules.RedirectResolver"),
    )
    fun unwrapRedirect(url: String): String? {
        val parsed = normalize(url) ?: return null
        if (parsed.host != "google.com" && parsed.host != "www.google.com") return null
        val query = parsed.query ?: return null
        val q = query.split('&')
            .firstOrNull { it.startsWith("q=") }
            ?.removePrefix("q=")
            ?.let { urlDecode(it) }
            ?.trim()
            ?: return null
        if (q.isEmpty()) return null
        // Guard against a wrapped-in-wrapper (q= itself pointing at another redirect).
        return if (q.startsWith("http://") || q.startsWith("https://")) q else null
    }

    private fun urlDecode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    private fun patternHost(pattern: String): String {
        val host = pattern.substringBefore('/')
        return normalizeHost(host)
    }

    private fun patternPath(prefixPattern: String): String {
        val i = prefixPattern.indexOf('/')
        val p = if (i >= 0) prefixPattern.substring(i) else "/"
        return if (p.isEmpty()) "/" else p
    }

    /** Score per DESIGN.md section 5.3, or null when the rule does not match. */
    fun scoreRule(rule: Rule, parsed: ParsedUrl): Int? {
        val host = parsed.host
        val path = parsed.path
        return when (rule.matchType) {
            MatchType.EXACT_HOST ->
                if (host == patternHost(rule.pattern)) 30 else null

            MatchType.PATH_PREFIX -> {
                val ph = patternHost(rule.pattern)
                val pp = patternPath(rule.pattern)
                if (host == ph && path.startsWith(pp)) 40 else null
            }

            MatchType.SUBDOMAIN -> {
                val p = rule.pattern.trim()
                if (p.startsWith("*.")) {
                    val base = normalizeHost(p.substring(2))
                    if (base.isNotEmpty() && host.endsWith(".$base") && host != base) 20 else null
                } else {
                    null
                }
            }

            MatchType.REGEX -> {
                val full = host + path
                val ok = try {
                    full.matches(regexFor(rule.pattern))
                } catch (e: Exception) {
                    false
                }
                if (ok) 10 else null
            }
        }
    }

    fun resolve(rules: List<Rule>, url: String): Rule? {
        val parsed = normalize(url) ?: return null
        return resolve(rules, parsed)
    }

    fun resolve(rules: List<Rule>, parsed: ParsedUrl): Rule? {
        var best: Rule? = null
        var bestScore = -1
        for (rule in rules) {
            if (!rule.enabled) continue
            val score = scoreRule(rule, parsed) ?: continue
            val cur = best
            val better = cur == null ||
                score > bestScore ||
                (score == bestScore && rule.priority > cur.priority)
            if (better) {
                best = rule
                bestScore = score
            }
        }
        return best
    }
}
