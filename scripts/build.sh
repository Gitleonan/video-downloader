#!/usr/bin/env bash
# One-shot build.
#
#   ./scripts/build.sh            # debug   -> app/build/outputs/apk/debug/Video Downloader.apk
#   ./scripts/build.sh release    # release -> app/build/outputs/apk/release/Video Downloader.apk
#
# The debug variant is signed with the public Android debug key and carries
# android:debuggable="true", which device security scanners flag as high risk.
# Use `release` for anything you hand to other people.
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT="${1:-debug}"
case "$VARIANT" in
  debug|release) ;;
  *) echo "error: unknown variant '$VARIANT' (expected debug or release)." >&2; exit 1 ;;
esac

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

if [ "$VARIANT" = "release" ] && [ ! -f keystore.properties ]; then
  echo "error: a release build needs signing credentials." >&2
  echo "       Create keystore.properties at the repo root — see README." >&2
  exit 1
fi

# Gradle/AGP need JDK 17+. When JAVA_HOME is unset or points at an older JVM
# (a stock macOS env often carries JDK 8), pick up a 17 via java_home and use
# it for this build only; elsewhere Gradle reports the mismatch itself.
need17=1
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  if "$JAVA_HOME/bin/java" -version 2>&1 | head -1 \
      | grep -Eq 'version "1[7-9]\.|version "[2-9][0-9]\.'; then
    need17=0
  fi
fi
if [ "$need17" = 1 ] && [ "$(uname -s)" = "Darwin" ] \
   && [ -x /usr/libexec/java_home ]; then
  JH17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [ -n "$JH17" ]; then
    export JAVA_HOME="$JH17"
    echo "using JDK 17: $JAVA_HOME"
  fi
fi

# Capitalize the variant without bash-4 `${VARIANT^}` (macOS ships bash 3.2).
TASK_VARIANT="$(printf '%s' "$VARIANT" | cut -c1 | tr '[:lower:]' '[:upper:]')${VARIANT#?}"
./gradlew ":app:assemble$TASK_VARIANT"

APK="app/build/outputs/apk/${VARIANT}/Video Downloader.apk"
echo
echo "built: $APK"
ls -lh "$APK"
