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

            val lb = body.lowercase()
            val meta = lb.contains("http-equiv") && lb.contains("refresh")
            val js = lb.contains("location.replace") || lb.contains("location.href")
            if (meta || js) {
                ShortenResolveLog.i(
                    "fast-path resolve url=$current -> Interstitial after ${hops} hop(s) " +
                    "(metaRefresh=${lb.contains("http-equiv") && lb.contains("refresh")} " +
                    "jsReplace=${lb.contains("location.replace")} jsHref=${lb.contains("location.href")})"
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
