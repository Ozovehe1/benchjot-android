package app.benchjot.core.chart

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Lays a Chart out at a pixel size into draw ops (lines, rects, circles, text, wedges),
// with the view's data range, hidden series and the tapped point's tooltip. The app draws
// the ops on a Canvas (screen and PNG / JPG / WebP export); toSvg writes them as SVG. Pure,
// so layout, ticks, zoom and hit testing are unit-tested on the JVM.

sealed interface Op {
    data class Line(val pts: FloatArray, val color: Int, val width: Float, val dash: Boolean = false) : Op
    data class Fill(val pts: FloatArray, val color: Int) : Op
    data class Rect(val l: Float, val t: Float, val r: Float, val b: Float, val color: Int, val stroke: Int? = null, val radius: Float = 0f) : Op
    data class Circle(val x: Float, val y: Float, val r: Float, val color: Int) : Op
    data class Text(val x: Float, val y: Float, val text: String, val size: Float, val color: Int, val align: Align = Align.Left, val bold: Boolean = false, val rotate: Boolean = false) : Op
    /** A pie slice (degrees, clockwise from 12 o'clock), with an optional hole. */
    data class Wedge(val cx: Float, val cy: Float, val r: Float, val start: Float, val sweep: Float, val color: Int, val hole: Float = 0f, val gap: Int? = null) : Op
    data class Clip(val l: Float, val t: Float, val r: Float, val b: Float) : Op
    data object Unclip : Op
}

enum class Align { Left, Center, Right }

/** The app's colours (charcoal / ash), so charts sit on the note like the web's themed ones. */
data class ChartTheme(val bg: Int, val fg: Int, val muted: Int, val line: Int)

/** The data range shown; null axes keep their full range. */
data class Viewport(val x0: Double, val x1: Double, val y0: Double, val y1: Double)

data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun contains(x: Float, y: Float, slop: Float = 0f) = x >= l - slop && x <= r + slop && y >= t - slop && y <= b + slop
}

/** What a tap found: the series and point, the lines its tooltip shows, and where. */
data class Hover(val series: Int, val index: Int, val lines: List<String>, val x: Float, val y: Float)

data class Scene(val ops: List<Op>, val plot: Box, val legend: List<Pair<Box, Int>>, val width: Float, val height: Float)

/** Sizes in px for a given screen density (1 on the JVM, 2.6 on a typical phone). */
class Metrics(density: Float) {
    val font = 11f * density
    val title = 13f * density
    val pad = 8f * density
    val tick = 4f * density
    val stroke = 1.5f * density
    val marker = 3.2f * density
    val legendRow = 24f * density
    val swatch = 10f * density
    /** A tap within this of a point counts (half the 48 dp target). */
    val slop = 24f * density
    fun textWidth(s: String, size: Float = font) = s.length * size * 0.58f
}

/** The full data range of the visible series, padded like Plotly's autorange; bars include 0. */
fun fullRange(chart: Chart, hidden: Set<Int>): Viewport {
    var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY
    var y0 = Double.POSITIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
    var bars = false
    fun x(v: Double) { if (!v.isNaN() && v.isFinite()) { x0 = min(x0, v); x1 = max(x1, v) } }
    fun y(v: Double) { if (!v.isNaN() && v.isFinite()) { y0 = min(y0, v); y1 = max(y1, v) } }
    var tight = false
    for ((i, s) in chart.series.withIndex()) {
        if (i in hidden) continue
        when (s) {
            is XYSeries -> { s.xs.forEach(::x); s.ys.forEach(::y); s.fillTo?.forEach(::y) }
            is RectSeries -> { bars = bars || !s.heatmap; tight = tight || s.heatmap; s.cells.forEach { x(it.x0); x(it.x1); y(it.y0); y(it.y1) } }
            is BoxSeries -> {
                val vs = listOf(s.stats.min, s.stats.max) + s.stats.outliers
                if (s.horizontal) { vs.forEach(::x); y(s.pos - 0.5); y(s.pos + 0.5) } else { vs.forEach(::y); x(s.pos - 0.5); x(s.pos + 0.5) }
            }
            is PieSeries, is Scatter3DSeries -> {}
        }
    }
    if (chart.x.kind == AxisKind.Category && chart.x.categories.isNotEmpty()) { x(-0.5); x(chart.x.categories.size - 0.5) }
    if (chart.y.kind == AxisKind.Category && chart.y.categories.isNotEmpty()) { y(-0.5); y(chart.y.categories.size - 0.5) }
    if (x0 > x1) { x0 = 0.0; x1 = 1.0 }
    if (y0 > y1) { y0 = 0.0; y1 = 1.0 }
    if (x0 == x1) { x0 -= 1; x1 += 1 }
    if (y0 == y1) { y0 -= 1; y1 += 1 }
    val catX = chart.x.kind == AxisKind.Category
    val catY = chart.y.kind == AxisKind.Category
    val px = if (catX || tight) 0.0 else (x1 - x0) * 0.04
    val py = if (catY || tight) 0.0 else (y1 - y0) * 0.06
    return Viewport(x0 - px, x1 + px, y0 - (if (bars && y0 >= 0) 0.0 else py), y1 + py)
}

