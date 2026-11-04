# Benchjot for Android

The native Android app for [Benchjot](https://benchjot.vercel.app) — a research jotter for your code. Kotlin + Jetpack Compose (Material 3), same Supabase backend as the web app.

**Install:** download `benchjot.apk` from the [latest release](https://github.com/Ozovehe1/benchjot-android/releases/latest) on your phone and open it.

## Layout

- `core/` — pure Kotlin, no Android: the rules shared with the web (links, tree, search, usernames, languages, runs, Fix diff, highlighting, autosave). Tested on any JVM: `./gradlew :core:test -PcoreOnly`.
- `core/src/test/resources/web/` — fixtures generated from the web repo by `scripts/web-fixtures.mjs`. CI regenerates them from the web's `main` and fails if they differ, so the two apps can't silently drift.
- `app/` — the Compose app. `data/` talks to Supabase (supabase-kt) and the web's API routes (Ktor), `run/` runs code on the device, `ui/` is the screens.
- `app/benchjot.properties` — the public client config (Supabase URL + anon key, web URL).

## Running code

Python and JavaScript/TypeScript run on the phone in a headless WebView that hosts the web app's own workers (`/workers/python.js` = Pyodide, `/workers/js.js`), so the runtime, packages and error formats are the web's. Needs a connection the first time (Pyodide is cached after). Pro "Run on server" and Fix call the web's `/api/run` and `/api/fix` with the user's Supabase access token.

## CI

`.github/workflows/android.yml`: web parity check, unit tests, APK build, and a GitHub Release with `benchjot.apk` on every push to `main`. The APK is signed with `app/debug-ci.keystore` (a sideload key committed on purpose so updates install over each other; not for the Play Store).
