package net.chaosengine.linkrouter.rules

/**
 * Diagnostic logging for the pure-JVM [HostRewriter].
 *
 * Every line is emitted under the single tag [TAG] (`HostRewrite`) so a real
 * device capture is trivial to filter: `adb logcat -s HostRewrite:V`. This is
 * the SAME tag the production call site in `DispatcherActivity` uses, so the
 * whole host-rewrite flow (entry → per-rule detail → result) is visible under
 * one filter.
 *
 * Why this shim instead of calling `android.util.Log` directly in [HostRewriter]:
 *  - [HostRewriter] is intentionally pure-JVM (no Android imports) so it stays
 *    unit-testable on the plain JVM.
 *  - Under the unit-test classpath the `android.util.Log` stubs throw
 *    `RuntimeException("Stub!")`, which would blow up [HostRewriterTest].
 *    Wrapping the call in a try/catch keeps those tests green.
 *  - On a real device the guard never triggers (Log works normally), so this
 *    adds NO behavior change — it only wraps the diagnostic output.
 *
 * Same approach as [net.chaosengine.linkrouter.ShortenResolveLog].
 */
internal object HostRewriteLog {

    internal const val TAG = "HostRewrite"

    fun d(msg: String) = emit(android.util.Log.DEBUG, msg)
    fun i(msg: String) = emit(android.util.Log.INFO, msg)

    private fun emit(priority: Int, msg: String) {
        try {
            android.util.Log.println(priority, TAG, msg)
        } catch (thr: Throwable) {
            // Pure-JVM (unit-test) environment: android.util.Log static field
            // access or println throws "Stub!". Diagnostic logging is
            // intentionally a no-op there, and the guard swallows it so the
            // rewrite behavior is unchanged.
        }
    }
}
