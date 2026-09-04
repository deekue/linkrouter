package com.linkrouter.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fallback mode for unmatched / uninstalled / loop-guarded URLs (DESIGN.md 9). */
enum class FallbackMode { CHOOSER, OS_DEFAULT, BLOCK, ASK_REMEMBER, FALLBACK_BROWSER }

/**
 * Lightweight settings store (SharedPreferences). Persists only user settings —
 * never URL history (DESIGN.md 11).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("linkrouter_settings", Context.MODE_PRIVATE)

    private val _fallbackMode = MutableStateFlow(FallbackMode.valueOf(
        prefs.getString(KEY_FALLBACK, FallbackMode.CHOOSER.name) ?: FallbackMode.CHOOSER.name
    ))
    val fallbackMode: StateFlow<FallbackMode> = _fallbackMode.asStateFlow()

    /** Package remembered by ASK_REMEMBER fallback, or null. */
    private val _rememberedPackage = MutableStateFlow(
        prefs.getString(KEY_REMEMBERED_PKG, null)
    )
    val rememberedPackage: StateFlow<String?> = _rememberedPackage.asStateFlow()

    /**
     * Package used as the fallback browser when mode is [FallbackMode.FALLBACK_BROWSER].
     * Independent of the mode itself — the mode is a global setting, this is
     * "which browser that mode should use."
     */
    private val _fallbackBrowser = MutableStateFlow(
        prefs.getString(KEY_FALLBACK_BROWSER, null)
    )
    val fallbackBrowser: StateFlow<String?> = _fallbackBrowser.asStateFlow()

    /** Global toggle for the "private not truly supported" warning (settings, D6). */
    private val _warnPrivate = MutableStateFlow(prefs.getBoolean(KEY_WARN_PRIVATE, true))
    val warnPrivate: StateFlow<Boolean> = _warnPrivate.asStateFlow()

    /** True once the "private not supported" toast has fired for a browser. */
    private val warnedPrivate = mutableSetOf<String>()

    fun setFallbackMode(mode: FallbackMode) {
        _fallbackMode.value = mode
        prefs.edit().putString(KEY_FALLBACK, mode.name).apply()
    }

    fun setRememberedPackage(pkg: String?) {
        _rememberedPackage.value = pkg
        val editor = prefs.edit()
        if (pkg == null) editor.remove(KEY_REMEMBERED_PKG) else editor.putString(KEY_REMEMBERED_PKG, pkg)
        editor.apply()
    }

    fun setFallbackBrowser(pkg: String?) {
        _fallbackBrowser.value = pkg
        val editor = prefs.edit()
        if (pkg == null) editor.remove(KEY_FALLBACK_BROWSER) else editor.putString(KEY_FALLBACK_BROWSER, pkg)
        editor.apply()
    }

    fun setWarnPrivate(enabled: Boolean) {
        _warnPrivate.value = enabled
        prefs.edit().putBoolean(KEY_WARN_PRIVATE, enabled).apply()
    }

    /** Reset already-warned browsers (settings "acknowledge" action). */
    fun resetPrivateWarnings() {
        warnedPrivate.clear()
    }

    /** Returns true the first time we warn for a browser (drives one-time toast, D6). */
    fun shouldWarnPrivate(packageName: String): Boolean {
        if (!_warnPrivate.value) return false
        if (packageName in warnedPrivate) return false
        warnedPrivate.add(packageName)
        return true
    }

    private companion object {
        const val KEY_FALLBACK = "fallback_mode"
        const val KEY_REMEMBERED_PKG = "remembered_package"
        const val KEY_WARN_PRIVATE = "warn_private"
        const val KEY_FALLBACK_BROWSER = "fallback_browser"
    }
}
