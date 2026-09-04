package com.linkrouter

import android.app.Activity

/**
 * Background-start guard (DESIGN.md section 11).
 *
 * All link handling is foreground-triggered (a user tap), but the dispatch is
 * performed on a coroutine that may resume after the dispatcher activity has
 * been destroyed — in which case a background `startActivity` is rejected on
 * Android 10+. `Activity.isActivityStarted()` is `protected`, so this helper
 * uses the public equivalent (`!isFinishing && !isDestroyed`) as the
 * accessible approximation of "the activity is at least started."
 */
object ActivityLaunchGuard {

    /** True when [activity] is in a state from which it may start other activities. */
    fun canStart(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed
}
