"""Rich run outputs for Benchjot: one module shared by every Python runner.

The web's Pyodide worker, the server (Modal) runner and the Android app (Chaquopy)
all run user code through `run()`, so every platform captures the same outputs:

  image  matplotlib figures (and everything drawn on them: seaborn, pandas .plot,
         scikit-learn displays), PIL images, and image files the code writes.
         Figures are saved at run time as PNG + SVG + PDF, so later downloads are
         re-encodings of the source figure, never of a screenshot.
  table  pandas DataFrame / Series (a preview inline, the full table as CSV).
  chart  interactive figures as their own JSON spec: Plotly (fig.to_json()),
         Altair / Vega-Lite (chart.to_dict()) and Bokeh (json_item()).

An output is shown when the code calls show() / display(obj), or when it is the
value of the last line (like a notebook cell). Figures still open at the end are
shown too. Output files go into `out_dir` with a manifest.json describing them in
order (the contract mirrored by src/lib/run/outputs.ts and the Android app).

Pure standard library at import; the plotting libraries are only touched when the
user's code has imported them.
"""

import ast
import json
import os
import sys

MANIFEST = "manifest.json"
MAX_OUTPUTS = 30
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = int(os.environ.get("BJ_MAX_TOTAL_BYTES", 24 * 1024 * 1024))
MAX_INLINE_CHARS = 300_000  # table previews kept inline in the run record (snippets.last_run <= 600 KB)
TABLE_PREVIEW_CHARS = 60_000
TABLE_ROWS = 100
TABLE_COLS = 30
CELL_CHARS = 200
MAX_CSV_BYTES = 5 * 1024 * 1024
FIG_DPI = 150
IMAGE_EXTS = {".png": "png", ".jpg": "jpg", ".jpeg": "jpg", ".gif": "gif", ".webp": "webp", ".svg": "svg", ".bmp": "bmp"}
FILE_SCAN_LIMIT = 2000

_session = None


class _Session:
    def __init__(self, out_dir):
        self.dir = out_dir
        self.items = []
        self.total = 0
        self.emitted = set()  # ids of matplotlib figures already shown
        self.notes = []
        self.inline = 0

    def _name(self, ext):
        return f"{len(self.items)}.{ext}"

    def _write(self, name, data):
        if isinstance(data, str):
            data = data.encode("utf-8")
        if len(data) > MAX_FILE_BYTES or self.total + len(data) > MAX_TOTAL_BYTES:
            return False
        with open(os.path.join(self.dir, name), "wb") as f:
            f.write(data)
        self.total += len(data)
        return True

    def full(self):
        if len(self.items) >= MAX_OUTPUTS:
            self.note(f"Only the first {MAX_OUTPUTS} outputs are kept.")
            return True
        return False

    def note(self, text):
        if text not in self.notes:
            self.notes.append(text)

    def add(self, item, files):
        """files: {format: bytes}. Keeps the item only if its files fit the limits."""
        if self.full():
            return
        names = {}
        for fmt, data in files.items():
            name = self._name(fmt)
            if not self._write(name, data):
                self.note("An output was too large to keep.")
                continue
            names[fmt] = name
        if files and not names:
            return
        item["files"] = names
        self.items.append(item)

    def save(self):
        manifest = {"version": 1, "items": self.items, "notes": self.notes}
        with open(os.path.join(self.dir, MANIFEST), "w", encoding="utf-8") as f:
            json.dump(manifest, f, allow_nan=False, default=str)
        return manifest


