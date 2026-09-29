#!/usr/bin/env bash
# Serve the packaged page for browser development, without building an APK.
#
#   ./scripts/serve.sh [port]      ->  http://localhost:8123
#
# Why a server at all: the page is split into index.html + css/ + js/ + img/
# with relative paths. Opening the file directly (file://) breaks those, and
# IndexedDB / localStorage behave differently there too. Inside the APK the
# WebViewAssetLoader serves the same tree over https, so this mirrors it.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${1:-8123}"
PY="$(command -v python3 || command -v python || true)"
if [ -z "$PY" ]; then
  echo "error: python3 not found (needed for a static file server)." >&2
  exit 1
fi

echo "serving app/src/main/assets on http://localhost:${PORT}  (Ctrl-C to stop)"
exec "$PY" -m http.server "$PORT" --directory app/src/main/assets
