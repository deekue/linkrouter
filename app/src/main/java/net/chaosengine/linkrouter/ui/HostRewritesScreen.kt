package net.chaosengine.linkrouter.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.chaosengine.linkrouter.R
import net.chaosengine.linkrouter.rules.HostRewrite
import net.chaosengine.linkrouter.rules.HostRewriteValidator
import net.chaosengine.linkrouter.rules.RuleEngine
import net.chaosengine.linkrouter.rules.RewriteKind
import net.chaosengine.linkrouter.rules.RewriteMatchType

/**
 * Host-rewrite manager. Lists rewrite rules (match host, kind, target host,
 * enabled), reorders them (mirrors the rule list's up/down mechanism), toggles
 * them, and adds/edits them. Built-in rules are not deletable (delete is
 * surfaced as a disabled action; disabling/editing remain allowed).
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HostRewritesScreen(
    vm: RulesViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HostRewrite?>(null) }
    val rows = vm.hostRewrites

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.host_rewrites_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = context.getString(R.string.add_host_rewrite),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    text = context.getString(R.string.host_rewrites_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(rows, key = { it.rewrite.id }) { row ->
                val index = rows.indexOfFirst { it.rewrite.id == row.rewrite.id }
                HostRewriteRowItem(
                    rewrite = row.rewrite,
                    canMoveUp = index > 0,
                    canMoveDown = index < rows.size - 1,
                    onDelete = { vm.deleteHostRewrite(row.rewrite.id) },
                    onEdit = { editing = row.rewrite },
                    onToggle = { vm.setHostRewriteEnabled(row.rewrite.id, !row.rewrite.enabled) },
                    onMoveUp = { moveHostRewrite(vm, rows, row.rewrite.id, -1) },
                    onMoveDown = { moveHostRewrite(vm, rows, row.rewrite.id, +1) },
                )
            }
        }
    }

    if (showAdd) {
        HostRewriteDialog(
            vm = vm,
            existing = null,
            onDone = { showAdd = false },
            onDismiss = { showAdd = false },
        )
    }

    if (editing != null) {
        HostRewriteDialog(
            vm = vm,
            existing = editing,
            onDone = { editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** Swap a rewrite's position with its neighbour, then persist the new top-first id order. */
private fun moveHostRewrite(vm: RulesViewModel, rows: List<HostRewriteRow>, id: Long, delta: Int) {
    val idx = rows.indexOfFirst { it.rewrite.id == id }
    val target = idx + delta
    if (idx < 0 || target < 0 || target >= rows.size) return
    val newOrder = rows.map { it.rewrite.id }.toMutableList()
    val item = newOrder.removeAt(idx)
    newOrder.add(target, item)
    vm.reorderHostRewrites(newOrder)
}

@Composable
private fun HostRewriteRowItem(
    rewrite: HostRewrite,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reorder (up/down)
        IconButton(onClick = onMoveUp, enabled = canMoveUp,
            modifier = Modifier.size(32.dp).testTag("hostrewrite_${rewrite.id}_moveUp")) {
            Icon(Icons.Filled.KeyboardDoubleArrowUp, contentDescription = context.getString(R.string.move_up))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown,
            modifier = Modifier.size(32.dp).testTag("hostrewrite_${rewrite.id}_moveDown")) {
            Icon(Icons.Filled.KeyboardDoubleArrowDown, contentDescription = context.getString(R.string.move_down))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rewrite.matchHost,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (rewrite.isBuiltIn) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(R.string.builtin_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = "${kindLabel(context, rewrite.kind)}  →  ${rewrite.targetHost}",
                style = MaterialTheme.typography.bodySmall,
                color = if (rewrite.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(checked = rewrite.enabled, onCheckedChange = { onToggle() })

        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = context.getString(R.string.edit))
        }

        if (rewrite.isBuiltIn) {
            // Built-in: delete is refused downstream — show it disabled, not gone.
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Filled.Delete, contentDescription = context.getString(R.string.host_rewrite_delete_disabled))
            }
        } else {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = context.getString(R.string.delete))
            }
        }
    }
}

