"""Rich run outputs for Benchjot: one module shared by every Python runner.

The web's Pyodide worker, the server (Modal) runner and the Android app (Chaquopy)
all run user code through `run()`, so every platform captures the same outputs:

  image  matplotlib figures (and everything drawn on them: seaborn, pandas .plot,
         scikit-learn displays), PIL images, and image files the code writes.
         A figure is drawn once at run time, as PNG, and kept as itself (FIG_FILE, its
         pickle): SVG and PDF are made from that on Download (render()), in a Python with
         the same matplotlib, so they are the source figure, never a screenshot, and
         cost the run nothing. The server runner (main()) saves PNG + SVG + PDF instead:
         no browser or phone has its matplotlib to load its figures with.
  table  pandas DataFrame / Series (a preview inline, the full table as CSV).
  chart  interactive figures as their own JSON spec: Plotly (fig.to_json()),
         Altair / Vega-Lite (chart.to_dict()) and Bokeh (json_item()).

An output is shown when the code calls show() / display(obj), or when it is the
value of the last line (like a notebook cell). Figures still open at the end are
shown too. Output files go into `out_dir` with a manifest.json describing them in
order (the contract mirrored by src/lib/run/outputs.ts and the Android app).

GPU code on a machine without a GPU (the browser, the phone, a server run on the CPU)
runs on the CPU instead of failing: PyTorch's .cuda(), device="cuda", autocast and
GradScaler, and torch.load of GPU checkpoints go to the CPU, and CuPy reads as NumPy /
SciPy. Custom GPU kernels (Numba CUDA, Triton, CUDA C) can't, and the run says so.

Pure standard library at import; the plotting libraries are only touched when the
user's code has imported them.
"""

import ast
import json
import math
import os
import re
import sys

MANIFEST = "manifest.json"
MAX_LOGGED = 50  # the most values one run may log
MAX_SAVE_BYTES = 50 * 1024 * 1024  # the most one Data library file may be (the bucket's limit)
MAX_SAVES = 8  # the most files one run may save to the library
SAVE_NAME = re.compile(r"^[\w][\w .-]{0,118}\.[A-Za-z0-9]{1,10}$")  # datasets-match.ts validDataName


def _scalar(v):
    """A logged value as JSON-safe scalar: a bool, a finite number, or short text; else None."""
    if isinstance(v, bool):
        return v
    if isinstance(v, int):
        return v
    if isinstance(v, float):  # includes numpy float64 (a float subclass); normalise to a plain float
        return float(v) if math.isfinite(v) else None
    if isinstance(v, str):
        return v[:200]
    item = getattr(v, "item", None)  # numpy / torch 0-d scalars
    if callable(item):
        try:
            got = item()
        except Exception:
            return None
        return _scalar(got) if isinstance(got, (bool, int, float, str)) else None
    return None
MAX_OUTPUTS = 30
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = int(os.environ.get("BJ_MAX_TOTAL_BYTES", 24 * 1024 * 1024))
MAX_INLINE_CHARS = 300_000  # table previews kept inline in the run record (cells.last_run <= 600 KB)
TABLE_PREVIEW_CHARS = 60_000
TABLE_ROWS = 100
TABLE_COLS = 30
CELL_CHARS = 200
MAX_CSV_BYTES = 5 * 1024 * 1024
FIG_DPI = 150
FIG_FILE = "mpl"  # a matplotlib figure kept for SVG / PDF on Download: gzip of its pickle
VECTOR_FORMATS = ("svg", "pdf")
# A figure's SVG or PDF past this is saved again with its dense parts (scatter points, filled
# contours, meshes) as pictures inside the file: text, axes and lines stay sharp, and a decision-
# boundary plot's SVG drops from about 8 MB to about 1 MB, so it fits beside the run's other outputs.
VECTOR_DENSE_BYTES = 512 * 1024
IMAGE_EXTS = {".png": "png", ".jpg": "jpg", ".jpeg": "jpg", ".gif": "gif", ".webp": "webp", ".svg": "svg", ".bmp": "bmp", ".mp4": "mp4", ".webm": "webm"}
# Formats whose files carry another extension: an animation's frame strip is a PNG.
FILE_EXT = {"strip": "png"}
# An animation's frame strip: this many frames in time order, each at most this wide, in this many columns.
STRIP_FRAMES, STRIP_WIDTH, STRIP_COLUMNS = 6, 320, 3
VIDEO_FORMATS = ("mp4", "webm")  # videos the code wrote (anim.save("x.mp4"), OpenCV): shown with a player
FILE_SCAN_LIMIT = 2000
# Animations (matplotlib.animation: simulations) become a GIF, like the user saving one: at most
# ANIMATION_FRAMES frames at ANIMATION_DPI, and at most ANIMATION_FPS frames a second.
# Where ffmpeg is (the server), the animation is also kept as an MP4 video: full colour and far
# smaller than the GIF, so the server allows more frames (BJ_ANIMATION_FRAMES / BJ_ANIMATION_DPI).
ANIMATION_FRAMES = int(os.environ.get("BJ_ANIMATION_FRAMES", 120))
ANIMATION_DPI = int(os.environ.get("BJ_ANIMATION_DPI", 72))
ANIMATION_FPS = 30

_session = None


class _Session:
    def __init__(self, out_dir, vectors=False):
        self.dir = out_dir
        self.vectors = vectors  # save figures' SVG + PDF at run time (server) instead of FIG_FILE
        self.items = []
        self.total = 0
        self.emitted = set()  # ids of matplotlib figures already shown
        self.animations = []  # matplotlib Animations made by the code, in order (weak references)
        self.animated = set()  # ids of Animations already captured or saved by the code
        self.notes = []
        self.logged = {}  # scalar metrics/params the code logged, in order (log())
        self.saved = []  # files for the Data library that the app uploads after the run: {"name", "path"} (save_data())
        self.inline = 0

    def _name(self, fmt):
        # A file's extension is its format's, except an animation's frame strip (a PNG).
        return f"{len(self.items)}.{FILE_EXT.get(fmt, fmt)}"

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

    def log_value(self, key, value):
        """One logged scalar for this run; numbers, text and true/false only."""
        key = str(key).strip()[:60]
        if not key:
            return
        if len(self.logged) >= MAX_LOGGED and key not in self.logged:
            self.note(f"Only the first {MAX_LOGGED} logged values are kept.")
            return
        v = _scalar(value)
        if v is None:
            self.note(f"log({key}=…) was skipped: only numbers, text and true/false can be logged (not arrays or objects).")
            return
        self.logged[key] = v

    def add(self, item, files):
        """files: {format: bytes}. Keeps the item only if its files fit the limits."""
        if self.full():
            return
        names = {}
        for fmt, data in files.items():
            name = self._name(fmt)
            if not self._write(name, data):
                size = len(data.encode("utf-8") if isinstance(data, str) else data) / (1024 * 1024)
                what = f"“{item['title']}”" if item.get("title") else f"output {len(self.items) + 1}"
                why = f"over the {MAX_FILE_BYTES // (1024 * 1024)} MB a file may be" if size * 1024 * 1024 > MAX_FILE_BYTES else f"past the {MAX_TOTAL_BYTES / (1024 * 1024):g} MB a run's outputs may total"
                self.note(f"The {fmt.upper()} of {what} ({size:.1f} MB) wasn't kept: {why}.")
                continue
            names[fmt] = name
        if files and not names:
            return
        item["files"] = names
        self.items.append(item)

    def save(self):
        manifest = {"version": 1, "items": self.items, "notes": self.notes}
        if self.logged:
            manifest["logged"] = self.logged
        if self.saved:
            manifest["saved"] = self.saved
        with open(os.path.join(self.dir, MANIFEST), "w", encoding="utf-8") as f:
            json.dump(manifest, f, allow_nan=False, default=str)
        return manifest


