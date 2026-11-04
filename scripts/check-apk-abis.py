#!/usr/bin/env python3
"""Fails unless the APK carries every native engine for each given ABI, built for it:
Python (libpython, Chaquopy's runtime), numpy, QuickJS and Ruff, each checked by its
ELF header (32/64-bit and machine), not just by its path.

    scripts/check-apk-abis.py app.apk arm64-v8a armeabi-v7a
"""
import io
import re
import sys
import zipfile

ELF = {  # abi: (ELF class, e_machine)
    "arm64-v8a": (2, 183),   # 64-bit, AArch64
    "armeabi-v7a": (1, 40),  # 32-bit, ARM
    "x86_64": (2, 62),       # 64-bit, x86-64
    "x86": (1, 3),
}


def elf_kind(data: bytes):
    assert data[:4] == b"\x7fELF", "not an ELF file"
    return data[4], int.from_bytes(data[18:20], "little")


def main(apk: str, abis: list[str]) -> int:
    z = zipfile.ZipFile(apk)
    names = set(z.namelist())
    py = re.compile(r"lib/[^/]+/libpython3\.\d+\.so$")
    problems = []

    def check(where: str, data: bytes, abi: str):
        try:
            got = elf_kind(data)
        except AssertionError as e:
            problems.append(f"{where}: {e}")
            return
        if got != ELF[abi]:
            problems.append(f"{where}: ELF {got}, expected {ELF[abi]} for {abi}")
        else:
            print(f"ok  {abi:12} {where} ({len(data) / 1048576:.1f} MB)")

    for abi in abis:
        libs = [n for n in names if py.match(n) and n.startswith(f"lib/{abi}/")]
        if not libs:
            problems.append(f"lib/{abi}/libpython3.*.so missing")
        for n in libs + [f"lib/{abi}/{f}" for f in ("libchaquopy_java.so", "libquickjs.so", "libruff.so")] \
                + [f"assets/chaquopy/bootstrap-native/{abi}/java/chaquopy.so"]:
            if n in names:
                check(n, z.read(n), abi)
            else:
                problems.append(f"{n} missing")
        # Chaquopy puts files that differ per ABI in requirements-<abi>; with one ABI everything is in -common.
        reqs = f"assets/chaquopy/requirements-{abi}.imy" if len(abis) > 1 else "assets/chaquopy/requirements-common.imy"
        if reqs not in names:
            problems.append(f"{reqs} missing")
            continue
        r = zipfile.ZipFile(io.BytesIO(z.read(reqs)))
        numpy = [n for n in r.namelist() if re.search(r"numpy/core/_multiarray_umath[^/]*\.so$", n)]
        if not numpy:
            problems.append(f"numpy's _multiarray_umath missing from {reqs}")
        for n in numpy:
            check(f"{reqs}!{n}", r.read(n), abi)

    for p in problems:
        print(f"::error::{apk}: {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) < 3 or any(a not in ELF for a in sys.argv[2:]):
        sys.exit(f"usage: {sys.argv[0]} <apk> <abi>... (abis: {', '.join(ELF)})")
    sys.exit(main(sys.argv[1], sys.argv[2:]))
