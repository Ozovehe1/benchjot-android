#!/usr/bin/env bash
# Downloads the pinned Ruff binaries (scripts/ruff.lock), checking their sha256.
#   scripts/fetch-ruff.sh app    -> app/src/main/jniLibs/{arm64-v8a,x86_64}/libruff.so (bundled in the APK)
#   scripts/fetch-ruff.sh host   -> build/ruff-host/ruff for this machine (engine tests)
# Not committed: 20 MB binaries stay out of git; the lock file is the source of truth.
set -euo pipefail
cd "$(dirname "$0")/.."
lock() { grep "^$1=" scripts/ruff.lock | cut -d= -f2; }
VERSION=$(lock version)
fetch() { # arch dest
  local arch=$1 dest=$2 tmp; tmp=$(mktemp -d)
  local mark="build/ruff-markers/$(echo "$dest" | tr '/' '_')"
  if [ -x "$dest" ] && [ "$(cat "$mark" 2>/dev/null)" = "$(lock "$arch")" ]; then return 0; fi
  curl -fsSL -o "$tmp/r.tgz" "https://github.com/astral-sh/ruff/releases/download/$VERSION/ruff-$arch-unknown-linux-musl.tar.gz"
  echo "$(lock "$arch")  $tmp/r.tgz" | sha256sum -c --quiet -
  tar -xzf "$tmp/r.tgz" -C "$tmp"
  mkdir -p "$(dirname "$dest")"
  install -m 755 "$tmp/ruff-$arch-unknown-linux-musl/ruff" "$dest"
  mkdir -p build/ruff-markers && lock "$arch" > "$mark"
  rm -rf "$tmp"
  echo "ruff $VERSION ($arch) -> $dest"
}
case "${1:-}" in
  app)
    fetch aarch64 app/src/main/jniLibs/arm64-v8a/libruff.so
    fetch x86_64 app/src/main/jniLibs/x86_64/libruff.so ;;
  host)
    case "$(uname -m)" in aarch64|arm64) fetch aarch64 build/ruff-host/ruff ;; x86_64) fetch x86_64 build/ruff-host/ruff ;; *) echo "no Ruff binary for $(uname -m)"; exit 1 ;; esac ;;
  *) echo "usage: $0 app|host"; exit 2 ;;
esac
