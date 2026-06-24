#!/usr/bin/env bash
# capture.sh — deterministic screenshot harness for etabli-focus (v0.1.0)
# Prereqs: adb on PATH; emulator/device booted; debug APK installed; notification
#          permission granted (adb shell pm grant <pkg> android.permission.POST_NOTIFICATIONS).
set -euo pipefail
PKG=com.raban.etabli.focus.debug
OUT="$(cd "$(dirname "$0")/.." && pwd)/vignettes/assets/0.1.0"; mkdir -p "$OUT"
cap(){ for t in 1 2 3; do adb exec-out screencap -p > "$OUT/$1.png"; [ "$(wc -c < "$OUT/$1.png")" -gt 1000 ] && break; sleep 1; done; echo "  + $1.png"; }
nav(){ adb shell input tap "$1" 2230; sleep 0.9; }
adb shell am force-stop "$PKG"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
cap 01-today
nav 318; cap 02-template
nav 538; cap 03-stats
nav 758; cap 04-categories
nav 978; cap 05-settings
nav 98; adb shell input tap 540 1017; sleep 1; cap 06-start-block
echo "Captured $(ls "$OUT"/*.png|wc -l) frames"