@androidx.compose.runtime.Composable
@OptIn(ExperimentalLayoutApi::class)
fun HostRewriteDialog(
    vm: RulesViewModel,
    existing: HostRewrite?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isBuiltin = existing?.isBuiltIn == true

    var matchHost by rememberSaveable { mutableStateOf(existing?.matchHost ?: "") }
    var matchType by rememberSaveable {
        mutableStateOf(existing?.matchType?.name ?: RewriteMatchType.EXACT_HOST.name)
    }
    var kindName by rememberSaveable {
        mutableStateOf(existing?.kind?.name ?: RewriteKind.HOST_SWAP.name)
    }
    var targetHost by rememberSaveable { mutableStateOf(existing?.targetHost ?: "") }
    var preserveHostInPath by rememberSaveable { mutableStateOf(existing?.preserveHostInPath ?: false) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val mt = RewriteMatchType.valueOf(matchType)
    val kd = RewriteKind.valueOf(kindName)

    val validation = vm.validateHostRewrite(matchHost, mt, kd, targetHost, preserveHostInPath)

    val sample = sampleUrl(matchHost, mt)
    val previewRule = HostRewrite(
        id = existing?.id ?: 0,
        matchHost = matchHost,
        matchType = mt,
        kind = kd,
        targetHost = targetHost,
        preserveHostInPath = preserveHostInPath,
        enabled = enabled,
        priority = 0,
        isBuiltIn = isBuiltin,
    )
    val preview = if (sample.isEmpty()) "" else vm.hostRewritePreview(previewRule, sample)
    val previewOk = sample.isNotEmpty() && preview.isNotEmpty() && preview != sample

    val canSave = validation is HostRewriteValidator.Result.Valid && previewOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                context.getString(
                    if (existing == null) R.string.add_host_rewrite
                    else R.string.edit_host_rewrite,
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                Text(context.getString(R.string.host_rewrite_match_host), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = matchHost,
                    onValueChange = { matchHost = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(10.dp))

                Text(context.getString(R.string.host_rewrite_match_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RewriteMatchType.entries.forEach { m ->
                        FilterChip(
                            selected = mt == m,
                            onClick = { matchType = m.name },
                            label = { Text(matchTypeLabel(context, m)) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(context.getString(R.string.host_rewrite_kind), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RewriteKind.entries.forEach { k ->
                        FilterChip(
                            selected = kd == k,
                            onClick = { kindName = k.name },
                            label = { Text(kindLabel(context, k)) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                Text(context.getString(R.string.host_rewrite_target_host), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
                            text = context.getString(R.string.host_rewrite_preserve_host),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = context.getString(R.string.host_rewrite_preserve_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preserveHostInPath,
                        onCheckedChange = { preserveHostInPath = it },
                        enabled = kd == RewriteKind.PATH_PREFIX_REWRITE,
                    )
                }
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = context.getString(R.string.host_rewrite_enabled),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                val saveMsg = saveError
                if (saveMsg != null) {
                    Text(
                        text = saveMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (validation is HostRewriteValidator.Result.Invalid) {
                    Text(
                        text = validation.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val warnings = (validation as? HostRewriteValidator.Result.Valid)?.warnings
                if (warnings != null && warnings.isNotEmpty()) {
                    Text(
                        text = context.getString(R.string.host_rewrite_warnings),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    warnings.forEach { w ->
                        Text(
                            text = w,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(context.getString(R.string.live_preview), style = MaterialTheme.typography.labelLarge)
                if (sample.isEmpty()) {
                    Text(
                        text = context.getString(R.string.no_match),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    RewritePreviewRow(source = sample, destination = preview, matched = previewOk)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    val result = if (existing == null) {
                        vm.add(matchHost.trim(), mt, kd, targetHost.trim(), preserveHostInPath)
                    } else {
                        vm.edit(
                            existing.copy(
                                matchHost = matchHost.trim(),
                                matchType = mt,
                                kind = kd,
                                targetHost = targetHost.trim(),
                                preserveHostInPath = preserveHostInPath,
                                enabled = enabled,
                            )
                        )
                    }
                    when (result) {
                        is HostRewriteValidator.Result.Invalid -> saveError = result.reason
                        else -> onDone()
                    }
                },
            ) { Text(context.getString(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
}

private fun matchTypeLabel(context: android.content.Context, m: RewriteMatchType): String = when (m) {
    RewriteMatchType.EXACT_HOST -> context.getString(R.string.rewrite_match_exact_host)
    RewriteMatchType.EXACT_WWW_HOST -> context.getString(R.string.rewrite_match_exact_www)
    RewriteMatchType.SUBDOMAIN -> context.getString(R.string.rewrite_match_subdomain)
}

private fun kindLabel(context: android.content.Context, k: RewriteKind): String = when (k) {
    RewriteKind.HOST_SWAP -> context.getString(R.string.rewrite_kind_host_swap)
    RewriteKind.PATH_PREFIX_REWRITE -> context.getString(R.string.rewrite_kind_path_prefix)
}

/** A sample URL that exercises the rule's host scope so the live preview is meaningful. */
private fun sampleUrl(matchHost: String, matchType: RewriteMatchType): String {
    val host = RuleEngine.normalizeHost(matchHost)
    if (host.isEmpty()) return ""
    return when (matchType) {
        RewriteMatchType.SUBDOMAIN -> "https://sub.$host/sample"
        else -> "https://$host/sample"
    }
}

@Composable
private fun RewritePreviewRow(source: String, destination: String, matched: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (matched) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (matched) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (matched) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = destination,
                style = MaterialTheme.typography.bodySmall,
                color = if (matched) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
