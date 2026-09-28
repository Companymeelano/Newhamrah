#!/usr/bin/env bash
# Runs the app's debug self-test on the emulator against the restored Atiran copy on the host (10.0.2.2).
set -u
PKG=ir.meelano.visitor.debug
ACT=ir.meelano.android.MainActivity
OUT=${1:-e2e}
mkdir -p "$OUT"
adb shell settings put global hide_error_dialogs 1 || true
adb install -r -g app-debug.apk
adb logcat -c
adb shell am start -S -W -n "$PKG/$ACT" --es meelano_test_db 10.0.2.2 --es meelano_selftest "latifi:${E2E_PASS}" --es meelano_selftest_customer 412 --es meelano_selftest_items "1796:70,667:2"
for i in $(seq 1 100); do
  sleep 4
  adb logcat -d -s MEELANO_SELFTEST:I > "$OUT/selftest.txt" 2>/dev/null
  if grep -q "MEELANO_SELFTEST.*DONE" "$OUT/selftest.txt"; then echo "self-test finished after $((i*4))s"; break; fi
done
sleep 2
adb exec-out screencap -p > "$OUT/e2e-screen.png" || true
adb logcat -d | grep -E "MEELANO|AndroidRuntime|FATAL|jtds" > "$OUT/logcat.txt" || true
grep -c "STEP" "$OUT/selftest.txt" | sed 's/^/self-test steps logged: /'
grep -c " FAIL" "$OUT/selftest.txt" | sed 's/^/failed steps: /'
