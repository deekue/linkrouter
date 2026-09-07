package com.linkrouter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linkrouter.ui.RulesViewModel
import com.linkrouter.ui.SettingsScreen
import com.linkrouter.ui.RulesScreen
import com.linkrouter.ui.RedirectFormatsScreen
import com.linkrouter.ui.ShortenerHostsScreen

/**
 * Normal app icon (DESIGN.md section 3/10) — distinct from the browser role.
 * Hosts the Compose UI: rule list, editor, settings.
 */
class LauncherActivity : ComponentActivity() {

    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: RulesViewModel = viewModel()
                // State MUST live inside the composition (remember) so Compose
                // observes it and recomposes when the route changes. An
                // activity-level mutableStateOf is not tracked by the snapshot
                // system and tapping the cog would not re-render.
                var route by remember { mutableStateOf("rules") }
                RulesApp(
                    vm = vm,
                    currentRoute = route,
                    onNavigate = { route = it },
                )
            }
        }
    }
}

/** Simple 2-route navigation (rules / settings). */
@androidx.compose.runtime.Composable
@androidx.compose.material3.ExperimentalMaterial3Api
private fun RulesApp(
    vm: RulesViewModel,
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val openDefaultBrowserPrompt: () -> Unit = {
        try {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // No system entry point available.
        }
    }
    when (currentRoute) {
        "settings" -> SettingsScreen(
            vm = vm,
            onBack = { onNavigate("rules") },
            onOpenDefaultBrowserPrompt = openDefaultBrowserPrompt,
        )
        "redirects" -> RedirectFormatsScreen(
            vm = vm,
            onBack = { onNavigate("rules") },
        )
        "shorteners" -> ShortenerHostsScreen(
            vm = vm,
            onBack = { onNavigate("rules") },
        )
        else -> RulesScreen(
            vm = vm,
            onOpenSettings = { onNavigate("settings") },
            onOpenDefaultBrowserPrompt = openDefaultBrowserPrompt,
            onOpenRedirects = { onNavigate("redirects") },
            onOpenShorteners = { onNavigate("shorteners") },
        )
    }
}
