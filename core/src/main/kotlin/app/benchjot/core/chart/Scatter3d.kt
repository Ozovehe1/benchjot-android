package app.benchjot.core.chart

import kotlinx.serialization.json.JsonElement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// 3D scatter (Plotly scatter3d: markers, lines or both) drawn natively: the data cube is
// normalized to [-1, 1] on each axis, turned by the camera (yaw about the vertical z axis,
// then pitch), and projected orthographically into the plot box. Points are painted back
// to front. Drag turns the camera, pinch zooms, tap shows a point's x / y / z, double-tap
// resets: the same gestures as the 2D charts, with rotation in place of panning.

/** One scatter3d trace. `colors`: per-point colours (a numeric marker.color through its colorscale). */
data class Scatter3DSeries(
    override val name: String,
    override val color: Int,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val zs: DoubleArray,
    val lines: Boolean = false,
    val markers: Boolean = true,
    val colors: IntArray? = null,
    val labels: List<String>? = null,
    override val inLegend: Boolean = true,
) : Series {
    init { require(xs.size == ys.size && ys.size == zs.size) { "x, y and z differ in length" } }
}

/** Where the 3D chart is looked at from (radians) and how close. Plotly's default eye is about (1.25, 1.25, 1.25). */
data class Camera(val yaw: Double = DEFAULT_YAW, val pitch: Double = DEFAULT_PITCH, val zoom: Double = 1.0) {
    companion object {
        const val DEFAULT_YAW = -0.785398 // -45°
        const val DEFAULT_PITCH = 0.6155 // ~35°, the (1, 1, 1) diagonal
    }
}

/** Turns the camera by a drag of (dx, dy) px; pitch stays between straight down and level. */
fun rotate(c: Camera, dx: Float, dy: Float, plot: Box): Camera {
    val k = Math.PI / max(plot.w, plot.h).coerceAtLeast(1f)
    return c.copy(yaw = c.yaw - dx * k, pitch = (c.pitch + dy * k).coerceIn(-1.5, 1.5))
}

fun zoom3d(c: Camera, factor: Double) = c.copy(zoom = (c.zoom * factor).coerceIn(0.4, 8.0))

/** Data ranges per axis over the visible 3D series: (lo, hi) for x, y, z. */
fun range3d(chart: Chart, hidden: Set<Int>): List<Pair<Double, Double>> {
    val lo = DoubleArray(3) { Double.POSITIVE_INFINITY }
    val hi = DoubleArray(3) { Double.NEGATIVE_INFINITY }
    for ((i, s) in chart.series.withIndex()) {
        if (i in hidden || s !is Scatter3DSeries) continue
        for ((a, vs) in listOf(s.xs, s.ys, s.zs).withIndex()) for (v in vs) if (v.isFinite()) { lo[a] = min(lo[a], v); hi[a] = max(hi[a], v) }
    }
    return (0..2).map { a ->
        when {
            lo[a] > hi[a] -> 0.0 to 1.0
            lo[a] == hi[a] -> lo[a] - 1 to hi[a] + 1
            else -> lo[a] to hi[a]
        }
    }
}

/** Projects data-normalized points (each in [-1, 1]) to screen px with depth (larger = nearer). */
class Projection(val camera: Camera, val plot: Box) {
    private val cy = cos(camera.yaw); private val sy = sin(camera.yaw)
    private val cp = cos(camera.pitch); private val sp = sin(camera.pitch)
    // The cube's diagonal is 2*sqrt(3); fit it in the smaller side, then zoom.
    private val scale = min(plot.w, plot.h) / 2f / 1.8f * camera.zoom.toFloat()
    private val ox = (plot.l + plot.r) / 2; private val oy = (plot.t + plot.b) / 2

    /** (screen x, screen y, depth) of a normalized point (x, y, z), z up. */
    fun at(x: Double, y: Double, z: Double): Triple<Float, Float, Double> {
        // yaw about z: x' along the screen, y' into the screen
        val x1 = x * cy - y * sy
        val y1 = x * sy + y * cy
        // pitch: tilt the view down onto the xy plane
        val up = z * cp - y1 * sp
        val depth = -(z * sp + y1 * cp)
        return Triple(ox + (x1 * scale).toFloat(), oy - (up * scale).toFloat(), depth)
    }
}

