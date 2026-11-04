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

- **Python**: embedded CPython 3.13 (Chaquopy) in its own process (`run/PythonService.kt`, `src/main/python/bj_runner.py`). Same behaviour as the web's Pyodide runner: fresh `__main__` per run, stdout/stderr line by line, no stdin, top-level `await`, tracebacks trimmed to the user's `<exec>` lines, 30 s limit from the start. Stop and the time limit kill the process, so even a busy loop stops. Bundled packages: **numpy**. Pyodide loads other packages on demand (pandas, scipy, matplotlib, scikit-learn, sympy…); a phone has no pip at run time, so those are not available on the device (use Pro "Run on server").
- **JavaScript / TypeScript**: QuickJS, a fresh runtime per run, with the web's own `transpile()` (sucrase) bundled from the web repo (`scripts/web-engines.mjs` → `engine/src/main/resources/engine/transpile.js`). No network APIs; console, timers and top-level `await` as in the web's worker. Stop interrupts the engine.
- **Ruff** (Autofix): the official static Ruff binary, the same version as the web's `ruff-wasm-web` (`scripts/ruff.lock`, fetched by `scripts/fetch-ruff.sh`), bundled as `lib/<abi>/libruff.so` and run as a process. `RuffTest` checks it fixes sample code exactly like the web's WASM.
- Runs save `snippets.last_run` with the web's code hash, so the web shows them as current.

Pro "Run on server" and Fix call the web's `/api/run` and `/api/fix` with the user's Supabase access token.

## Also native

- **Map** (`ui/NoteMap.kt`): Compose Canvas; the layout is a line-for-line port of the web's d3-force setup (`core/Graph.kt`), checked against d3's own positions.
- **Math** in the preview: remark-math's syntax (`$…$`, `$$` blocks), found by `core/MathText.kt` (checked against remark-math), drawn with JLaTeXMath.
- **Continue with Google**: Credential Manager → Google ID token for the web OAuth client → Supabase `signInWith(IDToken)`. The Android OAuth client in GCP project "benchjot" matches package `app.benchjot` + the SHA-1 of `app/debug-ci.keystore`; a new keystore needs a new client.

## CI

`.github/workflows/android.yml`: no-WebView check (sources and the APK's dex), web parity check, unit tests (core, engine incl. the real Ruff binary), APK build, instrumented engine tests and a smoke test on an emulator, then a GitHub Release with `benchjot.apk` on every push to `main` that passed them. The APK is signed with `app/debug-ci.keystore` (a sideload key committed on purpose so updates install over each other; not for the Play Store).
