package app.benchjot.core.chart

// One normalized chart model that every interactive spec (Plotly, Vega-Lite, Bokeh) maps
// into, drawn natively (no WebView) by Scene + the app's canvas. Data space: numbers, with
// categories at 0, 1, 2, ... on a category axis. Colors are ARGB.

enum class AxisKind { Linear, Category }

data class Axis(
    val title: String = "",
    val kind: AxisKind = AxisKind.Linear,
    val categories: List<String> = emptyList(),
    /** Larger values at the bottom / left (Plotly's autorange "reversed", heatmap rows). */
    val reversed: Boolean = false,
)

sealed interface Series {
    val name: String
    val color: Int
    /** Listed in the legend (and so can be hidden by tapping it). */
    val inLegend: Boolean
}

/** Points joined by lines and/or drawn as markers; `fillTo` fills down to a baseline (area). */
data class XYSeries(
    override val name: String,
    override val color: Int,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val lines: Boolean = true,
    val markers: Boolean = false,
    /** Baseline per point for an area (zero, or the series below for stacked / tonexty). */
    val fillTo: DoubleArray? = null,
    val labels: List<String>? = null,
    override val inLegend: Boolean = true,
) : Series {
    init { require(xs.size == ys.size) { "x and y differ in length" } }
}

/** A filled cell in data space: a bar, a histogram bin, a heatmap cell. */
data class Cell(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val color: Int, val tip: List<String>, val text: String? = null)

/** Bars, histogram bins or heatmap cells. */
data class RectSeries(
    override val name: String,
    override val color: Int,
    val cells: List<Cell>,
    override val inLegend: Boolean = true,
    /** Heatmap: no gaps, value text in each cell. */
    val heatmap: Boolean = false,
) : Series

data class BoxStats(val min: Double, val q1: Double, val median: Double, val q3: Double, val max: Double, val outliers: List<Double>)

/** One box (and whiskers) at `pos` on the category axis; horizontal swaps the axes. */
data class BoxSeries(
    override val name: String,
    override val color: Int,
    val pos: Double,
    val stats: BoxStats,
    val horizontal: Boolean = false,
    override val inLegend: Boolean = false,
) : Series

data class Slice(val label: String, val value: Double, val color: Int)

/** A pie (or donut, with `hole` 0..1). A chart with a pie has no axes. */
data class PieSeries(override val name: String, val slices: List<Slice>, val hole: Double = 0.0, override val color: Int = slices.firstOrNull()?.color ?: 0, override val inLegend: Boolean = false) : Series

data class Chart(
    val title: String = "",
    val x: Axis = Axis(),
    val y: Axis = Axis(),
    val series: List<Series>,
    val showLegend: Boolean = series.count { it.inLegend } > 1 || series.any { it is PieSeries },
    /** Series hidden at first (Plotly's visible: "legendonly"). */
    val hidden: Set<Int> = emptySet(),
    /** The vertical axis of a 3D chart (Scatter3d.kt); unused in 2D. */
    val z: Axis = Axis(),
) {
    val isPie get() = series.isNotEmpty() && series.all { it is PieSeries }
    val is3d get() = series.isNotEmpty() && series.all { it is Scatter3DSeries }
}

/** A spec the renderer can't draw yet: the app shows the stored static image instead and says why. */
class UnsupportedChart(message: String) : Exception(message)

sealed interface ChartResult {
    data class Ok(val chart: Chart) : ChartResult
    data class Unsupported(val reason: String) : ChartResult
}

/** Maps a saved spec (the manifest's chart `lib` + its JSON) into the model. */
fun mapChart(lib: String, specJson: String): ChartResult = try {
    val spec = kotlinx.serialization.json.Json.parseToJsonElement(specJson)
    ChartResult.Ok(
        when (lib) {
            "plotly" -> fromPlotly(spec)
            "vega-lite" -> fromVegaLite(spec)
            "bokeh" -> fromBokeh(spec)
            else -> throw UnsupportedChart("$lib charts")
        },
    )
} catch (e: UnsupportedChart) {
    ChartResult.Unsupported(e.message ?: "this chart")
} catch (e: Exception) {
    ChartResult.Unsupported("this spec (${e.message ?: e::class.simpleName})")
}

// ------------------------------------------------------------------ shared helpers
// (NAMED first: the colour lists below are built with parseColor at class init.)
private val NAMED = mapOf(
    "black" to "#000000", "white" to "#ffffff", "red" to "#ff0000", "green" to "#008000", "blue" to "#0000ff", "navy" to "#000080",
    "gray" to "#808080", "grey" to "#808080", "lightgrey" to "#d3d3d3", "lightgray" to "#d3d3d3", "orange" to "#ffa500",
    "purple" to "#800080", "firebrick" to "#b22222", "steelblue" to "#4682b4", "olive" to "#808000", "teal" to "#008080",
    "crimson" to "#dc143c", "gold" to "#ffd700", "pink" to "#ffc0cb", "brown" to "#a52a2a", "cyan" to "#00ffff", "magenta" to "#ff00ff",
    "yellow" to "#ffff00", "darkblue" to "#00008b", "darkred" to "#8b0000", "darkgreen" to "#006400", "lightblue" to "#add8e6",
    "skyblue" to "#87ceeb", "tomato" to "#ff6347", "coral" to "#ff7f50", "indigo" to "#4b0082", "salmon" to "#fa8072",
    "seagreen" to "#2e8b57", "orchid" to "#da70d6", "limegreen" to "#32cd32", "darkorange" to "#ff8c00", "royalblue" to "#4169e1",
)
/** Plotly's default colorway, also Bokeh's / matplotlib's tab10 family for unnamed series. */
val PLOTLY_COLORS = listOf("#636efa", "#EF553B", "#00cc96", "#ab63fa", "#FFA15A", "#19d3f3", "#FF6692", "#B6E880", "#FF97FF", "#FECB52").map(::parseColor)
/** Vega's default categorical scheme ("tableau10"). */
val VEGA_COLORS = listOf("#4c78a8", "#f58518", "#e45756", "#72b7b2", "#54a24b", "#eeca3b", "#b279a2", "#ff9da6", "#9d755d", "#bab0ac").map(::parseColor)
/** Viridis, Vega's default for quantitative color, as stops. */
val VIRIDIS = listOf(0.0 to "#440154", 0.25 to "#3b528b", 0.5 to "#21918c", 0.75 to "#5ec962", 1.0 to "#fde725").map { it.first to parseColor(it.second) }


