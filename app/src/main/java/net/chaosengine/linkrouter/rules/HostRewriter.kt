package net.chaosengine.linkrouter.rules

import java.util.regex.Pattern

/**
 * Pure-JVM (no Android imports) host rewriter. Given the LAUNCHED final URL
 * and the caller's enabled [HostRewrite] rules (top-priority first), [rewrite]
 * returns the same URL with the first matching rule applied — preserving
 * scheme casing, path percent-encoding, query, fragment, and (for non-implicit
 * ports) the port. Rule matching is untouched: this only shapes the URL that
 * gets launched, and it never affects which routing rule matched.
 *
 * Supports both [RewriteKind.HOST_SWAP] and [RewriteKind.PATH_PREFIX_REWRITE]
 * (see [applyOne]). Host matching (host-only, path irrelevant) is shared; the
 * path is only reshaped for PATH_PREFIX_REWRITE. P1 is a pure library — there
 * are no production call sites yet (that is P2).
 *
 * Every entry point is defensive: non-web URLs, malformed rules, malformed
 * ports — any of these return the input UNCHANGED instead of throwing (D6).
 */
object HostRewriter {

    // Same web-scheme gate as RuleEngine.normalize / QueryParamStripper:
    // non-http(s) schemes (mailto:, etc.) are out of scope and returned unchanged.
    private val SCHEME = Pattern.compile("^([Hh][Tt][Tt][Pp][Ss]?):\\/\\/(.+)$")

    /**
     * Apply the FIRST matching enabled [rules] entry (list order is priority:
     * top = highest) to [url]. Single-pass: the output is never re-evaluated,
     * so a rule can never loop against its own output. Returns [url] UNCHANGED
     * when the URL is not a web URL, no rule matches, or every matching rule is
     * malformed (D6: never silently pretend, never crash on malformed rules).
     */
    fun rewrite(url: String, rules: List<HostRewrite>): String {
        for (rule in rules) {
            if (!rule.enabled) continue
            val result = applyOne(url, rule) ?: continue
            return result
        }
        return url
    }

    /**
     * Single-rule core: returns the rewritten URL, or null when the rule does
     * not apply to [url] (non-web URL or host mismatch). Pure string in/out —
     * the unit-testable heart of the feature. Never throws.
     *
     * [RewriteKind.HOST_SWAP]: the URL host is replaced by the rule's
     * `targetHost` (normalized), the path (with its leading `/` forced),
     * query, and fragment are preserved verbatim, and a non-implicit port is
     * carried over to the target host (`x.com:8080/a` → `twitter.com:8080/a`).
     * Any userinfo in the original URL is dropped — credentials must not follow
     * the host to a different site.
     *
     * [RewriteKind.PATH_PREFIX_REWRITE]: matching is host-only (path is
     * match-irrelevant) — once the host matches, the path is rewritten. When
     * [HostRewrite.preserveHostInPath] is true the *literal incoming host* (as
     * received, `www.` and all, userinfo/port removed) is prepended:
     * `newPath = "/" + literalHost + originalPath`, so
     * `nytimes.com/blah` → `archive.md/nytimes.com/blah` and
     * `www.nytimes.com/blah` → `archive.md/www.nytimes.com/blah`. The authority
     * is the normalized `targetHost`, a non-implicit port is carried over, and
     * the original path/query/fragment are kept verbatim. A missing original
     * path is normalized to `/` so an incoming `nytimes.com` becomes
     * `archive.md/nytimes.com/` (documented, acceptable).
     */
    fun applyOne(url: String, rule: HostRewrite): String? {
        val parsed = parse(url) ?: return null
        if (!matchesHost(parsed.host, rule)) return null

        val target = RuleEngine.normalizeHost(rule.targetHost)
        if (target.isEmpty()) return null // malformed stored rule (D6)
        val portPart = parsed.portIfExplicit(rule.kind)

        return when (rule.kind) {
            RewriteKind.HOST_SWAP ->
                buildUrl(parsed.scheme, target, portPart, parsed.path, parsed.query, parsed.fragment)

            RewriteKind.PATH_PREFIX_REWRITE -> {
                // Path is match-irrelevant; once the host matched, rewrite it.
                // Normalise a missing path to "/" so the prefix is terminated.
                val originalPath = if (parsed.path.isEmpty()) "/" else parsed.path
                val newPath = if (rule.preserveHostInPath && parsed.rawHost.isNotEmpty()) {
                    "/" + parsed.rawHost + originalPath
                } else {
                    originalPath
                }
                buildUrl(parsed.scheme, target, portPart, newPath, parsed.query, parsed.fragment)
            }
        }
    }

