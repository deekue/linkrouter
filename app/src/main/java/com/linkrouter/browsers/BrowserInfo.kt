package com.linkrouter.browsers

import android.graphics.drawable.Drawable

/**
 * A discovered browser (DESIGN.md section 3/8). `icon` is loaded lazily and
 * cached in memory by [BrowserRegistry].
 */
data class BrowserInfo(
    val packageName: String,
    val label: String,
    /** Launchable (MAIN/LAUNCHER) activity component, or null. */
    val activity: String?,
    val icon: Drawable?,
    /** True only when a strategy with real private support exists (DESIGN.md 7). */
    val isPrivateCapable: Boolean,
)
