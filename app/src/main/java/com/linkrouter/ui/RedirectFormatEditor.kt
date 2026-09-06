package com.linkrouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkrouter.R as AppR
import com.linkrouter.rules.ExtractType
import com.linkrouter.rules.MatchType
import com.linkrouter.rules.RedirectFormat
import com.linkrouter.rules.RedirectFormatValidator

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RedirectFormatEditor(
    existing: RedirectFormat?,
    onSave: (name: String, pattern: String, matchType: MatchType, extractType: ExtractType, extractTarget: String, openRealDestination: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var pattern by rememberSaveable { mutableStateOf(existing?.pattern ?: "") }
    var matchType by rememberSaveable {
        mutableStateOf(existing?.matchType?.name ?: MatchType.PATH_PREFIX.name)
    }
    var extractType by rememberSaveable {
        mutableStateOf(existing?.extractType?.name ?: ExtractType.QUERY_PARAM.name)
    }
    var extractTarget by rememberSaveable { mutableStateOf(existing?.extractTarget ?: "") }
    var openRealDestination by rememberSaveable { mutableStateOf(existing?.openRealDestination ?: false) }

    val isBuiltin = existing?.isBuiltIn == true
    val mt = MatchType.valueOf(matchType)
    val et = ExtractType.valueOf(extractType)

    val validation = RedirectFormatValidator.validate(
        RedirectFormat(
            id = existing?.id ?: 0,
            name = name,
            pattern = pattern,
            matchType = mt,
            extractType = et,
            extractTarget = extractTarget,
            enabled = true,
            priority = 0,
            isBuiltIn = isBuiltin,
            openRealDestination = openRealDestination,
        )
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existing == null) "New redirect format" else "Edit redirect format")
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                Text(context.getString(AppR.string.name), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBuiltin,
                    placeholder = { Text("Google") },
                    singleLine = true,
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    context.getString(AppR.string.pattern_wrapper),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBuiltin,
                    placeholder = { Text("google.com/url?q=...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    context.getString(AppR.string.match_type),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MatchType.entries.forEach { m ->
                        FilterChip(
                            selected = mt == m,
                            enabled = !isBuiltin,
                            onClick = { matchType = m.name },
                            label = { Text(matchLabel(context, m)) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    context.getString(AppR.string.extract_type),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExtractType.entries.forEach { e ->
                        FilterChip(
                            selected = et == e,
                            enabled = !isBuiltin,
                            onClick = { extractType = e.name },
                            label = { Text(extractLabel(context, e)) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = extractTarget,
                    onValueChange = { extractTarget = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBuiltin,
                    label = { Text(context.getString(AppR.string.extraction_target)) },
                    placeholder = { Text(targetPlaceholder(context, et)) },
                    singleLine = true,
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = context.getString(AppR.string.open_real_destination),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = context.getString(AppR.string.open_real_destination_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = openRealDestination, onCheckedChange = { openRealDestination = it })
                }

                if (validation is RedirectFormatValidator.Result.Invalid) {
                    Text(
                        text = validation.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (isBuiltin) {
                    Text(
                        text = context.getString(AppR.string.builtin_readonly),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    context.getString(AppR.string.live_preview),
                    style = MaterialTheme.typography.labelLarge,
                )
                val sample = RedirectFormatValidator.sampleWrapper(
                    RedirectFormat(
                        id = existing?.id ?: 0,
                        name = name,
                        pattern = pattern,
                        matchType = mt,
                        extractType = et,
                        extractTarget = extractTarget,
                        enabled = true,
                        priority = 0,
                        isBuiltIn = isBuiltin,
                    )
                )
                val resolved = if (sample.isEmpty()) null else RedirectFormatValidator.preview(
                    RedirectFormat(
                        id = existing?.id ?: 0,
                        name = name,
                        pattern = pattern,
                        matchType = mt,
                        extractType = et,
                        extractTarget = extractTarget,
                        enabled = true,
                        priority = 0,
                        isBuiltIn = isBuiltin,
                    )
                )
                if (sample.isEmpty()) {
                    Text(
                        text = context.getString(AppR.string.no_match),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    PreviewRow(wrapper = sample, destination = resolved)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = validation is RedirectFormatValidator.Result.Valid,
                onClick = {
                    val valid = validation as? RedirectFormatValidator.Result.Valid
                    val normalized = valid?.normalized
                    onSave(
                        normalized?.name ?: name.trim(),
                        normalized?.pattern ?: pattern.trim(),
                        mt,
                        et,
                        normalized?.extractTarget ?: extractTarget.trim(),
                        openRealDestination,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun matchLabel(context: android.content.Context, m: MatchType): String = when (m) {
    MatchType.EXACT_HOST -> context.getString(AppR.string.match_exact_host)
    MatchType.SUBDOMAIN -> context.getString(AppR.string.match_subdomain)
    MatchType.PATH_PREFIX -> context.getString(AppR.string.match_path_prefix)
    MatchType.REGEX -> context.getString(AppR.string.match_regex)
}

private fun extractLabel(context: android.content.Context, e: ExtractType): String = when (e) {
    ExtractType.QUERY_PARAM -> context.getString(AppR.string.extract_query_param)
    ExtractType.PATH_REGEX -> context.getString(AppR.string.extract_path_regex)
    ExtractType.FULL_URL_REGEX -> context.getString(AppR.string.extract_full_url_regex)
    ExtractType.BASE64_PARAM -> context.getString(AppR.string.extract_base64_param)
}

private fun targetPlaceholder(context: android.content.Context, e: ExtractType): String = when (e) {
    ExtractType.QUERY_PARAM, ExtractType.BASE64_PARAM -> context.getString(AppR.string.target_param_name)
    ExtractType.PATH_REGEX, ExtractType.FULL_URL_REGEX -> context.getString(AppR.string.target_regex)
}

@Composable
private fun PreviewRow(wrapper: String, destination: String?) {
    val resolved = destination != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (resolved) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (resolved) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (resolved) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = wrapper,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = destination ?: "no match",
                style = MaterialTheme.typography.bodySmall,
                color = if (resolved) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
