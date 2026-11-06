#!/usr/bin/env bash
# Builds libbj_llm.so (llama.cpp at the commit in scripts/llama.lock, plus the JNI bridge in
# app/src/main/cpp) for every ABI the app ships or tests, and puts it where the APK picks it up:
# app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libbj_llm.so.
#
# CPU baselines are the ABI's own minimum, so every phone of that ABI can load it:
#   arm64-v8a  : ARMv8-A with NEON (no dotprod/i8mm: older 64-bit phones lack them)
#   armeabi-v7a: ARMv7-A with NEON (the NDK's default for this ABI), hard-float ABI calls
#   x86_64     : the emulator, for CI only
# Needs: the Android NDK ($ANDROID_NDK_LATEST_HOME or $ANDROID_NDK_HOME), cmake >= 3.22, ninja, git.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
COMMIT=$(grep '^commit=' scripts/llama.lock | cut -d= -f2)
NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}}
API=26 # the app's minSdk
SRC=${LLAMA_SRC:-/tmp/llama-src-$COMMIT}
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[ -x "$STRIP" ] || STRIP=$(command -v llvm-strip)

if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" remote add origin https://github.com/ggml-org/llama.cpp
fi
git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
git -C "$SRC" checkout -q --force "$COMMIT"
[ "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT" ] || { echo "llama.cpp is not at $COMMIT"; exit 1; }

for abi in $ABIS; do
  B="$ROOT/build/llama-android/$abi"
  mkdir -p "$ROOT/build/llama-android"
  cmake -S app/src/main/cpp -B "$B" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DLLAMA_SRC="$SRC" > "$B.config.log" 2>&1 || { tail -40 "$B.config.log"; exit 1; }
  cmake --build "$B" --target bj_llm -j"$(nproc)"
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  "$STRIP" -o "$ROOT/app/src/main/jniLibs/$abi/libbj_llm.so" "$B/libbj_llm.so"
  echo "llama.cpp ${COMMIT::7} ($abi) -> app/src/main/jniLibs/$abi/libbj_llm.so ($(stat -c %s "$ROOT/app/src/main/jniLibs/$abi/libbj_llm.so") bytes)"
done
