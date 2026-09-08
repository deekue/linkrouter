package net.chaosengine.linkrouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun RedirectFormatsScreen(
    vm: RulesViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingFormat by remember { mutableStateOf<net.chaosengine.linkrouter.rules.RedirectFormat?>(null) }
    val formats = vm.formats

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(net.chaosengine.linkrouter.R.string.redirect_formats_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { editingFormat = null; showEditor = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = context.getString(net.chaosengine.linkrouter.R.string.add_redirect_format),
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
                    text = context.getString(net.chaosengine.linkrouter.R.string.redirect_formats_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(formats, key = { it.format.id }) { row ->
                RedirectFormatRowItem(
                    row = row,
                    onEdit = { editingFormat = row.format; showEditor = true },
                    onReset = { vm.resetFormatBuiltIn() },
                    onDelete = { vm.deleteFormat(row.format.id) },
                    onToggle = { vm.setFormatEnabled(row.format.id, !row.format.enabled) },
                )
            }
        }
    }

    if (showEditor) {
        RedirectFormatEditor(
            existing = editingFormat,
            onSave = { name, pattern, matchType, extractType, extractTarget, openRealDestination ->
                val editing = editingFormat
                if (editing == null) {
                    vm.addFormat(name, pattern, matchType, extractType, extractTarget, openRealDestination)
                } else {
                    vm.updateFormat(
                        editing.copy(
                            name = name,
                            pattern = pattern,
                            matchType = matchType,
                            extractType = extractType,
                            extractTarget = extractTarget,
                            openRealDestination = openRealDestination,
                        )
                    )
                }
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun RedirectFormatRowItem(
    row: RedirectFormatRow,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val fmt = row.format
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fmt.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (fmt.openRealDestination) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.tertiaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "real destination",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                if (fmt.isBuiltIn) {
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
                            text = "built-in",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = fmt.pattern,
                style = MaterialTheme.typography.bodySmall,
                color = if (fmt.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(checked = fmt.enabled, onCheckedChange = { onToggle() })

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                if (fmt.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("Reset to default") },
                        onClick = { menuOpen = false; onReset() },
                    )
                }
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}
