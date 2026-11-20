#!/usr/bin/env bash
# Fails if the app uses a WebView: in its own sources, or anywhere in the built
# APK's code (dependencies included), checked in the dex with dexdump.
#   scripts/check-no-webview.sh [path/to/app.apk]
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
if grep -rn --include='*.kt' --include='*.java' --include='*.xml' -E 'android\.webkit\.WebView|<WebView|androidx\.webkit' app/src engine/src core/src; then
  echo "::error::WebView found in the app's sources (above)"; status=1
fi
if grep -n 'webkit' gradle/libs.versions.toml app/build.gradle.kts; then
  echo "::error::a webkit dependency is declared (above)"; status=1
fi
APK=${1:-}
if [ -n "$APK" ]; then
  DEXDUMP=$(ls "$ANDROID_HOME"/build-tools/*/dexdump | sort -V | tail -1)
  tmp=$(mktemp -d); unzip -q -o "$APK" 'classes*.dex' -d "$tmp"
  refs=0
  for dex in "$tmp"/classes*.dex; do
    n=$("$DEXDUMP" "$dex" 2>/dev/null | grep -c 'Landroid/webkit/WebView;' || true)
    refs=$((refs + n))
  done
  rm -rf "$tmp"
  if [ "$refs" -gt 0 ]; then echo "::error::the APK's code references android.webkit.WebView ($refs places)"; status=1; else echo "APK code: no android.webkit.WebView references"; fi
fi
[ $status -eq 0 ] && echo "no WebView: ok"
exit $status
