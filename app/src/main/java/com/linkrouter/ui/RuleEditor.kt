package com.linkrouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PrivateConnectivity
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.browsers.StrategyTable
import com.linkrouter.browsers.WebViewTarget
import com.linkrouter.rules.MatchType
import com.linkrouter.rules.OpenMode
import com.linkrouter.rules.Rule
import com.linkrouter.rules.RuleValidator
import com.linkrouter.rules.RuleValidator.Result
import com.linkrouter.rules.RuleValidator.Result.Invalid
import com.linkrouter.rules.RuleValidator.Result.Valid

/**
 * Rule editor (DESIGN.md section 10):
 * - Pattern input with auto-detected type shown as an editable chip.
 * - Live match preview against representative URLs + a Test dry-run.
 * - Target picker: grid of installed browsers.
 * - Private toggle with the capability badge (DESIGN.md section 7).
 * - System dialog on first rule save (DESIGN.md section 11).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RuleEditor(
    existing: Rule?,
    browsers: List<BrowserInfo>,
    onSave: (pattern: String, type: MatchType, pkg: String, activity: String?, mode: OpenMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var pattern by rememberSaveable { mutableStateOf(existing?.pattern ?: "") }
    var matchType by rememberSaveable {
        mutableStateOf(existing?.matchType?.name ?: RuleValidator.detectType(existing?.pattern ?: "").name)
    }
    var targetPkg by rememberSaveable { mutableStateOf(existing?.targetPackage ?: "") }
    var openMode by rememberSaveable { mutableStateOf(existing?.openMode?.name ?: OpenMode.NORMAL.name) }
    var testUrl by rememberSaveable { mutableStateOf("https://www.example.com/path") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showRoutingDialog by remember { mutableStateOf(false) }

    val type = MatchType.valueOf(matchType)
    val validation: Result = remember(pattern, matchType) {
        RuleValidator.validate(pattern, type)
    }

    val selectedBrowser = browsers.firstOrNull { it.packageName == targetPkg }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New rule" else "Edit rule") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                // Pattern
                Text("Pattern", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        // Auto-detect the type chip as the user types (DESIGN.md 5/10).
                        matchType = RuleValidator.detectType(it).name
                        testResult = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("example.com  |  *.app.com  |  docs.example.com/api  |  regex") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                // Editable type chips (wrap to a second line on narrow screens)
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Type", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
                    MatchType.entries.forEach { mt ->
                        FilterChip(
                            selected = type == mt,
                            onClick = { matchType = mt.name },
                            label = { Text(chipLabel(mt)) },
                        )
                    }
                }

                if (validation is Invalid) {
                    Text(
                        text = (validation as Invalid).reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Target picker: grid of installed browsers
                Text("Target browser", style = MaterialTheme.typography.labelLarge)
                BrowserGrid(
                    browsers = browsers,
                    selected = targetPkg,
                    onSelect = { targetPkg = it.packageName; testResult = null },
                )

                Spacer(Modifier.height(12.dp))

                // Private toggle + capability badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PrivateConnectivity,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Open in private window", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Switch(
                        checked = openMode == OpenMode.PRIVATE.name,
                        onCheckedChange = { openMode = if (it) OpenMode.PRIVATE.name else OpenMode.NORMAL.name },
                    )
                }
                val capable = StrategyTable.capabilityFor(targetPkg)
                if (openMode == OpenMode.PRIVATE.name) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .background(
                                when (capable) {
                                    com.linkrouter.browsers.PrivateCapability.REAL ->
                                        MaterialTheme.colorScheme.primaryContainer
                                    com.linkrouter.browsers.PrivateCapability.ATTEMPT ->
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    com.linkrouter.browsers.PrivateCapability.NONE ->
                                        MaterialTheme.colorScheme.errorContainer
                                },
                                RoundedCornerShape(8.dp),
                            )
                            .padding(10.dp),
                    ) {
                        Text(
                            text = when (capable) {
                                com.linkrouter.browsers.PrivateCapability.REAL ->
                                    "Verified: opens a private window"
                                com.linkrouter.browsers.PrivateCapability.ATTEMPT ->
                                    "Best-effort: tries a private window (not guaranteed — verify on your browser)"
                                com.linkrouter.browsers.PrivateCapability.NONE ->
                                    "No private support — will warn and open normally"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Live match preview
                Text("Live match preview", style = MaterialTheme.typography.labelLarge)
                val samples = remember(pattern, matchType) {
                    if (pattern.isBlank()) emptyList() else RuleValidator.sampleUrls(pattern, type)
                }
                samples.forEach { url ->
                    val matches = RuleValidator.previewMatch(pattern, type, url)
                    PreviewRow(url = url, matches = matches)
                }

                // Test URL + dry run
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = testUrl,
                    onValueChange = { testUrl = it; testResult = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Test URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextButton(
                    onClick = {
                        testResult = runTest(pattern, type, testUrl, selectedBrowser)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Test")
                }
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("→")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = validation is Valid && targetPkg.isNotBlank(),
                onClick = { showRoutingDialog = true },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showRoutingDialog) {
        val label = selectedBrowser?.label ?: targetPkg
        AlertDialog(
            onDismissRequest = { /* must confirm */ },
            title = { Text("Confirm routing") },
            text = { Text("You are routing matching links to $label.") },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(
                            (validation as? Valid)?.normalizedPattern ?: pattern,
                            type,
                            targetPkg,
                            selectedBrowser?.activity,
                            OpenMode.valueOf(openMode),
                        )
                    },
                ) { Text("Route") }
            },
            dismissButton = {
                TextButton(onClick = { showRoutingDialog = false }) { Text("Back") }
            },
        )
    }
}

