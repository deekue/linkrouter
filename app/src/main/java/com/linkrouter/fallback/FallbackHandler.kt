package com.linkrouter.fallback

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.linkrouter.ActivityLaunchGuard
import com.linkrouter.LinkRouter
import com.linkrouter.settings.FallbackMode
import com.linkrouter.settings.SettingsStore

/**
 * Fallback dispatch for unmatched / uninstalled / loop-guarded URLs
 * (DESIGN.md section 9), driven by the user's chosen mode.
 */
object FallbackHandler {

    private const val CHOOSER_TITLE = "Open link with"

    /**
     * [browserResolver] resolves a browser package name to a launchable
     * pinned ACTION_VIEW intent (or null if it can't). Passed in by the
     * dispatcher so this object stays decoupled from the registry.
     */
    fun route(
        activity: Activity,
        uri: Uri,
        settings: SettingsStore,
        browserResolver: (String, Uri) -> Intent? = { _, _ -> null },
    ) {
        // Background-start guard (DESIGN.md 11): skip entirely if the activity
        // is no longer started (e.g. coroutine resumed after it was destroyed).
        if (!ActivityLaunchGuard.canStart(activity)) return
        when (settings.fallbackMode.value) {
            FallbackMode.CHOOSER -> showChooser(activity, uri)
            FallbackMode.OS_DEFAULT -> launchOsDefault(activity, uri)
            FallbackMode.BLOCK -> {
                Toast.makeText(
                    activity,
                    activity.getString(com.linkrouter.R.string.blocked_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            FallbackMode.ASK_REMEMBER -> askAndRemember(activity, uri, settings)
            FallbackMode.FALLBACK_BROWSER -> launchSpecificBrowser(activity, uri, settings, browserResolver)
        }
    }

    /**
     * Launch the user's chosen fallback browser (settings). If no browser is
     * selected, or it is no longer installed/resolvable, degrade to the
     * system chooser so the link still opens.
     */
    private fun launchSpecificBrowser(
        activity: Activity,
        uri: Uri,
        settings: SettingsStore,
        browserResolver: (String, Uri) -> Intent?,
    ) {
        val pkg = settings.fallbackBrowser.value
        val intent = if (pkg != null) browserResolver(pkg, uri) else null
        if (intent == null) {
            if (pkg != null) {
                Toast.makeText(
                    activity,
                    activity.getString(com.linkrouter.R.string.fallback_specific_uninstalled, pkg),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            showChooser(activity, uri)
            return
        }
        intent.putExtra(LinkRouter.EXTRA_HANDLED, true) // loop-guard marker
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            if (ActivityLaunchGuard.canStart(activity)) showChooser(activity, uri)
        }
    }

    fun showChooser(activity: Activity, uri: Uri, title: String? = null) {
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LinkRouter.EXTRA_HANDLED, true) // loop-guard marker
        }
        val chooser = Intent.createChooser(view, title ?: CHOOSER_TITLE)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(chooser)
        } catch (e: Exception) {
            // Crash-safe: nothing more to do (DESIGN.md section 6).
        }
    }

    private fun launchOsDefault(activity: Activity, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // If LinkRouter is the OS default browser, an unpinned ACTION_VIEW
        // resolves back to us → infinite self-loop (and the loop guard would
        // never fire, since this path used to omit the marker). Detect it and
        // hand off to the chooser instead of re-launching ourselves.
        val resolved = try {
            activity.packageManager.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            null
        }
        if (resolved != null && resolved.activityInfo?.packageName == activity.packageName) {
            showChooser(activity, uri)
            return
        }
        view.putExtra(LinkRouter.EXTRA_HANDLED, true) // loop-guard marker (safety net)
        try {
            activity.startActivity(view)
        } catch (e: Exception) {
            showChooser(activity, uri)
        }
    }

    private fun askAndRemember(activity: Activity, uri: Uri, settings: SettingsStore) {
        val remembered = settings.rememberedPackage.value
        if (remembered != null &&
            activity.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, uri).setPackage(remembered), 0
            ) != null
        ) {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(remembered)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
                // fall through to chooser
            }
        }
        showChooser(activity, uri)
        // Note: remembering the *picked* package requires the chooser result;
        // on API 29+ that is not directly exposed, so we keep the chooser open
        // and let the user re-pick. Future improvement: ACTION_CHOOSER_RESULT.
    }
}
