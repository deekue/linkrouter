package com.linkrouter.ui

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shortener-host manager (D9). Lists enabled/disabled shortener hosts, toggles
 * them, adds a new host, and deletes user-added hosts. Built-in hosts are
 * non-deletable (the VM redirects delete→disable for them).
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ShortenerHostsScreen(
    vm: RulesViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val hosts = vm.shortenerHosts

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(com.linkrouter.R.string.shorteners_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = context.getString(com.linkrouter.R.string.add_shortener_host),
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
                    text = context.getString(com.linkrouter.R.string.shorteners_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(hosts, key = { it.host.id }) { row ->
                ShortenerHostRowItem(
                    row = row,
                    onDelete = { vm.deleteShortenerHost(row.host.id) },
                    onToggle = { vm.setShortenerHostEnabled(row.host.id, !row.host.enabled) },
                )
            }
        }
    }

    if (showAdd) {
        ShortenerHostAddDialog(
            onSave = { name, host, pathPrefix ->
                vm.addShortenerHost(name, host, pathPrefix)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun ShortenerHostRowItem(
    row: ShortenerHostRow,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
) {
    val host = row.host

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
                    text = host.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (host.isBuiltIn) {
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
                text = host.host + (host.pathPrefix ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (host.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(checked = host.enabled, onCheckedChange = { onToggle() })

        if (!host.isBuiltIn) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ShortenerHostAddDialog(
    onSave: (name: String, host: String, pathPrefix: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var pathPrefix by remember { mutableStateOf("") }

    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(com.linkrouter.R.string.add_shortener_host)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(context.getString(com.linkrouter.R.string.name)) },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(context.getString(com.linkrouter.R.string.shortener_host)) },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pathPrefix,
                    onValueChange = { pathPrefix = it },
                    label = { Text(context.getString(com.linkrouter.R.string.shortener_path_prefix)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, host, pathPrefix.takeIf { it.isNotBlank() }) },
                enabled = name.isNotBlank() && host.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
