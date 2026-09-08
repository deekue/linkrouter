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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import net.chaosengine.linkrouter.rules.QueryParamFilter

/**
 * Query-param-filter manager (M9). Lists enabled/disabled param filters,
 * toggles them, adds a new filter (param + optional host scope), edits
 * user-added filters, and deletes user-added filters. Built-in filters are
 * non-deletable and non-editable (the VM/repository redirect delete→disable).
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun QueryParamFiltersScreen(
    vm: RulesViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QueryParamFilter?>(null) }
    val rows = vm.queryParamFilters

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(net.chaosengine.linkrouter.R.string.param_filters_title)) },
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
                            contentDescription = context.getString(net.chaosengine.linkrouter.R.string.add_param_filter),
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
                    text = context.getString(net.chaosengine.linkrouter.R.string.param_filters_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(rows, key = { it.filter.id }) { row ->
                ParamFilterRowItem(
                    row = row,
                    onDelete = { vm.deleteQueryParamFilter(row.filter.id) },
                    onEdit = { editing = row.filter },
                    onToggle = { vm.setQueryParamFilterEnabled(row.filter.id, !row.filter.enabled) },
                )
            }
        }
    }

    if (showAdd) {
        ParamFilterDialog(
            title = context.getString(net.chaosengine.linkrouter.R.string.add_param_filter),
            initialParam = "",
            initialHost = "",
            onSave = { param, host ->
                vm.addQueryParamFilter(param, host)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }

    if (editing != null) {
        ParamFilterDialog(
            title = context.getString(net.chaosengine.linkrouter.R.string.edit_param_filter),
            initialParam = editing!!.param,
            initialHost = editing!!.host ?: "",
            onSave = { param, host ->
                val current = editing!!
                vm.updateQueryParamFilter(
                    current.copy(
                        param = param.trim().lowercase(),
                        host = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                    )
                )
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ParamFilterRowItem(
    row: QueryParamFilterRow,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    val filter = row.filter

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
                    text = filter.param,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (filter.isBuiltIn) {
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
            val context = LocalContext.current
            Text(
                text = filter.host ?: context.getString(net.chaosengine.linkrouter.R.string.param_filter_all_domains),
                style = MaterialTheme.typography.bodySmall,
                color = if (filter.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(checked = filter.enabled, onCheckedChange = { onToggle() })

        if (!filter.isBuiltIn) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ParamFilterDialog(
    title: String,
    initialParam: String,
    initialHost: String,
    onSave: (param: String, host: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var param by remember { mutableStateOf(initialParam) }
    var host by remember { mutableStateOf(initialHost) }

    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = param,
                    onValueChange = { param = it },
                    label = { Text(context.getString(net.chaosengine.linkrouter.R.string.param_filter_param)) },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(context.getString(net.chaosengine.linkrouter.R.string.param_filter_host)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(param, host.takeIf { it.isNotBlank() }) },
                enabled = param.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
