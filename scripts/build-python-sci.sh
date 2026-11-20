#!/usr/bin/env bash
# Cross-builds the NumPy-2 scientific stack (scripts/python-sci-packages.txt: OpenBLAS, numpy,
# contourpy, matplotlib, pandas, scipy, scikit-learn) for one Android ABI, from source, with
# meson-python, as cp313 wheels Chaquopy installs:
#
#   scripts/build-python-sci.sh armeabi-v7a|arm64-v8a|x86_64
#
# Why from source: Chaquopy's index stops at numpy 1.26 / no scipy for armeabi-v7a, and no
# Fortran compiler targets 32-bit ARM Android. SciPy 1.18's Fortran-free build
# (-D_without-fortran=true) needs NumPy >= 2, so the whole numpy-linked stack is rebuilt
# against NumPy 2, the same versions for every ABI (Chaquopy pins every ABI to the first's).
#
# How: Chaquopy's own android-env.sh (same tag as the app's plugin) sets the NDK compilers and
# flags; a meson cross file points meson at them; the build Python (3.13 on the host) reports
# the target's sysconfig (_PYTHON_SYSCONFIGDATA_NAME + _PYTHON_HOST_PLATFORM, the way CPython's
# own cross builds and cibuildwheel's Android support do), so extension suffixes and wheel tags
# are the target's. BLAS/LAPACK is OpenBLAS with its C LAPACK (NOFORTRAN), one shared
# libopenblas.so shipped in the benchjot-openblas wheel's chaquopy/lib (Chaquopy loads it for
# the modules that need it). One C++ runtime and one OpenMP runtime per process, as Chaquopy's own
# wheels do: C++ links the NDK's libc++_shared.so, which the wheel then requires as Chaquopy's
# chaquopy-libcxx (same NDK libc++ version; kiwisolver already uses it), and a wheel needing
# libomp.so carries the NDK's in its chaquopy/lib (Chaquopy's index only has an old libomp).
# Static copies per extension module would break both: libomp aborts when a second copy starts
# in the process, and C++ exceptions crossing pybind11 modules need one runtime's type_info.
#
# Inputs: build/python-armv7/maven (scripts/build-python-armv7.sh: Chaquopy's Python target
# zips for every ABI, incl. our armeabi-v7a one, and the stdlib with each ABI's sysconfigdata).
# Output: build/python-sci/<abi>/*.whl (pip --find-links). Wheels already there are kept, so a
# cached partial run resumes at the first missing package.
# Needs: Linux x86-64, python3.13, $ANDROID_HOME with sdkmanager, patchelf, curl, unzip, git.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
ABI=${1:?usage: build-python-sci.sh <abi>}
# The app's minSdk: bionic declares some C99 functions SciPy calls (clog, cpow) only from API 26.
API=$(grep -oE 'minSdk = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+$')
[ -n "$API" ] || { echo "minSdk not found in app/build.gradle.kts"; exit 1; }
CHAQUOPY=$(grep -m1 '^chaquopy = "' gradle/libs.versions.toml | cut -d'"' -f2)
PY=$(grep -oE 'version = "3\.[0-9]+"' app/build.gradle.kts | cut -d'"' -f2)
MAVEN=${PY_ARMV7_OUT:-$ROOT/build/python-armv7}/maven/com/chaquo/python
OUT=${PY_SCI_OUT:-$ROOT/build/python-sci}/$ABI
WORK=${PY_SCI_WORK:-/tmp/py-sci}/$ABI
PLAT=android_${API}_${ABI//-/_}
case $ABI in
  # OpenBLAS targets as Chaquopy's chaquopy-openblas recipe (ARMv6 kernels: armeabi-v7a doesn't promise VFPv3-D32).
  # long double: 64-bit on 32-bit ARM; IEEE quad (128-bit) on arm64 and on Android's x86_64 (not x87's 80 bits as on Linux).
  armeabi-v7a) HOST=arm-linux-androideabi; CPU_FAMILY=arm;    CPU=armv7;  LDBL=IEEE_DOUBLE_LE;             OB_ARGS="TARGET=ARMV6 ARM_SOFTFP_ABI=1"; RT_ARCH=arm ;;
  arm64-v8a)   HOST=aarch64-linux-android; CPU_FAMILY=aarch64; CPU=armv8; LDBL=IEEE_QUAD_LE;               OB_ARGS="TARGET=ARMV8"; RT_ARCH=aarch64 ;;
  x86_64)      HOST=x86_64-linux-android;  CPU_FAMILY=x86_64; CPU=x86_64; LDBL=IEEE_QUAD_LE;               OB_ARGS="TARGET=NEHALEM"; RT_ARCH=x86_64 ;;
  *) echo "unknown ABI $ABI"; exit 1 ;;