    /**
     * Does [parsedHost] (already in [RuleEngine.normalizeHost] form) match
     * [rule]'s host scope? Exposed separately so the UI preview can answer
     * "would this rule apply to this host?" without a full URL.
     */
    fun matchesHost(parsedHost: String, rule: HostRewrite): Boolean {
        val host = RuleEngine.normalizeHost(parsedHost)
        val matcher = RuleEngine.normalizeHost(rule.matchHost)
        if (host.isEmpty() || matcher.isEmpty()) return false
        return when (rule.matchType) {
            RewriteMatchType.EXACT_HOST -> stripWww(host) == stripWww(matcher)
            RewriteMatchType.EXACT_WWW_HOST -> host == matcher
            RewriteMatchType.SUBDOMAIN -> host.endsWith(".$matcher") && host != matcher
        }
    }

    /**
     * Preview helper for the editor: [sampleUrl] with [rule] applied, or
     * [sampleUrl] unchanged when the rule does not match (mirrors
     * `RedirectFormatValidator.preview`).
     */
    fun preview(rule: HostRewrite, sampleUrl: String): String = applyOne(sampleUrl, rule) ?: sampleUrl

    // ------------------------------------------------------------------
    // String-level URL decomposition — the same manual algorithm as
    // QueryParamStripper/RuleEngine.normalize, but keeping the ORIGINAL
    // scheme casing and the fragment (which ParsedUrl drops), so the
    // launched URL is left untouched except for the host we replace.
    // ------------------------------------------------------------------

    private data class ParsedParts(
        val scheme: String,
        val host: String,      // normalized (lowercase, IDN→ASCII) form
        val rawHost: String,   // literal incoming host as received (original case), userinfo+port stripped, www. preserved
        val port: Int?,        // null when absent OR non-numeric
        val path: String,      // always starts with '/' (empty when URL has no path segment)
        val query: String?,    // null when absent or empty
        val fragment: String?  // null when absent
    ) {
        fun portIfExplicit(kind: RewriteKind): String? =
            if (port != null && !isImplicitPort(scheme, port)) ":$port" else null
    }

    private fun parse(url: String): ParsedParts? {
        val m = SCHEME.matcher(url)
        if (!m.matches()) return null
        val scheme = m.group(1) ?: return null
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

        // Fragment separates off first (it may appear after the query).
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
            if (query.isEmpty()) query = null
        }

        // Drop userinfo — credentials never follow the host to a different site.
        var auth = authority
        val at = auth.lastIndexOf('@')
        if (at >= 0) auth = auth.substring(at + 1)

        var host = auth
        var port: Int? = null
        val colon = auth.indexOf(':')
        if (colon >= 0) {
            host = auth.substring(0, colon)
            port = auth.substring(colon + 1).toIntOrNull()
        }
        // Literal incoming host as received (original case), userinfo/port stripped,
        // www. preserved — used as the path prefix when preserveHostInPath is true.
        val rawHost = host
        host = RuleEngine.normalizeHost(host)
        if (host.isEmpty()) return null

        return ParsedParts(
            scheme = scheme,
            host = host,
            rawHost = rawHost,
            port = port,
            path = tail,
            query = query,
            fragment = fragment,
        )
    }

    private fun buildUrl(
        scheme: String,
        host: String,
        portPart: String?,
        path: String,
        query: String?,
        fragment: String?,
    ): String {
        val sb = StringBuilder()
        sb.append(scheme).append("://").append(host)
        if (portPart != null) sb.append(portPart)
        sb.append(path)
        if (query != null) sb.append('?').append(query)
        if (fragment != null) sb.append('#').append(fragment)
        return sb.toString()
    }

    /** Implicit port for the ORIGINAL scheme: 443 for https, 80 for http. */
    private fun isImplicitPort(scheme: String, port: Int): Boolean =
        (scheme.equals("https", ignoreCase = true) && port == 443) ||
            (scheme.equals("http", ignoreCase = true) && port == 80)

    private fun stripWww(host: String): String {
        val h = host.lowercase()
        return if (h.startsWith("www.")) h.substring(4) else h
    }
}
