package net.chaosengine.linkrouter.rules

import java.util.regex.Pattern

/**
 * Unwraps AMP cache URLs to the canonical origin URL. Pure-JVM (no Android
 * imports), no side effects, no logging.
 *
 * AMP cache URLs embed the original host + path in the cache URL's path:
 *   https://<prefix>.<cache-domain>/<serving-dir>[/s]/<original-host>/<original-path>[?query]
 * where <cache-domain> is one of [CACHE_DOMAINS] and the `/s` infix is present
 * iff the origin document was https (absent => the origin was http).
 *
 * The original host is read STRAIGHT OUT OF THE PATH (always present and
 * verbatim) — it is NOT reverse-engineered from the cache subdomain prefix,
 * which is an ambiguous, lossy transform (`-` <-> `.` collisions).
 *
 * Examples:
 *   https://example-com.cdn.ampproject.org/c/s/example.com/news/story?id=42
 *     -> https://example.com/news/story?id=42
 *   https://example-com.cdn.ampproject.org/c/example.com/   (no /s => http origin)
 *     -> http://example.com/
 *   https://example-com.cdn.ampproject.org/c/s/example.com/?amp_latest_update_time=1719000000
 *     -> https://example.com/   (cache-only query param stripped)
 *
 * OkHttp's `okhttp3.HttpUrl` would be the robust-parsing choice, but it is NOT
 * on this module's classpath (app/build.gradle.kts ships no okhttp dependency;
 * the equivalent pure-JVM resolvers here — [HostRewriter], [QueryParamStripper],
 * ShortenerResolver — all parse manually with java.util.regex + String ops).
 * To stay dependency-light and match sibling style, this parses manually.
 *
 * This file must not import any `android.*` classes so it is unit-testable on
 * the plain JVM. It is a pure utility: no Android, no networking, no logging.
 */
object AmpCacheUnwrapper {

    /** Web-scheme gate: AMP caches are always https. We capture the scheme so
     *  we can REJECT any non-https scheme (http, mailto:, ...) explicitly,
     *  rather than accepting the first thing that matches. */
    private val URL = Pattern.compile("^([Hh][Tt][Tt][Pp][Ss]?):\\/\\/(.+)$")

    /** Known AMP cache domains. The host must be a SUBDOMAIN of one of these
     *  (a `<prefix>.` in front) — the bare cache domain with no prefix is not
     *  an unwrap-able serving URL and is rejected. */
    private val CACHE_DOMAINS = listOf("cdn.ampproject.org", "www.bing-amp.com")

    /** Serving dirs we unwrap. Only content dirs: `c` (content — the common
     *  case), `v` (viewer), `wp`, `cert`. Image dirs `i`/`ii` and anything
     *  else are NOT unwrapped (returned as null) per spec. */
    private val SERVED_DIRS = setOf("c", "v", "wp", "cert")

    /** The literal path segment that flags an https origin. */
    private const val S_INFIX = "s"

    /** Cache-only query params that AMP caches append to the outgoing URL.
     *  They are NOT part of the origin URL and must be stripped on unwrap.
     *  Compared case-insensitively, like [QueryParamStripper]. */
    private val CACHE_ONLY_PARAMS = setOf("amp_latest_update_time", "amp_cache_update_time")