/** Round tick values between lo and hi, about `count` of them. */
fun ticks(lo: Double, hi: Double, count: Int): List<Double> {
    if (!(hi > lo)) return listOf(lo)
    val step = niceStep(hi - lo, count)
    val first = ceil(lo / step - 1e-9) * step
    val out = mutableListOf<Double>()
    var v = first
    while (v <= hi + step * 1e-9 && out.size < 50) { out += if (abs(v) < step * 1e-9) 0.0 else v; v += step }
    return out
}

/** Zooms the view by `factor` (>1 zooms in) around the pixel (px, py) of the plot box; axis = "x", "y" or "xy". */
fun zoom(v: Viewport, plot: Box, factor: Double, px: Float, py: Float, axis: String = "xy"): Viewport {
    val fx = ((px - plot.l) / plot.w).toDouble().coerceIn(0.0, 1.0)
    val fy = ((plot.b - py) / plot.h).toDouble().coerceIn(0.0, 1.0)
    val cx = v.x0 + (v.x1 - v.x0) * fx
    val cy = v.y0 + (v.y1 - v.y0) * fy
    val zx = if ('x' in axis) factor else 1.0
    val zy = if ('y' in axis) factor else 1.0
    return Viewport(cx - (cx - v.x0) / zx, cx + (v.x1 - cx) / zx, cy - (cy - v.y0) / zy, cy + (v.y1 - cy) / zy)
}

/** Pans the view by a drag of (dx, dy) pixels (the content follows the finger). */
fun pan(v: Viewport, plot: Box, dx: Float, dy: Float, flipX: Boolean = false, flipY: Boolean = false): Viewport {
    val sx = (v.x1 - v.x0) / plot.w * dx * (if (flipX) -1 else 1)
    val sy = (v.y1 - v.y0) / plot.h * dy * (if (flipY) -1 else 1)
    return Viewport(v.x0 - sx, v.x1 - sx, v.y0 + sy, v.y1 + sy)
}

private class Map2(val v: Viewport, val p: Box, val revX: Boolean, val revY: Boolean) {
    fun x(d: Double): Float { val f = ((d - v.x0) / (v.x1 - v.x0)).toFloat(); return if (revX) p.r - f * p.w else p.l + f * p.w }
    fun y(d: Double): Float { val f = ((d - v.y0) / (v.y1 - v.y0)).toFloat(); return if (revY) p.t + f * p.h else p.b - f * p.h }
}

internal fun withAlpha(c: Int, a: Float) = (((c ushr 24) * a).toInt().coerceIn(0, 255) shl 24) or (c and 0xffffff)

