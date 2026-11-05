"""Runs a code block on the phone, as the web's Pyodide worker (public/workers/python.js):
a fresh __main__ namespace per run, stdout/stderr line by line, no stdin, top-level
await, and the code compiled as "<exec>" so tracebacks point at the user's lines.
The code runs through bj_capture.py (the web's own file, copied by
scripts/web-fixtures.mjs), so figures, tables and charts land in `out_dir` with a
manifest.json, exactly as in the browser; the app uploads them after the run.

`emit` is a Kotlin object (PythonService.Emitter) with out(stream, text), start(),
done() and error(text).
"""
import builtins
import faulthandler
import os
import sys
import traceback

# OpenMP (LLVM libomp, used by scikit-learn): on Linux it pins every thread it starts, the
# calling one included, to the CPU set it saw at start-up, and aborts the process if that call
# fails (__kmp_affinity_bind_init_mask -> sched_setaffinity, abort_on_error; android/ndk#1180).
# Android moves an app between cpusets (foreground, background) while it runs, so the saved set
# can become invalid (EINVAL) and the first parallel region kills Python: seen as SIGABRT in
# KMeans on armeabi-v7a. Pinning buys nothing on a phone; switch it off before libomp reads its
# settings (at its first use, after this). A user's own KMP_AFFINITY still wins.
os.environ.setdefault("KMP_AFFINITY", "disabled")

import bj_capture  # noqa: E402 (after the OpenMP setting above)


class _Stream:
    """Line-buffered like Pyodide's batched stdout: each line goes out with its newline."""

    encoding = "utf-8"
    errors = "strict"

    def __init__(self, emit, name):
        self._emit, self.name, self._buf = emit, name, ""

    def write(self, s):
        if not isinstance(s, str):
            raise TypeError(f"write() argument must be str, not {type(s).__name__}")
        self._buf += s
        if "\n" in self._buf:
            *lines, self._buf = self._buf.split("\n")
            self._emit.out(self.name, "\n".join(lines) + "\n")
        return len(s)

    def flush(self):
        if self._buf:
            self._emit.out(self.name, self._buf + "\n")
            self._buf = ""

    def isatty(self):
        return False

    def writable(self):
        return True


class _NoStdin:
    """Pyodide runs with setStdin({ error: true }): reading input fails."""

    def _fail(self, *args):
        raise OSError(29, "I/O error")

    read = readline = readlines = _fail

    def isatty(self):
        return False


def capture_native_stderr(path):
    """Native code reports a crash on fd 2 (CPython's fatal errors, OpenMP, C asserts), which Android
    discards. Send fd 2 to `path`, a file that outlives this process, and dump the Python traceback of
    every thread there on a fatal signal (SIGABRT, SIGSEGV...); the app shows the file's tail when the
    Python process dies. Truncated per run, so it holds only this run's messages."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.dup2(fd, 2)
    finally:
        os.close(fd)
    faulthandler.enable(file=2, all_threads=True)


def run(code, emit, out_dir, work_dir, native_stderr=None):
    if native_stderr:
        capture_native_stderr(native_stderr)
    out, err = _Stream(emit, "stdout"), _Stream(emit, "stderr")
    saved = sys.stdout, sys.stderr, sys.stdin
    sys.stdout, sys.stderr, sys.stdin = out, err, _NoStdin()
    try:
        # Files the code writes land here (kept between runs, like Pyodide's home folder).
        os.makedirs(work_dir, exist_ok=True)
        os.chdir(work_dir)
        os.environ.setdefault("MPLCONFIGDIR", os.path.join(work_dir, ".matplotlib"))
        emit.start()
        globals_ = {"__name__": "__main__", "__builtins__": builtins}
        bj_capture.run_sync(code, out_dir, "<exec>", globals_)
        out.flush()
        err.flush()
        emit.done()
    except BaseException as e:  # SystemExit too: the run ends with its traceback, as in Pyodide
        out.flush()
        err.flush()
        emit.error("".join(traceback.format_exception(type(e), e, e.__traceback__)))
    finally:
        sys.stdout, sys.stderr, sys.stdin = saved
