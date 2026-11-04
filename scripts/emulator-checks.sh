#!/usr/bin/env bash
# Everything CI does on the emulator. Both parts always run, so one red run shows every problem.
#   scripts/emulator-checks.sh <apks dir>
D=${1:-apks}
bash scripts/instrumented.sh "$D/app-debug-x86_64.apk" "$D/app-debug-androidTest.apk"; I=$?
sleep 3
bash scripts/smoke.sh "$D/app-release-x86_64.apk"; S=$?
echo "instrumented exit $I, smoke exit $S"
[ $I -eq 0 ] && [ $S -eq 0 ]
