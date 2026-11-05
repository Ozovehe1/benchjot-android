#!/usr/bin/env bash
# Builds what Chaquopy needs to run the app's Python (3.13 + its packages) on 32-bit ARM
# phones (armeabi-v7a), which Chaquopy only publishes for 64-bit ABIs from 3.12 on:
#
#   1. CPython for arm-linux-androideabi, with Chaquopy's own target scripts
#      (CPython's official Android/android.py underneath), packaged as Chaquopy's
#      `target` artifact for armeabi-v7a.
#   2. Chaquopy's runtime (chaquopy.so, libchaquopy_java.so) for that Python, from
#      the same Chaquopy tag's Cython sources.
#   3. The native packages that don't link NumPy (kiwisolver, pillow, markupsafe, pyyaml,
#      tornado: scripts/python-armv7-packages.txt) as cp313
#      armeabi-v7a wheels, with Chaquopy's build-wheel recipes (the same recipe and
#      version its index has for cp313 arm64/x86_64).
#   4. The Chaquopy Gradle plugin with armeabi-v7a allowed for Python 3.13: its only
#      change is Common.supportedAbis (one line, patched below and asserted).
#
# Output: build/python-armv7/maven, a Maven repo that settings.gradle.kts puts in
# front of Maven Central for exactly these modules (the 64-bit files are mirrored
# from Central, sha1-checked), and build/python-armv7/wheels for pip --find-links.
# Versions come from the project (DRY): Chaquopy from gradle/libs.versions.toml,
# the Python minor version from app/build.gradle.kts, the micro version and build
# number from that Chaquopy tag's Common.java.
#
# Needs: Linux x86-64, python3.13, JDK 11+ (javac, jar), $ANDROID_HOME with
# sdkmanager (the NDK version Chaquopy pins is installed if missing), cmake,
# patchelf, git, curl, zip, unzip.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
ABI=armeabi-v7a
HOST=arm-linux-androideabi
CHAQUOPY=$(grep -m1 '^chaquopy = "' gradle/libs.versions.toml | cut -d'"' -f2)
PY=$(grep -oE 'version = "3\.[0-9]+"' app/build.gradle.kts | cut -d'"' -f2)
OUT=${PY_ARMV7_OUT:-$ROOT/build/python-armv7}
WORK=${PY_ARMV7_WORK:-/tmp/py-armv7}
SRC=$WORK/chaquopy-$CHAQUOPY
CENTRAL=https://repo1.maven.org/maven2/com/chaquo/python
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
[ -n "$CHAQUOPY" ] && [ -n "$PY" ] || { echo "could not read the Chaquopy or Python version"; exit 1; }
command -v "python$PY" >/dev/null || { echo "python$PY must be on PATH"; exit 1; }
log() { echo "::group::$*" 2>/dev/null; echo "== $*"; }
endlog() { echo "::endgroup::"; }

mkdir -p "$WORK"
[ -d "$SRC" ] || git clone -q --depth 1 --branch "$CHAQUOPY" https://github.com/chaquo/chaquopy "$SRC"
MICRO=$("python$PY" "$SRC/target/list-versions.py" --micro | grep "^$PY\.")
BUILD=$("python$PY" "$SRC/target/list-versions.py" --build | grep "^$PY\." | sed 's/.*-//')
TVER=$MICRO-$BUILD                         # e.g. 3.13.16-0
NDK_VERSION=$(grep -oE '^ndk_version=\S+' "$SRC/target/android-env.sh" | cut -d= -f2)
echo "Chaquopy $CHAQUOPY, Python $TVER, NDK $NDK_VERSION, ABI $ABI"

# Download a Maven Central file into the local repo, checked against its .sha1.
mirror() { # <group path under com/chaquo/python> <file>
  local dir="$OUT/maven/com/chaquo/python/$1" f=$2
  mkdir -p "$dir"
  [ -f "$dir/$f" ] && return 0
  curl -fsSL --retry 5 -o "$dir/$f.part" "$CENTRAL/$1/$f"
  echo "$(curl -fsSL --retry 5 "$CENTRAL/$1/$f.sha1" | cut -c1-40)  $dir/$f.part" | sha1sum -c --quiet -
  mv "$dir/$f.part" "$dir/$f"
}
rm -rf "$OUT"; mkdir -p "$OUT/maven" "$OUT/wheels"

# 1. CPython ----------------------------------------------------------------
log "CPython $MICRO for $HOST"
PREFIX=$SRC/target/prefix/$ABI
if [ ! -f "$PREFIX/lib/libpython$PY.so" ]; then
  (cd "$SRC/target" && python/build.sh "prefix/$ABI" "$MICRO")
fi
test -f "$PREFIX/lib/libpython$PY.so"
endlog

