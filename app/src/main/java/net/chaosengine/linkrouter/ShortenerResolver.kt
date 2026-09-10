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
        while (true) {
            if (current in seen) return Result.Loop(current, hops)
            seen.add(current)

            val resp = try {
                fetcher.fetch(current)
            } catch (e: Exception) {
                return Result.Error(current, hops, e.message ?: e.javaClass.name)
            }

            // A 3xx without a Location header is not a usable redirect: bailing
            // out to body inspection would misclassify it as Resolved.
            if (resp.status in 300..399 && resp.location == null) {
                return Result.Error(current, hops, "HTTP ${resp.status} without Location header")
            }

            if (resp.status in 300..399 && resp.location != null) {
                val next = resolveRelative(current, resp.location)
                val scheme = schemeOf(next)
                if (scheme != "http" && scheme != "https") {
                    return Result.Rejected(next, hops)
                }
                hops++
                if (hops > maxHops) return Result.MaxHops(next, hops)
                current = next
                continue
            }

            // Settled page (e.g. 200). Final destination, or a meta/JS interstitial?
            val body = if (resp.body.length > BODY_CAP) resp.body.substring(0, BODY_CAP) else resp.body
            val lb = body.lowercase()
            val meta = lb.contains("http-equiv") && lb.contains("refresh")
            val js = lb.contains("location.replace") || lb.contains("location.href")
            if (meta || js) {
                return Result.Interstitial(current, hops)
            }
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
