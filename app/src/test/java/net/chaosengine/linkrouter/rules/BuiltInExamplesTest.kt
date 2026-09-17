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
    fun builtInParamFilterExamples_areCorrect() {
        val byParam = builtInQueryParamFilters.associateBy { it.param }
        for ((param, examples) in builtInParamFilterExamples) {
            if (examples.isEmpty()) continue
            val found = byParam[param]
                ?: throw AssertionError("param filter '$param' declares examples but has no built-in rule with that param")
            val filter = found.copy(enabled = true)
            for (ex in examples) {
                val actual = QueryParamStripper.strip(ex.input, listOf(filter))
                assertEquals(
                    "param=$param input=${ex.input}",
                    ex.expectedOutput,
                    actual,
                )
            }
        }
    }

    @Test
    fun builtInHostRewriteExamples_areCorrect() {
        val byHost = builtInHostRewrites.associateBy { it.matchHost }
        for ((matchHost, examples) in builtInHostRewriteExamples) {
            if (examples.isEmpty()) continue
            val found = byHost[matchHost]
                ?: throw AssertionError("host rewrite '$matchHost' declares examples but has no built-in rule with that matchHost")
            val rule = found.copy(enabled = true)
            for (ex in examples) {
                val actual = HostRewriter.rewrite(ex.input, listOf(rule))
                assertEquals(
                    "host=$matchHost input=${ex.input}",
                    ex.expectedOutput,
                    actual,
                )
            }
        }
    }

    @Test
    fun builtInRulesExamples_resolveToTheirTarget() {
        // For every "rules" fixture (isBuiltIn true AND false), resolve each declared
        // input with the rule (enabled) and assert the destination is what the JSON
        // declares. expectedOutput is the rule's destination: targetActivity when
        // that is the meaningful destination, else targetPackage.
        for ((rule, examples) in builtInRulesExamples) {
            if (examples.isEmpty()) continue
            val enabled = rule.copy(enabled = true)
            val expectedDest = rule.targetActivity ?: rule.targetPackage
            for ((idx, ex) in examples.withIndex()) {
                val matched = RuleEngine.resolve(listOf(enabled), ex.input)
                assertEquals(
                    "rule '${rule.pattern}' example #$idx did not match (input=${ex.input})",
                    enabled,
                    matched,
                )
                assertEquals(
                    "rule '${rule.pattern}' example #$idx (input=${ex.input}) resolved to wrong target",
                    expectedDest,
                    matched!!.targetActivity ?: matched.targetPackage,
                )
            }
        }
    }

    @Test
    fun isBuiltInFalse_rules_areExcludedFromImportButPresentInExamples() {
        // The model has no isBuiltIn field, so the two generated vals are the
        // observable signal of the build-time filter:
        //   * `builtInRules`       <- imported at build time  (isBuiltIn=true only)
        //   * `builtInRulesExamples` <- test-facing set (isBuiltIn true AND false)
        // A genuine isBuiltIn=false fixture therefore sits in builtInRulesExamples
        // but is ABSENT from builtInRules. Assert both directions really happen.
        val importedPatterns = builtInRules.mapTo(HashSet()) { it.pattern }
        val allPatterns = builtInRulesExamples.map { it.first.pattern }

        // (1) imported set is a proper subset of the test-facing set.
        assertTrue(
            "imported builtInRules should be a subset of builtInRulesExamples",
            allPatterns.containsAll(importedPatterns),
        )
        val excludedPatterns = allPatterns.filter { it !in importedPatterns }
        assertTrue(
            "expected at least one isBuiltIn=false rule present in builtInRulesExamples but excluded from builtInRules",
            excludedPatterns.isNotEmpty(),
        )
        // (2) the isBuiltIn=true fixture(s) ARE imported at build time.
        assertTrue(
            "expected at least one isBuiltIn=true rule to be imported into builtInRules",
            importedPatterns.isNotEmpty() && importedPatterns.all { it in allPatterns },
        )
    }
}
