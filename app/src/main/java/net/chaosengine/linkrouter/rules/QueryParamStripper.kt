package net.chaosengine.linkrouter.rules

import net.chaosengine.linkrouter.LinkRouter
import java.util.regex.Pattern

/**
 * Pure-JVM (no Android imports) query-string cleaner. Given the LAUNCHED url
 * and the caller's enabled [QueryParamFilter] set, [strip] returns the same url
 * minus every query key an applicable filter selects — preserving scheme,
 * authority, path, fragment, the param order of survivors, and the raw encoding
 * of every param that is kept. Rule matching is untouched: this only shapes the
 * URL that gets launched.
 */
object QueryParamStripper {

    // Same web-scheme gate as RuleEngine.normalize: non-http(s) schemes
    // (mailto:, etc.) are out of scope and returned unchanged.
    private val SCHEME = Pattern.compile("^([Hh][Tt][Tt][Pp][Ss]?):\\/\\/(.+)$")

    /**
     * Strip enabled [filters] from [url]'s query string, preserving scheme,
     * authority, path, fragment, remaining param order, and raw encoding of
     * everything kept. Returns [url] UNCHANGED when: url is not a web URL
     * (no http/https scheme — check with RuleEngine.normalize returning null),
     * the URL has no query string, or no enabled filter matches.
     *
     * Matching: a filter applies when (host == null) OR
     * ShortenerMatcher.domainMatches(urlHost, filter.host), AND the query
     * contains a key equal to filter.param case-insensitively. All keys
     * matching any applicable filter are removed (case-insensitive). Values are
     * never inspected. The loop-guard param [LinkRouter.LOOP_GUARD_PARAM]
     * ("__lr") is never stripped (defensive: filters are user data, the guard
     * is internal). Rebuild is string-level so the raw encoding of kept params
     * is preserved — this deliberately does NOT use android.net.Uri.
     */
    fun strip(url: String, filters: List<QueryParamFilter>): String {
        val m = SCHEME.matcher(url)
        if (!m.matches()) return url
        val scheme = m.group(1) ?: return url
        val rest = m.group(2) ?: return url

        // Split into authority, then path + query + fragment using the same
        // manual algorithm as RuleEngine.normalize, but keeping the ORIGINAL
        // scheme casing (normalize forces "https") so the launched URL is left
        // untouched except for the params we strip.
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

        // No query (or an empty query string) → nothing to strip.
        val qIdx = tail.indexOf('?')
        if (qIdx < 0) return url
        val query = tail.substring(qIdx + 1)
        if (query.isEmpty()) return url
        val path = tail.substring(0, qIdx)

        val urlHost = RuleEngine.normalize(url)?.host ?: return url
        val stripSet = buildStripSet(urlHost, filters)
        if (stripSet.isEmpty()) return url

        val kept = mutableListOf<String>()
        var removed = false
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (stripSet.contains(key.lowercase())) {
                removed = true
                continue
            }
            kept.add(pair)
        }
        if (!removed) return url

        val sb = StringBuilder()
        sb.append(scheme).append("://").append(authority).append(path)
        val remaining = kept.joinToString("&")
        if (remaining.isNotEmpty()) sb.append('?').append(remaining)
        if (fragment != null) sb.append('#').append(fragment)
        return sb.toString()
    }

    /**
     * The lowercased set of param names to remove for this [urlHost]: every
     * enabled, applicable filter contributes its param name (global when
     * [QueryParamFilter.host] is null, else domain-scoped via
     * [ShortenerMatcher.domainMatches]). The internal loop-guard param is never
     * included, even if a filter named it.
     */
    private fun buildStripSet(urlHost: String, filters: List<QueryParamFilter>): Set<String> {
        val set = mutableSetOf<String>()
        for (f in filters) {
            if (!f.enabled) continue
            val hostOk = if (f.host == null) true
            else ShortenerMatcher.domainMatches(urlHost, f.host)
            if (!hostOk) continue
            val p = f.param.lowercase()
            if (p != LinkRouter.LOOP_GUARD_PARAM) set.add(p)
        }
        return set
    }
}
