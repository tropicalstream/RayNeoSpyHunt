#!/usr/bin/env bash
# Build, install, launch and screenshot Spy Hunt XR on the RayNeo X3 Pro.
#
#   ./tools/run.sh              build + install + launch
#   ./tools/run.sh shot         capture the framebuffer to /tmp/spyhunt.png
#   ./tools/run.sh log          tail the game's log tag
#   ./tools/run.sh perf         frame-time / GL diagnostics only
set -euo pipefail

PKG=com.rayneo.spyhunt
ACT=$PKG/.MainActivity
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Pick the RayNeo specifically — a phone is usually plugged in alongside it.
DEV="$(adb devices | awk '/device$/{print $1}' | while read -r d; do
  [ "$(adb -s "$d" shell getprop ro.product.manufacturer | tr -d '\r')" = "RayNeo" ] && echo "$d"
done | head -1)"

if [ -z "${DEV:-}" ]; then
  echo "No RayNeo device found. Connected devices:" >&2
  adb devices -l >&2
  exit 1
fi

case "${1:-run}" in
  shot)
    adb -s "$DEV" exec-out screencap -p > /tmp/spyhunt.png
    echo "wrote /tmp/spyhunt.png ($(file -b /tmp/spyhunt.png))"
    ;;
  log)
    adb -s "$DEV" logcat -s SpyHunt:V AndroidRuntime:E
    ;;
  perf)
    adb -s "$DEV" logcat -d -s SpyHunt | grep -E "ms/frame|stereo:|GL " | tail -20
    ;;
  run)
    cd "$ROOT"
    # Build with Gradle but install with adb: :app:installDebug fans out to every
    # attached device, and a phone is usually plugged in next to the glasses.
    ./gradlew --console=plain :app:assembleDebug
    adb -s "$DEV" install -r -t app/build/outputs/apk/debug/app-debug.apk
    adb -s "$DEV" shell am force-stop $PKG
    adb -s "$DEV" logcat -c
    adb -s "$DEV" shell am start -n $ACT
    echo "launched on $DEV"
    ;;
  *)
    echo "usage: $0 [run|shot|log|perf]" >&2
    exit 2
    ;;
esac