log "package target $TVER ($ABI)"
OWN=$WORK/target-own/$TVER
rm -rf "$WORK/target-own"
(cd "$SRC/target/prefix" && ../package-target.sh "$OWN" "$ABI")
T=target/$TVER
mirror "$T" "target-$TVER.pom"
for a in arm64-v8a x86_64; do mirror "$T" "target-$TVER-$a.zip"; done
cp "$OWN/target-$TVER-$ABI.zip" "$OUT/maven/com/chaquo/python/$T/"
# The stdlib is shared by every ABI except its _sysconfigdata module: Central's
# stdlib plus this ABI's sysconfigdata (same CPython source, so nothing else differs).
for kind in stdlib stdlib-pyc; do
  mirror "$T" "target-$TVER-$kind.zip"
  "python$PY" - "$OUT/maven/com/chaquo/python/$T/target-$TVER-$kind.zip" "$OWN/target-$TVER-$kind.zip" "$HOST" <<'PY'
import sys, zipfile
dest, own, host = sys.argv[1:]
with zipfile.ZipFile(own) as src:
    extra = [n for n in src.namelist() if "_sysconfigdata_" in n and host in n]
    assert extra, f"no sysconfigdata for {host} in {own}"
    with zipfile.ZipFile(dest, "a", zipfile.ZIP_DEFLATED) as out:
        have = set(out.namelist())
        for n in extra:
            if n not in have:
                out.writestr(n, src.read(n))
print("stdlib +", extra)
PY
done
mkdir -p "$SRC/maven/com/chaquo/python/target"   # where build-wheel.py looks
rm -rf "$SRC/maven/com/chaquo/python/target/$TVER"; cp -r "$OWN" "$SRC/maven/com/chaquo/python/target/$TVER"
# build-wheel.py links lib<name>.so to each library under its other names, and both
# libpython3.so and libpython3.13.so map to libpython.so, so it fails on the second.
# Extensions link libpython3.13.so; the stable-ABI stub only goes into the app's copy.
zip -q -d "$SRC/maven/com/chaquo/python/target/$TVER/target-$TVER-$ABI.zip" "jniLibs/$ABI/libpython3.so"
endlog

# 2. Chaquopy runtime ---------------------------------------------------------
log "Chaquopy $CHAQUOPY runtime for Python $PY $ABI"
VENV=$WORK/venv; [ -x "$VENV/bin/python" ] || "python$PY" -m venv "$VENV"
"$VENV/bin/pip" install -q -r "$SRC/product/runtime/requirements-build.txt"
RT=$SRC/product/runtime; CY=$WORK/runtime/cython; rm -rf "$WORK/runtime"; mkdir -p "$CY"
cython_one() { # <dir> <pyx> : as product/runtime/build.gradle's cythonTask
  (cd "$1" && "$VENV/bin/cython" -Wextra -Werror "$2" -I "$CY" -o "$CY/${2%.pyx}.c")
  "python$PY" - "$CY/${2%.pyx}.c" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
s, n = re.subn(r"^__PYX_EXTERN_C (\w+)", r"/* cythonTask altered */ __PYX_EXTERN_C JNIEXPORT \1 JNICALL", s, flags=re.M)
s = re.sub(r"__pyx_insert_code_object\(c_line", r"// cythonTask disabled: \g<0>", s)
open(p, "w").write(s)
PY
}
cython_one "$RT/src/main/python/java" chaquopy.pyx
cython_one "$RT/src/main/python" chaquopy_java.pyx
NDK=$ANDROID_HOME/ndk/$NDK_VERSION   # installed by step 1 (android-env.sh)
cmake -S "$RT" -B "$WORK/runtime/build" -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI \
  -DANDROID_NATIVE_API_LEVEL=24 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCHAQUOPY_SRC_DIR="$CY" -DCHAQUOPY_PYTHON_VERSION="$PY" -DCHAQUOPY_PY_SUFFIX=.so \
  -DCHAQUOPY_INCLUDE_PYTHON="$PREFIX/include" -DCHAQUOPY_LIB_DIRS="$PREFIX/lib"
cmake --build "$WORK/runtime/build" -j "$(nproc)"
for name in chaquopy libchaquopy_java; do
  R=runtime/$name/$CHAQUOPY
  mirror "$R" "$name-$CHAQUOPY.pom"
  for a in arm64-v8a x86_64; do mirror "$R" "$name-$CHAQUOPY-$PY-$a.so"; done
  cp "$WORK/runtime/build/$name.so" "$OUT/maven/com/chaquo/python/$R/$name-$CHAQUOPY-$PY-$ABI.so"
done
endlog

