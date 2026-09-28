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
# The emulator's own launcher sometimes shows "isn't responding"; keep such system dialogs off the shots.
adb shell settings put global hide_error_dialogs 1 || true
dismiss_anr () {
  local i f
  for i in 1 2 3; do
    f=$(adb shell dumpsys window | grep -m1 mCurrentFocus)
    echo "$f" | grep -qi "not responding" || return 0
    echo "dismissing system dialog: $f"
    adb shell uiautomator dump /sdcard/anr.xml >/dev/null 2>&1
    local b; b=$(adb exec-out cat /sdcard/anr.xml | grep -o 'text="Wait"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
    set -- $b
    if [ $# -ge 4 ]; then adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); else adb shell input keyevent KEYCODE_BACK; fi
    sleep 2
  done
}
shot () {  # name page [theme] [showcase-mode]
  local name=$1 page=$2 theme=${3:-azure_diamond} mode=${4:-catalog}
  adb shell am start -S -W -n "$PKG/$ACT" --es meelano_preview "$page" --es meelano_theme "$theme" --es meelano_showcase_mode "$mode" >/dev/null
  sleep 6
  dismiss_anr
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
shot 12-products-smooth showcase azure_diamond compact
shot 13-products-ultra showcase azure_diamond ultra
shot 14-products-ultra-dark showcase noir_aurora ultra
# ---- behaviour checks (results in checks.txt) ----
check_back () {  # page expected(exit|stay) [screenshot-name]
  adb shell am start -S -W -n "$PKG/$ACT" --es meelano_preview "$1" --es meelano_theme azure_diamond >/dev/null
  sleep 6
  adb shell input keyevent KEYCODE_BACK
  sleep 3
  local f r
  f=$(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -s ' ')
  if echo "$f" | grep -q "$PKG"; then r=stay; else r=exit; fi
  local verdict=FAIL; [ "$r" = "$2" ] && verdict=PASS
  echo "$verdict back-on-$1: got=$r expected=$2 ::$f" | tee -a "$OUT/checks.txt"
  if [ -n "${3:-}" ]; then adb exec-out screencap -p > "$OUT/$3.png"; fi
}
check_back visitor_dashboard exit
check_back showcase stay 11-back-from-products
adb logcat -d -t 400 | grep -E "AndroidRuntime|FATAL|MainActivity" | tail -40 > "$OUT/logcat.txt" || true
