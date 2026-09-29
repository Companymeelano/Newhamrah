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
shot 15-loading loading
shot 16-loading-dark loading noir_aurora
shot 17-new-customer new_customer
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
# ---- store edition («پخش درخشان فروشگاه») ----
if [ -f app-store-debug.apk ]; then
  adb install -r -g app-store-debug.apk
  PKG=ir.meelano.store.debug
  shot 20-store-login login emerald_royal
  shot 21-store-home store_home emerald_royal
  shot 22-store-debtors store_reports:debtors emerald_royal
  shot 23-store-overdue store_reports:overdue emerald_royal
  shot 24-store-sales store_reports:sales emerald_royal
  shot 25-store-products store_reports:products emerald_royal
  shot 26-store-attendance attendance emerald_royal
  shot 27-store-invoice-done store_invoice_done emerald_royal
  shot 28-store-home-dark store_home noir_aurora
  shot 29-store-more visitor_more emerald_royal
  shot 30-store-checkout store_checkout emerald_royal
  shot 31-store-checkout-pay store_checkout_pay emerald_royal
  shot 32-store-receipt store_receipt emerald_royal
  shot 33-store-settings settings emerald_royal
  shot 34-store-checkout-dark store_checkout_pay noir_aurora
  shot 35-store-attendance-mission attendance_mission emerald_royal
  shot 36-store-mission-dialog mission_dialog emerald_royal
  shot 37-store-reports-customers store_reports:customers emerald_royal
  shot 43-store-me-account store_me:account emerald_royal
  shot 44-store-me-advance store_me:advance emerald_royal
  shot 45-store-advance-dialog advance_dialog emerald_royal
  shot 46-store-me-dark store_me:account noir_aurora
  shot 48-store-me-row store_me_row emerald_royal
  shot 49-store-delivery store_reports:delivery emerald_royal
  # responsive check: a narrow phone and a tablet-sized screen
  adb shell wm size 720x1520; adb shell wm density 320
  shot 38-store-narrow-home store_home emerald_royal
  shot 39-store-narrow-debtors store_reports:debtors emerald_royal
  shot 40-store-narrow-sales store_reports:sales emerald_royal
  shot 47-store-narrow-me store_me:account emerald_royal
  adb shell wm size 1600x2560; adb shell wm density 320
  shot 41-store-tablet-home store_home emerald_royal
  shot 42-store-tablet-debtors store_reports:debtors emerald_royal
  adb shell wm size reset; adb shell wm density reset
fi
# ---- staff edition («پخش درخشان پرسنل», amethyst_pearl) ----
if [ -f app-staff-debug.apk ]; then
  adb install -r -g app-staff-debug.apk
  PKG=ir.meelano.staff.debug
  shot 60-staff-login login amethyst_pearl
  shot 61-staff-home staff_home amethyst_pearl
  shot 62-staff-delivery-open staff_delivery:open amethyst_pearl
  shot 63-staff-delivery-mine staff_delivery:mine amethyst_pearl
  shot 64-staff-delivery-detail staff_delivery_detail amethyst_pearl
  shot 65-staff-receipt-dialog staff_receipt_dialog amethyst_pearl
  shot 66-staff-handover-dialog staff_handover_dialog amethyst_pearl
  shot 67-staff-delivery-done staff_delivery_done amethyst_pearl
  shot 68-staff-delivery-history staff_delivery:done amethyst_pearl
  shot 69-staff-pay staff_pay amethyst_pearl
  shot 70-staff-pay-prev staff_pay_prev amethyst_pearl
  shot 71-staff-attendance attendance amethyst_pearl
  shot 72-staff-me store_me:account amethyst_pearl
  shot 73-staff-more visitor_more amethyst_pearl
  shot 74-staff-home-dark staff_home noir_aurora
  shot 75-staff-open-item staff_delivery_open amethyst_pearl
  adb shell wm size 720x1520; adb shell wm density 320
  shot 76-staff-narrow-detail staff_delivery_detail amethyst_pearl
  shot 77-staff-narrow-pay staff_pay amethyst_pearl
  adb shell wm size 1600x2560; adb shell wm density 320
  shot 78-staff-tablet-home staff_home amethyst_pearl
  adb shell wm size reset; adb shell wm density reset
fi
adb logcat -d -t 400 | grep -E "AndroidRuntime|FATAL|MainActivity" | tail -40 > "$OUT/logcat.txt" || true
