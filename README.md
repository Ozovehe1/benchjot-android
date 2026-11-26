# Benchjot for Android

The Android app for [Benchjot](https://benchjot.vercel.app), a notebook for coding experiments. It's a native app (Kotlin and Jetpack Compose, no WebView) and uses the same account and data as the [web app](https://github.com/Ozovehe1/benchjot).

## Install

Download [benchjot.apk](https://github.com/Ozovehe1/benchjot-android/releases/latest/download/benchjot.apk) on your phone (about 145 MB) and open it. Android will ask you to allow installs from your browser or Files app, because the app isn't on the Play Store. Each new version installs over the last one.

That file is for 64-bit phones, which is nearly every phone from the last several years. For an older 32-bit phone, use [benchjot-32bit.apk](https://github.com/Ozovehe1/benchjot-android/releases/latest/download/benchjot-32bit.apk). Android 8.0 or newer.

## What it does

A note holds what you planned, your code, what it printed and drew, and whether it worked. Notes can sit in projects, nest under other notes and link to each other with `[[links]]`, and each note's Assistant can draw on the others.

- Python runs on the phone, offline: Python 3.13 with numpy, pandas, matplotlib, seaborn, scipy, scikit-learn, Pillow, Plotly, Altair and Bokeh. Code written for a GPU (PyTorch `.cuda()`, CuPy) is moved to the CPU.
- Anything heavier runs on the server: Python with PyTorch and TensorFlow, R, Julia, Octave, C, C++ and Fortran, with a GPU when the code needs one (10 GPU runs a day).
- Figures, tables and charts each open on their own screen. Charts are interactive, and everything can be downloaded as PNG, JPG, WebP, SVG, PDF, CSV or JSON.
- Experiment tracking: a cell that ends with `log(accuracy=acc, lr=lr)` adds its run to the note's Runs table and chart.
- The Data library (same as the web's): search Hugging Face, Zenodo, OpenML, Harvard Dataverse and figshare, or add a file by its address. A cell opens a library file by name, on the phone and on the server, and `save_data("file.h5")` keeps a file a cell downloaded or made.
- Figures gathers every figure from your notes, and a note's Contents lists its headings and cells to jump to.
- Fix suggests a corrected program after a failed run, as a diff you accept or reject.
- The Assistant answers questions about a note and can do the work in it: add, change and run cells, find and fetch real datasets, and write in the note. Type or speak. It shows each step, and Undo puts everything back.
- It reads the note's context graph (a card for the note and each cell), sees what figures and animations show, and reaches your other notes: it finds them by words or by meaning, reads them, asks their own agents, and proposes changes you apply with one tap.
- Share a note with people by username, or with a link anyone can open. When someone shares a note with you, the app notifies you (it checks about every 15 minutes, even when closed; Android picks the exact times).
- Deleted notes wait in Recently deleted for 30 days. Notes can be pinned, and the list is grouped by date.
- The Research Map shows how notes, sub-notes, links and attempts connect.

Fix and the Assistant run on Claude (Sonnet by default, or Haiku, chosen under the chat box or in Settings), included, with nothing to connect: 50 AI requests a day and 1,000 credits (US$10 of Claude usage) a month per person.

## How it's built

- Python is CPython 3.13 through Chaquopy, run in its own process so Stop always works. Chaquopy only ships 3.13 for 64-bit phones, so `scripts/build-python-armv7.sh` builds it for 32-bit ones. NumPy, SciPy, pandas, matplotlib and scikit-learn are cross-compiled from source for both (`scripts/build-python-sci.sh`).
- Ruff (Autofix) is built from source for Android and run as a native binary.
- Charts and the Research Map are drawn natively with Compose Canvas, and math with JLaTeXMath.
- Data goes to Supabase directly. Server runs, Fix, the Assistant and the seer go through the web app's API; search by meaning calls the `note-search` Edge Function.
- The rules both apps share (links, search, dates, the map, error messages and more) are copied from the web repo as test fixtures. CI regenerates them from the web's main branch and fails if the two apps disagree.

## Build from source

You need JDK 17, the Android SDK (compileSdk 36) and NDK, Python 3.13, Node.js 22, rustup, cmake 3.22+ and git. The CI workflow (`.github/workflows/android.yml`) is the reference build.

```bash
scripts/build-python-armv7.sh            # Python 3.13 for 32-bit ARM
scripts/build-python-sci.sh arm64-v8a    # the NumPy stack, one chip type per call
scripts/build-python-sci.sh armeabi-v7a
mkdir -p build/python-sci/wheels && cp build/python-sci/arm64-v8a/*.whl build/python-sci/armeabi-v7a/*.whl build/python-sci/wheels/
scripts/build-ruff-android.sh            # Ruff
./gradlew -Pabi=arm64-v8a :app:assembleRelease   # benchjot.apk (-Pabi=armeabi-v7a for the 32-bit one)
```

The pure Kotlin modules build and test without the Android SDK: `./gradlew :core:test -PcoreOnly`.

The code is in three modules: `core/` (the rules shared with the web, plain Kotlin), `engine/` (Ruff, tested on the build machine) and `app/` (the Compose app). The Supabase URL and anon key in `app/benchjot.properties` are public client values; data is protected by Row Level Security.

On every push to main, CI runs the tests, builds both APKs, checks them on emulators and publishes a release. APKs are signed with a key committed to the repo on purpose, so updates install over each other.

## Credits

Built with AI coding assistance from Claude Code, Anthropic's coding agent. The author designed the product, directed the work, reviewed it, and tested it.

In the app: Claude (Anthropic), through the web's API, for Fix and the Assistant, and the phone's own speech recognition for dictation.

Fonts: Newsreader, Inter and JetBrains Mono, under the SIL Open Font License. Libraries are listed in `gradle/libs.versions.toml`: Jetpack Compose, Kotlin coroutines and serialization, supabase-kt, Ktor, Chaquopy, AndroidX Credentials, JLaTeXMath, AndroidSVG and multiplatform-markdown-renderer. Built into the APK from source: CPython, Ruff, OpenBLAS, numpy, pandas, matplotlib, scipy, scikit-learn and Pillow; Plotly, Bokeh, seaborn and Altair come from PyPI. The map layout is a port of d3-force. Each is under its own license.

## Limits

- Not on the Play Store; you install the APK yourself.
- The download is large because Python and its science packages are built in, so everything works offline.
- AI is limited per account to 50 requests a day (each Assistant step, Fix or look at a figure is one) and 1,000 credits a month (a credit is one US cent of what Claude charged).

## License

All rights reserved. See [LICENSE](LICENSE). The code is published for viewing only; using or copying it needs written permission.
