package com.linkrouter.ui

import android.content.pm.ApplicationInfo
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PrivateConnectivity
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Web
import com.linkrouter.browsers.WebViewTarget
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.rules.MatchType
import com.linkrouter.rules.OpenMode
import com.linkrouter.rules.Rule

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun RulesScreen(
    vm: RulesViewModel,
    onOpenSettings: () -> Unit,
    onOpenDefaultBrowserPrompt: () -> Unit,
    onOpenRedirects: () -> Unit,
    onOpenShorteners: () -> Unit,
) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }
    val rows = vm.rows
    val targets by vm.targets.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LinkRouter") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = onOpenRedirects) {
                        Icon(Icons.Filled.Link, contentDescription = context.getString(com.linkrouter.R.string.redirect_formats_title))
                    }
                    IconButton(onClick = onOpenShorteners) {
                        Icon(Icons.Filled.Public, contentDescription = context.getString(com.linkrouter.R.string.shorteners_title))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingRule = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add rule")
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = context.getString(com.linkrouter.R.string.empty_rules_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = context.getString(com.linkrouter.R.string.empty_rules_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenDefaultBrowserPrompt) {
                    Text(context.getString(com.linkrouter.R.string.set_default_browser))
                }
            }
            return@Scaffold
        }

        RuleList(
            rows = rows,
            onEdit = { editingRule = it; showEditor = true },
            onDelete = { vm.delete(it.id) },
            onDuplicate = { vm.duplicate(it.id) },
            onToggle = { vm.setEnabled(it.id, !it.enabled) },
            onMoveUp = { id -> moveRule(vm, rows, id, -1) },
            onMoveDown = { id -> moveRule(vm, rows, id, +1) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    if (showEditor) {
        RuleEditor(
            existing = editingRule,
            browsers = targets,
            onSave = { pattern, type, pkg, activity, mode ->
                val editing = editingRule
                if (editing == null) {
                    vm.addRule(pattern, type, pkg, activity, mode)
                } else {
                    vm.updateRule(
                        editing.copy(
                            pattern = pattern,
                            matchType = type,
                            targetPackage = pkg,
                            targetActivity = activity,
                            openMode = mode,
                        )
                    )
                }
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

private fun moveRule(vm: RulesViewModel, rows: List<RuleRow>, id: Long, delta: Int) {
    val idx = rows.indexOfFirst { it.rule.id == id }
    val target = idx + delta
    if (idx < 0 || target < 0 || target >= rows.size) return
    val newOrder = rows.map { it.rule.id }.toMutableList()
    val item = newOrder.removeAt(idx)
    newOrder.add(target, item)
    vm.reorder(newOrder)
}

@Composable
private fun RuleList(
    rows: List<RuleRow>,
    onEdit: (Rule) -> Unit,
    onDelete: (Rule) -> Unit,
    onDuplicate: (Rule) -> Unit,
    onToggle: (Rule) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(rows, key = { it.rule.id }) { row ->
            RuleRowItem(
                row = row,
                canMoveUp = true,
                canMoveDown = true,
                onEdit = { onEdit(row.rule) },
                onDelete = { onDelete(row.rule) },
                onDuplicate = { onDuplicate(row.rule) },
                onToggle = { onToggle(row.rule) },
                onMoveUp = { onMoveUp(row.rule.id) },
                onMoveDown = { onMoveDown(row.rule.id) },
            )
        }
    }
}

@Composable
private fun RuleRowItem(
    row: RuleRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val rule = row.rule
    val installed = row.browser != null
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .alpha(if (installed) 1f else 0.5f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reorder (up/down)
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.KeyboardDoubleArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.KeyboardDoubleArrowDown, contentDescription = "Move down")
        }

        // Browser icon
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val isWebView = WebViewTarget.isWebView(rule.targetPackage)
            val painter = if (isWebView) null else row.browser?.let { browserIcon(it) }
            when {
                isWebView -> Icon(
                    Icons.Filled.Web,
                    contentDescription = WebViewTarget.LABEL,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                painter != null -> Icon(
                    painter,
                    contentDescription = row.browser?.label,
                    modifier = Modifier.size(30.dp),
                )
                else -> Text("?", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.pattern,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (installed) {
                    (row.browser?.label ?: rule.targetPackage) +
                        if (rule.openMode == OpenMode.PRIVATE) "  ·  private" else ""
                } else {
                    rule.targetPackage + "  ·  uninstalled"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (installed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (rule.openMode == OpenMode.PRIVATE) {
            Icon(
                Icons.Filled.PrivateConnectivity,
                contentDescription = "Private",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp),
            )
        }

        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

/** Convert a Drawable to a Compose Painter without extra dependencies. */
@Composable
internal fun browserIcon(browser: BrowserInfo): Painter? {
    val drawable = browser.icon ?: return null
    return remember(drawable) {
        try {
            val bmp = when (val d = drawable) {
                is BitmapDrawable -> d.bitmap
                else -> {
                    val w = d.intrinsicWidth.coerceAtLeast(1)
                    val h = d.intrinsicHeight.coerceAtLeast(1)
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also {
                        val canvas = android.graphics.Canvas(it)
                        d.setBounds(0, 0, w, h)
                        d.draw(canvas)
                    }
                }
            }
            BitmapPainter(bmp.asImageBitmap())
        } catch (e: Exception) {
            null
        }
    }
}
