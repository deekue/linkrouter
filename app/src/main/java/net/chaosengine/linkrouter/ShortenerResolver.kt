package net.chaosengine.linkrouter

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pure-JVM shortener resolver (fast path for server-side 3xx shorteners).
 *
 * Follows 3xx `Location` headers (resolving relative ones against the base).
 * When it lands on a settled page (e.g. 200), it inspects the body:
 *  - contains `<meta http-equiv=refresh>` or `location.replace` / `location.href`
 *      => INTERSTITIAL (needs WebView escalation)
 *  - otherwise
 *      => RESOLVED (final destination)
 *
 * Guards: non-http(s) target => Rejected; revisit => Loop; > maxHops => MaxHops;
 * exception => Error.
 *
 * This file must not import any `android.*` classes so the resolver core is
 * unit-testable on the plain JVM. [RealFetcher] uses only `java.net`.
 */
object ShortenerResolver {

    data class HopResponse(val status: Int, val location: String?, val body: String)

    fun interface Fetcher {
        fun fetch(url: String): HopResponse
    }

    sealed class Result {
        data class Resolved(val finalUrl: String, val hops: Int) : Result()
        data class Interstitial(val url: String, val hops: Int) : Result()
        data class Rejected(val url: String, val hops: Int) : Result()   // non-http(s)
        data class Loop(val url: String, val hops: Int) : Result()
        data class MaxHops(val url: String, val hops: Int) : Result()
        data class Error(val url: String, val hops: Int, val message: String) : Result()
    }

    const val MAX_HOPS = 6

    // JVM-safe logging (no android.* on this file — unit-tested on plain JVM).
    private val LOG = Logger.getLogger(ShortenerResolver::class.java.name)

    private const val BODY_CAP = 64 * 1024
    private const val TIMEOUT = 6000
    private const val UA = "Mozilla/5.0 (Linux; Android 14; LinkRouter/1.0 resolver)"

    fun resolve(startUrl: String, fetcher: Fetcher, maxHops: Int = MAX_HOPS): Result {
        var current = startUrl
        var hops = 0
        val seen = HashSet<String>()
        ShortenResolveLog.i("fast-path resolve start url=$startUrl (maxHops=$maxHops)")
        while (true) {
            if (current in seen) {
                ShortenResolveLog.w("fast-path resolve url=$current -> Loop after ${hops} hop(s) (URL already visited)")
                return Result.Loop(current, hops)
            }
            seen.add(current)

            val resp = try {
                fetcher.fetch(current)
            } catch (e: Exception) {
                ShortenResolveLog.e("fast-path fetch threw after ${hops} hop(s) url=$current: ${e.javaClass.simpleName}: ${e.message}")
                return Result.Error(current, hops, e.message ?: e.javaClass.name)
            }

            ShortenResolveLog.i("fast-path hop#$hops url=$current http=${resp.status} location=${resp.location ?: "<none>"}")

            // A 3xx without a Location header is not a usable redirect: bailing
            // out to body inspection would misclassify it as Resolved.
            if (resp.status in 300..399 && resp.location == null) {
                ShortenResolveLog.w("fast-path resolve url=$current -> Error after ${hops} hop(s): HTTP ${resp.status} without Location header")
                return Result.Error(current, hops, "HTTP ${resp.status} without Location header")
            }

            if (resp.status in 300..399 && resp.location != null) {
                val next = resolveRelative(current, resp.location)
                val scheme = schemeOf(next)
                if (scheme != "http" && scheme != "https") {
                    ShortenResolveLog.w("fast-path resolve url=$current -> Rejected after ${hops} hop(s): non-http(s) target '$next' (scheme=$scheme)")
                    return Result.Rejected(next, hops)
                }
                hops++
                if (hops > maxHops) {
                    ShortenResolveLog.w("fast-path resolve url=$current -> MaxHops after ${hops} hop(s): exceeded $maxHops (target='$next')")
                    return Result.MaxHops(next, hops)
                }
                current = next
                continue
            }

            // Settled page (e.g. 200). bit.ly-style shortener, final destination,
            // or a meta/JS interstitial?
            val body = if (resp.body.length > BODY_CAP) resp.body.substring(0, BODY_CAP) else resp.body

            // bit.ly does NOT send an HTTP redirect: it answers 200 with an
            // interstitial page that carries the real destination in an
            // `<a id="action:continue" href="...">` anchor. Unlike the meta/JS
            // interstitials below (which need a WebView to execute), the target
            // is verbatim in the body, so resolve it directly. Checked BEFORE the
            // generic meta/JS heuristic on purpose: the bit.ly marker is a hard,
            // self-contained destination, and a page could carry both — trusting
            // the explicit href is strictly better than a WebView escalation we
            // can avoid. On extraction failure we fall through to the existing
            // behaviour (no regression).
            val bitly = extractBitlyTarget(body)
            if (bitly != null) {
                ShortenResolveLog.i("fast-path resolve url=$current -> Resolved (bit.ly target '$bitly') after ${hops} hop(s)")
                return Result.Resolved(bitly, hops)
            }

            // A `<meta http-equiv="refresh" ...>` page (e.g. a Google Docs `/pub`
            // interstitial) already carries its destination in the body. Resolve it
            // directly instead of escalating to a WebView: the WebView path times
            // out on slow ad/tracker beacons (main frame never fires
            // `onPageFinished`) and then falls back to the shortener URL. Only an
            // absolute http(s) target is accepted; anything else (no url, relative,
            // tel:, ...) falls through to the meta/JS Interstitial behaviour below,
            // preserving the existing classification (no regression).
            val metaTarget = extractMetaRefreshTarget(body)
            if (metaTarget != null) {
                ShortenResolveLog.i("fast-path resolve url=$current -> Resolved (meta refresh target '$metaTarget') after ${hops} hop(s)")
                return Result.Resolved(metaTarget, hops)
            }

            // Interstitial heuristics, now precise: a REAL meta-refresh tag with
            // a clean content value, or an actual JS redirect WRITE. Substring
            // matching (e.g. the old `lb.contains("location.href")`) produced
            // false positives on pages that merely mention these strings (e.g. a
            // JS-embedded template literal, or a read of `window.location.href`)
            // and misclassified real destinations as Interstitial.
            val meta = hasCleanMetaRefresh(body)
            val js = hasJsRedirect(body)
            if (meta || js) {
                ShortenResolveLog.i(
                    "fast-path resolve url=$current -> Interstitial after ${hops} hop(s) " +
                    "(metaRefresh=$meta jsRedirect=$js)"
                )
                return Result.Interstitial(current, hops)
            }
            ShortenResolveLog.i("fast-path resolve url=$current -> Resolved after ${hops} hop(s)")
            return Result.Resolved(current, hops)
        }
    }

