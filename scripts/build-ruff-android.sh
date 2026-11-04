#!/usr/bin/env bash
# Builds Ruff (the version in scripts/ruff.lock, the web's ruff-wasm-web version)
# from source for Android, linked against Android's own libc (bionic), and puts it
# where the APK picks it up: app/src/main/jniLibs/{arm64-v8a,x86_64}/libruff.so.
#
# Why not the official static Linux (musl) binaries: Android runs apps under a
# seccomp filter, and the x86_64 musl build makes legacy syscalls it forbids, so
# it was killed with SIGSYS on the emulator. A bionic build makes the calls
# Android expects on every ABI. Ruff supports target_os = "android" (Termux).
# Needs: rustup, the Android NDK ($ANDROID_NDK_LATEST_HOME or $ANDROID_NDK_HOME), git.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
VERSION=$(grep '^version=' scripts/ruff.lock | cut -d= -f2)
NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}}
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26 # the app's minSdk
SRC=${RUFF_SRC:-/tmp/ruff-src-$VERSION}

[ -d "$SRC" ] || git clone -q --depth 1 --branch "$VERSION" https://github.com/astral-sh/ruff "$SRC"
cd "$SRC"
rustup show active-toolchain >/dev/null 2>&1 || rustup toolchain install "$(grep channel rust-toolchain.toml | cut -d'"' -f2)" --profile minimal
rustup target add aarch64-linux-android x86_64-linux-android

for t in aarch64 x86_64; do
  T=$(echo "$t" | tr a-z A-Z)
  export "CARGO_TARGET_${T}_LINUX_ANDROID_LINKER=$TC/$t-linux-android$API-clang"
  export "CC_${t}_linux_android=$TC/$t-linux-android$API-clang"
  export "AR_${t}_linux_android=$TC/llvm-ar"
done
# Thin LTO: much faster to build than upstream's fat LTO, same behaviour.
export CARGO_PROFILE_RELEASE_LTO=thin
cargo build --release --locked -p ruff --target aarch64-linux-android --target x86_64-linux-android

for pair in aarch64:arm64-v8a x86_64:x86_64; do
  t=${pair%%:*}; abi=${pair##*:}
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  "$TC/llvm-strip" -o "$ROOT/app/src/main/jniLibs/$abi/libruff.so" "target/$t-linux-android/release/ruff"
  chmod 755 "$ROOT/app/src/main/jniLibs/$abi/libruff.so"
  echo "ruff $VERSION ($t-linux-android) -> app/src/main/jniLibs/$abi/libruff.so ($(stat -c %s "$ROOT/app/src/main/jniLibs/$abi/libruff.so") bytes)"
done
