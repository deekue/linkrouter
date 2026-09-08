package net.chaosengine.linkrouter.importexport

import net.chaosengine.linkrouter.rules.ShortenerHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + back-compat coverage for the additive `shortenerHosts` field
 * (M8). The serializer is pure Moshi, so no Robolectric/Room is needed.
 */
class RuleSerializerShortenerHostTest {

    private fun host(
        name: String,
        host: String,
        pathPrefix: String? = null,
        enabled: Boolean = true,
        builtIn: Boolean = false,
    ) = ShortenerHost(
        id = 0,
        name = name,
        host = host,
        pathPrefix = pathPrefix,
        enabled = enabled,
        priority = 0,
        isBuiltIn = builtIn,
    )

    @Test
    fun roundTrip_hostOnlyAndPathPrefix_preservesAllFields() {
        val hostOnly = host("t.co", "t.co", pathPrefix = null, enabled = true, builtIn = false)
        val pathPrefixed = host("tiktok", "www.tiktok.com", pathPrefix = "/t/", enabled = true, builtIn = false)
        val disabled = host("dead", "example.com", pathPrefix = null, enabled = false, builtIn = false)

        val json = RuleSerializer.toJson(emptyList(), emptyList(), emptyList(), listOf(hostOnly, pathPrefixed, disabled))
        val parsed = RuleSerializer.fromShortenerHostJson(json)

        assertEquals(3, parsed.size)

        val t = parsed.single { it.host == "t.co" }
        assertNull("a host-only shortener must keep a null pathPrefix", t.pathPrefix)
        assertEquals(true, t.enabled)
        assertEquals(false, t.isBuiltIn)

        val tk = parsed.single { it.host == "www.tiktok.com" }
        assertEquals("/t/", tk.pathPrefix)
        assertEquals(true, tk.enabled)

        val d = parsed.single { it.host == "example.com" }
        assertEquals(false, d.enabled)
    }

    @Test
    fun roundTrip_builtinFlagSurvives() {
        val builtIn = host("bit.ly", "bit.ly", pathPrefix = null, enabled = false, builtIn = true)
        val json = RuleSerializer.toJson(emptyList(), emptyList(), emptyList(), listOf(builtIn))
        val parsed = RuleSerializer.fromShortenerHostJson(json)
        assertEquals(true, parsed.single().isBuiltIn)
    }

    @Test
    fun documentWithNoShortenerHosts_parsesToEmptyList() {
        // Back-compat: a file produced before M8 had no shortenerHosts field at all.
        val parsed = RuleSerializer.fromShortenerHostJson("""{"version":1,"rules":[],"redirectFormats":[],"queryParamFilters":[]}""")
        assertTrue("a shortener-less document must parse to an empty host list", parsed.isEmpty())
    }

    @Test
    fun ruleOnlyDocument_parsesShortenerHostsToEmptyList() {
        // Back-compat: even a rule-only document parses without crashing.
        val parsed = RuleSerializer.fromShortenerHostJson("""{"version":1,"rules":[]}""")
        assertTrue(parsed.isEmpty())
    }
}