# --------------------------------------------------------------------- emitters
def _bbox_extra(fig):
    """savefig arguments that keep 3D axis labels inside a tight crop.

    matplotlib before 3.9 leaves Axes3D axis labels out of the tight bbox, so a z label
    (and sometimes x / y) is cut at the edge. Count every 3D axis label explicitly; 2D-only
    figures get no extra arguments, so their files stay byte-identical."""
    try:
        axes3d = [ax for ax in fig.axes if hasattr(ax, "zaxis")]
        if not axes3d:
            return {}
        extra = list(fig.get_default_bbox_extra_artists())
        for ax in axes3d:
            extra += [a.label for a in (ax.xaxis, ax.yaxis, ax.zaxis) if a.label.get_text()]
        return {"bbox_extra_artists": extra, "pad_inches": 0.15}
    except Exception:
        return {}


def _png(fig, extra):
    """The figure as PNG at FIG_DPI, cropped as savefig(bbox_inches="tight") crops it.

    savefig's tight crop draws the figure twice (once to measure it, once to save); this
    draws it once, measures the crop on that drawing and cuts it out of the pixels. When
    the crop reaches past the canvas (a legend placed outside it) or the saved look is
    configured differently from the drawn one, savefig does it as before."""
    import io

    import matplotlib
    from matplotlib.backends.backend_agg import FigureCanvasAgg

    rc = matplotlib.rcParams
    if isinstance(fig.canvas, FigureCanvasAgg) and not rc["savefig.transparent"] and rc["savefig.facecolor"] == "auto" and rc["savefig.edgecolor"] == "auto":
        data = _png_one_draw(fig, extra)
        if data is not None:
            return data
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=FIG_DPI, bbox_inches="tight", **extra)
    return buf.getvalue()


def _png_one_draw(fig, extra):
    """_png's one-draw path; None when something drawn lies outside the canvas.

    Only the padding may reach past the canvas (a figure filled to its edges by
    tight_layout): savefig paints the figure's background there, and so does this."""
    import io

    import matplotlib
    import numpy as np
    from matplotlib.colors import to_rgba
    from matplotlib.image import imsave

    if fig.patch.get_visible() and fig.patch.get_linewidth() > 0:
        return None  # a framed figure: savefig draws the frame around the crop
    dpi = fig.dpi
    fig.dpi = FIG_DPI
    try:
        canvas = fig.canvas
        canvas.draw()
        tight = fig.get_tightbbox(canvas.get_renderer(), bbox_extra_artists=extra.get("bbox_extra_artists"))
        width, height = canvas.get_width_height(physical=True)
        tx0, ty0, tx1, ty1 = (v * FIG_DPI for v in tight.extents)  # pixels, from the bottom left
        if tx0 < -0.5 or ty0 < -0.5 or tx1 > width + 0.5 or ty1 > height + 0.5:
            return None
        box = tight.padded(extra.get("pad_inches", matplotlib.rcParams["savefig.pad_inches"]))
        w, h = int(box.width * FIG_DPI), int(box.height * FIG_DPI)  # savefig's canvas size for this crop
        # savefig moves the crop's bottom left to the origin and cuts its canvas to (w, h).
        left, top = round(box.x0 * FIG_DPI), round(height - box.y0 * FIG_DPI) - h
        drawn = np.asarray(canvas.buffer_rgba())
        bg = to_rgba(fig.patch.get_facecolor()) if fig.patch.get_visible() else (0, 0, 0, 0)
        pixels = np.empty((h, w, 4), dtype=np.uint8)
        pixels[:] = [round(c * 255) for c in bg]
        sx, sy = max(left, 0), max(top, 0)
        ex, ey = min(left + w, width), min(top + h, height)
        if ex > sx and ey > sy:
            pixels[sy - top : ey - top, sx - left : ex - left] = drawn[sy:ey, sx:ex]
    finally:
        fig.dpi = dpi
    buf = io.BytesIO()
    imsave(buf, pixels, format="png", origin="upper", dpi=FIG_DPI)
    return buf.getvalue()


def _dense_artists(fig):
    """The figure's collections (scatter points, filled contours, meshes) not already drawn as pictures."""
    from matplotlib.collections import Collection

    found = []
    for ax in fig.axes:
        for artist in ax.get_children():
            if isinstance(artist, Collection) and not artist.get_rasterized():
                found.append(artist)
    return found


def _vector_bytes(fig, fmt, extra):
    """The figure as SVG or PDF; when that is large, saved again with its dense parts as pictures, if smaller."""
    import io

    buf = io.BytesIO()
    fig.savefig(buf, format=fmt, dpi=FIG_DPI, bbox_inches="tight", **extra)
    data = buf.getvalue()
    if len(data) <= VECTOR_DENSE_BYTES:
        return data
    dense = _dense_artists(fig)
    if not dense:
        return data
    try:
        for artist in dense:
            artist.set_rasterized(True)
        buf = io.BytesIO()
        fig.savefig(buf, format=fmt, dpi=FIG_DPI, bbox_inches="tight", **extra)
        lighter = buf.getvalue()
    except Exception:
        return data
    finally:
        for artist in dense:
            artist.set_rasterized(False)
    if len(lighter) < len(data):
        if _session is not None:
            _session.note("A figure's many points or filled areas are stored as an image inside its SVG and PDF, so the files stay small; text and axes stay sharp.")
        return lighter
    return data