# --------------------------------------------------------------------- emitters
def _figure(fig, source="matplotlib"):
    s = _session
    if s is None or id(fig) in s.emitted:
        return
    s.emitted.add(id(fig))
    import io

    files = {}
    for fmt in ("png", "svg", "pdf"):
        buf = io.BytesIO()
        try:
            fig.savefig(buf, format=fmt, dpi=FIG_DPI, bbox_inches="tight")
        except Exception as e:  # a backend without that format: keep the others
            if fmt == "png":
                raise
            s.note(f"Couldn't save a figure as {fmt.upper()}: {e}")
            continue
        files[fmt] = buf.getvalue()
    w, h = _png_size(files["png"])
    title = ""
    try:
        title = fig._suptitle.get_text() if fig._suptitle else ""
        if not title and fig.axes:
            title = fig.axes[0].get_title()
    except Exception:
        pass
    s.add({"kind": "image", "source": source, "width": w, "height": h, "title": title}, files)
    try:
        import matplotlib.pyplot as plt

        plt.close(fig)
    except Exception:
        pass


def _pil(img):
    import io

    buf = io.BytesIO()
    im = img
    if im.mode not in ("RGB", "RGBA", "L", "LA", "P", "1", "I;16"):
        im = im.convert("RGBA")
    im.save(buf, format="PNG")
    _session.add({"kind": "image", "source": "pil", "width": img.width, "height": img.height, "title": ""}, {"png": buf.getvalue()})


def _cell(v):
    if v is None:
        return None
    try:
        import math

        if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
            return None if math.isnan(v) else str(v)
    except Exception:
        pass
    if isinstance(v, bool):
        return v
    if isinstance(v, int) and abs(v) < 2**53:
        return v
    if isinstance(v, float):
        return v
    try:  # numpy scalars
        import numbers

        if isinstance(v, numbers.Integral) and abs(int(v)) < 2**53:
            return int(v)
        if isinstance(v, numbers.Real):
            return _cell(float(v))
    except Exception:
        pass
    text = str(v)
    return text if len(text) <= CELL_CHARS else text[: CELL_CHARS - 1] + "…"