/** Lays out and draws the chart. `view` null shows the full range. */
fun layout(chart: Chart, width: Float, height: Float, theme: ChartTheme, density: Float = 1f, view: Viewport? = null, hidden: Set<Int> = chart.hidden, hover: Hover? = null, compact: Boolean = false, camera: Camera = Camera()): Scene {
    val m = Metrics(density)
    val ops = mutableListOf<Op>()
    var top = m.pad
    if (chart.title.isNotEmpty()) {
        ops += Op.Text(m.pad, top + m.title, chart.title, m.title, theme.fg, bold = true)
        top += m.title + m.pad * 1.5f
    }
    // Legend: rows of swatch + name, wrapping, under the title.
    val legend = mutableListOf<Pair<Box, Int>>()
    val entries: List<Triple<Int, String, Int>> = when {
        !chart.showLegend -> emptyList()
        chart.isPie -> (chart.series.first() as PieSeries).slices.mapIndexed { i, s -> Triple(i, s.label, s.color) }
        else -> chart.series.withIndex().filter { it.value.inLegend }.map { (i, s) -> Triple(i, s.name.ifEmpty { "trace $i" }, s.color) }
    }
    if (entries.isNotEmpty()) {
        var x = m.pad
        var rowTop = top
        for ((i, name, color) in entries) {
            val w = m.swatch + m.pad * 0.6f + m.textWidth(name) + m.pad * 1.6f
            if (x + w > width - m.pad && x > m.pad) { x = m.pad; rowTop += m.legendRow }
            val off = i in hidden
            val cy = rowTop + m.legendRow / 2
            ops += Op.Rect(x, cy - m.swatch / 2, x + m.swatch, cy + m.swatch / 2, if (off) withAlpha(color, 0.25f) else color, radius = m.swatch * 0.2f)
            ops += Op.Text(x + m.swatch + m.pad * 0.6f, cy + m.font * 0.36f, name, m.font, if (off) theme.muted else theme.fg)
            legend += Box(x, rowTop, x + w, rowTop + m.legendRow) to i
            x += w
        }
        top = rowTop + m.legendRow + m.pad * 0.5f
    }

    if (chart.is3d) {
        val (data, plot) = layout3d(chart, width, height, theme, m, top, camera, hidden, hover, compact)
        ops += data
        hover?.let { tooltip(ops, it, m, theme, Box(0f, 0f, width, height)) }
        return Scene(ops, plot, legend, width, height)
    }

    if (chart.isPie) {
        val pie = chart.series.first() as PieSeries
        val r = min(width - 2 * m.pad, height - top - m.pad) / 2
        val cx = width / 2
        val cy = top + (height - top) / 2
        val plot = Box(cx - r, cy - r, cx + r, cy + r)
        val shown = pie.slices.withIndex().filter { it.index !in hidden }
        val total = shown.sumOf { it.value.value }.takeIf { it > 0 } ?: 1.0
        var start = 0f
        for ((i, s) in shown) {
            val sweep = (s.value / total * 360).toFloat()
            val lifted = hover?.index == i
            ops += Op.Wedge(cx, cy, if (lifted) r * 1.03f else r, start, sweep, s.color, (pie.hole * r).toFloat(), gap = theme.bg)
            if (sweep > 18 && !compact) {
                val mid = Math.toRadians((start + sweep / 2 - 90).toDouble())
                val lr = r * (if (pie.hole > 0) (1 + pie.hole.toFloat()) / 2 else 0.62f)
                ops += Op.Text(cx + (lr * cos(mid)).toFloat(), cy + (lr * sin(mid)).toFloat() + m.font * 0.36f, "${fmt(s.value / total * 100)}%", m.font, 0xffffffff.toInt(), Align.Center)
            }
            start += sweep
        }
        hover?.let { tooltip(ops, it, m, theme, Box(0f, 0f, width, height)) }
        return Scene(ops, plot, legend, width, height)
    }

    val v = view ?: fullRange(chart, hidden)
    val xTicks = axisTicks(chart.x, v.x0, v.x1, width / (60 * density))
    val yTicks = axisTicks(chart.y, v.y0, v.y1, (height - top) / (36 * density))
    val yLabelW = (yTicks.maxOfOrNull { m.textWidth(it.second) } ?: 0f).coerceAtMost(width * 0.3f)
    val left = m.pad + (if (chart.y.title.isNotEmpty()) m.font + m.pad else 0f) + yLabelW + m.tick + m.pad * 0.5f
    val bottom = height - m.pad - m.font - m.tick - m.pad * 0.5f - (if (chart.x.title.isNotEmpty()) m.font + m.pad else 0f)
    val plot = Box(left, top, width - m.pad * 1.5f, bottom.coerceAtLeast(top + 20f))
    val map = Map2(v, plot, chart.x.reversed, chart.y.reversed)

    // Grid, ticks and labels
    for ((value, label) in yTicks) {
        val y = map.y(value)
        if (y < plot.t - 0.5f || y > plot.b + 0.5f) continue
        ops += Op.Line(floatArrayOf(plot.l, y, plot.r, y), theme.line, density * 0.8f)
        ops += Op.Text(plot.l - m.tick - m.pad * 0.5f, y + m.font * 0.36f, label, m.font, theme.muted, Align.Right)
    }
    val xLabels = mutableListOf<Pair<Float, String>>()
    for ((value, label) in xTicks) {
        val x = map.x(value)
        if (x < plot.l - 0.5f || x > plot.r + 0.5f) continue
        ops += Op.Line(floatArrayOf(x, plot.t, x, plot.b), theme.line, density * 0.8f)
        xLabels += x to label
    }
    // Skip x labels that would overlap.
    var lastRight = Float.NEGATIVE_INFINITY
    for ((x, label) in xLabels) {
        val w = m.textWidth(label)
        if (x - w / 2 < lastRight + m.pad) continue
        ops += Op.Text(x, plot.b + m.tick + m.font + m.pad * 0.25f, label, m.font, theme.muted, Align.Center)
        lastRight = x + w / 2
    }
    ops += Op.Line(floatArrayOf(plot.l, plot.b, plot.r, plot.b), theme.muted, density)
    ops += Op.Line(floatArrayOf(plot.l, plot.t, plot.l, plot.b), theme.muted, density)
    if (chart.x.title.isNotEmpty()) ops += Op.Text(plot.l + plot.w / 2, height - m.pad, chart.x.title, m.font, theme.fg, Align.Center)
    if (chart.y.title.isNotEmpty()) ops += Op.Text(m.pad + m.font, plot.t + plot.h / 2, chart.y.title, m.font, theme.fg, Align.Center, rotate = true)

    // Data, clipped to the plot
    ops += Op.Clip(plot.l, plot.t, plot.r, plot.b)
    for ((i, s) in chart.series.withIndex()) {
        if (i in hidden) continue
        when (s) {
            is RectSeries -> for (c in s.cells) {
                val l = min(map.x(c.x0), map.x(c.x1)); val r = max(map.x(c.x0), map.x(c.x1))
                val t = min(map.y(c.y0), map.y(c.y1)); val b = max(map.y(c.y0), map.y(c.y1))
                val gap = if (s.heatmap) 0f else density * 0.5f
                ops += Op.Rect(l + gap, t, r - gap, b, c.color)
                if (s.heatmap && c.text != null && r - l > m.textWidth(c.text) + 2) {
                    ops += Op.Text((l + r) / 2, (t + b) / 2 + m.font * 0.36f, c.text, m.font, contrast(c.color), Align.Center)
                }
            }
            is XYSeries -> {
                val pts = FloatArray(s.xs.size * 2)
                for (k in s.xs.indices) { pts[2 * k] = map.x(s.xs[k]); pts[2 * k + 1] = map.y(s.ys[k]) }
                s.fillTo?.let { base ->
                    val poly = FloatArray(s.xs.size * 4)
                    for (k in s.xs.indices) { poly[2 * k] = pts[2 * k]; poly[2 * k + 1] = pts[2 * k + 1] }
                    for (k in s.xs.indices) { val j = s.xs.size - 1 - k; poly[2 * (s.xs.size + k)] = map.x(s.xs[j]); poly[2 * (s.xs.size + k) + 1] = map.y(base[j]) }
                    ops += Op.Fill(poly, withAlpha(s.color, 0.35f))
                }
                if (s.lines && s.xs.size > 1) ops += Op.Line(pts, s.color, m.stroke)
                if (s.markers || s.xs.size == 1) for (k in s.xs.indices) ops += Op.Circle(pts[2 * k], pts[2 * k + 1], m.marker, s.color)
            }
            is BoxSeries -> box(ops, s, map, m, theme)
            is PieSeries, is Scatter3DSeries -> {}
        }
    }
    hover?.let { h ->
        ops += Op.Circle(h.x, h.y, m.marker * 1.9f, withAlpha(theme.fg, 0.35f))
    }
    ops += Op.Unclip
    hover?.let { tooltip(ops, it, m, theme, Box(0f, 0f, width, height)) }
    return Scene(ops, plot, legend, width, height)
}

