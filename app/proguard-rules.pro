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