def _table(df):
    import pandas as pd

    if isinstance(df, pd.Series):
        df = df.to_frame()
    show = df.iloc[:TABLE_ROWS, :TABLE_COLS]
    index_names = [n if n is not None else "" for n in show.index.names]
    columns = [" / ".join(map(str, c)) if isinstance(c, tuple) else str(c) for c in show.columns]
    rows = []
    for idx, row in zip(show.index, show.itertuples(index=False, name=None)):
        idx = idx if isinstance(idx, tuple) else (idx,)
        rows.append([_cell(v) for v in idx] + [_cell(v) for v in row])
    budget = min(TABLE_PREVIEW_CHARS, MAX_INLINE_CHARS - _session.inline)
    while rows and len(json.dumps(rows, default=str)) > budget:
        rows = rows[: len(rows) // 2]
    _session.inline += len(json.dumps(rows, default=str))
    csv = df.to_csv()
    files = {}
    if len(csv.encode("utf-8")) <= MAX_CSV_BYTES:
        files["csv"] = csv
    else:
        _session.note("A table was too large to keep as CSV; its preview is kept.")
    item = {
        "kind": "table",
        "index_columns": len(index_names),
        "columns": index_names + columns,
        "rows": rows,
        "total_rows": int(df.shape[0]),
        "total_columns": int(df.shape[1]),
    }
    if files:
        _session.add(item, files)
    else:
        item["files"] = {}
        if not _session.full():
            _session.items.append(item)


def _chart(lib, spec_json, version, title=""):
    _session.add({"kind": "chart", "lib": lib, "version": version, "title": title}, {"json": spec_json})


def _plotly(fig):
    import plotly

    spec = fig.to_json()
    title = ""
    try:
        title = fig.layout.title.text or ""
    except Exception:
        pass
    _chart("plotly", spec, plotly.__version__, title)


def _altair(chart):
    import altair as alt

    spec = chart.to_dict()
    title = spec.get("title", "")
    if isinstance(title, dict):
        title = title.get("text", "")
    _chart("vega-lite", json.dumps(spec, allow_nan=False, default=str), alt.__version__, title if isinstance(title, str) else "")


def _bokeh(obj):
    import bokeh
    from bokeh.embed import json_item

    title = ""
    try:
        title = obj.title.text or ""
    except Exception:
        pass
    _chart("bokeh", json.dumps(json_item(obj)), bokeh.__version__, title)


def _module(obj):
    return type(obj).__module__ or ""


def display(*objs):
    """Shows each object as a rich output when it is one; prints it otherwise."""
    for obj in objs:
        if not _emit(obj):
            print(repr(obj))


def _emit(obj):
    """Emits obj if it is a rich output; returns whether it was."""
    if _session is None or obj is None:
        return False
    mod = _module(obj)
    root = mod.split(".")[0]
    try:
        if root in ("matplotlib", "seaborn", "sklearn") or hasattr(obj, "figure_") or hasattr(obj, "get_figure"):
            fig = _as_figure(obj)
            if fig is not None:
                _figure(fig)
                return True
        if root == "pandas":
            import pandas as pd

            if isinstance(obj, (pd.DataFrame, pd.Series)):
                _table(obj)
                return True
            if hasattr(obj, "data") and isinstance(getattr(obj, "data"), pd.DataFrame):  # Styler
                _table(obj.data)
                return True
        if root == "plotly" and hasattr(obj, "to_plotly_json"):
            _plotly(obj)
            return True
        if root == "altair" and hasattr(obj, "to_dict"):
            _altair(obj)
            return True
        if root == "bokeh":
            from bokeh.model import Model

            if isinstance(obj, Model):
                _bokeh(obj)
                return True
        if root == "PIL" and hasattr(obj, "save") and hasattr(obj, "mode"):
            _pil(obj)
            return True
        if isinstance(obj, (list, tuple)) and obj and all(_module(o).startswith("matplotlib") for o in obj):
            return True  # e.g. plt.plot(...) returns [Line2D]: its figure is shown at the end
    except Exception as e:  # never let display break the user's run
        _session.note(f"Couldn't show a {type(obj).__name__}: {e}")
        return True
    return False


def _as_figure(obj):
    """The matplotlib Figure behind obj (Figure, Axes, artist, array of Axes, sklearn display)."""
    if "matplotlib" not in sys.modules:
        return None
    from matplotlib.figure import Figure, SubFigure

    if isinstance(obj, SubFigure):
        return obj.figure
    if isinstance(obj, Figure):
        return obj
    for attr in ("figure_", "figure", "fig"):  # sklearn *Display, seaborn grids
        fig = getattr(obj, attr, None)
        if isinstance(fig, SubFigure):
            fig = fig.figure
        if isinstance(fig, Figure):
            return fig
    if hasattr(obj, "get_figure"):
        try:
            fig = obj.get_figure()
            while isinstance(fig, SubFigure):
                fig = fig.figure
            if isinstance(fig, Figure):
                return fig
        except Exception:
            return None
    try:  # numpy array of Axes (plt.subplots)
        import numpy as np

        if isinstance(obj, np.ndarray) and obj.size and obj.dtype == object:
            return _as_figure(obj.flat[0])
    except Exception:
        pass
    return None


def _png_size(data):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
    return 0, 0


# ------------------------------------------------------------------ show() hooks
def _mpl_show(*args, **kwargs):
    import matplotlib.pyplot as plt

    for n in list(plt.get_fignums()):
        _figure(plt.figure(n))


def _patch(module_name):
    """Points the library's show() at the capture; idempotent; run on import and when already imported."""
    m = sys.modules.get(module_name)
    if m is None or getattr(m, "_bj_patched", False):
        return
    m._bj_patched = True
    if module_name == "matplotlib.pyplot":
        m.show = _mpl_show
    elif module_name == "plotly.basedatatypes":
        m.BaseFigure.show = lambda self, *a, **k: _emit(self)
    elif module_name == "plotly.io":
        m.show = lambda fig, *a, **k: _emit(fig)
    elif module_name == "bokeh.io.showing":
        m.show = lambda obj, *a, **k: _emit(obj)
        for other in ("bokeh.io", "bokeh.plotting"):
            if other in sys.modules:
                sys.modules[other].show = m.show
    elif module_name in ("bokeh.io", "bokeh.plotting"):
        if "bokeh.io.showing" in sys.modules:
            _patch("bokeh.io.showing")
            m.show = sys.modules["bokeh.io.showing"].show
    elif module_name == "altair":
        for cls in getattr(m, "Chart", type).__mro__:
            if "show" in vars(cls):
                cls.show = lambda self, *a, **k: _emit(self)
                break
    elif module_name == "PIL.Image":
        m.Image.show = lambda self, *a, **k: _emit(self)


_HOOKED = ("matplotlib.pyplot", "plotly.basedatatypes", "plotly.io", "bokeh.io.showing", "bokeh.io", "bokeh.plotting", "altair", "PIL.Image")


class _Finder:
    """Patches show() right after one of the libraries above is imported."""

    def find_spec(self, name, path=None, target=None):
        if name not in _HOOKED:
            return None
        import importlib.util

        sys.meta_path.remove(self)
        try:
            spec = importlib.util.find_spec(name)
        finally:
            sys.meta_path.insert(0, self)
        if spec is None or spec.loader is None or not hasattr(spec.loader, "exec_module"):
            return spec
        loader = spec.loader
        orig = loader.exec_module

        class _Loader:
            def __getattr__(self, a):
                return getattr(loader, a)

            def create_module(self, s):
                return loader.create_module(s)

            def exec_module(self, module):
                orig(module)
                _patch(name)

        spec.loader = _Loader()
        return spec


_finder = _Finder()


def _install_hooks():
    if not any(isinstance(f, _Finder) for f in sys.meta_path):
        sys.meta_path.insert(0, _finder)
    for name in _HOOKED:
        _patch(name)


# ---------------------------------------------------------------- written files
def _scan(root):
    seen = {}
    count = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".") and d not in ("__pycache__", "site-packages", "node_modules")]
        for fn in filenames:
            ext = os.path.splitext(fn)[1].lower()
            if ext not in IMAGE_EXTS:
                continue
            p = os.path.join(dirpath, fn)
            try:
                st = os.stat(p)
            except OSError:
                continue
            seen[p] = (st.st_mtime_ns, st.st_size)
            count += 1
            if count >= FILE_SCAN_LIMIT:
                return seen
    return seen