    private fun resolveRelative(base: String, location: String): String {
        return try {
            val l = URI(location)
            if (l.isAbsolute) l.toString() else URI(base).resolve(l).toString()
        } catch (e: Exception) {
            location
        }
    }

    private fun schemeOf(url: String): String {
        return try {
            URI(url).scheme?.lowercase() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Extract the real destination from a bit.ly interstitial body.
     *
     * bit.ly serves a 200 HTML page (no HTTP redirect) whose target lives in an
     * anchor: `<a id="action:continue" href="https://...">`. We locate the first
     * `<a ...>` tag whose attributes include `id="action:continue"` (single or
     * double quotes), then pull the `href` value from that same tag.
     *
     * Dependency-free string scanning, consistent with the existing substring
     * heuristics. Returns `null` when no such anchor / href is present (or it is
     * empty / whitespace) so the caller falls back to its existing behaviour.
     */
    private fun extractBitlyTarget(body: String): String? {
        val lb = body.lowercase()
        var searchFrom = 0
        while (true) {
            // Find the next opening <a> tag (word-boundary so we don't match <aside>, <area>, ...).
            // Case-insensitive: HTML tag names are not case-sensitive (<A> is valid).
            val tagStart = indexOfAnchorTag(lb, searchFrom)
            if (tagStart < 0) return null

            val tagEnd = lb.indexOf('>', tagStart)
            if (tagEnd < 0) return null
            // Operate on the original (un-lowercased) substring so href values are
            // returned exactly as written; only tag/attribute names were matched by name.
            val attrs = body.substring(tagStart + 1, tagEnd)
            searchFrom = tagEnd + 1

            // Only consider anchors carrying the bit.ly marker id.
            if (!attrsHasId(attrs, "action:continue")) continue
            val href = extractAttr(attrs, "href")?.trim()
            if (!href.isNullOrEmpty()) return href
            // href missing/empty: keep scanning in case a later anchor has a real
            // one, but never return whitespace / empty (would regress).
        }
    }

    /**
     * Extract the destination from a `<meta http-equiv="refresh">` redirect page.
     *
     * Real-world forms handled (all case-insensitive, single or double quotes):
     *  - `content="0; url=https://example.com/x"`  (delay, space after `;`)
     *  - `content="0;url=https://example.com/x"`   (delay, no space)
     *  - `content="https://example.com/x"`         (url only, no delay)
     *  - `url=<unquoted value>` inside the content attribute
     *
     * Reuses [extractAttr] for the attribute value (consistent with
     * [extractBitlyTarget]). Returns `null` when there is no meta-refresh tag, no
     * usable target, an empty / whitespace target, or a non-absolute-http(s)
     * target (e.g. relative or `tel:`) so the caller's existing behaviour stands.
     */
    private fun extractMetaRefreshTarget(body: String): String? {
        val lb = body.lowercase()
        var searchFrom = 0
        while (true) {
            val tagStart = indexOfMetaTag(lb, searchFrom)
            if (tagStart < 0) return null
            val tagEnd = lb.indexOf('>', tagStart)
            if (tagEnd < 0) return null
            // Operate on the original substring so the URL is returned as written.
            val attrs = body.substring(tagStart + 1, tagEnd)
            searchFrom = tagEnd + 1

            // Only consider meta tags that are a refresh redirect.
            if (!attrsContainsHttpEquiv(attrs, "refresh")) continue

            val content = extractAttr(attrs, "content")?.trim() ?: continue
            val target = metaRefreshUrlFromContent(content)?.trim() ?: continue
            if (target.isEmpty()) continue
            // Only an absolute http(s) URL is a usable destination here.
            if (!target.startsWith("http://") && !target.startsWith("https://")) continue
            return target
        }
    }

    /**
     * True only when [body] carries a REAL `<meta http-equiv="refresh">` tag
     * whose `content` attribute is a clean (non-JS) value.
     *
     * A genuine meta-refresh `content` is a plain value such as `0; url=...` or
     * a bare delay like `5`. A JS-embedded template (the false positive, e.g. a
     * Google Docs `/pub` page whose body literally contains the string
     * `'<meta http-equiv="refresh" content="0; url=' + d + '>'`) carries quote
     * characters and/or a `+` (string concatenation) inside the content, so any
     * of those means this tag is NOT a clean redirect and is excluded. This is
     * what pins Interstitial for real meta-refresh pages (see the pinned tests)
     * while letting the JS-embedded false positive fall through to Resolved.
     *
     * Reuses [indexOfMetaTag], [attrsContainsHttpEquiv] and [extractAttr] (the
     * same helpers [extractMetaRefreshTarget] uses) so there is no duplicated
     * tag-scanning logic.
     */
    private fun hasCleanMetaRefresh(body: String): Boolean {
        val lb = body.lowercase()
        var searchFrom = 0
        while (true) {
            val tagStart = indexOfMetaTag(lb, searchFrom)
            if (tagStart < 0) return false
            val tagEnd = lb.indexOf('>', tagStart)
            if (tagEnd < 0) return false
            // Operate on the original substring so the value is read as written.
            val attrs = body.substring(tagStart + 1, tagEnd)
            searchFrom = tagEnd + 1

            // Only consider meta tags that are a refresh redirect.
            if (!attrsContainsHttpEquiv(attrs, "refresh")) continue

            val content = extractAttr(attrs, "content")?.trim() ?: continue
            if (content.isEmpty()) continue
            // A real meta-refresh content is a plain value; a JS-embedded
            // template smuggles in quotes and/or `+` (concatenation), so any of
            // those disqualifies this tag as a clean redirect.
            if (content.contains('\'') || content.contains('"') || content.contains('+')) continue
            return true
        }
    }

    /**
     * True only when [body] contains a JS redirect WRITE:
     *  - `location.replace(`, or
     *  - `location.assign(`, or
     *  - `location.href` immediately followed (ignoring whitespace) by `=`.
     *
     * A plain READ such as `window.location.href)` must NOT match — the
     * distinguishing factor is a trailing `=` (write) vs `)`/end-of-token
     * (read). This pins Interstitial for genuine JS redirects (see the pinned
     * tests) while letting a read-only reference fall through to Resolved.
     */
    private fun hasJsRedirect(body: String): Boolean {
        val lb = body.lowercase()
        if (lb.contains("location.replace(") || lb.contains("location.assign(")) return true
        val token = "location.href"
        var i = 0
        while (true) {
            i = lb.indexOf(token, i)
            if (i < 0) return false
            var j = i + token.length
            while (j < lb.length && lb[j].isWhitespace()) j++
            if (j < lb.length && lb[j] == '=') return true
            i += token.length
        }
    }

    /** Return the start index of the next `<meta` opening tag (word boundary); or -1. */
    private fun indexOfMetaTag(body: String, from: Int): Int {
        var i = from
        while (true) {
            i = body.indexOf("<meta", i)
            if (i < 0) return -1
            // Word boundary: the char after `<meta` must not be a letter/digit
            // (rules out `<metadata>` and friends).
            val next = if (i + 5 < body.length) body[i + 5] else ' '
            if (!next.isLetterOrDigit()) return i
            i += 1
        }
    }

    /** Does this meta tag's attribute string carry `http-equiv="refresh"` (single or double quotes)? */
    private fun attrsContainsHttpEquiv(attrs: String, value: String): Boolean {
        val lb = attrs.lowercase()
        return lb.contains("http-equiv=\"$value\"") || lb.contains("http-equiv='$value'")
    }

    /**
     * Pull the redirect URL out of a meta-refresh `content` attribute value.
     * Accepts an explicit `url=<target>` (any casing) or a bare absolute URL.
     * Returns `null` when no target can be parsed.
     *
     * [content] is already an unquoted attribute value (as returned by
     * [extractAttr]), so the `url=` value runs simply to the next whitespace:
     * URLs do not contain whitespace.
     */
    private fun metaRefreshUrlFromContent(content: String): String? {
        val lb = content.lowercase()
        // Preferred: an explicit `url=` token (any casing).
        var i = 0
        while (true) {
            i = lb.indexOf("url=", i)
            if (i < 0) break
            // Word boundary so we match the token, not a substring of a longer word.
            // The preceding char may be whitespace, `;`, or another non-word
            // separator (the content `0;url=…` form puts `;` before `url=`).
            if (i != 0 && content[i - 1].isLetterOrDigit()) { i += 1; continue }
            var j = i + "url=".length
            while (j < content.length && content[j].isWhitespace()) j++
            var end = j
            while (end < content.length && !content[end].isWhitespace()) end++
            val v = content.substring(j, end).trim()
            if (v.isNotEmpty()) return v
        }
        // Fallback: no `url=` token. Strip a leading `delay;` portion and treat
        // what remains as a bare URL.
        val bare = content.substringAfterLast(';').trim()
        return bare.ifEmpty { null }
    }

    /** Return the start index of the next `<a` opening tag with a word boundary after `a`; or -1. */
    private fun indexOfAnchorTag(body: String, from: Int): Int {
        var i = from
        while (true) {
            i = body.indexOf("<a", i)
            if (i < 0) return -1
            // Word boundary: the char after `<a` must not be a letter/digit (rules out <aside>, <area>, ...).
            val next = if (i + 2 < body.length) body[i + 2] else ' '
            if (!next.isLetterOrDigit()) return i
            i += 2
        }
    }

    /** Does this anchor's attribute string carry `id="action:continue"` (single or double quotes)? */
    private fun attrsHasId(attrs: String, idValue: String): Boolean {
        val lb = attrs.lowercase()
        return lb.contains("id=\"$idValue\"") || lb.contains("id='$idValue'")
    }

    /** Extract the value of a named attribute from an attribute string; null if absent. */
    private fun extractAttr(attrs: String, name: String): String? {
        val lb = attrs.lowercase()
        val prefix = name.lowercase() + "="
        var i = 0
        while (i < attrs.length) {
            i = lb.indexOf(prefix, i)
            if (i < 0) return null
            // Ensure the attribute starts at a word boundary (preceded by whitespace / start).
            if (i != 0 && !attrs[i - 1].isWhitespace()) { i += prefix.length; continue }
            var j = i + prefix.length
            while (j < attrs.length && attrs[j].isWhitespace()) j++
            if (j >= attrs.length) return null
            val quote = attrs[j]
            if (quote == '"' || quote == '\'') {
                val close = attrs.indexOf(quote, j + 1)
                if (close < 0) return null
                return attrs.substring(j + 1, close)
            }
            // Unquoted value: read until whitespace or end of the attribute string.
            var end = j
            while (end < attrs.length && !attrs[end].isWhitespace()) end++
            return attrs.substring(j, end)
        }
        return null
    }

    /**
     * Real fetcher using `HttpURLConnection` (JVM only, no Android).
     * Only called from the dispatcher when a shortener host is enabled.
     */
    class RealFetcher : Fetcher {
        override fun fetch(url: String): HopResponse {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                // Refuse compressed bodies: a gzip'd response would corrupt the
                // interstitial body heuristics (meta/JS detection) downstream.
                setRequestProperty("Accept-Encoding", "identity")
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
            }
            return try {
                val status = conn.responseCode
                val location = conn.getHeaderField("Location")
                LOG.log(Level.FINE, "Shortener fetch: url=$url status=$status location=$location")
                val body = readBody(conn)
                HopResponse(status, location, body)
            } catch (e: Exception) {
                ShortenResolveLog.e("fast-path RealFetcher.fetch url=$url: ${e.javaClass.name}: ${e.message}")
                throw e
            } finally {
                conn.disconnect()
            }
        }

        private fun readBody(conn: HttpURLConnection): String {
            return try {
                val bos = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(4096)
                    var total = 0
                    while (total < BODY_CAP) {
                        val n = input.read(buf, 0, minOf(buf.size, BODY_CAP - total))
                        if (n == -1) break
                        bos.write(buf, 0, n)
                        total += n
                    }
                }
                String(bos.toByteArray(), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
    }
}
