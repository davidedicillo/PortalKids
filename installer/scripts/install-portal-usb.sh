#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
SERIAL="${ANDROID_SERIAL:-}"

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
fi

if [[ -z "$SERIAL" ]]; then
  echo "No authorized Portal found. Enable USB debugging, connect USB, and accept the Portal prompt." >&2
  "$ADB" devices
  exit 1
fi

"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" shell cmd package set-home-activity com.davidedicillo.portalroutine/.HomeActivity
"$ADB" -s "$SERIAL" shell am start -n com.davidedicillo.portalroutine/.HomeActivity

echo "Installed PortalKids on $SERIAL"
