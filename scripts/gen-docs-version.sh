#!/bin/bash
#
# gen-docs-version.sh — compute the docs home-page version and hand it to Hugo.
#
# Hugo templates cannot run shell commands / git (there is no `exec` in the
# template language), so "show the newest git tag" must be resolved at build
# time, not in docs/layouts/home.html. This script picks the newest git tag
# matching v[0-9.]+ (e.g. v0.3.5), strips the leading "v" (so the page shows
# 0.3.5, matching the previous "Version: 0.2.1" style), and writes the value
# to docs/data/version.json where the template reads it via
# `hugo.Data.version.version`.
#
# It mirrors the version selection already done in build-debug.sh / CI, so the
# docs version and the app version stay consistent: same tag pattern, same
# "first match by version sort", same fallback when no matching tag exists.
#
# docs/data/version.json is committed so that a bare `hugo build` (locally and
# in the Pages CI job) serves the correct version with no extra wiring. Run
# this script to refresh it when a new tag is cut, then commit the result.
# Safe and idempotent: it only touches docs/data/version.json.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DATA_DIR="docs/data"
OUT_FILE="${DATA_DIR}/version.json"

# Newest tag matching v[0-9.]+ by version order (same as build-debug.sh).
LATEST_TAG="$(git tag --list 'v[0-9.]*' --sort=-v:refname 2>/dev/null | head -n1 || true)"
# Strip a single leading "v".
VERSION="${LATEST_TAG#v}"

# Fall back to "unknown" when no matching tag exists, rather than guessing.
if ! [[ "${VERSION:-}" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]]; then
  echo "::notice::No git tag matching 'v[0-9.]*'; writing version=unknown." >&2
  VERSION="unknown"
fi

mkdir -p "$DATA_DIR"
printf '{\n  "version": "%s"\n}\n' "$VERSION" > "$OUT_FILE"

echo "Wrote ${OUT_FILE} -> ${VERSION} (from tag: ${LATEST_TAG:-none})"