# 3. Native packages ------------------------------------------------------------
# Each package in scripts/python-armv7-packages.txt (kiwisolver, pillow, ...), in that order,
# with Chaquopy's build-wheel recipe for the same version its index has for cp313 arm64/x86_64.
# A recipe's host requirements are Chaquopy's prebuilt C libraries for armeabi-v7a (freetype,
# libjpeg, ...), put in dist/. The NumPy stack is scripts/build-python-sci.sh.
PYPI=$SRC/server/pypi
"$VENV/bin/pip" install -q -r "$PYPI/requirements.txt"
host_wheel() { # <dist name> <wheel file> : a prebuilt armeabi-v7a library from Chaquopy's index
  mkdir -p "$PYPI/dist/$1"
  [ -f "$PYPI/dist/$1/$2" ] || curl -fsSL --retry 5 -o "$PYPI/dist/$1/$2" "https://chaquo.com/pypi-13.1/$1/$2"
}
host_wheel chaquopy-freetype chaquopy_freetype-2.9.1-2-py3-none-android_16_armeabi_v7a.whl  # pillow
host_wheel chaquopy-libjpeg chaquopy_libjpeg-1.5.3-1-py3-none-android_16_armeabi_v7a.whl     # pillow
host_wheel chaquopy-libyaml chaquopy_libyaml-0.2.5-0-py3-none-android_24_armeabi_v7a.whl     # pyyaml
grep -vE '^\s*(#|$)' scripts/python-armv7-packages.txt | while read -r NAME VERSION; do
  log "$NAME $VERSION wheel (cp${PY/./} $ABI)"
  RECIPE=$(grep -m1 -E '^  version: ' "$PYPI/packages/$NAME/meta.yaml" | grep -oE '[0-9][0-9.]*' || true)
  # Recipes that set the version through a Jinja variable: {% set version = "x" %}
  [ -n "$RECIPE" ] || RECIPE=$(grep -oE 'set version = "[^"]+"' "$PYPI/packages/$NAME/meta.yaml" | cut -d'"' -f2)
  [ "$RECIPE" = "$VERSION" ] || { echo "::error::$NAME: the recipe builds $RECIPE, scripts/python-armv7-packages.txt pins $VERSION"; exit 1; }
  (cd "$PYPI" && PATH="$VENV/bin:$PATH" python build-wheel.py --python "$PY" --abi "$ABI" "$NAME")
  WHEEL=$(ls "$PYPI"/dist/$NAME/*-$VERSION-*${ABI//-/_}.whl 2>/dev/null | head -1 || true)
  [ -n "$WHEEL" ] || { echo "::error::no $NAME $VERSION wheel for $ABI in dist/$NAME"; ls "$PYPI/dist/$NAME" || true; exit 1; }
  cp "$WHEEL" "$OUT/wheels/"
  endlog
done

# 4. Gradle plugin with armeabi-v7a allowed for this Python ----------------------
log "Chaquopy $CHAQUOPY Gradle plugin, $ABI allowed for Python $PY"
G=gradle/$CHAQUOPY; GD=$OUT/maven/com/chaquo/python/$G
mirror "$G" "gradle-$CHAQUOPY.pom"; mirror "$G" "gradle-$CHAQUOPY.jar"
J=$WORK/plugin; rm -rf "$J"; mkdir -p "$J/src" "$J/classes"
COMMON=product/buildSrc/src/main/java/com/chaquo/python/internal/Common.java
cp "$SRC/$COMMON" "$J/src/Common.java"
"python$PY" - "$J/src/Common.java" "$PY" "$ABI" <<'PY'
import sys
p, py, abi = sys.argv[1:]
s = open(p).read()
anchor = '        result.sort(null);'
assert s.count(anchor) == 1, "Common.supportedAbis changed upstream; review this patch"
s = s.replace(anchor, f'        if (pythonVersion.equals("{py}") && !result.contains("{abi}")) result.add("{abi}");  // Benchjot: own {abi} build\n' + anchor)
open(p, "w").write(s)
PY
javac --release 11 -d "$J/classes" "$J/src/Common.java"
(cd "$J/classes" && jar uf "$GD/gradle-$CHAQUOPY.jar" com/chaquo/python/internal/Common*.class)
unzip -l "$GD/gradle-$CHAQUOPY.jar" | grep -q 'com/chaquo/python/internal/Common.class'
# Its pom says "published-with-gradle-metadata", so Gradle reads the .module: keep it, with the new jar's size and hashes.
mirror "$G" "gradle-$CHAQUOPY.module"
"python$PY" - "$GD/gradle-$CHAQUOPY.module" "$GD/gradle-$CHAQUOPY.jar" <<'PY'
import hashlib, json, os, sys
mod, jar = sys.argv[1:]
data = open(jar, "rb").read(); m = json.load(open(mod)); n = 0
for v in m["variants"]:
    for f in v.get("files", []):
        if f["name"] == os.path.basename(jar):
            f["size"] = len(data); n += 1
            for h in ("sha512", "sha256", "sha1", "md5"): f[h] = hashlib.new(h, data).hexdigest()
assert n, "jar not listed in the .module"
json.dump(m, open(mod, "w"), indent=2)
PY
endlog

find "$OUT" -type f | sort | sed "s|$OUT/||"
echo "python-armv7 -> $OUT"
