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
    fun `meta refresh body is interstitial`() {
        val body = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://final.example/\">"
        val fetcher = FakeFetcher(
            mapOf(
                "https://t.co/abc" to ShortenerResolver.HopResponse(302, "https://interstitial.example/x", ""),
                "https://interstitial.example/x" to ShortenerResolver.HopResponse(200, null, body),
            )
        )
        val result = ShortenerResolver.resolve("https://t.co/abc", fetcher)
        val interstitial = result as ShortenerResolver.Result.Interstitial
        assertEquals("https://interstitial.example/x", interstitial.url)
        assertEquals(1, interstitial.hops)
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
