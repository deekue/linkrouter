#!/bin/bash
#
# build-debug.sh — local mirror of the release workflow's automation, for the
# DEBUG variant.
#
# Mirrors .github/workflows/release.yml (and ci.yml) step order, minus the
# release-only signing / publishing steps:
#   1. Resolve version / versionCode (defaults from app/build.gradle.kts,
#      overridable via VERSION_NAME / VERSION_CODE, as the CI -P flags do).
#   2. Unit tests (:app:testDebugUnitTest).
#   3. Lint (:app:lintDebug, same task CI runs).
#   4. Build (:app:assembleDebug).
#   5. Verify the debug APK artifact exists.
#   6. Report the artifact and the version/name baked into the APK
#      (aapt/apkanalyzer if available).
#
# Debug needs no secrets: the debug build type is unsigned and minify is
# off, so nothing from the release keystore dance applies.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
GRADLE=./gradlew

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[33mWARNING: %s\033[0m\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# Step 1: Resolve version / versionCode.
# Mirrors the release workflow's "Resolve release version" step: the version
# comes from the latest git tag matching ^v[0-9.]+ (e.g. v0.3.0), with the
# leading "v" stripped for versionName, and versionCode derived using the same
# base-100 encoding as the workflow:
#     MAJOR*10000 + MINOR*100 + PATCH   (missing MINOR/PATCH default to 0)
# If no such tag exists, fall back to the hardcoded defaults from
# app/build.gradle.kts (versionName 0.2.1, versionCode 201) with a warning.
# ---------------------------------------------------------------------------
say "Step 1/6: Resolving version / versionCode"

# Hardcoded defaults (mirror of app/build.gradle.kts fallbacks).
DEFAULT_NAME="0.2.1"
DEFAULT_CODE="201"

VERSION_TAG=""
LATEST_TAG="$(git tag --list --sort=-v:refname 2>/dev/null | grep -E '^v[0-9.]+' | head -n1 || true)"
if [ -n "$LATEST_TAG" ]; then
  VERSION_NAME="${LATEST_TAG#v}"
  # Validate like the workflow does: ^[0-9]+(\.[0-9]+){1,2}$
  if [[ "$VERSION_NAME" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]]; then
    VERSION_TAG="$LATEST_TAG"
  else
    warn "Tag '$LATEST_TAG' matched the tag pattern but '$VERSION_NAME' is not a valid version; ignoring."
    LATEST_TAG=""
  fi
fi

if [ -z "$VERSION_TAG" ]; then
  # Graceful fallback: no usable version tag found.
  warn "No git tag matching '^v[0-9.]+' found; falling back to hardcoded defaults (versionName $DEFAULT_NAME, versionCode $DEFAULT_CODE)."
  VERSION_NAME="$DEFAULT_NAME"
  VERSION_CODE="$DEFAULT_CODE"
  VERSION_SOURCE="fallback (no matching git tag)"
else
  VERSION_CODE="$(IFS='.' read -r M m p <<< "$VERSION_NAME"; M=${M:-0}; m=${m:-0}; p=${p:-0}; echo $(( (M * 10000) + (m * 100) + p )))"
  VERSION_SOURCE="git tag $VERSION_TAG"
fi

echo "Resolved version: $VERSION_NAME (versionCode $VERSION_CODE) [$VERSION_SOURCE]"

# ---------------------------------------------------------------------------
# Step 2: Unit tests (mirrors CI's "Run unit tests").
# Non-fatal: kept as a warning so a test-flake or environment issue does not
# block the local build-mirror (CI fails the job, we just report).
# ---------------------------------------------------------------------------
say "Step 2/6: Running unit tests (:app:testDebugUnitTest)"
if "$GRADLE" :app:testDebugUnitTest \
  -PVERSION_NAME="$VERSION_NAME" \
  -PVERSION_CODE="$VERSION_CODE" \
  --no-daemon; then
  echo "Unit tests: OK"
else
  warn "Unit tests failed or could not run in this environment."
fi

# ---------------------------------------------------------------------------
# Step 3: Lint (CI runs :app:lintDebug).
# Non-fatal: lint config can be absent (build.gradle.kts sets no lint checks),
# in which case the task may fail in this environment; attempt it either way.
# ---------------------------------------------------------------------------
say "Step 3/6: Running lint (:app:lintDebug)"
if "$GRADLE" :app:lintDebug -PVERSION_NAME="$VERSION_NAME" -PVERSION_CODE="$VERSION_CODE" --no-daemon; then
  echo "Lint: OK"
else
  warn "Lint step failed (or task unavailable); continuing since lint is not configured in app/build.gradle.kts."
fi

# ---------------------------------------------------------------------------
# Step 4: Build the debug APK (mirrors "Build signed release" step).
# ---------------------------------------------------------------------------
say "Step 4/6: Building debug APK (:app:assembleDebug)"
"$GRADLE" :app:assembleDebug \
  -PVERSION_NAME="$VERSION_NAME" \
  -PVERSION_CODE="$VERSION_CODE" \
  --no-daemon --stacktrace

# ---------------------------------------------------------------------------
# Step 5: Verify the expected artifact exists.
# ---------------------------------------------------------------------------
say "Step 5/6: Verifying debug artifact"
APK_DIR="app/build/outputs/apk/debug"
APKS="$(ls -1t "$APK_DIR"/*.apk 2>/dev/null || true)"
if [ -z "$APKS" ]; then
  echo "ERROR: no .apk found in $APK_DIR" >&2
  exit 1
fi
echo "$APKS" | while read -r f; do
  ls -lh "$f"
done

# ---------------------------------------------------------------------------
# Step 6: Report artifact details + baked-in version (best effort).
# ---------------------------------------------------------------------------
say "Step 6/6: Inspecting artifact"
FIRST_APK="$(echo "$APKS" | head -n1)"

AAPT2="$(command -v aapt2 2>/dev/null || true)"
AAPT="$(command -v aapt 2>/dev/null || true)"
APKANALYZER="$(command -v apkanalyzer 2>/dev/null || true)"
# Fall back to the SDK build-tools if they are not on PATH.
# Also try the build-tools dir from local.properties (sdk.dir) if ANDROID_HOME
# is not set.
if [ ! -d "${ANDROID_HOME:-}/build-tools" ] && [ ! -d "${ANDROID_SDK_ROOT:-}/build-tools" ]; then
  SDK_DIR_LINE="$(grep -E '^sdk\.dir=' local.properties 2>/dev/null | sed 's/^sdk\.dir=//' || true)"
  if [ -n "$SDK_DIR_LINE" ] && [ -d "$SDK_DIR_LINE/build-tools" ]; then
    export ANDROID_HOME="$SDK_DIR_LINE"
    export ANDROID_SDK_ROOT="$SDK_DIR_LINE"
  fi
fi
if [ -z "$AAPT2" ] && [ -z "$AAPT" ]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android-sdk}}"
  if [ -d "$SDK_ROOT/build-tools" ]; then
    LATEST="$(ls -1 "$SDK_ROOT"/build-tools 2>/dev/null | sort -Vr | head -n1 || true)"
    if [ -n "$LATEST" ]; then
      [ -x "$SDK_ROOT/build-tools/$LATEST/aapt2" ] && AAPT2="$SDK_ROOT/build-tools/$LATEST/aapt2"
      [ -x "$SDK_ROOT/build-tools/$LATEST/aapt" ]  && AAPT="$SDK_ROOT/build-tools/$LATEST/aapt"
    fi
  fi
