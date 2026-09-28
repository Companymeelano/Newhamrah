#!/usr/bin/env bash
# Deep UI review capture: every page, full scroll, UI hierarchy dumps, dark theme,
# large font and small screen variants, plus a few taps into detail screens.
set -u
PKG=ir.meelano.visitor.debug
ACT=ir.meelano.android.MainActivity
OUT=${1:-review}
mkdir -p "$OUT/xml"
W=$(adb shell wm size | tail -1 | sed 's/.*: //' | cut -dx -f1)
H=$(adb shell wm size | tail -1 | sed 's/.*: //' | cut -dx -f2)
echo "screen ${W}x${H} density $(adb shell wm density | tail -1)" | tee "$OUT/env.txt"

open_page () {  # page [theme]
  adb shell am start -S -W -n "$PKG/$ACT" --es meelano_preview "$1" --es meelano_theme "${2:-azure_diamond}" >/dev/null
  sleep 6
}
dump () {  # name
  local k
  for k in 1 2 3; do
    adb shell rm -f /sdcard/u.xml
    adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
    adb pull /sdcard/u.xml "$OUT/xml/$1.xml" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "dump failed: $1" >> "$OUT/env.txt"
}
screensig () {  # md5 of the middle band of the raw framebuffer (ignores status bar clock)
  adb exec-out screencap | tail -c +$((16 + W*4*H*22/100)) | head -c $((W*4*H*46/100)) | md5sum | cut -c1-12
}
cap () {  # name
  adb exec-out screencap -p > "$OUT/$1.png"; dump "$1"
}
scrollcap () {  # name [maxframes]
  local name=$1 max=${2:-9} i prev="" sig
  for i in $(seq 0 $((max-1))); do
    sig=$(screensig)
    if [ "$sig" = "$prev" ]; then break; fi
    prev=$sig
    cap "$name-$i"
    adb shell input swipe $((W/2)) $((H*72/100)) $((W/2)) $((H*34/100)) 900
    sleep 2
  done
}
tap_text () {  # substring  -> taps first node whose text/content-desc contains it
  dump _tap
  local xy
  xy=$(python3 - "$OUT/xml/_tap.xml" "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for n in root.iter('node'):
    if sys.argv[2] in (n.get('text', '') + ' ' + n.get('content-desc', '')):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; sleep 3; echo "tapped '$1' at $xy" >> "$OUT/env.txt"; return 0; fi
  echo "NOT FOUND '$1'" >> "$OUT/env.txt"; return 1
}

# 1) main pages, light theme, full scroll
for p in visitor_dashboard visit showcase cart customers visitor_more settings; do
  open_page "$p"; scrollcap "L-$p" 10
done
# 2) detail screens and dialogs reached by tapping
open_page showcase;  tap_text "جزئیات" && scrollcap "T-product-detail" 6
open_page customers; tap_text "هایپر خانواده" && scrollcap "T-customer" 6
open_page cart;      tap_text "ویرایش" && cap "T-cart-edit"
open_page cart;      tap_text "تغییر مشتری" && scrollcap "T-customer-picker" 3
open_page visitor_dashboard; tap_text "انتخاب تم" && scrollcap "T-theme" 3
open_page visitor_more; tap_text "پایان روز" && cap "T-end-of-day"
open_page visitor_more; tap_text "پیش‌نویس‌ها" && cap "T-drafts"
open_page showcase;  tap_text "سه‌بعدی" && scrollcap "T-mode-3d" 3
open_page showcase;  tap_text "فوق‌سبک" && scrollcap "T-mode-ultra" 3
rm -f "$OUT/xml/_tap.xml"
adb logcat -d -t 600 | grep -E "AndroidRuntime|FATAL|ANR|StrictMode|Choreographer.*Skipped" | tail -60 > "$OUT/logcat.txt" || true
echo "done: $(ls "$OUT"/*.png | wc -l) screenshots"
