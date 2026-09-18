package net.chaosengine.linkrouter

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class ShortenerResolverTest {

    private class FakeFetcher(private val script: Map<String, ShortenerResolver.HopResponse>) :
        ShortenerResolver.Fetcher {
        override fun fetch(url: String): ShortenerResolver.HopResponse =
            script[url] ?: ShortenerResolver.HopResponse(200, null, "")
    }

    @Test
    fun `single hop 302 then clean 200 resolves`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(302, "https://example.com/final", ""),
                "https://example.com/final" to ShortenerResolver.HopResponse(200, null, "<html>hello</html>"),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/final", resolved.finalUrl)
        assertEquals(1, resolved.hops)
    }

    @Test
    fun `multi hop follows redirects with correct hop count`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/a" to ShortenerResolver.HopResponse(301, "https://hop1.example/b", ""),
                "https://hop1.example/b" to ShortenerResolver.HopResponse(302, "https://hop2.example/c", ""),
                "https://hop2.example/c" to ShortenerResolver.HopResponse(200, null, "final page"),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/a", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://hop2.example/c", resolved.finalUrl)
        assertEquals(2, resolved.hops)
    }

    @Test
    fun `relative location is resolved against base`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(302, "/path/to/page", ""),
                "https://t.co/path/to/page" to ShortenerResolver.HopResponse(200, null, "ok"),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://t.co/path/to/page", resolved.finalUrl)
        assertEquals(1, resolved.hops)
    }

    @Test
    fun `non http target is rejected`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/tel" to ShortenerResolver.HopResponse(302, "tel:12345", ""),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/tel", fetcher)
        val rejected = result as ShortenerResolver.Result.Rejected
        assertTrue(rejected.url.startsWith("tel:"))
    }

    @Test
    fun `mailto target is rejected`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/mail" to ShortenerResolver.HopResponse(302, "mailto:x@example.com", ""),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/mail", fetcher)
        assertTrue(result is ShortenerResolver.Result.Rejected)
    }

    @Test
    fun `revisit is a loop`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://a.example/1" to ShortenerResolver.HopResponse(302, "https://b.example/2", ""),
                "https://b.example/2" to ShortenerResolver.HopResponse(302, "https://a.example/1", ""),
            )
        )
        val result = ShortenerResolver.resolve("https://a.example/1", fetcher)
        val loop = result as ShortenerResolver.Result.Loop
        assertEquals("https://a.example/1", loop.url)
        assertEquals(2, loop.hops)
    }

    @Test
    fun `exceeding maxHops yields MaxHops`() {
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/1" to ShortenerResolver.HopResponse(302, "https://t.co/2", ""),
                "https://t.co/2" to ShortenerResolver.HopResponse(302, "https://t.co/3", ""),
                "https://t.co/3" to ShortenerResolver.HopResponse(302, "https://t.co/4", ""),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/1", fetcher, maxHops = 2)
        val max = result as ShortenerResolver.Result.MaxHops
        assertEquals("https://t.co/4", max.url)
        assertEquals(3, max.hops)
    }

    @Test
    fun `meta refresh body with http target resolves to target`() {
        // Changed from the old "interstitial" expectation: a meta-refresh page
        // carries a usable http(s) destination, which is now resolved directly
        // (avoiding the WebView escalation that times out on slow beacons).
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://final.example/\">"
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(302, "https://interstitial.example/x", ""),
                "https://interstitial.example/x" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://final.example/", resolved.finalUrl)
        assertEquals(1, resolved.hops)
    }

    @Test
    fun `meta refresh body without http target is interstitial`() {
        // A meta-refresh whose url is not an absolute http(s) target (no usable
        // destination) keeps the legacy Interstitial classification.
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=tel:12345\">"
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/abc", fetcher)
        val interstitial = result as ShortenerResolver.Result.Interstitial
        assertEquals("https://t.co/abc", interstitial.url)
    }

    @Test
    fun `location replace body is interstitial`() {
        val body = "<script>window.location.replace('https://final.example/');</script>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/abc", fetcher)
        val interstitial = result as ShortenerResolver.Result.Interstitial
        assertEquals("https://t.co/abc", interstitial.url)
        assertEquals(0, interstitial.hops)
    }

    @Test
    fun `location href body is interstitial`() {
        val body = "<script>window.location.href = 'https://final.example/';</script>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        assertTrue(ShortenerResolver.resolve("https://t.co/abc", fetcher) is ShortenerResolver.Result.Interstitial)
    }

    @Test
    fun `fetcher exception yields Error`() {
        val fetcher = ShortenerResolver.Fetcher {
            throw RuntimeException("connection refused")
        }
        val result = ShortenerResolver.resolve("https://t.co/boom", fetcher)
        val error = result as ShortenerResolver.Result.Error
        assertEquals("https://t.co/boom", error.url)
        assertNotNull(error.message)
    }

    @Test
    fun `bitly action continue anchor resolves to real target`() {
        val body = "<html><body><a id=\"action:continue\" href=\"https://example.com/real\"></a></body></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `bitly anchor is not treated as an interstitial`() {
        val body = "<html><body><a id=\"action:continue\" href=\"https://example.com/real\"></a></body></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        assertTrue(result !is ShortenerResolver.Result.Interstitial)
    }

    @Test
    fun `bitly anchor single quotes resolves to real target`() {
        val body = "<html><body><a id='action:continue' href='https://example.com/real'></a></body></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `mixed case anchor tag and attributes resolves to real target`() {
        // Uppercase tag `<A` and mixed-case `ID`/`href` attributes must still match.
        val body = "<html><body><A ID='action:continue' href='https://example.com/real'></A></body></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `action continue with empty href resolves to current url`() {
        val body = "<html><body><a id=\"action:continue\" href=\"\"></a></body></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://bit.ly/abc", resolved.finalUrl)
    }

    @Test
    fun `no action continue anchor still resolves to current url`() {
        // Regression guard: unchanged behaviour when the bit.ly marker is absent.
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, "<html><body>hello</body></html>"),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://bit.ly/abc", resolved.finalUrl)
    }

    @Test
    fun `aside tag is not matched as an anchor`() {
        // <aside ... id=...> must not be mistaken for the <a> marker anchor.
        val body = "<html><aside id=\"action:continue\"></aside><body>hello</body></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://bit.ly/abc", resolved.finalUrl)
    }

    // ------------------------------------------------------------------
    // Meta-refresh interstitial resolution (Google Docs /pub, slow trackers).
    // These pages carry a hard, usable destination in the body — the resolver
    // should return it directly instead of escalating to a WebView (which
    // times out on slow ad/tracker beacons and then falls back to the URL).
    // ------------------------------------------------------------------

    @Test
    fun `meta refresh no space resolves to target`() {
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://example.com/real\"></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `meta refresh spaced resolves to target`() {
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"0; url=https://example.com/real\"></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `meta refresh single quotes resolves to target`() {
        val body = "<html><head><meta http-equiv='refresh' content='0;url=https://example.com/real'></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `meta refresh bare url only resolves to target`() {
        // No delay; content is the URL itself.
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"https://example.com/real\"></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }

    @Test
    fun `meta refresh with non http target is not resolved to that target`() {
        // tel: is not a usable destination for this resolver; it must fall
        // through to the meta/JS Interstitial classification.
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=tel:12345\"></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        // Must not be Resolved to tel:. The safest expectation: an Interstitial
        // (the legacy classification that still triggers WebView fallback).
        val interstitial = result as ShortenerResolver.Result.Interstitial
        assertEquals("https://bit.ly/abc", interstitial.url)
    }

    @Test
    fun `meta refresh without url falls through to interstitial`() {
        // `content="5"` has no url; must not be extracted. Existing behaviour
        // (Interstitial) applies.
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"5\"></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val interstitial = result as ShortenerResolver.Result.Interstitial
        assertEquals("https://bit.ly/abc", interstitial.url)
    }

    @Test
    fun `normal 200 page with no meta refresh resolves to current url`() {
        // Regression guard: unchanged behaviour when the meta-refresh marker is absent.
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, "<html><body>hello</body></html>"),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://bit.ly/abc", resolved.finalUrl)
    }

    @Test
    fun `multi hop 301 to meta refresh pub page resolves to pub target`() {
        // Mimics the bit.ly -> Google Docs /pub log scenario: a 301 Location
        // to a 200 page that is a meta-refresh redirect.
        val pubBody = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://docs.google.com/viewer?a=ABC&embedded=true\"></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/adhdlist" to ShortenerResolver.HopResponse(
                    301,
                    "https://docs.google.com/pub/abc/pub?embedded=true&single=true",
                    ""
                ),
                "https://docs.google.com/pub/abc/pub?embedded=true&single=true" to ShortenerResolver.HopResponse(
                    200, null, pubBody
                ),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/adhdlist", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://docs.google.com/viewer?a=ABC&embedded=true", resolved.finalUrl)
        assertEquals(1, resolved.hops)
    }

    @Test
    fun `mixed case meta tag and attributes resolves to target`() {
        // Uppercase tag `<META`, mixed-case `HTTP-EQUIV` / `content`, and
        // single-quoted value must still match.
        val body = "<html><head><META HTTP-EQUIV='refresh' content='0;url=https://example.com/real'></head></html>"
        val fetcher = FakeFetcher(
            mapOf(
                "https://bit.ly/abc" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://bit.ly/abc", fetcher)
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("https://example.com/real", resolved.finalUrl)
    }
}

/**
 * [ShortenerResolver.RealFetcher] against a local [HttpServer] on 127.0.0.1
 * (ephemeral port) — exercises the pure-JVM fetch path end-to-end, including
 * relative redirects, an interstitial body, and a 3xx without Location.
 */
class RealFetcherTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a") { ex -> redirect(ex, "/b", 302) }
        server.createContext("/b") { ex -> redirect(ex, "/final", 301) }
        server.createContext("/final") { ex -> respond(ex, 200, "done") }
        server.createContext("/i") { ex ->
            respond(ex, 200, "<html><script>window.location.replace('https://x.example/')</script></html>")
        }
        server.createContext("/noLoc") { ex ->
            // 302 with NO Location header (no header set at all).
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
        port = server.address.port
    }

    private fun redirect(ex: com.sun.net.httpserver.HttpExchange, location: String, status: Int) {
        ex.responseHeaders.set("Location", location)
        ex.sendResponseHeaders(status, -1)
        ex.close()
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `relative redirect chain resolves to final url`() {
        startServer()
        val result = ShortenerResolver.resolve("http://127.0.0.1:$port/a", ShortenerResolver.RealFetcher())
        val resolved = result as ShortenerResolver.Result.Resolved
        assertEquals("http://127.0.0.1:$port/final", resolved.finalUrl)
        // Hop count = number of redirects FOLLOWED (a -> b, b -> final), per the
        // resolver's existing semantics (pinned by the FakeFetcher tests above).
        assertEquals(2, resolved.hops)
    }

    @Test
    fun `js location replace body is an interstitial`() {
        startServer()
        val result = ShortenerResolver.resolve("http://127.0.0.1:$port/i", ShortenerResolver.RealFetcher())
        val interstitial = result as ShortenerResolver.Result.Interstitial
        assertEquals("http://127.0.0.1:$port/i", interstitial.url)
    }

    @Test
    fun `3xx without location header is an error`() {
        // Regression: previously this fell through to body inspection and could
        // be misclassified as Resolved.
        startServer()
        val result = ShortenerResolver.resolve("http://127.0.0.1:$port/noLoc", ShortenerResolver.RealFetcher())
        val error = result as ShortenerResolver.Result.Error
        assertTrue(error.message.contains("Location"))
    }
}