private fun chipLabel(mt: MatchType): String = when (mt) {
    MatchType.EXACT_HOST -> "Exact host"
    MatchType.SUBDOMAIN -> "Subdomain"
    MatchType.PATH_PREFIX -> "Path prefix"
    MatchType.REGEX -> "Regex"
}

@Composable
private fun PreviewRow(url: String, matches: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (matches) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BrowserGrid(
    browsers: List<BrowserInfo>,
    selected: String,
    onSelect: (BrowserInfo) -> Unit,
) {
    if (browsers.isEmpty()) {
        Text(
            "No browsers found in this profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    // Simple wrapping grid: 3 columns.
    val cols = 3
    val perRow = (browsers.size + cols - 1) / cols
    Column {
        for (row in 0 until perRow) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                for (col in 0 until cols) {
                    val idx = row * cols + col
                    if (idx >= browsers.size) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val b = browsers[idx]
                    val isSel = b.packageName == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 3.dp)
                            .background(
                                if (isSel) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                width = if (isSel) 2.dp else 0.dp,
                                color = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelect(b) }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val isWebView = WebViewTarget.isWebView(b.packageName)
                        val painter = if (isWebView) null else browserIcon(b)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isWebView) {
                                Icon(
                                    Icons.Filled.Web,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                            } else if (painter != null) {
                                Icon(painter, contentDescription = null, modifier = Modifier.size(28.dp))
                            } else {
                                Spacer(Modifier.size(28.dp))
                            }
                            Text(
                                b.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dry-run the full chain without launching (DESIGN.md section 10). */
private fun runTest(
    pattern: String,
    type: MatchType,
    testUrl: String,
    browser: BrowserInfo?,
): String {
    val parsed = com.linkrouter.rules.RuleEngine.normalize(testUrl)
    if (parsed == null) return "✗ Not a web URL (http/https only)"
    val rule = Rule(id = 0, pattern = pattern, matchType = type, targetPackage = browser?.packageName ?: "")
    val score = com.linkrouter.rules.RuleEngine.scoreRule(rule, parsed)
    if (score == null) return "✗ No match for this URL"
    val mode = if (browser != null && StrategyTable.isRealPrivate(browser.packageName)) {
        "PRIVATE (verified)"
    } else if (browser != null && StrategyTable.capabilityFor(browser.packageName) == com.linkrouter.browsers.PrivateCapability.ATTEMPT) {
        "private (best-effort, not guaranteed)"
    } else {
        "NORMAL"
    }
    val target = browser?.label ?: "(no browser selected)"
    return "→ would launch $target in $mode window (score $score)"
}
