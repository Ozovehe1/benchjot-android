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
# Centre of the nodes whose text or content-desc is exactly $1 (or starts with it, written ^text) ($2 = which one: first|last).
find() {
  python3 - "$1" "${2:-first}" "$(dump)" <<'PY'
import re, sys
want, which, xml = sys.argv[1], sys.argv[2], sys.argv[3]
hits = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n).group(1); d = re.search(r' content-desc="([^"]*)"', n).group(1)
    t = t.replace("&#10;", "\n")
    if want in (t, d) or (want.startswith("^") and t.startswith(want[1:])):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        hits.append(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
if hits: print(hits[0] if which == "first" else hits[-1])
PY
}
# Wait up to $2 seconds for $1 to be on screen.
waitfor() {
  for _ in $(seq 1 "${2:-30}"); do [ -n "$(find "$1")" ] && return 0; sleep 1; done
  echo "timed out waiting for: $1"; shot "timeout-$(echo "$1" | tr -cd 'a-zA-Z0-9')"; dump | head -c 4000; exit 1
}
tap() {
  local xy; xy=$(find "$1" "${2:-first}")
  [ -n "$xy" ] || { echo "not found on screen: $1"; dump | head -c 3000; exit 1; }
  adb shell input tap $xy
  sleep 2
}
alive() { adb shell pidof app.benchjot >/dev/null || { echo "app is not running (crash?)"; adb logcat -d | grep -A30 "FATAL EXCEPTION" | tail -60; exit 1; }; }
clear_field() { adb shell input keyevent KEYCODE_MOVE_END; adb shell input keyevent $(printf 'KEYCODE_DEL %.0s' $(seq 1 40)); }
type_text() { adb shell "input text '$(echo "$1" | sed "s/ /%s/g")'"; sleep 1; }

adb shell am start -W -n app.benchjot/.MainActivity
sleep 8; alive; shot 1-landing
tap "Sign in"; alive; shot 2-sign-in
tap "Create an account"; alive; shot 3-create-account
tap "Email"; type_text "not-an-email"; adb shell input keyevent 4; sleep 1; tap "Create account" last; alive
waitfor "Password needs at least 8 characters." 5; shot 4-validation
adb shell "cmd uimode night yes" || true
sleep 3; alive; shot 5-dark
adb shell "cmd uimode night no" || true

# Signed in (only when CI is given a throwaway test account): sign in by @username,
# write a note with a [[link]]-free body, run Python on the device, find it by search.
if [ -n "${BJ_USER:-}" ] && [ -n "${BJ_PASS:-}" ]; then
  tap "I have an account"; sleep 1
  tap "Email or username"; clear_field; type_text "$BJ_USER"
  tap "Password"; clear_field; type_text "$BJ_PASS"; adb shell input keyevent 4; sleep 1
  tap "Sign in" last
  waitfor "New note" 30; alive; shot 6-home
  tap "New note"; waitfor "Experiment note" 20
  type_text "Smoke test note"; sleep 1
  tap "Experiment note"; type_text "Hypothesis: it runs."; adb shell input keyevent 4; sleep 2
  tap "Add code"; waitfor "Code 1" 20
  tap "Code 1"; type_text "print(6*7)"; adb shell input keyevent 4; sleep 2
  waitfor "Python" 10; shot 7-note
  tap "Run"; waitfor "^42" 120; alive; shot 8-ran
  waitfor "Saved" 10
  tap "Preview"; sleep 2; shot 9-preview
  tap "Successful"; sleep 2
  tap "All notes"; waitfor "Smoke test note" 20; shot 10-list
  tap "Search"; type_text "hypothesis"; sleep 3; waitfor "Smoke test note" 10; shot 11-search
  adb shell input keyevent 4; sleep 1
  tap "Settings"; waitfor "@${BJ_USER}" 10; shot 12-settings
  tap "Sign out"; waitfor "Create account" 20; alive; shot 13-signed-out
fi
adb logcat -d | grep -q "FATAL EXCEPTION" && { echo "crash in logcat"; adb logcat -d | grep -A30 "FATAL EXCEPTION" | head -60; exit 1; } || true
echo "smoke: ok"