def _written_images(cwd, before, out_dir):
    after = _scan(cwd)
    out = os.path.abspath(out_dir)
    for p in sorted(after, key=lambda q: after[q][0]):
        if os.path.abspath(p).startswith(out + os.sep) or before.get(p) == after[p]:
            continue
        fmt = IMAGE_EXTS[os.path.splitext(p)[1].lower()]
        try:
            with open(p, "rb") as f:
                data = f.read(MAX_FILE_BYTES + 1)
        except OSError:
            continue
        w, h = _png_size(data) if fmt == "png" else _image_size(data)
        _session.add({"kind": "image", "source": "file", "name": os.path.relpath(p, cwd), "width": w, "height": h, "title": os.path.basename(p)}, {fmt: data})


def _image_size(data):
    if "PIL" not in sys.modules and "PIL.Image" not in sys.modules:
        return 0, 0
    try:
        import io
        from PIL import Image

        with Image.open(io.BytesIO(data)) as im:
            return im.width, im.height
    except Exception:
        return 0, 0


# --------------------------------------------------------------------------- run
def _prepare_matplotlib():
    os.environ["MPLBACKEND"] = "Agg"
    if "matplotlib" in sys.modules:
        import matplotlib

        try:
            matplotlib.use("Agg", force=True)
        except Exception:
            pass
        if "matplotlib.pyplot" in sys.modules:
            sys.modules["matplotlib.pyplot"].close("all")


def _finish(cwd, before):
    if "matplotlib.pyplot" in sys.modules:
        _mpl_show()
    _written_images(cwd, before, _session.dir)


def _begin(out_dir, globals_):
    global _session
    os.makedirs(out_dir, exist_ok=True)
    _session = _Session(out_dir)
    _prepare_matplotlib()
    _install_hooks()
    cwd = os.getcwd()
    g = globals_ if globals_ is not None else {"__name__": "__main__"}
    g.setdefault("display", display)
    return cwd, _scan(cwd), g