    /**
     * Unwrap [url] to its canonical origin URL, or null when [url] is not a
     * recognized AMP cache URL (wrong scheme, bare cache domain, unknown
     * serving dir, missing original host, or unparseable). Pure in/out; never
     * throws. See [AmpCacheUnwrapper] class KDoc for the URL shape.
     */
    fun unwrap(url: String): String? {
        val m = URL.matcher(url)
        if (!m.matches()) return null
        val scheme = m.group(1) ?: return null
        val rest = m.group(2) ?: return null

        // AMP caches are always served over https; reject http and all other
        // schemes explicitly (sibling files accept either, but the spec is
        // stricter here — a http cache URL is not a valid unwrap input).
        if (!scheme.equals("https", ignoreCase = true)) return null

        // Split off the authority (up to the first /, ?, or #).
        var cut = rest.length
        for (i in rest.indices) {
            when (rest[i]) {
                '/', '?', '#' -> { cut = i; break }
            }
        }
        val authority = rest.substring(0, cut)
        var tail = rest.substring(cut)
        if (tail.isNotEmpty() && tail[0] != '/') tail = "/" + tail

        // Fragment separates off first (not part of a serving URL; ignored).
        val fragIdx = tail.indexOf('#')
        if (fragIdx >= 0) tail = tail.substring(0, fragIdx)

        // Query separates off; we rebuild it below minus cache-only params.
        var query: String? = null
        val qIdx = tail.indexOf('?')
        if (qIdx >= 0) {
            query = tail.substring(qIdx + 1)
            tail = tail.substring(0, qIdx)
        }

        // Host must be a subdomain of a known cache domain (prefix + dot).
        // The bare cache domain (no prefix) is rejected.
        val host = stripPort(authority).lowercase()
        if (!isCacheHost(host)) return null

        // Path segments: [servingDir](, "s")?, originalHost, path...
        // Split WITHOUT dropping empty segments: empty path segments (double
        // slashes) are legal and distinct (`/a//b` != `/a/b`) and must survive
        // verbatim. Only the structural slots (serving dir, `/s` infix,
        // original host) are matched — and compared case-insensitively, since
        // cache authorities/serving dirs are case-insensitive in practice —
        // while the original host and remaining path are read VERBATIM.
        val rawSegments = tail.trimStart('/').split('/')
        // First segment is the serving dir; must be a content dir we serve.
        val dir = rawSegments[0]
        if (SERVED_DIRS.none { it.equals(dir, ignoreCase = true) }) return null

        val sInfixPresent = rawSegments.getOrNull(1)?.equals(S_INFIX, ignoreCase = true) == true
        val hostIndex = if (sInfixPresent) 2 else 1
        // originalHost is the NEXT segment after the dir (and the `s`).
        if (hostIndex >= rawSegments.size) return null // missing original host (D6)
        if (rawSegments[hostIndex].isEmpty()) return null // empty host slot => malformed
        val originalHost = rawSegments[hostIndex]
        // Remaining path segments; empty segments (double slashes) preserved.
        val originalPathSegments = rawSegments.subList(hostIndex + 1, rawSegments.size)

        val protocol = if (sInfixPresent) "https" else "http"
        val path = if (originalPathSegments.isEmpty()) "/"
        else "/" + originalPathSegments.joinToString("/")

        val keptQuery = if (query.isNullOrEmpty()) null else rebuildQuery(query)
        val result = "$protocol://$originalHost$path" + (keptQuery?.let { "?$it" } ?: "")
        return result
    }

    /** True if [host] is a SUBDOMAIN of a known cache domain (i.e. has a
     *  non-empty `<prefix>.` in front) — anchored on a dot so lookalikes such
     *  as `evilcdn.ampproject.org` are rejected. */
    private fun isCacheHost(host: String): Boolean {
        for (d in CACHE_DOMAINS) {
            if (host.endsWith(".$d") && host.length > d.length + 1) return true
        }
        return false
    }

    /** Drop a trailing `:port` and any userinfo, lowercasing is done by caller. */
    private fun stripPort(authority: String): String {
        var a = authority
        val at = a.lastIndexOf('@')
        if (at >= 0) a = a.substring(at + 1)
        val colon = a.indexOf(':')
        if (colon >= 0) a = a.substring(0, colon)
        return a
    }

    /** Rebuild [query] minus [CACHE_ONLY_PARAMS], preserving order and the raw
     *  encoding of every surviving param (string-level, like [QueryParamStripper]).
     *  Returns null when nothing survives. */
    private fun rebuildQuery(query: String): String? {
        val kept = mutableListOf<String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key.lowercase() in CACHE_ONLY_PARAMS) continue
            kept.add(pair)
        }
        return kept.joinToString("&").ifEmpty { null }
    }
}
