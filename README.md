# Benchjot for Android

**A research jotter for your code**, as a native Android app (Kotlin + Jetpack Compose, Material 3; no WebView). It uses the same Supabase backend and account as the web app, https://benchjot.vercel.app ([Ozovehe1/benchjot](https://github.com/Ozovehe1/benchjot)).

## Install

Download **[benchjot.apk](https://github.com/Ozovehe1/benchjot-android/releases/latest/download/benchjot.apk)** (latest release, about 190 MB) on the phone and open it. Android will ask you to allow installs from your browser or file manager: the APK is sideloaded, not on the Play Store. One universal APK covers 64-bit (arm64-v8a) and 32-bit (armeabi-v7a) ARM phones, Android 8.0 (API 26) and newer.

## The problem

Students and researchers run many small coding experiments, and the code, the reasoning and the outcome end up in different places. Benchjot keeps them in one note: what you planned, the code, what it printed and drew, and whether it worked, with links between attempts. The Android app does this on the phone itself, with Python and the scientific stack running on the device.

## What it does

- **Notes with code cells**: Markdown body with LaTeX math, any number of code cells (new cells default to Python), projects, sub-notes, `[[links]]`, Linked from, search, and an outcome (Successful / Failed / unmarked) that only you set.
- **Run on the phone**: Python 3.13 with numpy, pandas, matplotlib, seaborn, scipy, scikit-learn, Pillow, Plotly, Altair and Bokeh; JavaScript / TypeScript; Ruff Autofix. Details below.
- **Cell notes**: "Note before run" and "Note after run" per cell, written only by you.
- **Saved runs, figures, tables and charts**, each opening on its own screen, interactive and downloadable.
- **Research Map** of notes, sub-notes, links and attempts; **New attempt** from a note.
- **Fix**: after a failed run, a corrected program shown as a diff to accept or reject. It runs on the phone when the on-device model is ready and the code fits (6,000 characters); otherwise, or if the phone attempt fails, the web server's Fix (Groq) answers. The diff says "Fixed on your phone" when it came from the phone.
- **Helper** (toolbar → bottom sheet): *Write code* or *Ask this note*, with chips "Run this experiment", "Plot the results", "Explain this code". A picker chooses **On phone** (the on-device model, default) or **Online helper** (the web's `/api/helper`, Groq). Only you switch it; the choice is remembered on the device. The note is read only when you send. Code is added only when you tap **Insert into note** (as a new cell); the helper never runs code, writes note text or sets the outcome.
- **Continue with Google** or email / username and password.

## On-device model

- **Model**: [Qwen2.5-Coder-0.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF), Q4_0 GGUF, 428,730,240 bytes (`core/src/main/kotlin/app/benchjot/core/OnDeviceModel.kt`). It is not in the APK: it is downloaded once from Hugging Face with Android's DownloadManager into the app's files folder, and accepted only if its SHA-256 matches the pinned hash. On the first launch it starts by itself on Wi-Fi; on mobile data the app asks first (Download now / Wait for Wi-Fi). It is never downloaded on an emulator. The Online helper works without it.
- **Engine**: [llama.cpp](https://github.com/ggml-org/llama.cpp) at the commit in `scripts/llama.lock`, built by `scripts/build-llama-android.sh` as `libbj_llm.so` per ABI with a small JNI bridge (`app/src/main/cpp/bj_llm.cpp`). It runs in its own `:llm` process (`llm/LlmService.kt`), with 4,096 tokens of context and threads = cores − 1 (at most 4), so the UI keeps a core. Each task has its own input, answer and time caps (`core/Assist.kt`).
- On-device errors (model missing, still downloading, damaged, storage full, low memory, timeout, engine crash, too long, no code, unsupported phone) each show plain text and one action (`core/Assist.kt`).

## Running code (native, no WebView)

- **Python**: embedded CPython 3.13 (Chaquopy) in its own process (`run/PythonService.kt`, `src/main/python/bj_runner.py`). Same behaviour as the web's Pyodide runner: fresh `__main__` per run, stdout/stderr line by line, no stdin, top-level `await`, tracebacks trimmed to the user's `<exec>` lines, 30 s limit from the start. Stop and the time limit kill the process, so even a busy loop stops. Bundled packages: **numpy, pandas, matplotlib, seaborn, scipy, scikit-learn, Pillow, Plotly, Altair, Bokeh**. 64-bit ARM (arm64-v8a) and 32-bit ARM (armeabi-v7a) phones get the same Python and packages in one universal APK: Chaquopy publishes 3.13 for 64-bit ABIs only, so `scripts/build-python-armv7.sh` builds CPython 3.13, Chaquopy's runtime and the native packages that don't link NumPy (Chaquopy's own recipes, same versions; `scripts/python-armv7-packages.txt`) for armeabi-v7a into a local Maven repo / wheel folder that `settings.gradle.kts` and the pip block use, with the plugin's one-line ABI allowance patched. The NumPy-2 stack (`scripts/python-sci-packages.txt`: OpenBLAS, numpy, contourpy, matplotlib, pandas, scipy, scikit-learn) is cross-built from source with meson-python for every ABI (`scripts/build-python-sci.sh`), the same versions on each: there is no Fortran compiler for 32-bit ARM Android, so SciPy uses its Fortran-free build and OpenBLAS its C LAPACK. CI keeps both builds as artifacts named by their inputs' hashes (`scripts/restore-artifact.sh`), so branches and main reuse them.

## Figures, tables and charts

The web's own capture module (`app/src/main/python/bj_capture.py`, copied by `scripts/web-fixtures.mjs`) runs on the phone too: matplotlib / seaborn / pandas plots (PNG at run time; SVG and PDF made on Download from the kept figure by the phone's Python, `run/PythonRunner.renderFigure`), PIL images, pandas tables (preview + CSV), and Plotly / Altair (Vega-Lite) / Bokeh specs. They show under the code block, upload to the private `run-outputs` Storage bucket with the run, and each opens on its own screen (`ui/OutputViewer.kt`) with Prev / Next. Charts are drawn natively from their spec (`core/chart/`: scatter / line, bar, histogram, heatmap, box, area, pie, 3D scatter) and are interactive: pinch to zoom, drag to pan (3D: drag turns it), tap a point for its values, tap the legend to hide a series, double-tap to reset. Download / Share in every format the output supports: PNG, JPG, WebP, SVG, PDF (vector, via `android.graphics.pdf`) for figures and charts, CSV for tables, the spec as JSON, saved to Downloads/Benchjot.
- **JavaScript / TypeScript**: QuickJS, a fresh runtime per run, with the web's own `transpile()` (sucrase) bundled from the web repo (`scripts/web-engines.mjs` → `engine/src/main/resources/engine/transpile.js`). No network APIs; console, timers and top-level `await` as in the web's worker. Stop interrupts the engine.
- **Ruff** (Autofix): Ruff built from source for Android (bionic) at the same version as the web's `ruff-wasm-web` (`scripts/ruff.lock`, `scripts/build-ruff-android.sh`, cached in CI), bundled as `lib/<abi>/libruff.so` and run as a process. The official static Linux binary is killed by Android's seccomp filter (SIGSYS) on x86_64, so it is only used for the host tests (`scripts/fetch-ruff.sh host`). `RuffTest` (JVM) and the instrumented `ruff_runsOnAndroid` check it fixes the shared samples exactly like the web's WASM.
- Runs save `snippets.last_run` with the web's code hash, so the web shows them as current.

Pro "Run on server", server Fix and the Online helper call the web's `/api/run`, `/api/fix` and `/api/helper` with the user's Supabase access token.

## Also native

- **Research Map** (`ui/NoteMap.kt`): Compose Canvas. Two lenses: Around a note (depth 1–3, the default from a note's Map button) and Everything. Marks by outcome (hollow circle unmarked, filled circle successful, diamond failed), size by connections, directed links / sub-notes / attempts with arrows; tap a note for a preview sheet; Fit; every note reachable by TalkBack and a "Map as list" view. The model (`core/MapModel.kt`) is checked against the web's shared fixture (`map_model.json`), the layout (`core/Graph.kt`, a line-for-line d3-force port) against d3's own positions; layout runs off the main thread. "New attempt" on a note calls the web's `new_attempt()`; the note index reads `derived_from` when the migration is applied and works without it.
- **Math** in the preview: remark-math's syntax (`$…$`, `$$` blocks), found by `core/MathText.kt` (checked against remark-math), drawn with JLaTeXMath.
- **Continue with Google**: Credential Manager → Google ID token for the web OAuth client → Supabase `signInWith(IDToken)`. The Android OAuth client in GCP project "benchjot" matches package `app.benchjot` + the SHA-1 of `app/debug-ci.keystore`; a new keystore needs a new client.

## Architecture

- **Backend**: Supabase (Auth, Postgres with owner-only Row Level Security, Storage bucket `run-outputs`) through supabase-kt; the web app on Vercel for server routes (`/api/run`, `/api/fix`, `/api/helper`) through Ktor. Server code runs (Pro) go to Modal Sandboxes via the web; the phone never talks to Groq or Modal directly.
- **On device**: Chaquopy (CPython 3.13) for Python, QuickJS for JS/TS, Ruff as a native binary, llama.cpp for the model, Compose Canvas for charts and the Research Map, JLaTeXMath for math.
- **Parity with the web**: the web repo's rules and engines are copied by `scripts/web-fixtures.mjs` and `scripts/web-engines.mjs`; CI regenerates them from the web's `main` and fails on any difference.

## Build from source

Needs JDK 17, the Android SDK (compileSdk 36) and NDK, Python 3.13, Node.js 22, rustup, cmake ≥ 3.22, ninja and git. CI (`.github/workflows/android.yml`) is the reference build; locally:

```bash
scripts/build-python-armv7.sh                 # CPython 3.13 + Chaquopy runtime for armeabi-v7a -> build/python-armv7/
scripts/build-python-sci.sh arm64-v8a         # NumPy-2 stack wheels, one ABI per call -> build/python-sci/<abi>/
scripts/build-python-sci.sh armeabi-v7a
mkdir -p build/python-sci/wheels && cp build/python-sci/arm64-v8a/*.whl build/python-sci/armeabi-v7a/*.whl build/python-sci/wheels/
scripts/build-ruff-android.sh                 # Ruff -> app/src/main/jniLibs/<abi>/libruff.so
scripts/build-llama-android.sh                # llama.cpp -> app/src/main/jniLibs/<abi>/libbj_llm.so
./gradlew :app:assembleRelease                # universal APK: app/build/outputs/apk/release/app-release.apk
```

The pure modules build and test without the Android SDK: `./gradlew :core:test -PcoreOnly`. The Supabase URL, anon key and web URL the app uses are public client values in `app/benchjot.properties` (the same ones the web app sends to every browser; data is protected by Row Level Security).

## Repository layout

- `core/` — pure Kotlin, no Android: the rules shared with the web (links, tree, search, usernames, languages, runs, Fix diff, highlighting, autosave). Tested on any JVM: `./gradlew :core:test -PcoreOnly`.
- `core/src/test/resources/web/` — fixtures generated from the web repo by `scripts/web-fixtures.mjs`. CI regenerates them from the web's `main` and fails if they differ, so the two apps can't silently drift.
- `engine/` — plain-JVM on-device engines, tested on the build machine: JavaScript/TypeScript in QuickJS (quickjs-kt) and Ruff as a process.
- `app/` — the Compose app. `data/` talks to Supabase (supabase-kt) and the web's API routes (Ktor), `run/` runs code on the device, `ui/` is the screens.
- `app/benchjot.properties` — the public client config (Supabase URL + anon key, web URL).

## CI

`.github/workflows/android.yml`: no-WebView check (sources and the APK's dex), web parity check, unit tests (core, engine incl. the real Ruff binary), APK build (universal arm64-v8a + armeabi-v7a), `scripts/check-apk-abis.py` (every engine present per ABI, checked by ELF header), instrumented engine tests and a smoke test on an x86_64 emulator, the same instrumented tests as armeabi-v7a under an Android 11 emulator's ARM translation, then a GitHub Release with `benchjot.apk` on every push to `main` that passed them. The APK is signed with `app/debug-ci.keystore` (a sideload key committed on purpose so updates install over each other; not for the Play Store).

## AI tools and services used

**In the product**

- **Qwen2.5-Coder-0.5B-Instruct** (Q4_0 GGUF) run on the phone with **llama.cpp**: on-device Fix and the "On phone" helper.
- **Groq, `openai/gpt-oss-120b`**, called by the Benchjot web server (never from the phone): server Fix and the Online helper.
- The AI only proposes code; you accept a diff or tap Insert.

**In development**

Benchjot was built with AI coding assistance from **Hark**, an AI agent, which wrote much of the code under the author's direction. The author (Abdulrahman Musa) designed the product, directed the work, reviewed it, and tested it.

## Third-party credits

Libraries in `gradle/libs.versions.toml`: Jetpack Compose (BOM 2024.12.01, Material 3, Navigation, Activity, Lifecycle), Kotlin and kotlinx.coroutines / kotlinx.serialization, supabase-kt (auth, postgrest, storage), Ktor (OkHttp engine), quickjs-kt, Chaquopy, AndroidX Credentials and Google ID (Google sign-in), JLaTeXMath (`ru.noties:jlatexmath-android`), AndroidSVG, multiplatform-markdown-renderer (m3). Tests: JUnit 4, AndroidX Test, Compose UI Test.

Built from source into the APK: CPython 3.13, Ruff 0.17.3, llama.cpp, OpenBLAS, and the Python packages numpy, pandas, matplotlib, contourpy, scipy, scikit-learn, Pillow, kiwisolver, markupsafe, pyyaml, tornado (versions in `scripts/python-armv7-packages.txt` and `scripts/python-sci-packages.txt`); plus pure-Python Plotly, Bokeh, seaborn, Altair and jsonschema from PyPI. From the web repo: sucrase (TypeScript transpile) and the d3-force algorithm (ported to Kotlin in `core/Graph.kt`).

Model: Qwen2.5-Coder-0.5B-Instruct by the Qwen team (Alibaba Cloud), Apache-2.0 license, downloaded from Hugging Face at first use. Services: Supabase, Vercel, Groq, Modal, Hugging Face, GitHub Actions and Releases. Each project is under its own license.

## Status and limits

- **Not yet tested on a real phone.** Everything is verified only on CI emulators (x86_64, and armeabi-v7a under an Android 11 emulator's ARM translation); arm64 builds are checked by ELF header but not run. Google sign-in reaches Credential Manager on the emulator but the full sign-in is untested. On-device model speed on a real phone is unmeasured.
- Sideloaded APK signed with a committed debug key (`app/debug-ci.keystore`) so updates install over each other; no Play Store, no Play signing.
- The APK is large (about 190 MB) because it bundles Python and the scientific stack for two ABIs; the model adds 429 MB on first use.
- Server Fix and the Online helper use Groq's free tier: at most 10 requests per user per rolling 24 hours (6 per minute), shared with the web, and they stop when Groq's shared quota runs out.
- Helper history (saved per note) exists on the web; the Android app on `main` does not read or save it yet.
- No license file yet: all rights reserved by the author until one is added.