def _compile(code, filename):
    """The code without its last line when that is an expression (shown like a notebook cell's value), and that line."""
    tree = ast.parse(code, filename, "exec")
    last = None
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        last = ast.Expression(tree.body.pop().value)
    flags = ast.PyCF_ALLOW_TOP_LEVEL_AWAIT
    return compile(tree, filename, "exec", flags=flags), (compile(last, filename, "eval", flags=flags) if last is not None else None)


def _awaitable(v):
    return type(v).__name__ == "coroutine" and hasattr(v, "__await__")


def _finish_and_save(cwd, before):
    try:
        _finish(cwd, before)
    except Exception as e:
        _session.note(f"Couldn't capture every output: {e}")
    return _session.save()


async def run(code, out_dir, filename="<exec>", globals_=None):
    """Runs `code` as __main__, captures its rich outputs into out_dir; returns the manifest dict.

    For runners inside an event loop (Pyodide). Exceptions from the user's code propagate
    (after the outputs made before them are saved), so each runner reports errors as before.
    """
    global _session
    cwd, before, g = _begin(out_dir, globals_)
    try:
        body, last = _compile(code, filename)
        r = eval(body, g)
        if _awaitable(r):
            await r
        if last is not None:
            value = eval(last, g)
            _emit(await value if _awaitable(value) else value)
        return _finish_and_save(cwd, before)
    except BaseException:
        _finish_and_save(cwd, before)
        raise
    finally:
        _session = None


def run_sync(code, out_dir, filename="<exec>", globals_=None):
    """The same for runners without an event loop (server, Android): the code runs outside any
    loop, so it may call asyncio.run itself; only top-level await starts one."""
    import asyncio

    global _session
    cwd, before, g = _begin(out_dir, globals_)
    try:
        body, last = _compile(code, filename)
        r = eval(body, g)
        if _awaitable(r):
            asyncio.run(r)
        if last is not None:
            value = eval(last, g)
            if _awaitable(value):
                value = asyncio.run(value)
            _emit(value)
        return _finish_and_save(cwd, before)
    except BaseException:
        _finish_and_save(cwd, before)
        raise
    finally:
        _session = None


def user_traceback(exc, filename):
    """The traceback from the first frame of the user's code on (no capture frames)."""
    import traceback

    tb = exc.__traceback__
    while tb is not None and tb.tb_frame.f_code.co_filename != filename:
        tb = tb.tb_next
    return "".join(traceback.format_exception(type(exc), exc, tb if tb is not None else exc.__traceback__))


BUNDLE_MARK = "\x00BJ-OUTPUTS\x00"


def bundle(out_dir):
    """manifest + every output file (base64) as one JSON string, for runners that only return stdout."""
    import base64

    try:
        with open(os.path.join(out_dir, MANIFEST), encoding="utf-8") as f:
            manifest = json.load(f)
    except OSError:
        return None
    files = {}
    for item in manifest["items"]:
        for name in item["files"].values():
            with open(os.path.join(out_dir, name), "rb") as f:
                files[name] = base64.b64encode(f.read()).decode("ascii")
    return json.dumps({"manifest": manifest, "files": files})


def main(argv):
    """python3 bj_capture.py <file.py> <out_dir>: the server runner. Prints the bundle after BUNDLE_MARK."""
    path, out_dir = argv[1], argv[2]
    with open(path, encoding="utf-8") as f:
        code = f.read()
    status = 0
    sys.argv = [path]
    try:
        run_sync(code, out_dir, filename=path)
    except SystemExit as e:
        status = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
    except BaseException as e:
        sys.stderr.write(user_traceback(e, path))
        status = 1
    sys.stdout.flush()
    b = bundle(out_dir)
    if b is not None:
        sys.stdout.write("\n" + BUNDLE_MARK + b + "\n")
    sys.stdout.flush()
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
