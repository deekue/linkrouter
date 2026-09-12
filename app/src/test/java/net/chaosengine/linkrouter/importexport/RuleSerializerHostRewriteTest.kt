package net.chaosengine.linkrouter.importexport

import net.chaosengine.linkrouter.rules.HostRewrite
import net.chaosengine.linkrouter.rules.RewriteKind
import net.chaosengine.linkrouter.rules.RewriteMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + back-compat coverage for the additive `hostRewrites` field.
 * Order/priority is preserved by list position (no numeric priority is stored).
 * The serializer is pure Moshi, so no Robolectric/Room is needed.
 */
class RuleSerializerHostRewriteTest {

    private fun rewrite(
        matchHost: String,
        matchType: RewriteMatchType = RewriteMatchType.EXACT_HOST,
        kind: RewriteKind = RewriteKind.HOST_SWAP,
        targetHost: String = "archive.org",
        preserveHostInPath: Boolean = false,
        enabled: Boolean = true,
        builtIn: Boolean = false,
    ) = HostRewrite(
        id = 0,
        matchHost = matchHost,
        matchType = matchType,
        kind = kind,
        targetHost = targetHost,
        preserveHostInPath = preserveHostInPath,
        enabled = enabled,
        priority = 0,
        isBuiltIn = builtIn,
    )

    @Test
    fun roundTrip_allFieldsAndOrder_preserved() {
        val hostSwap = rewrite("nytimes.com", RewriteMatchType.EXACT_WWW_HOST, RewriteKind.HOST_SWAP, "archive.org")
        val pathPrefix = rewrite("nytimes.com", RewriteMatchType.SUBDOMAIN, RewriteKind.PATH_PREFIX_REWRITE, "archive.md", preserveHostInPath = true)
        val disabledBuiltIn = rewrite("dead.example", RewriteMatchType.EXACT_HOST, RewriteKind.HOST_SWAP, "gone.org", enabled = false, builtIn = true)

        val json = RuleSerializer.toJson(emptyList(), emptyList(), emptyList(), emptyList(), listOf(hostSwap, pathPrefix, disabledBuiltIn))
        val parsed = RuleSerializer.fromHostRewriteJson(json)

        assertEquals(3, parsed.size)

        // Order is preserved by list position.
        val first = parsed[0]
        assertEquals("nytimes.com", first.matchHost)
        assertEquals(RewriteMatchType.EXACT_WWW_HOST, first.matchType)
        assertEquals(RewriteKind.HOST_SWAP, first.kind)
        assertEquals("archive.org", first.targetHost)
        assertFalse(first.preserveHostInPath)
        assertEquals(true, first.enabled)
        assertEquals(false, first.isBuiltIn)

        val mid = parsed[1]
        assertEquals(RewriteMatchType.SUBDOMAIN, mid.matchType)
        assertEquals(RewriteKind.PATH_PREFIX_REWRITE, mid.kind)
        assertEquals("archive.md", mid.targetHost)
        assertEquals(true, mid.preserveHostInPath)
        assertEquals(true, mid.enabled)
        assertEquals(false, mid.isBuiltIn)

        val third = parsed[2]
        assertEquals("dead.example", third.matchHost)
        assertEquals(false, third.enabled)
        assertEquals(true, third.isBuiltIn)
    }

    @Test
    fun roundTrip_builtinFlagSurvives() {
        val builtIn = rewrite("b.com", builtIn = true)
        val json = RuleSerializer.toJson(emptyList(), emptyList(), emptyList(), emptyList(), listOf(builtIn))
        val parsed = RuleSerializer.fromHostRewriteJson(json)
        assertEquals(true, parsed.single().isBuiltIn)
    }

    @Test
    fun roundTrip_defaultFalseFields_serializedExplicitly() {
        // A rewrite where preserveHostInPath is the default (false) must survive.
        val plain = rewrite("plain.com", preserveHostInPath = false, enabled = false)
        val json = RuleSerializer.toJson(emptyList(), emptyList(), emptyList(), emptyList(), listOf(plain))
        val parsed = RuleSerializer.fromHostRewriteJson(json)
        assertFalse(parsed.single().preserveHostInPath)
        assertFalse(parsed.single().enabled)
    }

    @Test
    fun documentWithNoHostRewrites_parsesToEmptyList() {
        // Back-compat: a file produced before host rewrites had no field at all.
        val parsed = RuleSerializer.fromHostRewriteJson("""{"version":1,"rules":[],"redirectFormats":[],"queryParamFilters":[]}""")
        assertTrue("a hostRewrite-less document must parse to an empty list", parsed.isEmpty())
    }

    @Test
    fun ruleOnlyDocument_parsesHostRewritesToEmptyList() {
        // Back-compat: even a rule-only document parses without crashing.
        val parsed = RuleSerializer.fromHostRewriteJson("""{"version":1,"rules":[]}""")
        assertTrue(parsed.isEmpty())
        assertNull("a rule-only document must yield no host rewrites", RuleSerializer.fromHostRewriteJson("""{"version":1,"rules":[]}""").getOrNull(0))
    }
}