private fun box(ops: MutableList<Op>, s: BoxSeries, map: Map2, m: Metrics, theme: ChartTheme) {
    val st = s.stats
    val half = 0.3
    fun seg(a: Double, b: Double, c: Double, d: Double) =
        if (s.horizontal) floatArrayOf(map.x(a), map.y(b), map.x(c), map.y(d)) else floatArrayOf(map.x(b), map.y(a), map.x(d), map.y(c))
    // Box q1..q3, median, whiskers, outliers
    val p = s.pos
    val (l, t, r, b) = if (s.horizontal) listOf(map.x(st.q1), map.y(p + half), map.x(st.q3), map.y(p - half)) else listOf(map.x(p - half), map.y(st.q3), map.x(p + half), map.y(st.q1))
    ops += Op.Rect(min(l, r), min(t, b), max(l, r), max(t, b), withAlpha(s.color, 0.35f), stroke = s.color)
    ops += Op.Line(seg(st.median, p - half, st.median, p + half), s.color, m.stroke * 1.4f)
    ops += Op.Line(seg(st.q3, p, st.max, p), s.color, m.stroke)
    ops += Op.Line(seg(st.q1, p, st.min, p), s.color, m.stroke)
    ops += Op.Line(seg(st.max, p - half / 2, st.max, p + half / 2), s.color, m.stroke)
    ops += Op.Line(seg(st.min, p - half / 2, st.min, p + half / 2), s.color, m.stroke)
    for (o in st.outliers) {
        val x = if (s.horizontal) map.x(o) else map.x(p)
        val y = if (s.horizontal) map.y(p) else map.y(o)
        ops += Op.Circle(x, y, m.marker, s.color)
    }
}

