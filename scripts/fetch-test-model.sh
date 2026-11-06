#!/usr/bin/env bash
# A 1.2 MB test model (llama.cpp's stories260K) for the instrumented llama.cpp tests, checked by
# SHA-256 and kept out of git: app/src/androidTest/assets/llm/stories260K.gguf.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/androidTest/assets/llm/stories260K.gguf
SHA=270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d
mkdir -p "$(dirname "$OUT")"
if [ ! -f "$OUT" ] || ! echo "$SHA  $OUT" | sha256sum -c --quiet - 2>/dev/null; then
  curl -fsSL --retry 3 -o "$OUT.part" https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf
  echo "$SHA  $OUT.part" | sha256sum -c --quiet - || { echo "test model hash mismatch"; rm -f "$OUT.part"; exit 1; }
  mv "$OUT.part" "$OUT"
fi
echo "test model: $OUT ($(stat -c %s "$OUT") bytes)"
