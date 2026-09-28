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
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb pull /sdcard/u.xml "$OUT/xml/$1.xml" >/dev/null 2>&1
}
cap () {  # name
  adb exec-out screencap -p > "$OUT/$1.png"; dump "$1"
}
scrollcap () {  # name [maxframes]
  local name=$1 max=${2:-9} i prev=""
  for i in $(seq 0 $((max-1))); do
    cap "$name-$i"
    local sig; sig=$(sed 's/text="[0-9:۰-۹]*"//g' "$OUT/xml/$name-$i.xml" 2>/dev/null | md5sum | cut -c1-12)
    if [ "$sig" = "$prev" ]; then rm -f "$OUT/$name-$i.png" "$OUT/xml/$name-$i.xml"; break; fi
    prev=$sig
    adb shell input swipe $((W/2)) $((H*70/100)) $((W/2)) $((H*32/100)) 700
    sleep 1.6
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

# 1) every page, light theme, full scroll
for p in login visitor_dashboard visit showcase cart customers visitor_more visitor_reports attendance chat assistant settings health; do
  open_page "$p"; scrollcap "L-$p"
done
# 2) dark theme, full scroll on the main pages
for p in visitor_dashboard visit showcase cart customers visitor_more settings; do
  open_page "$p" noir_aurora; scrollcap "D-$p" 5
done
# 3) detail screens reached by tapping
open_page showcase;      tap_text "جزئیات" && scrollcap "T-product-detail" 6
open_page customers;     tap_text "فروشگاه زنجیره" && scrollcap "T-customer" 6
open_page visit;         tap_text "شروع ویزیت" && scrollcap "T-visit-start" 4
open_page visitor_dashboard; tap_text "سبد" && cap "T-dock-cart"
open_page showcase;      tap_text "بارکد" && cap "T-barcode"
open_page showcase;      tap_text "افزودن" && cap "T-add-to-cart"
# 4) large font (accessibility 1.3x)
adb shell settings put system font_scale 1.3
for p in login visitor_dashboard showcase cart customers visit; do open_page "$p"; scrollcap "F-$p" 3; done
adb shell settings put system font_scale 1.0
# 5) small phone (360x640 dp)
adb shell wm size 720x1280; adb shell wm density 320; sleep 2
W=720; H=1280
for p in login visitor_dashboard showcase cart customers visit; do open_page "$p"; scrollcap "S-$p" 3; done
adb shell wm size reset; adb shell wm density reset
rm -f "$OUT/xml/_tap.xml"
adb logcat -d -t 600 | grep -E "AndroidRuntime|FATAL|ANR|StrictMode|Choreographer.*Skipped" | tail -60 > "$OUT/logcat.txt" || true
echo "done: $(ls "$OUT"/*.png | wc -l) screenshots"