esac
command -v "python$PY" >/dev/null || { echo "python$PY must be on PATH"; exit 1; }
log() { echo "::group::$*" 2>/dev/null; echo "== $*"; }
endlog() { echo "::endgroup::"; }
version_of() { grep -E "^$1 " scripts/python-sci-packages.txt | awk '{print $2}'; }

rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"
P=$WORK/prefix   # the target's include/ and lib/ (Python, OpenBLAS)
mkdir -p "$P/include" "$P/lib/pkgconfig" "$WORK/sysconfig" "$WORK/site" "$WORK/dist"

# Compilers and flags: Chaquopy's android-env.sh at the plugin's tag (installs its pinned NDK).
curl -fsSL --retry 5 -o "$WORK/android-env.sh" "https://raw.githubusercontent.com/chaquo/chaquopy/$CHAQUOPY/target/android-env.sh"
export HOST PREFIX=$P api_level=$API
# shellcheck disable=SC1091
source "$WORK/android-env.sh"
echo "Chaquopy $CHAQUOPY, Python $PY, $ABI ($HOST), CC=$CC"
# The NDK's C++ and OpenMP runtimes (see the top): libc++'s version is chaquopy-libcxx's version.
NDK_TC=$(cd "$(dirname "$CC")/.." && pwd)
LIBCXX_VERSION=$(sed -nE 's/^ *# *define +_LIBCPP_VERSION +([0-9]+)$/\1/p' "$NDK_TC/sysroot/usr/include/c++/v1/__config")
LIBOMP=$(ls "$NDK_TC"/lib/clang/*/lib/linux/$RT_ARCH/libomp.so)
[ -n "$LIBCXX_VERSION" ] && [ -f "$LIBOMP" ] || { echo "NDK runtimes not found under $NDK_TC"; exit 1; }

# Target Python: headers + libpython from its target zip, sysconfigdata from the stdlib zip.
TVER=$(ls "$MAVEN/target" | sort -V | tail -1)
unzip -q "$MAVEN/target/$TVER/target-$TVER-$ABI.zip" "include/*" "jniLibs/$ABI/libpython$PY.so" -d "$WORK/t"
cp -r "$WORK/t/include/python$PY" "$P/include/"
cp "$WORK/t/jniLibs/$ABI/libpython$PY.so" "$P/lib/"
SCD=$("python$PY" - "$MAVEN/target/$TVER/target-$TVER-stdlib.zip" "$HOST" "$WORK/sysconfig" "$P" "$PY" <<'PY'
import sys, zipfile
std, host, dest, prefix, py = sys.argv[1:]
z = zipfile.ZipFile(std)
names = [n for n in z.namelist() if n.startswith("_sysconfigdata_") and n.endswith(f"_{host}.py")]
assert len(names) == 1, f"sysconfigdata for {host}: {names}"
ns = {}
exec(z.read(names[0]).decode(), ns)
v = ns["build_time_vars"]
assert v["EXT_SUFFIX"].endswith(f"{host}.so") or host.startswith("arm-"), v["EXT_SUFFIX"]
# Paths from the machine that built CPython: point them at this build's copy.
v.update(INCLUDEPY=f"{prefix}/include/python{py}", LIBDIR=f"{prefix}/lib", LIBPL=f"{prefix}/lib", LIBPC=f"{prefix}/lib/pkgconfig")
open(f"{dest}/{names[0]}", "w").write(f"build_time_vars = {v!r}\n")
print(names[0][:-3])
PY
)
echo "target sysconfig $SCD"
# sysconfig's include paths come from the running interpreter's prefix, not the sysconfigdata, so
# meson would compile against the host's Python headers: point them at the target's (imported at
# start-up from PYTHONPATH below, like cibuildwheel's Android cross venv).
cat > "$WORK/sysconfig/sitecustomize.py" <<EOF
import sysconfig
_INC = "$P/include/python$PY"
_paths, _path = sysconfig.get_paths, sysconfig.get_path
def get_paths(*args, **kwargs):
    paths = _paths(*args, **kwargs)
    paths.update(include=_INC, platinclude=_INC)
    return paths
def get_path(name, *args, **kwargs):
    return _INC if name in ("include", "platinclude") else _path(name, *args, **kwargs)
sysconfig.get_paths, sysconfig.get_path = get_paths, get_path
EOF
# meson asks pkg-config for python-3.x first (in LIBPC, then the cross pkg-config dir): the target's.
for pc in "python-$PY" "python-$PY-embed"; do
  printf 'prefix=%s\nName: Python\nDescription: Python %s for %s\nVersion: %s\nLibs: -L${prefix}/lib -lpython%s\nCflags: -I${prefix}/include/python%s\n' \
    "$P" "$PY" "$ABI" "$PY" "$PY" "$PY" > "$P/lib/pkgconfig/$pc.pc"
done
# The build Python, reporting the target's sysconfig (extension suffix, platform tag).
TARGET_ENV=(_PYTHON_HOST_PLATFORM="android-$API-${ABI//-/_}" _PYTHON_SYSCONFIGDATA_NAME="$SCD" PYTHONPATH="$WORK/sysconfig")

# ELF checks for every native file in a wheel: right machine, and nothing needed beyond the
# system, libpython, our libopenblas, libc++_shared (only with chaquopy-libcxx in its metadata)
# and libraries the wheel carries in chaquopy/lib (no libgfortran or the like to go missing).
check_wheel() { # <wheel>
  "python$PY" - "$1" "$ABI" <<'PY'
import io, re, subprocess, sys, zipfile, os
whl, abi = sys.argv[1:]
machine = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86_64": (2, 62)}[abi]
ok_needed = re.compile(r"lib(c|m|dl|log|z|android|python3\.\d+|openblas)\.so$")
z = zipfile.ZipFile(whl); bad = []; n = 0
carried = {os.path.basename(n) for n in z.namelist() if n.startswith("chaquopy/lib/")}
meta = next(z.read(n).decode() for n in z.namelist() if n.endswith(".dist-info/METADATA"))
if re.search(r"^Requires-Dist: chaquopy-libcxx", meta, re.M): carried.add("libc++_shared.so")
for name in z.namelist():
    data = z.read(name)
    if data[:4] != b"\x7fELF": continue
    n += 1
    if (data[4], int.from_bytes(data[18:20], "little")) != machine: bad.append(f"{name}: wrong ELF machine"); continue
    open("/tmp/_elf", "wb").write(data)
    dyn = subprocess.run([os.environ["READELF"], "-d", "/tmp/_elf"], capture_output=True, text=True).stdout
    for lib in re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", dyn):
        if not (ok_needed.match(lib) or lib in carried): bad.append(f"{name}: needs {lib}")
assert n, f"{whl}: no native files"
if bad: sys.exit("\n".join(bad))
print(f"ok {os.path.basename(whl)}: {n} native files")
PY
}

# 1. OpenBLAS (BLAS + C LAPACK, single-threaded like Chaquopy's) ----------------------------
OBV=$(version_of openblas); OBD=$OUT/_openblas
if [ ! -f "$OBD/lib/libopenblas.so" ]; then
  log "OpenBLAS $OBV for $ABI"
  curl -fsSL --retry 5 "https://github.com/OpenMathLib/OpenBLAS/archive/v$OBV.tar.gz" | tar xz -C "$WORK"
  (
    cd "$WORK/OpenBLAS-$OBV"
    # Clang's assembler syntax for the ARM kernels (as Chaquopy's recipe).
    [ "$ABI" != armeabi-v7a ] || find kernel/arm -name '*.S' | xargs sed -i 's/fldmias/vldmia.f32/; s/fldmiad/vldmia.f64/; s/fstmias/vstmia.f32/; s/fstmiad/vstmia.f64/'
    # shellcheck disable=SC2086
    make -j "$CPU_COUNT" HOSTCC=gcc CC="$CC" AR="$AR" RANLIB="$RANLIB" NOFORTRAN=1 C_LAPACK=1 \
      USE_THREAD=0 USE_LOCKING=1 NUM_THREADS=8 NO_STATIC=1 $OB_ARGS >/dev/null
    # shellcheck disable=SC2086
    make HOSTCC=gcc CC="$CC" NOFORTRAN=1 C_LAPACK=1 USE_THREAD=0 NO_STATIC=1 $OB_ARGS PREFIX="$WORK/ob" install >/dev/null
  )
  rm -rf "$OBD"; mkdir -p "$OBD/lib"
  cp "$(readlink -f "$WORK/ob/lib/libopenblas.so")" "$OBD/lib/libopenblas.so"
  patchelf --set-soname libopenblas.so "$OBD/lib/libopenblas.so"
  "$STRIP" "$OBD/lib/libopenblas.so"
  cp -r "$WORK/ob/include" "$OBD/include"
  endlog
fi
cp "$OBD/lib/libopenblas.so" "$P/lib/"; cp -r "$OBD/include/." "$P/include/"
cat > "$P/lib/pkgconfig/openblas.pc" <<EOF
prefix=$P
libdir=\${prefix}/lib
includedir=\${prefix}/include
Name: openblas
Description: OpenBLAS $OBV (C LAPACK) for $ABI
Version: $OBV
Libs: -L\${libdir} -lopenblas
Cflags: -I\${includedir}
EOF
if ! ls "$OUT"/benchjot_openblas-"$OBV"-*.whl >/dev/null 2>&1; then
  W=$WORK/openblas-wheel; mkdir -p "$W/chaquopy/lib" "$W/benchjot_openblas-$OBV.dist-info"
  cp "$OBD/lib/libopenblas.so" "$W/chaquopy/lib/"
  printf 'Metadata-Version: 2.1\nName: benchjot-openblas\nVersion: %s\nSummary: OpenBLAS %s (C LAPACK) for Android %s, built by Benchjot\nLicense: BSD-3-Clause\n' "$OBV" "$OBV" "$ABI" > "$W/benchjot_openblas-$OBV.dist-info/METADATA"
  printf 'Wheel-Version: 1.0\nGenerator: benchjot\nRoot-Is-Purelib: false\nTag: py3-none-%s\n' "$PLAT" > "$W/benchjot_openblas-$OBV.dist-info/WHEEL"
  "python$PY" -m venv "$WORK/venv-tools"; "$WORK/venv-tools/bin/pip" install -q wheel
  "$WORK/venv-tools/bin/python" -m wheel pack "$W" -d "$OUT"
fi
check_wheel "$(ls "$OUT"/benchjot_openblas-"$OBV"-*.whl)"

# 2. The Python packages, in order ---------------------------------------------------------------
# A wheel linking libc++_shared.so requires chaquopy-libcxx; one linking libomp.so carries it (see the top).
runtimes() { # <wheel> <venv with wheel>
  local d=$WORK/fix needs
  rm -rf "$d"; "$2/bin/python" -m wheel unpack "$1" -d "$d" >/dev/null
  needs=$(find "$d" -name '*.so' -exec "$READELF" -d {} + 2>/dev/null | sed -nE 's/.*\(NEEDED\).*\[(libc\+\+_shared|libomp)\.so\]/\1/p' | sort -u || true)
  [ -n "$needs" ] || return 0
  local root; root=$(echo "$d"/*)
  if grep -qx 'libc++_shared' <<<"$needs"; then
    sed -i "/^Metadata-Version/a Requires-Dist: chaquopy-libcxx (>=$LIBCXX_VERSION)" "$root"/*.dist-info/METADATA
  fi
  if grep -qx libomp <<<"$needs"; then mkdir -p "$root/chaquopy/lib"; cp "$LIBOMP" "$root/chaquopy/lib/"; fi
  rm "$1"; "$2/bin/python" -m wheel pack "$root" -d "$(dirname "$1")" >/dev/null
  echo "runtimes for $(basename "$1"): $(echo $needs)"
}
# Meson cross file from the android-env.sh variables (C++: matplotlib narrows unsigned to int in
# braced initializers, which clang rejects where int is 32-bit); numpy's headers (the target's own build,
# so _numpyconfig.h has this ABI's type sizes) once numpy is built.
arr() { "python$PY" -c 'import shlex, sys; print(repr(shlex.split(" ".join(sys.argv[1:]))))' "$@"; }
cross_file() { # <venv>
  local f=$WORK/cross.ini npinc=$WORK/site/numpy/_core/include
  cat > "$f" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'
python = '$1/bin/python'
cython = '$1/bin/cython'

[host_machine]
system = 'android'
kernel = 'linux'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[properties]
needs_exe_wrapper = true
pkg_config_libdir = '$P/lib/pkgconfig'
longdouble_format = '$LDBL'
$( if [ -d "$npinc" ]; then echo "numpy-include-dir = '$npinc'"; fi )

[built-in options]
c_args = $(arr "$CFLAGS")
cpp_args = $(arr "$CXXFLAGS -Wno-c++11-narrowing")
c_link_args = $(arr "$LDFLAGS -lpython$PY")
cpp_link_args = $(arr "$LDFLAGS -lpython$PY")
EOF
  echo "$f"
}

grep -vE '^\s*(#|$)' scripts/python-sci-packages.txt | grep -v '^openblas ' | while read -r NAME VERSION; do
  DIST=${NAME//-/_}
  if WHEEL=$(ls "$OUT/$DIST-$VERSION"-*.whl 2>/dev/null | head -1) && [ -n "$WHEEL" ]; then
    echo "== $NAME $VERSION: cached $(basename "$WHEEL")"
  else
    log "$NAME $VERSION (cp${PY/./} $ABI)"
    SRC=$WORK/src/$NAME; mkdir -p "$SRC"
    URL=$(curl -fsSL --retry 5 "https://pypi.org/pypi/$NAME/$VERSION/json" | "python$PY" -c "import json,sys; print([u['url'] for u in json.load(sys.stdin)['urls'] if u['packagetype']=='sdist'][0])")
    curl -fsSL --retry 5 "$URL" | tar xz -C "$SRC" --strip-components=1
    # The host build environment: the package's own build requirements, with numpy / scipy at
    # this stack's versions (their pure parts: f2py, .pxd files, code generators).
    V=$WORK/venv-$NAME; "python$PY" -m venv "$V"
    "python$PY" - "$SRC/pyproject.toml" "$(version_of numpy)" "$(version_of scipy)" > "$WORK/reqs-$NAME.txt" <<'PY'
import re, sys, tomllib
reqs = tomllib.load(open(sys.argv[1], "rb"))["build-system"]["requires"]
pins = {"numpy": sys.argv[2], "scipy": sys.argv[3]}
for r in reqs:
    name = re.split(r"[ <>=!~;\[]", r.strip(), 1)[0].lower()
    print(f"{name}=={pins[name]}" if name in pins else r)
print("build"); print("wheel")
PY
    "$V/bin/pip" install -q -r "$WORK/reqs-$NAME.txt"
    ARGS=()
    case $NAME in
      numpy) ARGS=(-Csetup-args=-Dblas=openblas -Csetup-args=-Dlapack=openblas -Csetup-args=-Dallow-noblas=false) ;;
      scipy)
        ARGS=(-Csetup-args=-D_without-fortran=true -Csetup-args=-Duse-pythran=false -Csetup-args=-Dblas=openblas -Csetup-args=-Dlapack=openblas)
        # Bionic has no pthread_{get,set}affinity_np: take ducc0's portable path (hardware_concurrency, no pinning).
        "python$PY" - "$SRC/subprojects/duccfft/ducc0/infra/threading.cc" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "#if __has_include(<pthread.h>) && defined(__linux__) && defined(_GNU_SOURCE)"
assert s.count(old) == 3, "ducc0 threading.cc changed; review this patch"
open(p, "w").write(s.replace(old, old + " && !defined(__ANDROID__)"))
PY
        # Boost.Math takes every x86 long double for x87's 80 bits; Android's x86_64 one is IEEE quad, so its
        # x86 branch must not match there (Boost's generic 128-bit branch does).
        "python$PY" - "$SRC/subprojects/boost_math/math/include/boost/math/special_functions/detail/fp_traits.hpp" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "#elif defined(__i386) || defined(__i386__) || defined(_M_IX86) \\\n    || defined(__amd64) || defined(__amd64__)  || defined(_M_AMD64) \\\n    || defined(__x86_64) || defined(__x86_64__) || defined(_M_X64)\n"
assert s.count(old) == 1, "boost fp_traits.hpp changed; review this patch"
open(p, "w").write(s.replace(old, "#elif (LDBL_MANT_DIG == 64) && (" + old[len("#elif "):-1] + ")\n"))
PY
        ;;
      pandas)
        # pandas asks the build Python's numpy for its headers; take the cross file's (this ABI's) instead.
        "python$PY" - "$SRC/pandas/meson.build" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "incdir_numpy = run_command(py,"
assert s.count(old) == 1, "pandas/meson.build changed; review this patch"
s = s.replace(old, "incdir_numpy = meson.get_external_property('numpy-include-dir', 'not-given')\nif incdir_numpy == 'not-given'\nincdir_numpy = run_command(py,", 1)
end = ").stdout().strip()\n"
i = s.index(end, s.index("incdir_numpy = run_command(py,")) + len(end)
s = s[:i] + "endif\n" + s[i:]
open(p, "w").write(s)
PY
        ;;
    esac
    CROSS=$(cross_file "$V")
    rm -rf "$WORK/dist"/*
    # The venv's bin on PATH: meson looks Cython up as a build-machine tool, not from the cross file.
    # No PKG_CONFIG_PATH: setup-python points it at the host Python's python-3.x.pc.
    (cd "$SRC" && env -u PKG_CONFIG_PATH "${TARGET_ENV[@]}" PATH="$V/bin:$PATH" "$V/bin/python" -m build --wheel --no-isolation --skip-dependency-check \
      --outdir "$WORK/dist" -Csetup-args=--cross-file="$CROSS" -Cinstall-args=--strip "${ARGS[@]}" .)
    WHEEL=$(ls "$WORK"/dist/*.whl)
    case $WHEEL in
      *-cp${PY/./}-cp${PY/./}-$PLAT.whl) ;;
      *) "$V/bin/python" -m wheel tags --python-tag "cp${PY/./}" --abi-tag "cp${PY/./}" --platform-tag "$PLAT" --remove "$WHEEL"
         WHEEL=$(ls "$WORK"/dist/*.whl) ;;
    esac
    runtimes "$WHEEL" "$V"
    check_wheel "$WHEEL"
    cp "$WHEEL" "$OUT/"; WHEEL=$OUT/$(basename "$WHEEL")
    endlog
  fi
  # The target's numpy (headers, f2py sources) for the packages after it; dependency('numpy')
  # finds its headers through a numpy.pc in the cross pkg-config dir.
  if [ "$NAME" = numpy ]; then
    unzip -q -o "$WHEEL" -d "$WORK/site"
    printf 'includedir=%s\nName: numpy\nDescription: NumPy %s for %s\nVersion: %s\nCflags: -I${includedir}\n' \
      "$WORK/site/numpy/_core/include" "$VERSION" "$ABI" "$VERSION" > "$P/lib/pkgconfig/numpy.pc"
  fi
done
ls -l "$OUT"
