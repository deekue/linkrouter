package net.chaosengine.linkrouter.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For every built-in rule that declares "examples" in linkrouter-rules-examples.json
 * (surfaced as the generated `builtInExamples` map), assert RedirectResolver.resolve
 * turns the declared input into the declared expectedOutput. This is the single
 * source-of-truth check that these rules behave as documented
 * (see plans/examples-in-linkrouter-json.md).
 */
class BuiltInExamplesTest {

    @Test
    fun every_declared_example_resolves_as_expected() {
        val byName = builtInRedirectFormats.associateBy { it.name }
        for ((name, examples) in builtInExamples) {
            val fmt = byName[name]
                ?: throw AssertionError("rule '$name' declares examples but has no built-in rule by that name")
            for ((idx, ex) in examples.withIndex()) {
                val actual = RedirectResolver.resolve(ex.input, listOf(fmt))
                assertEquals(
                    "example #$idx of '$name' (input=${ex.input})",
                    ex.expectedOutput,
                    actual,
                )
            }
        }
    }

    @Test
    fun at_least_one_example_is_declared() {
        // Guard against the JSON "examples" field being silently dropped from the seed.
        assertTrue(
            "expected at least one built-in rule to declare examples",
            builtInExamples.values.any { it.isNotEmpty() },
        )
    }
}