private fun tooltip(ops: MutableList<Op>, h: Hover, m: Metrics, theme: ChartTheme, bounds: Box) {
    val w = (h.lines.maxOfOrNull { m.textWidth(it) } ?: 0f) + m.pad * 2
    val hgt = h.lines.size * (m.font * 1.35f) + m.pad * 1.4f
    var x = h.x + m.pad
    var y = h.y - hgt - m.pad
    if (x + w > bounds.r - 2) x = h.x - w - m.pad
    if (x < bounds.l + 2) x = bounds.l + 2
    if (y < bounds.t + 2) y = h.y + m.pad
    if (y + hgt > bounds.b - 2) y = bounds.b - hgt - 2
    ops += Op.Rect(x, y, x + w, y + hgt, theme.bg, stroke = theme.muted, radius = m.pad * 0.5f)
    for ((i, line) in h.lines.withIndex()) {
        ops += Op.Text(x + m.pad, y + m.pad * 0.7f + m.font * (1.35f * i + 1f), line, m.font, theme.fg, bold = i == 0 && h.lines.size > 1)
    }
}

/** Black or white text, whichever reads on the cell. */
private fun contrast(c: Int): Int {
    val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
    return if (0.299 * r + 0.587 * g + 0.114 * b > 150) 0xff161616.toInt() else 0xffffffff.toInt()
}

private fun axisTicks(a: Axis, lo: Double, hi: Double, room: Float): List<Pair<Double, String>> {
    if (a.kind == AxisKind.Category) {
        val from = max(0, ceil(lo - 1e-9).toInt())
        val to = min(a.categories.size - 1, floor(hi + 1e-9).toInt())
        return (from..to).map { it.toDouble() to a.categories[it] }
    }
    return ticks(lo, hi, room.toInt().coerceIn(2, 10)).map { it to fmt(it) }
}

