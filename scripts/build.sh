#!/usr/bin/env bash
# One-shot debug build.
#
#   ./scripts/build.sh
#
# Produces a universal, debug-signed APK:
#   app/build/outputs/apk/debug/Video Downloader.apk
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  echo "error: no JDK found. Install JDK 17 and set JAVA_HOME (or put java on PATH)." >&2
  exit 1
fi

if [ ! -f local.properties ] \
   && [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "error: no Android SDK configured." >&2
  echo "       Create local.properties with:  sdk.dir=/path/to/Android/Sdk" >&2
  echo "       (or export ANDROID_HOME)." >&2
  exit 1
fi

./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/Video Downloader.apk"
echo
echo "built: $APK"
ls -lh "$APK"
