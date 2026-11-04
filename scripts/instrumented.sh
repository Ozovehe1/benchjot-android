#!/usr/bin/env bash
# Instrumented tests on the emulator (CI): the native engines on real Android
# (app/src/androidTest). Installs the debug app + its test APK, runs them, saves
# logcat (and any seccomp kills from the kernel log) to smoke/, and removes both
# apps so the smoke test starts from a clean release install.
#   scripts/instrumented.sh <app-debug.apk> <app-debug-androidTest.apk>
set -uo pipefail
mkdir -p smoke
adb root >/dev/null 2>&1 && sleep 3 || true # google_apis images allow root: lets us read the kernel log
adb install -r "$1" && adb install -r "$2" || { echo "::error::could not install the test APKs"; exit 1; }
adb logcat -c
adb shell am instrument -w -r app.benchjot.test/androidx.test.runner.AndroidJUnitRunner > smoke/instrumented.txt 2>&1
grep -vE '^INSTRUMENTATION_STATUS(_CODE)?: (class|current|id|numtests|stream=$)' smoke/instrumented.txt | grep -vE '^INSTRUMENTATION_STATUS_CODE' | head -150
adb logcat -d > smoke/logcat-instrumented.txt
echo "--- logcat: crashes, Python, Ruff ---"
grep -E "FATAL|AndroidRuntime|PythonService|chaquopy|python|SIGSYS|seccomp|libruff" smoke/logcat-instrumented.txt | grep -v "^--" | head -80
echo "--- kernel log: seccomp ---"
adb shell dmesg 2>/dev/null | grep -iE "seccomp|audit.*1326|SIGSYS" | tail -20 || true
adb uninstall app.benchjot.test >/dev/null 2>&1 || true
adb uninstall app.benchjot >/dev/null 2>&1 || true
adb unroot >/dev/null 2>&1 || true
if grep -q "^OK (" smoke/instrumented.txt && ! grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" smoke/instrumented.txt; then
  echo "instrumented: $(grep '^OK (' smoke/instrumented.txt)"
else
  echo "::error::instrumented tests failed: $(grep -E '^Tests run' smoke/instrumented.txt)"; exit 1
fi