def _vectors(fig, extra):
    """{format: bytes} for VECTOR_FORMATS; a format the backend can't make is skipped with a note."""
    files = {}
    # PDF first: when a run's outputs are near their limit, the PDF (what papers use) is the one kept.
    for fmt in sorted(VECTOR_FORMATS, key=lambda f: f != "pdf"):
        try:
            files[fmt] = _vector_bytes(fig, fmt, extra)
        except Exception as e:
            _session.note(f"Couldn't save a figure as {fmt.upper()}: {e}")
    return files


def _kept_figure(fig):
    """FIG_FILE bytes for fig, or None when it couldn't be loaded again later: it doesn't
    pickle (a lambda in it), or it refers to the user's own code (__main__), which a later
    Python doesn't have."""
    import gzip
    import pickle

    try:
        data = pickle.dumps(fig, protocol=pickle.HIGHEST_PROTOCOL)
    except Exception:
        return None
    if b"__main__" in data:
        return None
    return gzip.compress(data, compresslevel=6, mtime=0)


def _figure(fig, source="matplotlib"):
    s = _session
    if s is None or id(fig) in s.emitted:
        return
    s.emitted.add(id(fig))
    import matplotlib

    extra = _bbox_extra(fig)
    files = {"png": _png(fig, extra)}
    w, h = _png_size(files["png"])
    title = ""
    try:
        title = fig._suptitle.get_text() if fig._suptitle else ""
        if not title and fig.axes:
            title = fig.axes[0].get_title()
    except Exception:
        pass
    item = {"kind": "image", "source": source, "width": w, "height": h, "title": title}
    # Closed first: a figure pickled while pyplot holds it registers itself with pyplot when loaded.
    try:
        import matplotlib.pyplot as plt

        plt.close(fig)
    except Exception:
        pass
    kept = None if s.vectors else _kept_figure(fig)
    if kept is not None:
        files[FIG_FILE] = kept
        item["matplotlib"] = matplotlib.__version__
    else:  # the server, or a figure that can't be kept: its SVG and PDF now
        files.update(_vectors(fig, extra))
    s.add(item, files)


class RenderError(ValueError):
    """A kept figure this Python can't draw; the message is for the user."""


def _minor(version):
    return tuple(version.split(".")[:2])


def render(data, fmt, made_with=None):
    """SVG or PDF bytes of a figure kept at run time (FIG_FILE), cropped as at run time.
    `made_with`: the matplotlib version that kept it (the manifest item's "matplotlib");
    figures load only in the same matplotlib (major.minor)."""
    import gzip
    import io
    import pickle

    import matplotlib

    if fmt not in VECTOR_FORMATS:
        raise RenderError(f"Figures are made as {' or '.join(f.upper() for f in VECTOR_FORMATS)} here, not {fmt}.")
    if made_with and _minor(made_with) != _minor(matplotlib.__version__):
        raise RenderError(
            f"This figure was made with matplotlib {'.'.join(_minor(made_with))} and this device has {'.'.join(_minor(matplotlib.__version__))}, "
            f"so its {fmt.upper()} can only be made where the code ran. Open the note there, or run the code again here."
        )
    try:
        fig = pickle.loads(gzip.decompress(data))
    except ModuleNotFoundError:
        raise  # a library the figure refers to (pandas' date axes): the runner may load it and try again
    except Exception as e:
        raise RenderError(f"This figure couldn't be loaded to make its {fmt.upper()} ({type(e).__name__}). Run the code again to make it.") from e
    return _vector_bytes(fig, fmt, _bbox_extra(fig))


def render_file(src, fmt, dest, made_with=None):
    """render() between files, for the runners (the browser's worker and the phone)."""
    with open(src, "rb") as f:
        data = render(f.read(), fmt, made_with)
    with open(dest, "wb") as f:
        f.write(data)


def _animation(anim):
    """A matplotlib Animation as a GIF, and as an MP4 video too where ffmpeg is installed (its
    figure isn't shown again as a still). Both are always kept: the video is small, and a GIF too
    large for the size limits is made again lighter until it fits."""
    s = _session
    if s is None or id(anim) in s.animated:
        return
    s.animated.add(id(anim))
    fig = getattr(anim, "_fig", None)
    if fig is None or s.full():
        return
    s.emitted.add(id(fig))
    import itertools
    import tempfile

    from matplotlib.animation import PillowWriter

    interval = getattr(anim, "_interval", 200) or 200
    # More frames than the limit: every step-th frame at a step-th of the speed, so the whole
    # simulation plays in its own time (not just its beginning). Unknown length: the first ones.
    total = getattr(anim, "_save_count", None)
    step = -(-total // ANIMATION_FRAMES) if isinstance(total, int) and total > ANIMATION_FRAMES else 1
    frames = anim.new_saved_frame_seq
    last = ANIMATION_FRAMES * step

    def saved(suffix, writer, every, dpi):
        """The animation's file with every `every`-th frame, played over the same time."""
        fps = max(1, min(ANIMATION_FPS, round(1000 / (interval * every))))
        anim.new_saved_frame_seq = lambda: itertools.islice(frames(), 0, last, every)
        tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
        tmp.close()
        try:
            _ORIGINAL_SAVE(anim, tmp.name, writer=writer(fps), dpi=dpi)
            with open(tmp.name, "rb") as f:
                return f.read()
        finally:
            anim.new_saved_frame_seq = frames
            os.unlink(tmp.name)

    dpi = min(fig.dpi, ANIMATION_DPI)
    files = {}
    if _has_ffmpeg():
        try:
            files["mp4"] = saved(".mp4", _mp4_writer, step, dpi)
        except Exception as e:
            s.note(f"Couldn't make the animation's video: {e}")
    # The GIF always comes too. One that doesn't fit the size limits (a long animation on the
    # server, next to its video) is made again lighter: fewer frames over the same time, then
    # fewer pixels, until it fits. Without a video, the first one is kept and the limits decide.
    room = min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - s.total - len(files.get("mp4", b"")))
    data = b""
    for every, gif_dpi in ((step, dpi), (step * 2, min(dpi, 72)), (step * 4, min(dpi, 72)), (step * 8, min(dpi, 56)), (step * 16, min(dpi, 48))):
        data = saved(".gif", lambda fps: PillowWriter(fps=fps), every, gif_dpi)
        if len(data) <= room or "mp4" not in files:
            break
    if len(data) <= room or "mp4" not in files:
        files["gif"] = data
    # A strip of frames in time order (a PNG), so whoever reads the outputs (the Assistant's
    # vision model) sees how it moves, not only its first frame. Never shown in the note.
    # Only when it fits beside them: it is never worth a note about a limit.
    strip = _frame_strip(files["gif"]) if "gif" in files else None
    if strip and s.total + sum(len(v) for v in files.values()) + len(strip) <= MAX_TOTAL_BYTES and len(strip) <= MAX_FILE_BYTES:
        files["strip"] = strip
    title = ""
    try:
        title = fig._suptitle.get_text() if fig._suptitle else (fig.axes[0].get_title() if fig.axes else "")
    except Exception:
        pass
    n = sum(1 for i in s.items if i.get("name", "").startswith("animation"))
    w, h = _image_size(data)
    s.add({"kind": "image", "source": "file", "name": f"animation-{n + 1}.gif", "width": w, "height": h, "title": title or "Animation"}, files)
    try:
        import matplotlib.pyplot as plt

        plt.close(fig)
    except Exception:
        pass