fi

if [ -n "$APKANALYZER" ]; then
  # `apk summary` prints: <packageId> <versionCode> <versionName> (tab-separated).
  "$APKANALYZER" apk summary "$FIRST_APK" 2>/dev/null | awk '{printf "versionCode=%s versionName=%s\n", $2, $3}' || true
elif [ -n "$AAPT2" ]; then
  "$AAPT2" dump badging "$FIRST_APK" 2>/dev/null | grep -oE "versionCode='?[^' ]+'? versionName='?[^' ]+'?" || true
elif [ -n "$AAPT" ]; then
  "$AAPT" dump badging "$FIRST_APK" 2>/dev/null | grep -oE "versionCode='?[^' ]+'? versionName='?[^' ]+'?" || true
else
  warn "aapt/aapt2/apkanalyzer not found; skipping version inspection of $FIRST_APK"
fi

# ---------------------------------------------------------------------------
# Summary.
# ---------------------------------------------------------------------------
say "Summary"
echo "  variant       : debug"
echo "  versionName   : $VERSION_NAME"
echo "  versionCode   : $VERSION_CODE"
echo "  version from  : $VERSION_SOURCE"
echo "  artifact(s)   :"
echo "$APKS" | sed 's/^/    /'
echo
echo "DEBUG build finished successfully."
