package com.linkrouter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linkrouter.R
import com.linkrouter.settings.FallbackMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings (DESIGN.md section 10, M4): fallback mode, browser refresh,
 * JSON import/export via SAF, private-warn acknowledgment.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun SettingsScreen(
    vm: RulesViewModel,
    onBack: () -> Unit,
    onOpenDefaultBrowserPrompt: () -> Unit,
) {
    val context = LocalContext.current
    val fallbackMode by vm.fallbackMode.collectAsStateWithLifecycle()
    val fallbackBrowser by vm.fallbackBrowser.collectAsStateWithLifecycle()
    val warnPrivate by vm.warnPrivate.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = remember { CoroutineScope(Dispatchers.Main.immediate) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().decodeToString() }
        if (json == null) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.import_failed)) }
            return@rememberLauncherForActivityResult
        }
        val imported = vm.parseJson(json)
        val importedFormats = vm.parseFormatJson(json)
        scope.launch {
            if (imported == null && importedFormats == null) {
                snackbarHostState.showSnackbar(context.getString(R.string.import_failed))
            } else {
                imported?.let { vm.importRules(it) }
                importedFormats?.let { vm.importFormats(it) }
                val rulesCount = imported?.size ?: 0
                val fmtCount = importedFormats?.size ?: 0
                val msg = if (rulesCount > 0 && fmtCount > 0) {
                    context.getString(R.string.import_ok_both, rulesCount, fmtCount)
                } else if (rulesCount > 0) {
                    context.getString(R.string.import_ok, rulesCount)
                } else {
                    context.getString(R.string.import_ok_formats, fmtCount)
                }
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val rules = kotlinx.coroutines.withContext(Dispatchers.IO) { vm.exportRules() }
                val formats = kotlinx.coroutines.withContext(Dispatchers.IO) { vm.exportFormats() }
                val text = com.linkrouter.importexport.RuleSerializer.toJson(rules, formats)
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(text.encodeToByteArray()) }
                    ?: error("no stream")
                snackbarHostState.showSnackbar(context.getString(R.string.export_ok, rules.size + formats.size))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.export_failed))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Fallback mode (DESIGN.md section 9)
            Text(
                context.getString(R.string.fallback_mode),
                style = MaterialTheme.typography.titleMedium,
            )
            for (mode in FallbackMode.entries) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = fallbackMode == mode,
                        onClick = { vm.setFallbackMode(mode) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = fallbackLabel(context, mode),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (fallbackMode == FallbackMode.FALLBACK_BROWSER) {
                FallbackBrowserPicker(
                    browsers = vm.browsers.value,
                    selected = fallbackBrowser,
                    onPick = { vm.setFallbackBrowser(it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    vm.refreshBrowsers()
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.rescan_done))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(context.getString(R.string.refresh_browsers)) }

            OutlinedButton(
                onClick = onOpenDefaultBrowserPrompt,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(context.getString(R.string.open_default_prompt)) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(context.getString(R.string.import_rules)) }
                OutlinedButton(
                    onClick = { exportLauncher.launch("linkrouter-rules.json") },
                    modifier = Modifier.weight(1f),
                ) { Text(context.getString(R.string.export_rules)) }
            }

            Spacer(Modifier.height(8.dp))

            // One-time private warning toggle (D6)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    context.getString(R.string.private_warn_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = warnPrivate,
                    onCheckedChange = { vm.setWarnPrivate(it) },
                )
            }
            TextButton(
                onClick = { vm.resetPrivateWarnings() },
                enabled = warnPrivate,
            ) { Text(context.getString(R.string.private_warn_reset)) }

            Text(
                context.getString(R.string.work_profile_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Picker for the fallback browser used when mode is [FallbackMode.FALLBACK_BROWSER].
 * Lists the installed browsers from the registry; selection is persisted in
 * [com.linkrouter.settings.SettingsStore].
 */
@Composable
private fun FallbackBrowserPicker(
    browsers: List<com.linkrouter.browsers.BrowserInfo>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = browsers.firstOrNull { it.packageName == selected }?.label
        ?: (selected ?: context.getString(R.string.fallback_specific_picker))

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.fallback_specific_picker),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedLabel)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (browsers.isEmpty()) {
                Text(
                    text = context.getString(R.string.no_browsers_found),
                    modifier = Modifier.padding(16.dp),
                )
            }
            browsers.sortedBy { it.label.lowercase() }.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b.label) },
                    onClick = {
                        onPick(b.packageName)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun fallbackLabel(context: android.content.Context, mode: FallbackMode): String = when (mode) {
    FallbackMode.CHOOSER -> context.getString(R.string.fallback_chooser)
    FallbackMode.OS_DEFAULT -> context.getString(R.string.fallback_os_default)
    FallbackMode.BLOCK -> context.getString(R.string.fallback_block)
    FallbackMode.ASK_REMEMBER -> context.getString(R.string.fallback_ask_remember)
    FallbackMode.FALLBACK_BROWSER -> context.getString(R.string.fallback_specific)
}