def _frame_strip(gif):
    """STRIP_FRAMES frames of a GIF, evenly spaced from first to last, side by side in rows (PNG bytes); None if it can't be made."""
    try:
        import io

        from PIL import Image

        im = Image.open(io.BytesIO(gif))
        count = getattr(im, "n_frames", 1)
        if count < 2:
            return None
        picks = sorted({round(i * (count - 1) / (STRIP_FRAMES - 1)) for i in range(STRIP_FRAMES)})
        frames = []
        for k in picks:
            im.seek(k)
            frame = im.convert("RGB")
            frame.thumbnail((STRIP_WIDTH, STRIP_WIDTH * 4))
            frames.append(frame)
        w, h = frames[0].size
        cols = min(STRIP_COLUMNS, len(frames))
        rows = -(-len(frames) // cols)
        sheet = Image.new("RGB", (cols * w, rows * h), "white")
        for i, frame in enumerate(frames):
            sheet.paste(frame, ((i % cols) * w, (i // cols) * h))
        out = io.BytesIO()
        sheet.save(out, format="PNG", optimize=True)
        return out.getvalue()
    except Exception:
        return None


def _has_ffmpeg():
    import shutil

    return shutil.which("ffmpeg") is not None


def _mp4_writer(fps):
    """An H.264 writer any phone and browser plays (yuv420p needs even pixel sizes)."""
    from matplotlib.animation import FFMpegWriter

    return FFMpegWriter(fps=fps, codec="libx264", extra_args=["-pix_fmt", "yuv420p", "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-movflags", "+faststart"])


def _animations():
    """Captures every Animation the code made and didn't save itself, oldest first."""
    s = _session
    if s is None:
        return
    for ref in list(s.animations):
        anim = ref()
        if anim is not None and id(anim) not in s.animated:
            try:
                _animation(anim)
            except Exception as e:
                s.animated.add(id(anim))
                s.note(f"Couldn't make the animation: {e}")


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
        if root == "IPython":
            return _ipython(obj)
        if mod.startswith("matplotlib.animation") and hasattr(obj, "new_saved_frame_seq"):
            _animation(obj)
            return True
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

    _animations()
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
        _patch_write_image(m.BaseFigure)
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
    elif module_name == "matplotlib.animation":
        _patch_animation(m.Animation)
    elif module_name in ("IPython.display", "IPython.core.display_functions"):
        m.display = display  # notebook code's display() is Benchjot's
    elif module_name == "scipy":
        _scipy_compat(m)
    elif module_name == "torchvision.datasets.utils":
        _dataset_cache(m)
    elif module_name == "torch":
        _torch_on_cpu(m)


_ORIGINAL_SAVE = None


def _dataset_cache(utils):
    """Server image only: datasets whose own host is slow (CIFAR's sends 170 MB at about 400 kB/s:
    seven minutes) are kept in the image under BJ_DATASETS as <host>/<path>. torchvision's
    download_url takes a file from there instead of downloading it; its checksum is still
    verified by torchvision afterwards. Anything not kept downloads as usual."""
    image = os.environ.get("BJ_DATASETS", "/opt/bj-datasets")
    # The user's own cache (a persistent volume on the server): every file torchvision downloads is
    # kept there by its URL, so any dataset is downloaded once per user, whatever folder the code uses.
    mine = os.environ.get("BJ_DOWNLOADS", "")
    original = getattr(utils, "download_url", None)
    if original is None or not (os.path.isdir(image) or mine):
        return

    def kept(cache, url):
        """The file for `url` inside `cache` (never outside it)."""
        if not cache:
            return None
        path = os.path.normpath(os.path.join(cache, str(url).split("://", 1)[-1].split("?", 1)[0]))
        return path if path.startswith(os.path.join(cache, "")) else None

    def download_url(url, root, filename=None, *args, **kwargs):
        import shutil

        root = os.path.expanduser(os.fspath(root))
        dest = os.path.join(root, filename or os.path.basename(str(url)))
        for cache in (mine, image):
            local = kept(cache, url)
            if local and os.path.isfile(local):
                os.makedirs(root, exist_ok=True)
                if not os.path.exists(dest):
                    shutil.copyfile(local, dest)
                return None
        result = original(url, root, filename, *args, **kwargs)
        local = kept(mine, url)
        if local and os.path.isfile(dest):
            try:  # keeping it is a convenience: never fail the run over it
                os.makedirs(os.path.dirname(local), exist_ok=True)
                shutil.copyfile(dest, local + ".part")
                os.replace(local + ".part", local)
            except OSError:
                pass
        return result

    utils.download_url = download_url

# Names SciPy removed that were only NumPy's under another name, which older libraries still import
# (scikit-plot: `from scipy import interp`, gone in SciPy 1.12). Restored as the NumPy function.
_SCIPY_ALIASES = ("interp",)


def _scipy_compat(scipy):
    try:
        import numpy
    except ImportError:
        return
    for name in _SCIPY_ALIASES:
        if not hasattr(scipy, name) and hasattr(numpy, name):
            setattr(scipy, name, getattr(numpy, name))


KALEIDO_NOTE = "[Benchjot] Saving a Plotly figure as an image needs Kaleido (a whole browser), which isn't installed: the chart is shown below instead. Its menu downloads it as PNG.\n"


def _patch_write_image(cls):
    """fig.write_image() without Kaleido: the chart is shown (and downloads as PNG from its menu) instead of failing."""
    real = cls.write_image
    if getattr(real, "_bj", False):
        return

    def write_image(self, *args, **kwargs):
        try:
            return real(self, *args, **kwargs)
        except (ValueError, RuntimeError, ImportError) as e:
            if "aleido" not in str(e):
                raise
            sys.stderr.write(KALEIDO_NOTE)
            _emit(self)

    write_image._bj = True
    cls.write_image = write_image


def _patch_animation(cls):
    """Remembers each Animation the code makes; one it saves itself is its own file (_written_images)."""
    import weakref

    global _ORIGINAL_SAVE
    init, save = cls.__init__, cls.save
    _ORIGINAL_SAVE = save

    def __init__(self, *args, **kwargs):
        init(self, *args, **kwargs)
        if _session is not None:
            _session.animations.append(weakref.ref(self))

    def save_(self, *args, **kwargs):
        if _session is not None:
            _session.animated.add(id(self))
        return save(self, *args, **kwargs)

    # Notebook code shows an animation with HTML(anim.to_jshtml()) or to_html5_video(): that HTML
    # doesn't show here (and to_html5_video needs ffmpeg). The animation is captured as a GIF instead,
    # as if the code had just left it open, and the HTML call returns a marker display() skips.
    def as_html(self, *args, **kwargs):
        _animation(self)
        return ANIMATION_HTML

    cls.__init__ = __init__
    cls.save = save_
    cls.to_jshtml = as_html
    cls.to_html5_video = as_html


ANIMATION_HTML = "<!-- Benchjot: this animation is shown as a GIF -->"


def _ipython(obj):
    """IPython's display objects, as notebook code makes them: an animation's HTML is already shown
    (its GIF); an Image is shown as one; other HTML is said plainly. Returns whether it was handled."""
    name = type(obj).__name__
    data = getattr(obj, "data", None)
    if isinstance(data, str) and ANIMATION_HTML in data:
        return True
    if name in ("Image", "Video"):
        path = getattr(obj, "filename", None)
        if path and os.path.abspath(path).startswith(os.getcwd() + os.sep):
            return True  # a file the code wrote here: shown from the file, once (written files are kept with the run)
        if name == "Image" and isinstance(data, (bytes, bytearray)):
            import io

            from PIL import Image as PILImage

            _pil(PILImage.open(io.BytesIO(bytes(data))))
            return True
        return False
    if name in ("Markdown", "Latex", "Pretty") and isinstance(data, str):
        print(data)  # its text, as the cell's output
        return True
    if name in ("HTML", "Javascript", "IFrame", "SVG", "JSON"):
        _session.note("HTML output isn't shown in Benchjot; print text, or show a figure, table or chart instead.")
        return True
    return False


# ---------------------------------------------------------------- GPU code without a GPU
CPU_NOTE = "[Benchjot] No GPU here, so this code's GPU calls ran on the CPU (it can take much longer).\n"
_cpu_noted = False


def _note_cpu():
    """Says once per run that GPU calls were moved to the CPU."""
    global _cpu_noted
    if not _cpu_noted:
        _cpu_noted = True
        sys.stderr.write(CPU_NOTE)


def _is_gpu(d):
    """A device naming a GPU: "cuda", "cuda:1", torch.device("cuda"), or a bare GPU index."""
    if isinstance(d, str):
        return d.split(":")[0].strip().lower() in ("cuda", "gpu", "mps")
    if type(d).__name__ == "device" and getattr(d, "type", None) in ("cuda", "mps"):
        return True
    return isinstance(d, int) and not isinstance(d, bool)


def _cpu(d):
    if _is_gpu(d):
        _note_cpu()
        return "cpu"
    return d


# Tensor factories that take device=: what they make goes to the CPU when the code asks for a GPU.
_TORCH_FACTORIES = (
    "tensor", "as_tensor", "zeros", "ones", "empty", "full", "rand", "randn", "randint", "randperm", "arange",
    "linspace", "logspace", "eye", "zeros_like", "ones_like", "empty_like", "full_like", "rand_like",
    "randn_like", "randint_like", "normal", "bernoulli", "multinomial", "tril_indices", "triu_indices",
)


def _torch_on_cpu(torch):
    """PyTorch without a GPU: every way code sends work to "cuda" sends it to the CPU instead.
    Nothing changes where a GPU is present."""
    import contextlib
    import functools

    try:
        if torch.cuda.is_available() or getattr(torch, "_bj_cpu", False):
            return
    except Exception:
        return
    torch._bj_cpu = True

    def device_kw(f):
        @functools.wraps(f)
        def g(*a, **k):
            if "device" in k:
                k["device"] = _cpu(k["device"])
            return f(*a, **k)
        return g

    for name in _TORCH_FACTORIES:
        if hasattr(torch, name):
            setattr(torch, name, device_kw(getattr(torch, name)))

    def to(orig):
        @functools.wraps(orig)
        def g(self, *a, **k):
            if a:
                a = (_cpu(a[0]),) + tuple(a[1:])
            if "device" in k:
                k["device"] = _cpu(k["device"])
            return orig(self, *a, **k)
        return g

    def stay(self, *a, **k):
        _note_cpu()
        return self

    torch.Tensor.to = to(torch.Tensor.to)
    torch.Tensor.cuda = stay
    torch.nn.Module.to = to(torch.nn.Module.to)
    torch.nn.Module.cuda = stay

    # Checkpoints saved on a GPU load onto the CPU.
    load = torch.load

    @functools.wraps(load)
    def load_cpu(f, *a, **k):
        if not a and k.get("map_location") is None or _is_gpu(k.get("map_location")):
            k["map_location"] = "cpu"
        return load(f, *a, **k)

    torch.load = load_cpu

    # The GPU's housekeeping calls do nothing on the CPU (instead of failing).
    cuda = torch.cuda
    for name in ("synchronize", "empty_cache", "manual_seed", "manual_seed_all", "set_device", "reset_peak_memory_stats", "reset_accumulated_memory_stats", "ipc_collect"):
        setattr(cuda, name, lambda *a, **k: None)
    for name in ("memory_allocated", "max_memory_allocated", "memory_reserved", "max_memory_reserved"):
        setattr(cuda, name, lambda *a, **k: 0)
    cuda.get_device_name = lambda *a, **k: "CPU (no GPU here)"

    # Mixed precision: the GPU's autocast and loss scaling switch off (full precision on the CPU).
    class _NoAutocast(contextlib.ContextDecorator):
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    autocast = torch.autocast

    def autocast_(device_type="cuda", *a, **k):
        return _NoAutocast() if _is_gpu(device_type) else autocast(device_type, *a, **k)

    torch.autocast = autocast_
    if hasattr(torch, "amp"):
        if hasattr(torch.amp, "autocast"):
            torch.amp.autocast = autocast_
        scaler = getattr(torch.amp, "GradScaler", None)
        if scaler is not None:
            init = scaler.__init__

            def scaler_init(self, device="cuda", *a, **k):
                k["enabled"] = False
                init(self, "cpu", *a, **k)

            scaler.__init__ = scaler_init
    amp = getattr(cuda, "amp", None)
    if amp is not None:
        amp.autocast = _NoAutocast
        if hasattr(torch, "amp") and hasattr(torch.amp, "GradScaler"):
            amp.GradScaler = torch.amp.GradScaler


# CuPy (NumPy on a GPU) when it isn't here: the same calls, on NumPy and SciPy.
_CUPY_KERNELS = ("RawKernel", "RawModule", "ElementwiseKernel", "ReductionKernel", "fuse")


def _cupy_module():
    import types

    import numpy

    cp = types.ModuleType("cupy")
    cp.__dict__.update({k: v for k, v in vars(numpy).items() if not k.startswith("__")})
    cp.__benchjot__ = "NumPy, as CuPy without a GPU"
    cp.asnumpy = lambda a, *args, **kwargs: numpy.asarray(a)
    cp.get_array_module = lambda *args: numpy
    cp.fuse = lambda *a, **k: (a[0] if a and callable(a[0]) else (lambda f: f))

    def kernel(name):
        def nope(*a, **k):
            raise RuntimeError(f"cupy.{name} is a custom GPU kernel: it can't run without a GPU. Run this cell on the server with # gpu: T4.")
        return nope

    for name in ("RawKernel", "RawModule", "ElementwiseKernel", "ReductionKernel"):
        setattr(cp, name, kernel(name))

    class _Device:
        def __init__(self, *a, **k):
            pass

        def use(self):
            return self

        def synchronize(self):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    cuda = types.ModuleType("cupy.cuda")
    cuda.Device = _Device
    cuda.Stream = _Device
    cuda.is_available = lambda: False
    runtime = types.ModuleType("cupy.cuda.runtime")
    runtime.getDeviceCount = lambda: 0
    runtime.deviceSynchronize = lambda: None
    cuda.runtime = runtime
    cp.cuda = cuda
    return cp, {"cupy.cuda": cuda, "cupy.cuda.runtime": runtime, "cupy.random": numpy.random, "cupy.linalg": numpy.linalg, "cupy.fft": numpy.fft}


def _cupyx_module(name):
    """cupyx and cupyx.scipy.<x>: SciPy's own modules."""
    import importlib
    import types

    if name == "cupyx":
        m = types.ModuleType("cupyx")
        m.__path__ = []
        m.scipy = _cupyx_module("cupyx.scipy")
        return m
    if name == "cupyx.scipy":
        import scipy

        return scipy
    return importlib.import_module("scipy" + name[len("cupyx.scipy"):])


class _AliasLoader:
    """Makes `cupy` / `cupyx` from NumPy and SciPy when the real ones aren't installed."""

    def __init__(self, name):
        self.name = name

    def create_module(self, spec):
        if self.name == "cupy" or self.name.startswith("cupy."):
            cp, subs = _cupy_module()
            sys.modules.update(subs)
            m = cp if self.name == "cupy" else sys.modules.get(self.name)
        else:
            m = _cupyx_module(self.name)
        if m is None:
            raise ModuleNotFoundError(f"No module named {self.name!r}")
        _note_cpu()
        return m

    def exec_module(self, module):
        pass


IPYTHON_SHIMMED = ("IPython", "IPython.display", "IPython.core", "IPython.core.display", "IPython.core.display_functions")


class _DisplayObject:
    """IPython's display objects where IPython isn't installed (the phone): notebook code that
    shows things with display(HTML(...)), Image(...) or Markdown(...) runs, and _ipython shows them."""

    def __init__(self, data=None, url=None, filename=None, **kwargs):
        if filename is None and isinstance(data, str) and type(self).__name__ in ("Image", "Video") and os.path.exists(data):
            data, filename = None, data
        if filename is not None and data is None and type(self).__name__ == "Image":
            with open(filename, "rb") as f:
                data = f.read()
        self.data, self.url, self.filename = data, url, filename

    def __repr__(self):
        return f"<IPython.core.display.{type(self).__name__} object>"


def _ipython_module(name):
    """A stand-in `IPython` / `IPython.display` with display() and the display objects."""
    import types

    m = types.ModuleType(name)
    m.__path__ = []  # a package, so IPython.display imports from it
    for cls in ("HTML", "Markdown", "Image", "Video", "Javascript", "IFrame", "Latex", "SVG", "JSON"):
        setattr(m, cls, type(cls, (_DisplayObject,), {"__module__": "IPython.core.display"}))
    m.display = display
    m.clear_output = lambda *a, **k: None
    m.get_ipython = lambda: None
    # matplotlib reads IPython.version_info when it makes any figure (backend_bases._fix_ipython_backend2gui):
    # without it every plt.figure() failed once the stand-in was imported. 8.24+ tells it to leave the GUI alone.
    m.version_info = (8, 24, 0, "")
    m.__version__ = "8.24.0"
    return m


class _IPythonLoader:
    def create_module(self, spec):
        return _ipython_module(spec.name)

    def exec_module(self, module):
        pass


def _alias_spec(name):
    """A spec for cupy / cupyx when they can't be imported for real (no GPU machine has them),
    and for IPython's display module where IPython isn't installed."""
    shim = name in IPYTHON_SHIMMED
    if not (shim or name in ("cupy", "cupyx") or name.startswith(("cupy.", "cupyx."))):
        return None
    import importlib.machinery
    import importlib.util

    sys.meta_path.remove(_finder)
    try:
        real = importlib.util.find_spec(name)
    except (ImportError, ValueError):
        real = None
    finally:
        sys.meta_path.insert(0, _finder)
    if real is not None:
        return None
    if shim:
        return importlib.machinery.ModuleSpec(name, _IPythonLoader(), is_package=True)
    return importlib.machinery.ModuleSpec(name, _AliasLoader(name))


# Errors only custom GPU kernels give on a machine without a GPU, and what to say then.
_KERNEL_ERRORS = ("CudaSupportError", "numba.cuda", "triton", "nvcc", "CUDA driver", "no CUDA-capable device", "Found no NVIDIA driver", "libcuda.so")
KERNEL_HINT = "[Benchjot] This code needs a GPU for its own CUDA kernels (Numba CUDA, Triton or CUDA C), which can't be moved to the CPU. Run it on the server with a GPU: add # gpu: T4 at the top of the cell.\n"


def gpu_hint(text):
    """The hint for an error a custom GPU kernel gives without a GPU; '' for any other error."""
    return KERNEL_HINT if any(k in text for k in _KERNEL_ERRORS) else ""


_HOOKED = ("torch", "torchvision.datasets.utils", "scipy", "matplotlib.animation", "matplotlib.pyplot", "plotly.basedatatypes", "plotly.io", "bokeh.io.showing", "bokeh.io", "bokeh.plotting", "altair", "PIL.Image", "IPython.display", "IPython.core.display_functions")


class _Finder:
    """Patches show() right after one of the libraries above is imported."""

    def find_spec(self, name, path=None, target=None):
        if name in IPYTHON_SHIMMED:
            shim = _alias_spec(name)  # None when IPython is installed: then it's patched like the rest
            if shim is not None:
                return shim
        if name not in _HOOKED:
            return _alias_spec(name)
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


PROCESS_NOTE = "[Benchjot] This device can't start new processes, so the pool runs its work in threads instead.\n"
_process_noted = False


def _processes_work():
    """Whether Python can start worker processes here (Android has no sem_open: multiprocessing isn't available)."""
    try:
        import multiprocessing.synchronize  # noqa: F401
        return True
    except ImportError:
        return False


def _threads_for_processes():
    """Where processes can't start (the phone), process pools become thread pools, so code written for
    multiprocessing.Pool or ProcessPoolExecutor still runs; joblib (scikit-learn's n_jobs) runs serially,
    without its warning."""
    import warnings

    # joblib probes for semaphores on import and warns when it can't. On the phone a partial
    # _multiprocessing can pass the check below yet fail joblib's probe: serial is expected either way.
    warnings.filterwarnings("ignore", message=r".*joblib will operate in serial mode")
    if _processes_work():
        return
    os.environ.setdefault("JOBLIB_MULTIPROCESSING", "0")
    import concurrent.futures
    import multiprocessing
    import multiprocessing.pool

    def note():
        global _process_noted
        if not _process_noted:
            _process_noted = True
            sys.stderr.write(PROCESS_NOTE)

    def pool(processes=None, *args, **kwargs):
        note()
        return multiprocessing.pool.ThreadPool(processes)

    class ProcessPoolExecutor(concurrent.futures.ThreadPoolExecutor):
        def __init__(self, max_workers=None, *args, **kwargs):
            note()
            super().__init__(max_workers=max_workers)

    multiprocessing.Pool = pool
    concurrent.futures.ProcessPoolExecutor = ProcessPoolExecutor


def _install_hooks():
    global _process_noted
    _process_noted = False
    _threads_for_processes()
    if not any(isinstance(f, _Finder) for f in sys.meta_path):
        sys.meta_path.insert(0, _finder)
    for name in _HOOKED:
        _patch(name)
    if "scipy" in sys.modules:  # cheap, and not once only: a warm runner keeps scipy between runs
        _scipy_compat(sys.modules["scipy"])


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
        w, h = _png_size(data) if fmt == "png" else ((0, 0) if fmt in VIDEO_FORMATS else _image_size(data))
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
    else:
        _animations()
    _written_images(cwd, before, _session.dir)


def log(*mappings, **values):
    """Record scalar metrics or settings for this run, e.g. log(accuracy=0.96, lr=0.01) or log({"accuracy": 0.96}).
    They show on the run and build the note's Runs table. Numbers, text and true/false only."""
    merged = {}
    for m in mappings:
        if isinstance(m, dict):
            merged.update(m)
        else:
            raise TypeError("log() takes keyword values or a dict, e.g. log(accuracy=0.96) or log({'accuracy': 0.96}).")
    merged.update(values)
    if _session is not None:
        for k, v in merged.items():
            _session.log_value(k, v)


def save_data(path, name=None):
    """Put a file this cell made or downloaded into your Data library, e.g. save_data("N87.h5") or
    save_data("raw/data.csv", name="clean.csv"). Every later cell, in any note, opens it by name:
    pd.read_csv("clean.csv"). Up to 50 MB a file; a file of the same name is replaced."""
    path = os.fspath(path)
    if not os.path.isfile(path):
        raise FileNotFoundError(f"save_data: there's no file {path!r}. Write the file first, then save it.")
    name = os.path.basename(str(name)) if name else os.path.basename(path)
    if not SAVE_NAME.match(name) or ".." in name:
        raise ValueError(f"save_data: {name!r} can't be a library name. Use a plain file name with an extension, like N87.h5.")
    size = os.path.getsize(path)
    if size > MAX_SAVE_BYTES:
        raise ValueError(f"save_data: {name} is {size / 1e6:.1f} MB, over the 50 MB one library file may be. Save a smaller part of it.")
    mb = f"{size / 1e6:.1f} MB" if size >= 100_000 else f"{max(1, round(size / 1000))} KB"
    slots = os.environ.get("BJ_SAVE")
    if slots is not None:
        # A server run: upload now, to the slot the app signed for this name.
        try:
            url = next((s["url"] for s in json.loads(slots) if s.get("name") == name), None)
        except (ValueError, TypeError, KeyError):
            url = None
        if not url:
            raise RuntimeError(f"save_data: on the server, give the name as plain text in quotes, e.g. save_data({name!r}), so the app can prepare its place in your library before the run.")
        import urllib.request
        with open(path, "rb") as f:
            body = f.read()
        req = urllib.request.Request(url, data=body, method="PUT", headers={"Content-Type": "application/octet-stream", "x-upsert": "true", "cache-control": "max-age=3600"})
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                r.read()
        except Exception as e:  # noqa: BLE001 - said plainly; the quota is the usual cause
            detail = getattr(e, "read", lambda: b"")()[:300].decode("utf-8", "replace") if hasattr(e, "read") else str(e)
            full = "quota" in detail.lower() or "policy" in detail.lower() or "security" in detail.lower()
            raise RuntimeError(f"save_data: couldn't save {name} to your Data library" + (": it's full (250 MB). Remove a dataset to make room." if full else f" ({detail or e}).")) from None
        print(f"Saved {name} to your Data library ({mb}).")
        return name
    # In the browser and on the phone the app uploads it once the run ends.
    if _session is None:
        raise RuntimeError("save_data works inside a cell's run.")
    _session.saved = [s for s in _session.saved if s["name"] != name]
    if len(_session.saved) >= MAX_SAVES:
        raise RuntimeError(f"save_data: a run can save up to {MAX_SAVES} files.")
    _session.saved.append({"name": name, "path": os.path.abspath(path)})
    print(f"{name} goes to your Data library when the run ends ({mb}).")
    return name


def _begin(out_dir, globals_, vectors):
    global _session, _cpu_noted
    _cpu_noted = False  # the CPU note is said once per run
    os.makedirs(out_dir, exist_ok=True)
    _session = _Session(out_dir, vectors)
    _prepare_matplotlib()
    _install_hooks()
    cwd = os.getcwd()
    _fetch_library(cwd)
    g = globals_ if globals_ is not None else {"__name__": "__main__"}
    g.setdefault("display", display)
    g.setdefault("log", log)
    g.setdefault("save_data", save_data)
    g.setdefault("input", _no_input)
    return cwd, _scan(cwd), g


def _fetch_library(cwd):
    """Server runs: the Data library files the code names (BJ_DATA, signed by the app) into the
    run's folder before the code starts, so open("iris.csv") finds them. Before the folder scan,
    so they aren't shown as the run's outputs. A file that fails says so and the run goes on."""
    raw = os.environ.get("BJ_DATA")
    if not raw:
        return
    import json, urllib.request
    try:
        files = json.loads(raw)
    except ValueError:
        return
    for f in files:
        name = os.path.basename(str(f.get("name", "")))
        if not name or os.path.exists(os.path.join(cwd, name)):
            continue
        try:
            with urllib.request.urlopen(f["url"], timeout=60) as r, open(os.path.join(cwd, name), "wb") as out:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
        except Exception as e:  # noqa: BLE001 - a missing file is reported, not fatal
            sys.stderr.write(f"Couldn't get {name} from your Data library: {e}\n")


INPUT_HINT = "input() can't wait for typing here: a cell runs on its own, with nobody at a prompt. Put the value in the code instead, e.g. name = \"Ada\"."


def _no_input(prompt=""):
    """input() in a cell: no one is there to type (browser, phone and server alike), so it says so plainly."""
    if prompt:
        print(prompt, end="")
    raise EOFError(INPUT_HINT)


MAGIC_LINE = re.compile(r"^(\s*)([%!].*)$")


def _without_magics(code):
    """Notebook code's IPython lines (%matplotlib inline, %%time, !pip install x) as no-ops, each
    on its own line so line numbers stay the same; None when the code has none."""
    lines = code.split("\n")
    out = [MAGIC_LINE.sub(r"\1pass  # \2", line) if MAGIC_LINE.match(line) else line for line in lines]
    return "\n".join(out) if out != lines else None


def _compile(code, filename):
    """The code without its last line when that is an expression (shown like a notebook cell's value), and that line."""
    try:
        tree = ast.parse(code, filename, "exec")
    except SyntaxError:
        # Only when the code isn't Python as written: a % or ! line in a string is left alone.
        plain = _without_magics(code)
        if plain is None:
            raise
        tree = ast.parse(plain, filename, "exec")
        if _session is not None:
            _session.note("Notebook-only lines (% and !) were skipped. Packages install by themselves here; to name one, write # pip: <name>.")
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


async def run(code, out_dir, filename="<exec>", globals_=None, vectors=False, on_code_done=None):
    """Runs `code` as __main__, captures its rich outputs into out_dir; returns the manifest dict.
    `vectors`: save figures' SVG and PDF now rather than keeping them for render().
    `on_code_done()`: called when the user's code has finished (or raised), before its figures and
    animations are drawn, so a runner's time limit can stop counting there.

    For runners inside an event loop (Pyodide). Exceptions from the user's code propagate
    (after the outputs made before them are saved), so each runner reports errors as before.
    """
    global _session
    cwd, before, g = _begin(out_dir, globals_, vectors)
    try:
        body, last = _compile(code, filename)
        r = eval(body, g)
        if _awaitable(r):
            await r
        if last is not None:
            value = eval(last, g)
            value = await value if _awaitable(value) else value
            _code_done(on_code_done)
            _emit(value)
        else:
            _code_done(on_code_done)
        return _finish_and_save(cwd, before)
    except BaseException:
        _code_done(on_code_done)
        _finish_and_save(cwd, before)
        raise
    finally:
        _session = None


def _code_done(callback):
    """Calls the runner's callback once (it may be a JS function in the browser); never fails the run."""
    if callback is not None and not getattr(_session, "code_done", False):
        _session.code_done = True
        try:
            callback()
        except Exception:
            pass


def run_sync(code, out_dir, filename="<exec>", globals_=None, vectors=False):
    """The same for runners without an event loop (server, Android): the code runs outside any
    loop, so it may call asyncio.run itself; only top-level await starts one."""
    import asyncio

    global _session
    cwd, before, g = _begin(out_dir, globals_, vectors)
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


def collect(command, out_dir):
    """Runs `command` (another language's program: R) with this process's stdout and
    stderr, then captures the image files it wrote under the working directory, as a Python run's
    written images are. Returns the command's exit code."""
    import subprocess

    global _session
    os.makedirs(out_dir, exist_ok=True)
    _session = _Session(out_dir)
    cwd = os.getcwd()
    before = _scan(cwd)
    try:
        status = subprocess.call(command)
        try:
            _written_images(cwd, before, out_dir)
        except Exception as e:
            _session.note(f"Couldn't capture every output: {e}")
        _session.save()
        return status
    finally:
        _session = None


def main(argv):
    """python3 bj_capture.py <file.py> <out_dir>: the server runner. Prints the bundle after BUNDLE_MARK.
    Figures come with their SVG and PDF: their kept form would need the server's matplotlib.
    python3 bj_capture.py --collect <out_dir> -- <command...>: the same for another language's program."""
    if len(argv) > 4 and argv[1] == "--collect" and argv[3] == "--":
        out_dir = argv[2]
        status = collect(argv[4:], out_dir)
        sys.stdout.flush()
        b = bundle(out_dir)
        if b is not None and json.loads(b)["manifest"]["items"]:
            sys.stdout.write("\n" + BUNDLE_MARK + b + "\n")
        sys.stdout.flush()
        return status
    path, out_dir = argv[1], argv[2]
    with open(path, encoding="utf-8") as f:
        code = f.read()
    status = 0
    sys.argv = [path]
    try:
        run_sync(code, out_dir, filename=path, vectors=True)
    except SystemExit as e:
        status = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
    except BaseException as e:
        text = user_traceback(e, path)
        sys.stderr.write(text + gpu_hint(text))
        status = 1
    sys.stdout.flush()
    b = bundle(out_dir)
    if b is not None:
        sys.stdout.write("\n" + BUNDLE_MARK + b + "\n")
    sys.stdout.flush()
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv))
