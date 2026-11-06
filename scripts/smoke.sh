#!/usr/bin/env bash
# Emulator smoke test (CI): install the APK, open it signed out, and walk the
# landing -> sign in (Continue with Google opens Credential Manager) -> create
# account screens by their accessible names, saving a screenshot of each to
# smoke/. With a throwaway account (BJ_USER/BJ_PASS): sign in, write a note,
# run Python/JS/TS on the device, Stop a runaway run, Ruff Autofix, figures and charts (own
# screen, downloads), math preview, the Map.
# Fails if the app crashes.
set -euo pipefail
APK=${1:-benchjot.apk}
mkdir -p smoke
trap 'adb logcat -d > smoke/logcat-smoke.txt 2>/dev/null' EXIT
adb install -r "$APK"
adb logcat -c
shot() { adb exec-out screencap -p > "smoke/$1.png"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
# Centre of the nodes whose text or content-desc is exactly $1 (or starts with it, written ^text) ($2 = which one: first|last).
find() {
  python3 - "$1" "${2:-first}" "$(dump)" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
want, which, xml = sys.argv[1], sys.argv[2], sys.argv[3]
hits = []
# A real XML parse: attribute values may hold '>' or quotes (the Python symbol row has '"').
try:
    nodes = ET.fromstring(xml[xml.index("<hierarchy"):]).iter("node")
except (ValueError, ET.ParseError):
    nodes = []
for n in nodes:
    t, d = n.get("text", ""), n.get("content-desc", "")
    if want in (t, d) or (want.startswith("^") and t.startswith(want[1:])):
        x1, y1, x2, y2 = map(int, re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get("bounds", "")).groups())
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
# Screen size (the CI emulator is 320x640): swipes are fractions of it.
read -r W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
swipe_up() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 200; } # content moves up (scroll down)
to_top() { for _ in 1 2 3 4 5 6; do adb shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 150; done; sleep 1; }
to_bottom() { for _ in 1 2 3 4 5 6; do swipe_up; done; sleep 1; }
# Like waitfor, but scrolls down while waiting: for things below the fold (a new code block).
seek() {
  for _ in $(seq 1 "${2:-20}"); do [ -n "$(find "$1")" ] && return 0; swipe_up; sleep 1; done
  echo "timed out seeking: $1"; shot "timeout-$(echo "$1" | tr -cd 'a-zA-Z0-9')"; dump | head -c 4000; exit 1
}
# Scrolls up a fifth of the screen at a time until $1 is on screen: for a block's header row
# (its Run button sits above the code, so a tall block's header is off screen after to_bottom).
seek_up() {
  for _ in $(seq 1 "${2:-10}"); do [ -n "$(find "$1")" ] && return 0; adb shell input swipe $((W / 2)) $((H * 2 / 5)) $((W / 2)) $((H * 3 / 5)) 300; sleep 1; done
  echo "timed out seeking up: $1"; shot "timeout-up-$(echo "$1" | tr -cd 'a-zA-Z0-9')"; dump | head -c 4000; exit 1
}
back() { adb shell input keyevent 4; sleep 2; }
focus() { adb shell dumpsys window | grep -E "mCurrentFocus" | head -1; }
type_text() { adb shell "input text '$(echo "$1" | sed "s/ /%s/g")'"; sleep 1; }

