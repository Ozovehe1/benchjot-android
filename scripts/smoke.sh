#!/usr/bin/env bash
# Emulator smoke test (CI): install the APK, open it signed out, and walk the
# landing -> sign in -> create account screens by their accessible names,
# saving a screenshot of each to smoke/. Fails if the app crashes.
set -euo pipefail
APK=${1:-benchjot.apk}
mkdir -p smoke
adb install -r "$APK"
adb logcat -c
shot() { adb exec-out screencap -p > "smoke/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
# Tap the centre of the first node whose text or content-desc is exactly $1.
tap() {
  local xml; xml=$(dump)
  local xy; xy=$(python3 - "$1" "$xml" <<'PY'
import re, sys
want, xml = sys.argv[1], sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n).group(1); d = re.search(r' content-desc="([^"]*)"', n).group(1)
    if want in (t, d):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  [ -n "$xy" ] || { echo "not found on screen: $1"; echo "$xml" | head -c 3000; exit 1; }
  adb shell input tap $xy
  sleep 2
}
alive() { adb shell pidof app.benchjot >/dev/null || { echo "app is not running (crash?)"; adb logcat -d | grep -E "AndroidRuntime|FATAL|app.benchjot" | tail -60; exit 1; }; }

adb shell am start -W -n app.benchjot/.MainActivity
sleep 8; alive; shot 1-landing
tap "Sign in"; alive; shot 2-sign-in
tap "Create an account"; alive; shot 3-create-account
tap "Email"; adb shell input text "not-an-email"; tap "Create account"; alive; shot 4-validation
adb shell "cmd uimode night yes" || true
sleep 3; alive; shot 5-dark
adb logcat -d | grep -E "AndroidRuntime|FATAL EXCEPTION" && { echo "crash in logcat"; exit 1; } || true
echo "smoke: ok"