/** The data point (or bar / cell / slice) nearest a tap, within reach; null when none. */
fun hitTest(chart: Chart, scene: Scene, view: Viewport?, hidden: Set<Int>, px: Float, py: Float, density: Float = 1f, camera: Camera = Camera()): Hover? {
    if (chart.is3d) return hitTest3d(chart, scene, camera, hidden, px, py, density)
    val m = Metrics(density)
    if (chart.isPie) {
        val pie = chart.series.first() as PieSeries
        val cx = (scene.plot.l + scene.plot.r) / 2; val cy = (scene.plot.t + scene.plot.b) / 2; val r = scene.plot.w / 2
        val d = hypot(px - cx, py - cy)
        if (d > r || d < pie.hole * r) return null
        val angle = ((Math.toDegrees(atan2((py - cy).toDouble(), (px - cx).toDouble())) + 90 + 360) % 360)
        val shown = pie.slices.withIndex().filter { it.index !in hidden }
        val total = shown.sumOf { it.value.value }.takeIf { it > 0 } ?: return null
        var start = 0.0
        for ((i, s) in shown) {
            val sweep = s.value / total * 360
            if (angle >= start && angle < start + sweep) return Hover(0, i, listOf(s.label, "${fmt(s.value)} (${fmt(s.value / total * 100)}%)"), px, py)
            start += sweep
        }
        return null
    }
    if (!scene.plot.contains(px, py, m.slop)) return null
    val v = view ?: fullRange(chart, hidden)
    val map = Map2(v, scene.plot, chart.x.reversed, chart.y.reversed)
    var best: Hover? = null
    var bestD = m.slop
    for ((i, s) in chart.series.withIndex()) {
        if (i in hidden) continue
        when (s) {
            is XYSeries -> for (k in s.xs.indices) {
                val x = map.x(s.xs[k]); val y = map.y(s.ys[k])
                val d = hypot(x - px, y - py)
                if (d < bestD) {
                    bestD = d
                    val lines = listOfNotNull(s.name.ifEmpty { null }, s.labels?.getOrNull(k) ?: "${fmt(s.xs[k])}, ${fmt(s.ys[k])}")
                    best = Hover(i, k, lines, x, y)
                }
            }
            is RectSeries -> for ((k, c) in s.cells.withIndex()) {
                val l = min(map.x(c.x0), map.x(c.x1)); val r = max(map.x(c.x0), map.x(c.x1))
                val t = min(map.y(c.y0), map.y(c.y1)); val b = max(map.y(c.y0), map.y(c.y1))
                if (px in l..r && py in t..b && (best == null || bestD > 0f)) {
                    bestD = 0f
                    best = Hover(i, k, c.tip, (l + r) / 2, if (s.heatmap) (t + b) / 2 else t)
                }
            }
            is BoxSeries -> {
                val cx = if (s.horizontal) px else map.x(s.pos)
                val cy = if (s.horizontal) map.y(s.pos) else py
                val reach = if (s.horizontal) abs(py - cy) else abs(px - cx)
                val halfPx = abs(if (s.horizontal) map.y(s.pos + 0.3) - map.y(s.pos) else map.x(s.pos + 0.3) - map.x(s.pos))
                if (reach <= max(halfPx, m.slop / 2) && reach < bestD) {
                    bestD = reach
                    val st = s.stats
                    best = Hover(i, 0, listOf(s.name, "max: ${fmt(st.max)}", "q3: ${fmt(st.q3)}", "median: ${fmt(st.median)}", "q1: ${fmt(st.q1)}", "min: ${fmt(st.min)}"), if (s.horizontal) map.x(st.median) else cx, if (s.horizontal) cy else map.y(st.median))
                }
            }
            is PieSeries, is Scatter3DSeries -> {}
        }
    }
    return best
}

/** The legend entry at a tap (series index, or slice index for a pie), or null. */
fun legendAt(scene: Scene, px: Float, py: Float, density: Float = 1f): Int? {
    val reach = Metrics(density).legendRow / 2 // rows are 24 dp; with this the tap target is 48 dp tall
    return scene.legend.firstOrNull { (b, _) -> px >= b.l && px <= b.r && py >= b.t - reach / 2 && py <= b.b + reach / 2 }?.second
}

/** Plain-text description of the chart for screen readers and tests. */
fun describe(chart: Chart, hidden: Set<Int> = chart.hidden): String {
    val parts = chart.series.withIndex().filter { it.index !in hidden }.map { (_, s) ->
        when (s) {
            is XYSeries -> "${s.name.ifEmpty { "series" }}: ${s.xs.size} points"
            is RectSeries -> "${s.name.ifEmpty { if (s.heatmap) "heatmap" else "bars" }}: ${s.cells.size} ${if (s.heatmap) "cells" else "bars"}"
            is BoxSeries -> "box ${s.name}"
            is PieSeries -> "pie: ${s.slices.size} slices"
            is Scatter3DSeries -> "${s.name.ifEmpty { "series" }}: ${s.xs.size} points in 3D"
        }
    }
    return listOfNotNull(chart.title.ifEmpty { null }, parts.joinToString("; ")).joinToString(". ")
}