/** "#rgb", "#rrggbb", "#rrggbbaa", "rgb(r,g,b)", "rgba(r,g,b,a)" or a common CSS name, as ARGB. Throws on anything else. */
fun parseColor(s: String): Int {
    val v = s.trim().lowercase()
    NAMED[v]?.let { return parseColor(it) }
    if (v.startsWith("#")) {
        val h = v.drop(1)
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("") + "ff"
            6 -> h + "ff"
            8 -> h
            else -> throw IllegalArgumentException("bad color $s")
        }
        val n = full.toLong(16)
        return (((n and 0xff) shl 24) or (n shr 8)).toInt()
    }
    val m = Regex("""rgba?\(([^)]*)\)""").matchEntire(v) ?: throw IllegalArgumentException("bad color $s")
    val p = m.groupValues[1].split(",").map { it.trim() }
    val (r, g, b) = p.take(3).map { it.removeSuffix("%").toDouble().toInt().coerceIn(0, 255) }
    val a = p.getOrNull(3)?.toDouble()?.let { Math.round(it * 255).toInt().coerceIn(0, 255) } ?: 255
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

fun colorOr(s: String?, fallback: Int): Int = s?.let { runCatching { parseColor(it) }.getOrNull() } ?: fallback

/** Linear interpolation along color stops (t in 0..1). */
fun colorAt(stops: List<Pair<Double, Int>>, t: Double): Int {
    if (stops.isEmpty()) return 0xff808080.toInt()
    val x = t.coerceIn(0.0, 1.0)
    val i = stops.indexOfFirst { it.first >= x }.let { if (it <= 0) 1 else it }.coerceAtMost(stops.size - 1)
    val (t0, c0) = stops[(i - 1).coerceAtLeast(0)]
    val (t1, c1) = stops[i]
    val f = if (t1 == t0) 0.0 else (x - t0) / (t1 - t0)
    fun ch(c: Int, s: Int) = (c shr s) and 0xff
    fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * f).toInt().coerceIn(0, 255)
    return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/** Number as Plotly / Vega show it in a tooltip: integers without ".0", others to 4 significant digits. */
fun fmt(v: Double): String {
    if (v.isNaN()) return ""
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
    val s = String.format(java.util.Locale.ROOT, "%.4g", v)
    return if (s.contains('e')) s else s.trimEnd('0').trimEnd('.')
}

/** Quartiles as Plotly's and Vega's boxplots compute them (linear interpolation), whiskers at 1.5 IQR. */
fun boxStats(values: List<Double>): BoxStats {
    val v = values.filter { !it.isNaN() }.sorted()
    require(v.isNotEmpty()) { "empty box" }
    fun q(p: Double): Double {
        val pos = p * (v.size - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        return v[lo] + (v[hi] - v[lo]) * (pos - lo)
    }
    val q1 = q(0.25)
    val q3 = q(0.75)
    val iqr = q3 - q1
    val inside = v.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }
    return BoxStats(inside.first(), q1, q(0.5), q3, inside.last(), v.filter { it < q1 - 1.5 * iqr || it > q3 + 1.5 * iqr })
}

/** Equal-width bins over [min, max] (Sturges' rule when no count is given); returns (edges, counts). */
fun histogram(values: List<Double>, bins: Int? = null, start: Double? = null, end: Double? = null, size: Double? = null): Pair<DoubleArray, IntArray> {
    val v = values.filter { !it.isNaN() }
    require(v.isNotEmpty()) { "empty histogram" }
    val lo = start ?: v.min()
    var hi = end ?: v.max()
    val step = when {
        size != null && size > 0 -> size
        else -> {
            val n = bins ?: (kotlin.math.ceil(kotlin.math.log2(v.size.toDouble())) + 1).toInt()
            niceStep((hi - lo).takeIf { it > 0 } ?: 1.0, n.coerceAtLeast(1))
        }
    }
    val first = kotlin.math.floor(lo / step) * step
    if (hi <= first) hi = first + step
    // The last bin includes the maximum (as Vega's bins do).
    val count = kotlin.math.ceil((hi - first) / step - 1e-9).toInt().coerceIn(1, 10_000)
    val edges = DoubleArray(count + 1) { first + it * step }
    val counts = IntArray(count)
    for (x in v) {
        val i = kotlin.math.floor((x - first) / step).toInt().coerceIn(0, count - 1)
        counts[i]++
    }
    return edges to counts
}

/** A 1 / 2 / 2.5 / 5 × 10^k step giving about `count` intervals over `span`. */
fun niceStep(span: Double, count: Int): Double {
    val raw = span / count.coerceAtLeast(1)
    val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val f = raw / mag
    val nice = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}
