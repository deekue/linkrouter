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

# --- Moshi (JSON) codegen + R8 (release) ---
# KotlinJsonAdapterFactory finds the KSP-generated adapters reflectively via
# Class.forName("<Outer>_<Inner>JsonAdapter"). For nested models (e.g.
# RuleSerializer.RuleList) the name is built from the enclosing class name +
# the simple name. If R8 renames those classes (release-only), the lookup
# misses the preserved adapter and Moshi throws "No JsonAdapter for class
# java.util.ArrayList". So keep the annotated model classes AND their
# enclosing serializer object BY NAME, in addition to the adapters.
-keep,includedescriptorclasses class * extends com.squareup.moshi.JsonAdapter
# R8 must not strip the (Moshi) constructor: Util.generatedAdapter calls
# getDeclaredConstructor(Moshi) reflectively on the KSP-generated adapters.
-keepclassmembers,allowobfuscation class * extends com.squareup.moshi.JsonAdapter {
    <init>(com.squareup.moshi.Moshi);
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class net.chaosengine.linkrouter.importexport.RuleSerializer { *; }
-keepclassmembers,allowobfuscation class * {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep,allowobfuscation @interface com.squareup.moshi.Json
-keep,allowobfuscation @interface com.squareup.moshi.JsonClass
