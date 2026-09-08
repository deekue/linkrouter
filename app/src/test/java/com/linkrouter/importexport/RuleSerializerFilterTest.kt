package com.linkrouter.importexport

import com.linkrouter.rules.QueryParamFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for the additive `queryParamFilters` field (M9 / Split C).
 * The serializer is pure Moshi, so no Robolectric/Room is needed — this mirrors
 * the style of the other pure-JVM rule tests.
 */
class RuleSerializerFilterTest {

    private fun filter(
        param: String,
        host: String? = null,
        enabled: Boolean = true,
        builtIn: Boolean = false,
    ) = QueryParamFilter(
        id = 0,
        name = param,
        param = param,
        host = host,
        enabled = enabled,
        priority = 0,
        isBuiltIn = builtIn,
    )

    @Test
    fun roundTrip_globalAndScopedFilters_preservesAllFields() {
        val global = filter("utm_source", host = null, enabled = true, builtIn = false)
        val scoped = filter("_t", host = "tiktok.com", enabled = true, builtIn = false)
        val disabledScoped = filter("si", host = "youtube.com", enabled = false, builtIn = false)

        val json = RuleSerializer.toJson(emptyList(), emptyList(), listOf(global, scoped, disabledScoped))
        val parsed = RuleSerializer.fromFilterJson(json)

        assertEquals(3, parsed.size)

        val g = parsed.single { it.param == "utm_source" }
        assertNull("a global filter must keep a null host", g.host)
        assertEquals(true, g.enabled)
        assertEquals(false, g.isBuiltIn)

        val s = parsed.single { it.param == "_t" }
        assertEquals("tiktok.com", s.host)
        assertEquals(true, s.enabled)
        assertEquals(false, s.isBuiltIn)

        val d = parsed.single { it.param == "si" }
        assertEquals("youtube.com", d.host)
        assertEquals(false, d.enabled)
        assertEquals(false, d.isBuiltIn)
    }

    @Test
    fun roundTrip_builtinFlagSurvives() {
        val builtIn = filter("utm_medium", host = null, enabled = true, builtIn = true)
        val json = RuleSerializer.toJson(emptyList(), emptyList(), listOf(builtIn))
        val parsed = RuleSerializer.fromFilterJson(json)
        assertEquals(true, parsed.single().isBuiltIn)
    }

    @Test
    fun documentWithNoFilters_parsesToEmptyList() {
        // Back-compat: a file produced before the feature had no field at all.
        val parsed = RuleSerializer.fromFilterJson("""{"version":1,"rules":[],"redirectFormats":[]}""")
        assertTrue("a filter-less document must parse to an empty filter list", parsed.isEmpty())
    }

    @Test
    fun missingFieldEntirely_parsesToEmptyList() {
        // Back-compat: even a rule-only document (no formats, no filters) parses.
        val parsed = RuleSerializer.fromFilterJson("""{"version":1,"rules":[]}""")
        assertTrue(parsed.isEmpty())
    }
}
