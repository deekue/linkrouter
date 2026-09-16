package net.chaosengine.linkrouter.rules

/**
 * One example input->expected-output pair for a built-in rule.
 * Sourced from the "examples" arrays in linkrouter-rules-examples.json and
 * code-generated at build time into `builtInExamples`. Used only to (a) show
 * example pairs in the rule edit dialogs and (b) unit tests that assert each
 * rule resolves as declared. Deliberately NOT part of the runtime import /
 * Room schema: RuleSerializer ignores this and it is never persisted.
 */
data class RuleExample(
    val input: String,
    val expectedOutput: String,
)
