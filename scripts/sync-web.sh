#!/usr/bin/env bash
# Copies the latest web build (forma-app.html) into the Android assets.
# Usage: scripts/sync-web.sh [path-to-forma-app.html]
set -euo pipefail
SRC="${1:-/home/claude/forma-hrv/outputs/forma-app.html}"
DST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/www/index.html"
if [ ! -f "$SRC" ]; then echo "Brak pliku: $SRC" >&2; exit 1; fi
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
echo "Skopiowano $SRC -> $DST"
