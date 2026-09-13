# LinkRouter app-level R8/ProGuard rules (release builds only).
#
# The default file (proguard-android-optimize.txt) plus these rules are applied
# to the `release` build type. Debug builds are unminified and never use this
# file.
#
# Notes:
# - Room entities/DAOs are retained automatically by the Room compiler's
#   generated keep rules; add explicit rules here only if R8 warnings appear.
# - Moshi models (rules/, settings/, importexport/ DTOs) use Moshi's Kotlin
#   reflection-free codegen (KSP) today; if reflection-based models are added,
#   they will need -keepclassmembers entry points here.

# ViewModel created from Compose via `viewModel()` (AndroidViewModel with an
# Application argument). lifecycle-viewmodel's AbstractSavedStateViewModelFactory
# instantiates it reflectively (modelClass.getConstructor(Application.class)),
# which R8 cannot see, so the (Application) constructor would be removed in
# release builds -> NoSuchMethodException crash on startup (debug is unminified,
# so it only fails in release). R8 can't verify the reflective `newInstance`
# call, so the constructor must stay AND be kept non-optimized.
-keep class net.chaosengine.linkrouter.ui.RulesViewModel {
    <init>(android.app.Application);
}

# Strip android.util.Log calls in release builds (DESIGN.md §11: no logging in release).
# Debug builds are unminified and never use this file, so debug logging is preserved.
-keep,allowobfuscation class android.util.Log { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** e(...);
    public static *** i(...);
    public static *** v(...);
    public static *** w(...);
}
