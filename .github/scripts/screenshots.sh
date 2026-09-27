#!/usr/bin/env bash
# Captures design-preview screenshots of the debug APK on a running emulator.
set -u
PKG=ir.meelano.visitor.debug
ACT=ir.meelano.android.MainActivity
OUT=${1:-shots}
mkdir -p "$OUT"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 1
adb install -r -g app-debug.apk
shot () {  # name page [theme]
  local name=$1 page=$2 theme=${3:-azure_diamond}
  adb shell am start -S -W -n "$PKG/$ACT" --es meelano_preview "$page" --es meelano_theme "$theme" >/dev/null
  sleep 6
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "captured $name ($(stat -c %s "$OUT/$name.png") bytes)"
}
shot 01-login login
shot 02-home visitor_dashboard
shot 03-visit visit
shot 04-products showcase
shot 05-cart cart
shot 06-customers customers
shot 07-more visitor_more
shot 08-settings settings
shot 09-home-dark visitor_dashboard noir_aurora
shot 10-products-dark showcase noir_aurora
adb logcat -d -t 400 | grep -E "AndroidRuntime|FATAL|MainActivity" | tail -40 > "$OUT/logcat.txt" || true
