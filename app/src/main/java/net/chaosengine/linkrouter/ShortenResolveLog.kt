package net.chaosengine.linkrouter

/**
 * Diagnostic logging for the short-link resolution path (pure-JVM fast path +
 * WebView escalation + settle detection).
 *
 * Every line is emitted under the single tag [TAG] (`ShortenResolve`) so a real
 * device capture is trivial to filter: `adb logcat -s ShortenResolve`.
 *
 * Why this shim instead of calling `android.util.Log` directly in every
 * instrumented file:
 *  - [ShortenerResolver] and [SettleDetector] are intentionally android-free so
 *    they stay unit-testable on the plain JVM.
 *  - Under the unit-test classpath the `android.util.Log` stubs throw
 *    `RuntimeException("Stub!")`, which would blow up the pure-JVM resolver /
 *    settle tests. Wrapping the call in a try/catch keeps those tests green.
 *  - On a real device the guard never triggers (Log works normally), so this
 *    adds NO behavior change — it only wraps the diagnostic output.
 */
internal object ShortenResolveLog {

    internal const val TAG = "ShortenResolve"

    fun d(msg: String) = emit(android.util.Log.DEBUG, msg)
    fun i(msg: String) = emit(android.util.Log.INFO, msg)
    fun w(msg: String) = emit(android.util.Log.WARN, msg)
    fun e(msg: String) = emit(android.util.Log.ERROR, msg)

    private fun emit(priority: Int, msg: String) {
        try {
            android.util.Log.println(priority, TAG, msg)
        } catch (thr: Throwable) {
            // Pure-JVM (unit-test) environment: android.util.Log stubs throw
            // "Stub!". Diagnostic logging is intentionally a no-op there, and
            // the guard swallows it so resolver/settle behavior is unchanged.
        }
    }
}
