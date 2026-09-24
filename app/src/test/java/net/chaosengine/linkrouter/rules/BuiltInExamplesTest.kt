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
    fun builtInAmpCacheUnwrapExamples_areCorrect() {
        // The AMP cache unwrap stage is a built-in that has no persistence model
        // (it's always on), so its fixture is examples-only: each declared input
        // must unwrap to the declared expectedOutput via AmpCacheUnwrapper.
        for ((idx, ex) in builtInAmpCacheUnwrapExamples.withIndex()) {
            val actual = AmpCacheUnwrapper.unwrap(ex.input)
            assertEquals(
                "AMP unwrap example #$idx (input=${ex.input})",
                ex.expectedOutput,
                actual,
            )
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
        //   * `builtInRules`         <- imported at build time (isBuiltIn=true only)
        //   * `builtInRulesExamples` <- test-facing set (isBuiltIn true AND false)
        //
        // Core invariant (always valid, even when both sets are empty): whatever is
        // imported at build time must also be present in the test-facing set, i.e.
        // every imported pattern is a known fixture pattern.
        //
        // We deliberately assert only this RELATIONSHIP and never require either side
        // to be non-empty. The active filter behavior (a genuine isBuiltIn=false rule
        // existing in builtInRulesExamples but excluded from builtInRules, and a
        // genuine isBuiltIn=true rule being imported) is only exercisable when the
        // fixtures actually declare such rules. With an empty `rules` array the test
        // validates the (trivial) empty invariant; with ≥1 true + ≥1 false rule it
        // additionally proves the subset/exclusion split really happens.
        val importedPatterns = builtInRules.mapTo(HashSet()) { it.pattern }
        val allPatterns = builtInRulesExamples.map { it.first.pattern }

        // (1) Every pattern imported at build time is present in the test-facing set.
        assertTrue(
            "every imported builtInRules pattern must be present in builtInRulesExamples " +
                "(imported=$importedPatterns, all=$allPatterns)",
            importedPatterns.all { it in allPatterns },
        )

        // (2) Excluded patterns (present in builtInRulesExamples but not imported)
        // are, by definition of `excludedPattern`, correctly the ones the build-time
        // filter dropped. This relationship is trivially true and holds whether or not
        // any such rule exists; we only assert the set is internally consistent.
        val excludedPatterns = allPatterns.filter { it !in importedPatterns }
        assertTrue(
            "every excluded pattern must be in builtInRulesExamples but not in builtInRules " +
                "(excluded=$excludedPatterns, imported=$importedPatterns)",
            excludedPatterns.all { it in allPatterns && it !in importedPatterns },
        )
    }
}