private fun norm(v: Double, r: Pair<Double, Double>) = (v - r.first) / (r.second - r.first) * 2 - 1

/** A 3D scene: the cube's back edges and grid, tick labels, axis titles, then the points back to front. */
internal fun layout3d(chart: Chart, width: Float, height: Float, theme: ChartTheme, m: Metrics, top: Float, camera: Camera, hidden: Set<Int>, hover: Hover?, compact: Boolean): Pair<List<Op>, Box> {
    val ops = mutableListOf<Op>()
    val plot = Box(m.pad, top, width - m.pad, height - m.pad)
    val p = Projection(camera, plot)
    val r = range3d(chart, hidden)
    val titles = listOf(chart.x.title.ifEmpty { "x" }, chart.y.title.ifEmpty { "y" }, chart.z.title.ifEmpty { "z" })

    // The 12 cube edges, muted; the three meeting at the farthest corner are the "walls".
    val corners = (0..7).map { i -> doubleArrayOf(if (i and 1 == 0) -1.0 else 1.0, if (i and 2 == 0) -1.0 else 1.0, if (i and 4 == 0) -1.0 else 1.0) }
    val far = corners.indices.minBy { p.at(corners[it][0], corners[it][1], corners[it][2]).third }
    for (a in 0..7) for (b in a + 1..7) {
        val diff = (0..2).count { corners[a][it] != corners[b][it] }
        if (diff != 1) continue
        val pa = p.at(corners[a][0], corners[a][1], corners[a][2]); val pb = p.at(corners[b][0], corners[b][1], corners[b][2])
        val back = a == far || b == far
        ops += Op.Line(floatArrayOf(pa.first, pa.second, pb.first, pb.second), if (back) theme.muted else theme.line, m.stroke * if (back) 0.6f else 0.5f)
    }
    // Ticks along one edge per axis (the edge nearest the viewer's lower side), with labels and titles.
    if (!compact) {
        for (axis in 0..2) {
            val (lo, hi) = r[axis]
            val ts = ticks(lo, hi, 4)
            // Of the 4 edges parallel to this axis, take the one lowest on screen (x / y) or leftmost (z).
            val others = (0..2).filter { it != axis }
            val choices = listOf(-1.0 to -1.0, -1.0 to 1.0, 1.0 to -1.0, 1.0 to 1.0).map { (u, v) ->
                val a = DoubleArray(3); a[others[0]] = u; a[others[1]] = v
                a
            }
            val edge = if (axis == 2) choices.minBy { p.at(it[0], it[1], 0.0).first } else choices.maxBy { p.at(it[0], it[1], it[2]).second + 0f }
            for (t in ts) {
                val q = edge.copyOf(); q[axis] = norm(t, r[axis])
                val s = p.at(q[0], q[1], q[2])
                ops += Op.Text(s.first + if (axis == 2) -m.pad else 0f, s.second + if (axis == 2) m.font * 0.36f else m.font + m.tick, fmt(t), m.font * 0.9f, theme.muted, if (axis == 2) Align.Right else Align.Center)
            }
            val mid = edge.copyOf(); mid[axis] = 0.0
            val s = p.at(mid[0], mid[1], mid[2])
            ops += Op.Text(s.first + if (axis == 2) -m.pad * 5 else 0f, s.second + if (axis == 2) 0f else m.font * 2.4f + m.tick, titles[axis], m.font, theme.fg, if (axis == 2) Align.Right else Align.Center)
        }
    }

    // Points and lines, back to front.
    data class Dot(val x: Float, val y: Float, val depth: Double, val color: Int)
    val dots = mutableListOf<Dot>()
    for ((i, s) in chart.series.withIndex()) {
        if (i in hidden || s !is Scatter3DSeries) continue
        val pts = s.xs.indices.map { k -> p.at(norm(s.xs[k], r[0]), norm(s.ys[k], r[1]), norm(s.zs[k], r[2])) }
        if (s.lines && pts.size > 1) {
            val flat = FloatArray(pts.size * 2); pts.forEachIndexed { k, q -> flat[2 * k] = q.first; flat[2 * k + 1] = q.second }
            ops += Op.Line(flat, s.color, m.stroke)
        }
        if (s.markers || pts.size == 1) pts.forEachIndexed { k, q -> dots += Dot(q.first, q.second, q.third, s.colors?.getOrNull(k) ?: s.color) }
    }
    for (d in dots.sortedBy { it.depth }) {
        // Nearer points a little larger, as a perspective cue.
        val zf = (1 + 0.12 * d.depth).toFloat().coerceIn(0.8f, 1.25f)
        ops += Op.Circle(d.x, d.y, m.marker * zf, d.color)
    }
    hover?.let { ops += Op.Circle(it.x, it.y, m.marker * 1.9f, withAlpha(theme.fg, 0.35f)) }
    return ops to plot
}

