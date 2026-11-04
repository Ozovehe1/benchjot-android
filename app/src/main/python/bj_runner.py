"""Runs a code block on the phone, as the web's Pyodide worker (public/workers/python.js):
a fresh __main__ namespace per run, stdout/stderr line by line, no stdin, top-level
await, and the code compiled as "<exec>" so tracebacks point at the user's lines.

`emit` is a Kotlin object (PythonService.Emitter) with out(stream, text), start(),
done() and error(text).
"""
import ast
import asyncio
import builtins
import inspect
import sys
import traceback


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


def run(code, emit):
    out, err = _Stream(emit, "stdout"), _Stream(emit, "stderr")
    saved = sys.stdout, sys.stderr, sys.stdin
    sys.stdout, sys.stderr, sys.stdin = out, err, _NoStdin()
    try:
        emit.start()
        globals_ = {"__name__": "__main__", "__builtins__": builtins}
        compiled = compile(code, "<exec>", "exec", flags=ast.PyCF_ALLOW_TOP_LEVEL_AWAIT, dont_inherit=True)
        result = eval(compiled, globals_)
        if inspect.iscoroutine(result):
            asyncio.run(result)
        out.flush()
        err.flush()
        emit.done()
    except BaseException as e:  # SystemExit too: the run ends with its traceback, as in Pyodide
        out.flush()
        err.flush()
        emit.error("".join(traceback.format_exception(type(e), e, e.__traceback__)))
    finally:
        sys.stdout, sys.stderr, sys.stdin = saved
