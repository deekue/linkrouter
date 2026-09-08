#!/usr/bin/env bash
#
# Regenerates the Google Play feature graphic (1024x500 PNG) from the SVG.
#
# Usage:
#   ./playstore-feature-graphic.sh
#
# What it does:
#   1. Checks for a Node.js runtime (v18+).
#   2. Installs the `sharp` dependency into docs/assets/node_modules
#      (skipped if already present, so re-runs are fast/offline).
#   3. Runs playstore-feature-graphic.mjs, which writes
#      playstore-feature-graphic-1024x500.png next to it.
#
# Requires: bash, node, npm. Internet access is needed only the first time
# (to download `sharp`), or after `node_modules` is deleted.

set -euo pipefail

# Resolve the directory this script lives in (so it works from any CWD).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# --- 1. Dependency check -----------------------------------------------------
if ! command -v node >/dev/null 2>&1; then
  echo "error: Node.js not found. Install Node v18+ and try again." >&2
  exit 1
fi

NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$NODE_MAJOR" -lt 18 ]; then
  echo "error: Node v18+ is required (found $(node --version))." >&2
  exit 1
fi

# --- 2. Install `sharp` if missing ------------------------------------------
if [ ! -d node_modules/sharp ]; then
  echo "Installing 'sharp' (first run / node_modules missing)..."
  npm install --no-audit --no-fund
else
  echo "Using existing node_modules/sharp (re-run; no install needed)."
fi

# --- 3. Render ---------------------------------------------------------------
echo "Rendering feature graphic..."
node playstore-feature-graphic.mjs

echo "Done. Output: $SCRIPT_DIR/playstore-feature-graphic-1024x500.png"