/** The nearest visible point to a tap, in screen px (the nearer of two equally close ones). */
fun hitTest3d(chart: Chart, scene: Scene, camera: Camera, hidden: Set<Int>, px: Float, py: Float, density: Float = 1f): Hover? {
    val m = Metrics(density)
    val p = Projection(camera, scene.plot)
    val r = range3d(chart, hidden)
    var best: Hover? = null
    var bestD = m.slop
    var bestDepth = Double.NEGATIVE_INFINITY
    val titles = listOf(chart.x.title.ifEmpty { "x" }, chart.y.title.ifEmpty { "y" }, chart.z.title.ifEmpty { "z" })
    for ((i, s) in chart.series.withIndex()) {
        if (i in hidden || s !is Scatter3DSeries) continue
        for (k in s.xs.indices) {
            val q = p.at(norm(s.xs[k], r[0]), norm(s.ys[k], r[1]), norm(s.zs[k], r[2]))
            val d = hypot(q.first - px, q.second - py)
            if (d < bestD - 0.5f || (abs(d - bestD) <= 0.5f && q.third > bestDepth)) {
                bestD = d; bestDepth = q.third
                val lines = listOfNotNull(s.name.ifEmpty { null }, s.labels?.getOrNull(k),
                    "${titles[0]}: ${fmt(s.xs[k])}", "${titles[1]}: ${fmt(s.ys[k])}", "${titles[2]}: ${fmt(s.zs[k])}")
                best = Hover(i, k, lines, q.first, q.second)
            }
        }
    }
    return best
}

/** Plotly scatter3d traces (all of the figure's traces) -> a 3D Chart. */
internal fun plotly3d(traces: List<JsonElement>, layout: JsonElement?, title: String, colorway: List<Int>): Chart {
    val scene = layout.path("scene")
    fun axisTitle(a: String) = scene.path(a, "title").titleText()
    val series = traces.mapIndexed { i, t ->
        val xs = numbers(t["x"]); val ys = numbers(t["y"]); val zs = numbers(t["z"])
        val n = minOf(xs.size, ys.size, zs.size)
        val mode = t["mode"].str() ?: if (n <= 20) "lines+markers" else "markers"
        val base = t.path("marker", "color").str()?.let { colorOr(it, colorway[i % colorway.size]) }
            ?: t.path("line", "color").str()?.let { colorOr(it, colorway[i % colorway.size]) } ?: colorway[i % colorway.size]
        // A numeric marker.color: each point's colour through the trace's colorscale.
        val numeric = numbers(t.path("marker", "color")).take(n).takeIf { it.size == n && n > 0 && it.none(Double::isNaN) }
        val colors = numeric?.let { vs ->
            val lo = t.path("marker", "cmin").num() ?: vs.min(); val hi = t.path("marker", "cmax").num() ?: vs.max()
            val stops = colorscale(t.path("marker", "colorscale"))
            IntArray(n) { k -> colorAt(stops, if (hi > lo) (vs[k] - lo) / (hi - lo) else 0.5) }
        }
        val text = values(t["text"]).map { it.text() }.takeIf { it.size == n }
        Scatter3DSeries(t["name"].text(), base, xs.take(n).toDoubleArray(), ys.take(n).toDoubleArray(), zs.take(n).toDoubleArray(),
            lines = "lines" in mode, markers = "markers" in mode, colors = colors, labels = text, inLegend = t["showlegend"].bool() != false)
    }
    return Chart(title, Axis(axisTitle("xaxis")), Axis(axisTitle("yaxis")), series, z = Axis(axisTitle("zaxis")))
}
