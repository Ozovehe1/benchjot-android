# Benchjot for Android

The native Android app for [Benchjot](https://benchjot.vercel.app) — a research jotter for your code. Kotlin + Jetpack Compose (Material 3), same Supabase backend as the web app.

**Install:** download `benchjot.apk` from the [latest release](https://github.com/Ozovehe1/benchjot-android/releases/latest) on your phone and open it.

## Layout

- `core/` — pure Kotlin, no Android: the rules shared with the web (links, tree, search, usernames, languages, runs, Fix diff, highlighting, autosave). Tested on any JVM: `./gradlew :core:test -PcoreOnly`.
- `core/src/test/resources/web/` — fixtures generated from the web repo by `scripts/web-fixtures.mjs`. CI regenerates them from the web's `main` and fails if they differ, so the two apps can't silently drift.
- `engine/` — plain-JVM on-device engines, tested on the build machine: JavaScript/TypeScript in QuickJS (quickjs-kt) and Ruff as a process.
- `app/` — the Compose app. `data/` talks to Supabase (supabase-kt) and the web's API routes (Ktor), `run/` runs code on the device, `ui/` is the screens.
- `app/benchjot.properties` — the public client config (Supabase URL + anon key, web URL).

## Running code (native, no WebView)

- **Python**: embedded CPython 3.13 (Chaquopy) in its own process (`run/PythonService.kt`, `src/main/python/bj_runner.py`). Same behaviour as the web's Pyodide runner: fresh `__main__` per run, stdout/stderr line by line, no stdin, top-level `await`, tracebacks trimmed to the user's `<exec>` lines, 30 s limit from the start. Stop and the time limit kill the process, so even a busy loop stops. Bundled packages: **numpy, pandas, matplotlib, seaborn, scipy, scikit-learn, Pillow, Plotly, Altair, Bokeh**. 64-bit ARM (arm64-v8a) and 32-bit ARM (armeabi-v7a) phones get the same Python and packages in one universal APK: Chaquopy publishes 3.13 for 64-bit ABIs only, so `scripts/build-python-armv7.sh` builds CPython 3.13, Chaquopy's runtime and the native packages that don't link NumPy (Chaquopy's own recipes, same versions; `scripts/python-armv7-packages.txt`) for armeabi-v7a into a local Maven repo / wheel folder that `settings.gradle.kts` and the pip block use, with the plugin's one-line ABI allowance patched. The NumPy-2 stack (`scripts/python-sci-packages.txt`: OpenBLAS, numpy, contourpy, matplotlib, pandas, scipy, scikit-learn) is cross-built from source with meson-python for every ABI (`scripts/build-python-sci.sh`), the same versions on each: there is no Fortran compiler for 32-bit ARM Android, so SciPy uses its Fortran-free build and OpenBLAS its C LAPACK. CI keeps both builds as artifacts named by their inputs' hashes (`scripts/restore-artifact.sh`), so branches and main reuse them.

## Figures, tables and charts

The web's own capture module (`app/src/main/python/bj_capture.py`, copied by `scripts/web-fixtures.mjs`) runs on the phone too: matplotlib / seaborn / pandas plots (PNG + SVG + PDF), PIL images, pandas tables (preview + CSV), and Plotly / Altair (Vega-Lite) / Bokeh specs. They show under the code block, upload to the private `run-outputs` Storage bucket with the run, and each opens on its own screen (`ui/OutputViewer.kt`) with Prev / Next. Charts are drawn natively from their spec (`core/chart/`: scatter / line, bar, histogram, heatmap, box, area, pie, 3D scatter) and are interactive: pinch to zoom, drag to pan (3D: drag turns it), tap a point for its values, tap the legend to hide a series, double-tap to reset. Download / Share in every format the output supports: PNG, JPG, WebP, SVG, PDF (vector, via `android.graphics.pdf`) for figures and charts, CSV for tables, the spec as JSON, saved to Downloads/Benchjot.
- **JavaScript / TypeScript**: QuickJS, a fresh runtime per run, with the web's own `transpile()` (sucrase) bundled from the web repo (`scripts/web-engines.mjs` → `engine/src/main/resources/engine/transpile.js`). No network APIs; console, timers and top-level `await` as in the web's worker. Stop interrupts the engine.
- **Ruff** (Autofix): Ruff built from source for Android (bionic) at the same version as the web's `ruff-wasm-web` (`scripts/ruff.lock`, `scripts/build-ruff-android.sh`, cached in CI), bundled as `lib/<abi>/libruff.so` and run as a process. The official static Linux binary is killed by Android's seccomp filter (SIGSYS) on x86_64, so it is only used for the host tests (`scripts/fetch-ruff.sh host`). `RuffTest` (JVM) and the instrumented `ruff_runsOnAndroid` check it fixes the shared samples exactly like the web's WASM.
- Runs save `snippets.last_run` with the web's code hash, so the web shows them as current.

Pro "Run on server" and Fix call the web's `/api/run` and `/api/fix` with the user's Supabase access token.

## Also native

- **Map** (`ui/NoteMap.kt`): Compose Canvas; the layout is a line-for-line port of the web's d3-force setup (`core/Graph.kt`), checked against d3's own positions.
- **Math** in the preview: remark-math's syntax (`$…$`, `$$` blocks), found by `core/MathText.kt` (checked against remark-math), drawn with JLaTeXMath.
- **Continue with Google**: Credential Manager → Google ID token for the web OAuth client → Supabase `signInWith(IDToken)`. The Android OAuth client in GCP project "benchjot" matches package `app.benchjot` + the SHA-1 of `app/debug-ci.keystore`; a new keystore needs a new client.

## CI

`.github/workflows/android.yml`: no-WebView check (sources and the APK's dex), web parity check, unit tests (core, engine incl. the real Ruff binary), APK build (universal arm64-v8a + armeabi-v7a), `scripts/check-apk-abis.py` (every engine present per ABI, checked by ELF header), instrumented engine tests and a smoke test on an x86_64 emulator, the same instrumented tests as armeabi-v7a under an Android 11 emulator's ARM translation, then a GitHub Release with `benchjot.apk` on every push to `main` that passed them. The APK is signed with `app/debug-ci.keystore` (a sideload key committed on purpose so updates install over each other; not for the Play Store).
