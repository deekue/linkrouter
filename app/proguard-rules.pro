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
