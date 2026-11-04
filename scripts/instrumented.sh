#!/usr/bin/env bash
# Instrumented tests on the emulator (CI): the native engines on real Android
# (app/src/androidTest). Installs the debug app + its test APK, runs them, and
# removes both so the smoke test starts from a clean release install.
#   scripts/instrumented.sh <dir with debug/app-debug.apk and androidTest/debug/app-debug-androidTest.apk>
set -euo pipefail
DIR=${1:-apks}
mkdir -p smoke
adb install -r "$DIR/debug/app-debug.apk"
adb install -r "$DIR/androidTest/debug/app-debug-androidTest.apk"
adb shell am instrument -w -r app.benchjot.test/androidx.test.runner.AndroidJUnitRunner | tee smoke/instrumented.txt
adb uninstall app.benchjot.test >/dev/null || true
adb uninstall app.benchjot >/dev/null || true
if grep -q "^OK (" smoke/instrumented.txt && ! grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" smoke/instrumented.txt; then
  echo "instrumented: $(grep '^OK (' smoke/instrumented.txt)"
else
  echo "::error::instrumented tests failed"; exit 1
fi
