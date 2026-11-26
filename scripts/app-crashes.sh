#!/usr/bin/env bash
# app-crashes.sh [logcat file] (default stdin): prints the app's own crashes in a logcat dump and exits 1
# if there are any, 0 if none. Counted: a Java "FATAL EXCEPTION" whose "Process:" line names app.benchjot
# (or app.benchjot:python), and a native tombstone (">>> app.benchjot... <<<"). Other processes' crashes,
# such as the uiautomator dump tool's own NPE on a changing screen, are not the app's and don't count.
awk '
  / AndroidRuntime: FATAL EXCEPTION:/ { pid = $3; hdr = $0; want = 1; next }
  want && $3 == pid && / AndroidRuntime: / {
    want = 0
    if ($0 ~ /AndroidRuntime: Process: app\.benchjot[,:]/) { grab = pid; n = 0; print hdr; found = 1 }
  }
  grab != "" && $3 == grab && / AndroidRuntime: / { if (n++ < 40) print; next }
  grab != "" && $3 != grab { grab = "" }
  / DEBUG +: pid: [0-9]+, tid: [0-9]+, name: .* >>> app\.benchjot/ { tomb = 1; m = 0; found = 1 }
  tomb && / DEBUG +: / { if (m++ < 40) print; next }
  tomb { tomb = 0 }
  END { exit found ? 1 : 0 }
' "${1:-/dev/stdin}"
