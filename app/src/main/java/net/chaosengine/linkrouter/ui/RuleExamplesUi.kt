package net.chaosengine.linkrouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.chaosengine.linkrouter.rules.RuleExample

/**
 * Read-only list of example input->output pairs for the built-in rule being
 * edited, from a generated examples map keyed by the rule's identifying field
 * (e.g. `param` for query-param filters, `matchHost` for host rewrites).
 * Rendered only when a matching non-empty list exists, capped at `max` entries
 * so the dialog stays compact. Shared by the query-param and host-rewrite
 * editor dialogs (see RuleExamplesUi).
 */
@Composable
internal fun BuiltInRuleExamples(
    key: String?,
    examples: Map<String, List<RuleExample>>,
    max: Int = 3,
) {
    key ?: return
    val list = examples[key]
    if (list.isNullOrEmpty()) return
    Text(
        text = "Built-in examples",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    list.take(max).forEach { ex ->
        RuleExampleRow(input = ex.input, expectedOutput = ex.expectedOutput)
    }
}

/**
 * A single input->expected-output pair, shown as two compact lines in a small
 * card. The expected output is prefixed with an arrow ("→ ") to make the
 * direction of the transformation explicit.
 */
@Composable
internal fun RuleExampleRow(input: String, expectedOutput: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .padding(top = 6.dp),
    ) {
        Text(
            text = input,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "→ $expectedOutput",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