# Start it the way the launcher icon does (MAIN/LAUNCHER), so the task's root intent matches the
# relaunch after process death below; a bare -n start makes that relaunch stack a fresh activity on top.
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.benchjot/.MainActivity
sleep 8; alive; shot 1-landing
# The landing's example note is read-only: shown, with no text field on screen.
landing="$(dump)"
echo "$landing" | grep -q 'Digits: logistic regression vs random forest' || { echo "::error::landing preview note not shown"; exit 1; }
if echo "$landing" | grep -q 'class="android.widget.EditText"'; then echo "::error::landing preview has an editable field"; exit 1; fi
# Its outputs (table, figure, chart, bundled in the APK) open read-only in the viewer, and Back returns.
seek "Open"; tap "Open"; waitfor "Back to note" 20; alive; shot 1b-example-output
[ -n "$(find "Sample note")" ] || { echo "::error::sample output viewer is not tagged Sample note"; exit 1; }
[ -n "$(find "Download")" ] || { echo "::error::example output viewer has no Download"; exit 1; }
tap "Back to note"; waitfor "Open" 20; alive
seek "Sign in"; tap "Sign in"; alive; shot 2-sign-in # below the example note on small screens
# Continue with Google: the button must hand over to Android's Credential Manager.
# (A real Google account can't be used by automation, so the sign-in itself isn't completed here.)
adb logcat -c
tap "Continue with Google"; sleep 6; shot 2b-google
{ echo "focus after tap: $(focus)"; adb logcat -d | grep -iE "CredentialManager|credentials|GetSignInWithGoogle|GoogleId|identity" | grep -v "^--" | head -40; } > smoke/google.txt || true
cat smoke/google.txt
grep -q "GoogleIdService" smoke/google.txt || { echo "::error::Continue with Google did not reach Credential Manager's Google provider"; exit 1; }
if focus | grep -q "app.benchjot/"; then
  echo "google: no system sheet in front; app message: $(dump | grep -o 'text="[^"]*"' | grep -iE 'google|credential|account' | head -3 | tr '\n' ' ')"
else
  echo "google: Credential Manager UI in front ($(focus))"; back
fi
alive; waitfor "Create an account" 10
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
  tap "Experiment note"; type_text 'Hypothesis: it runs. $E=mc^2$'; adb shell input keyevent 4; sleep 2
  tap "Add code"; waitfor "Code 1" 20
  tap "Code 1"; type_text "print(6*7)"; adb shell input keyevent 4; sleep 2
  waitfor "Python" 10; shot 7-note
  tap "Run"; waitfor "^42" 120; alive; shot 8-ran
  # JavaScript and TypeScript on the device (QuickJS + the web's own sucrase transpile)
  to_bottom; tap "Add code"; seek "Code 2"
  tap "Code 2"; type_text "console.log(6*7+1)"; back
  to_bottom; waitfor "JavaScript" 10; tap "Run" last; waitfor "^43" 60; alive; shot 8b-js
  to_bottom; tap "Add code"; seek "Code 3"
  tap "Code 3"; type_text "const n: number = 44; console.log(n)"; back
  to_bottom; waitfor "TypeScript" 10; tap "Run" last; waitfor "^44" 60; alive; shot 8c-ts
  # Stop really stops a runaway Python run (its process is killed)
  to_bottom; tap "Add code"; seek "Code 4"
  tap "Code 4"; type_text "import os"; adb shell input keyevent 66; type_text "while True: pass"; back
  to_bottom; tap "Run" last; waitfor "Stop" 30; sleep 2; shot 8d-running; tap "Stop"; waitfor "^Stopped" 15; alive; shot 8e-stopped
  # Ruff Autofix on the device: the unused `import os` goes, the loop stays
  to_bottom; waitfor "Autofix" 15; tap "Autofix"; sleep 2
  waitfor "^while True: pass" 10
  [ -z "$(find "^import os")" ] || { echo "::error::Autofix left the unused import"; shot timeout-autofix; exit 1; }
  shot 8f-autofixed; echo "ruff autofix: ok"
  # The note's text saved ("Saved" shows until the note screen is left: the viewer below replaces it).
  to_top; waitfor "Saved" 10
  # Rich outputs: a matplotlib figure and a Plotly chart under the block, saved to Storage; each
  # opens on its own screen and downloads (PNG, then the chart as PDF) into Downloads/Benchjot.
  to_bottom; tap "Add code"; seek "Code 5"
  tap "Code 5"
  for line in "import matplotlib.pyplot as plt" "plt.plot([1,2,3],[3,1,2])" "plt.show()" "import plotly.express as px" "px.scatter(x=[1,2,3],y=[4,1,2]).show()"; do
    type_text "$line"; adb shell input keyevent 66
  done
  back; to_bottom; seek_up "Run"; tap "Run" last
  seek "Open" 90; alive; shot 8g-outputs
  tap "Open"; waitfor "Download" 30
  # Whichever output's Open was on screen, step back to the first (the figure).
  for _ in 1 2 3; do [ -n "$(find "Prev")" ] || break; tap "Prev"; done
  waitfor "^Figure" 10; shot 8h-figure-viewer
  tap "Download"; waitfor "^Saved to Downloads/Benchjot/" 30; echo "figure download: ok"
  tap "Next"; waitfor "^Plotly chart" 20; sleep 2; shot 8i-chart-viewer
  tap "PNG"; waitfor "PDF" 10; tap "PDF"; tap "Download"; waitfor "^Saved to Downloads/Benchjot/" 30; alive; shot 8j-chart-pdf; echo "chart pdf download: ok"
  # Back on the same note at the same spot (Note.kt rememberRestoredScrollState): the outputs' Open
  # is on screen again without scrolling, and the top of the note (its Outcome row) is not.
  tap "Back to note"; waitfor "Open" 20; sleep 2
  [ -n "$(find "Open")" ] && [ -z "$(find "Outcome")" ] || { echo "::error::Back to note did not return to the same place in the note"; shot back-to-note; dump | head -c 3000; exit 1; }
  shot 8k-back-same-spot; echo "back to note at the same spot: ok"
  to_top; [ -n "$(find "Preview")" ] || to_top # the note is taller now (two figures under Code 5)
  waitfor "Preview" 10
  tap "Preview"; waitfor "E=mc^2" 10; shot 9-preview-math
  tap "Successful"; sleep 2
  tap "All notes"; waitfor "Smoke test note" 20; shot 10-list
  # The map: one dot for the one note, legend, tap the dot to open it
  tap "Map"; waitfor "Sub-note" 20; waitfor "Map of 1 notes" 20; sleep 1; shot 10b-map
  tap "Map of 1 notes"; waitfor "Preview" 20; alive; shot 10c-map-opened
  tap "All notes"; waitfor "Smoke test note" 20
  # A note in a project: leaving the app (process killed, task still in Recents) and coming back
  # reopens that note; deleting it then stays in the project, not All notes.
  seek "New project"; tap "New project"; waitfor "Project name" 10; type_text "Smoke project"; tap "OK"; waitfor "Smoke project" 20
  tap "Smoke project"; waitfor "Rename" 20
  tap "New note"; waitfor "Experiment note" 20; type_text "Filed note"; sleep 3
  adb shell input keyevent KEYCODE_HOME; sleep 2; adb shell am kill app.benchjot; sleep 2
  adb shell pidof app.benchjot >/dev/null && echo "process still alive after am kill" || echo "process killed while in Recents"
  adb shell monkey -p app.benchjot -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 # = tapping the icon / the Recents card
  waitfor "Preview" 60; waitfor "Filed note" 20; alive; shot 11b-restored
  tap "More"; waitfor "Delete note" 10; tap "Delete note"; waitfor "OK" 10; tap "OK" # Delete note lives in the ⋮ menu
  waitfor "Rename" 20; waitfor "Smoke project" 10; alive; shot 11c-deleted-in-project
  tap "Delete"; waitfor "OK" 10; tap "OK"; waitfor "New project" 20; to_top
  tap "Search"; type_text "hypothesis"; sleep 3; waitfor "Smoke test note" 10; shot 11-search
  adb shell input keyevent 4; sleep 1
  tap "Settings"; waitfor "@${BJ_USER}" 10; shot 12-settings
  tap "Sign out"; waitfor "Digits: logistic regression vs random forest" 20; seek "Create account"; alive; shot 13-signed-out # back on the landing; its buttons sit below the example note
fi
# Only the app's own crashes fail the smoke (the uiautomator dump tool can NPE on a screen that is changing).
adb logcat -d > smoke/logcat-smoke.txt
bash scripts/app-crashes.sh smoke/logcat-smoke.txt > smoke/app-crashes.txt || { echo "app crash in logcat"; head -80 smoke/app-crashes.txt; exit 1; }
echo "smoke: ok"
