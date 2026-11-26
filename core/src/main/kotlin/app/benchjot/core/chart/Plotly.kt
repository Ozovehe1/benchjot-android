package app.benchjot.core.chart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Plotly figure JSON (fig.to_json()) -> Chart. Traces: scatter / scattergl (lines, markers,
// fill to zero or to the previous trace), bar (vertical / horizontal; group, stack, overlay),
// histogram, heatmap, box, pie, and scatter3d (Scatter3d.kt). Anything else is UnsupportedChart.

private val SUPPORTED = setOf("scatter", "scattergl", "bar", "histogram", "heatmap", "box", "pie", "scatter3d")

fun fromPlotly(spec: JsonElement): Chart {
    val traces = spec["data"].arr()?.mapNotNull { it.obj() } ?: throw UnsupportedChart("a Plotly figure without data")
    val layout = spec["layout"].obj()
    val types = traces.map { it["type"].str() ?: "scatter" }
    types.firstOrNull { it !in SUPPORTED }?.let { throw UnsupportedChart("Plotly $it traces") }
    val colorway = (layout["colorway"] ?: layout.path("template", "layout", "colorway")).arr()?.mapNotNull { it.str()?.let { s -> colorOr(s, 0) } }
        ?.takeIf { it.isNotEmpty() } ?: PLOTLY_COLORS
    val title = layout["title"].titleText()

    if (types.isNotEmpty() && types.all { it == "pie" }) {
        val t = traces.first()
        val labels = values(t["labels"]).map { it.text() }
        val vals = numbers(t["values"]).ifEmpty { List(labels.size) { 1.0 } }
        val colors = values(t.path("marker", "colors")).map { it.str() }
        return Chart(title, series = listOf(PieSeries(t["name"].text(), labels.indices.map { i -> Slice(labels[i], vals.getOrElse(i) { 0.0 }, colorOr(colors.getOrNull(i), colorway[i % colorway.size])) }, t["hole"].num() ?: 0.0)))
    }
    if ("pie" in types) throw UnsupportedChart("Plotly pies mixed with other traces")
    if (types.isNotEmpty() && types.all { it == "scatter3d" }) return plotly3d(traces, layout, title, colorway)
    if ("scatter3d" in types) throw UnsupportedChart("Plotly 3D traces mixed with 2D ones")

    val xa = AxisBuilder()
    val ya = AxisBuilder()
    // Every value each trace puts on each axis (horizontal bars keep their categories in y).
    fun horizontal(t: JsonElement) = t["orientation"].str() == "h"
    for ((i, t) in traces.withIndex()) {
        when (types[i]) {
            "heatmap" -> {
                val z = values(t["z"])
                (t["x"]?.let(::values) ?: List(z.firstOrNull().arr()?.size ?: 0) { JsonPrimitive(it) }).let(xa::see)
                (t["y"]?.let(::values) ?: List(z.size) { JsonPrimitive(it) }).let(ya::see)
            }
            "histogram" -> if (t["y"] != null && t["x"] == null) ya.see(values(t["y"])) else xa.see(values(t["x"]))
            "box" -> {
                val cat = t["name"]?.text()?.ifEmpty { null } ?: "trace $i"
                if (horizontal(t) || (t["x"] != null && t["y"] == null)) { xa.see(values(t["x"])); ya.see(values(t["y"]).ifEmpty { listOf(JsonPrimitive(cat)) }) }
                else { ya.see(values(t["y"])); xa.see(values(t["x"]).ifEmpty { listOf(JsonPrimitive(cat)) }) }
            }
            else -> {
                val (xs, ys) = xy(t)
                xa.see(xs); ya.see(ys)
            }
        }
    }
    val useX = xa

    val barmode = layout["barmode"].str() ?: "group"
    val bars = traces.indices.filter { types[it] == "bar" || types[it] == "histogram" }
    val series = mutableListOf<Series>()
    val hidden = mutableSetOf<Int>()
    val stackTop = HashMap<Double, Double>()
    var lastYs: DoubleArray? = null
    for ((i, t) in traces.withIndex()) {
        val name = t["name"].text().ifEmpty { if (traces.size > 1) "trace $i" else "" }
        val color = traceColor(t, colorway[i % colorway.size])
        val inLegend = t["showlegend"].bool() ?: (traces.size > 1)
        when (t["visible"]) {
            JsonPrimitive(false) -> continue
            JsonPrimitive("legendonly") -> hidden += series.size
            else -> {}
        }
        when (types[i]) {
            "scatter", "scattergl" -> {
                val (xv, yv) = xy(t)
                val xs = xv.map(useX::pos).toDoubleArray()
                val ys = yv.map(ya::pos).toDoubleArray()
                val mode = t["mode"].str() ?: if (xs.size < 20) "lines+markers" else "lines"
                val fill = t["fill"].str()
                val base = when (fill) {
                    "tozeroy" -> DoubleArray(ys.size)
                    "tonexty" -> lastYs?.takeIf { it.size == ys.size } ?: DoubleArray(ys.size)
                    null, "none" -> null
                    else -> throw UnsupportedChart("Plotly fill \"$fill\"")
                }
                val labels = xv.indices.map { k -> "${xv[k].text()}, ${yv[k].text()}" }
                series += XYSeries(name, color, xs, ys, lines = "lines" in mode, markers = "markers" in mode, fillTo = base, labels = labels, inLegend = inLegend)
                lastYs = ys
            }
            "bar" -> {
                val h = horizontal(t)
                val (xv, yv) = xy(t)
                val cats = if (h) yv else xv
                val vals = (if (h) xv else yv).map { it.num() ?: 0.0 }
                val catAxis = if (h) ya else useX
                val slot = bars.indexOf(i)
                val (width, offset) = barSlot(barmode, bars.size, slot, cats.map(catAxis::pos))
                val cells = cats.indices.map { k ->
                    val p = catAxis.pos(cats[k]) + offset
                    val base = if (barmode == "stack" || barmode == "relative") stackTop.getOrDefault(p - offset, 0.0) else 0.0
                    if (barmode == "stack" || barmode == "relative") stackTop[p - offset] = base + vals[k]
                    val tip = listOf(name.ifEmpty { catAxis.label(cats[k]) }, "${cats[k].text()}: ${fmt(vals[k])}")
                    if (h) Cell(base, base + vals[k], p - width / 2, p + width / 2, color, tip) else Cell(p - width / 2, p + width / 2, base, base + vals[k], color, tip)
                }
                series += RectSeries(name, color, cells, inLegend)
            }
            "histogram" -> {
                val onY = t["y"] != null && t["x"] == null
                val raw = numbers(if (onY) t["y"] else t["x"])
                val bins = if (onY) t["ybins"] else t["xbins"]
                val (edges, counts) = histogram(raw, (if (onY) t["nbinsy"] else t["nbinsx"]).num()?.toInt()?.takeIf { it > 0 }, bins["start"].num(), bins["end"].num(), bins["size"].num())
                val norm = t["histnorm"].str().orEmpty()
                val total = counts.sum().toDouble()
                val cells = counts.indices.map { k ->
                    val w = edges[k + 1] - edges[k]
                    val v = when (norm) {
                        "" -> counts[k].toDouble()
                        "percent" -> 100 * counts[k] / total
                        "probability" -> counts[k] / total
                        "density" -> counts[k] / w
                        "probability density" -> counts[k] / total / w
                        else -> throw UnsupportedChart("Plotly histnorm \"$norm\"")
                    }
                    val tip = listOf("${fmt(edges[k])} – ${fmt(edges[k + 1])}", "count: ${fmt(v)}")
                    if (onY) Cell(0.0, v, edges[k], edges[k + 1], color, tip) else Cell(edges[k], edges[k + 1], 0.0, v, color, tip)
                }
                series += RectSeries(name, color, cells, inLegend)
            }
            "heatmap" -> {
                val z = values(t["z"]).map { numbers(it) }
                val xv = t["x"]?.let(::values) ?: List(z.firstOrNull()?.size ?: 0) { JsonPrimitive(it) }
                val yv = t["y"]?.let(::values) ?: List(z.size) { JsonPrimitive(it) }
                val flat = z.flatten().filter { !it.isNaN() }
                val zmin = t["zmin"].num() ?: flat.minOrNull() ?: 0.0
                val zmax = t["zmax"].num() ?: flat.maxOrNull() ?: 1.0
                val scale = colorscale(t["colorscale"] ?: layout.path("coloraxis", "colorscale") ?: layout.path("template", "layout", "colorscale", "sequential"))
                val showText = t["texttemplate"].str() != null || t["text"] != null
                val cells = mutableListOf<Cell>()
                for ((r, row) in z.withIndex()) for ((c, v) in row.withIndex()) {
                    if (v.isNaN()) continue
                    val cx = useX.pos(xv.getOrElse(c) { JsonPrimitive(c) })
                    val cy = ya.pos(yv.getOrElse(r) { JsonPrimitive(r) })
                    val tcol = colorAt(scale, if (zmax == zmin) 0.5 else (v - zmin) / (zmax - zmin))
                    cells += Cell(cx - 0.5, cx + 0.5, cy - 0.5, cy + 0.5, tcol, listOf("x: ${xv.getOrNull(c).text()}", "y: ${yv.getOrNull(r).text()}", "z: ${fmt(v)}"), if (showText) fmt(v) else null)
                }
                series += RectSeries(name, color, cells, inLegend = false, heatmap = true)
            }
            "box" -> {
                val h = horizontal(t) || (t["x"] != null && t["y"] == null)
                val vals = numbers(if (h) t["x"] else t["y"])
                val cat = t["name"]?.text()?.ifEmpty { null } ?: "trace $i"
                val pos = (if (h) ya else useX).pos(cat)
                series += BoxSeries(name.ifEmpty { cat }, color, pos, boxStats(vals), horizontal = h)
            }
        }
    }
    val xTitle = layout.path("xaxis", "title").titleText()
    val yTitle = layout.path("yaxis", "title").titleText()
    val showLegend = layout["showlegend"].bool() ?: (series.count { it.inLegend } > 1)
    return Chart(
        title,
        useX.axis(xTitle, layout.path("xaxis", "autorange").str() == "reversed"),
        ya.axis(yTitle, layout.path("yaxis", "autorange").str() == "reversed"),
        series,
        showLegend = showLegend,
        hidden = hidden,
    )
}

/** A trace's x and y values; a missing one is 0, 1, 2, ... as Plotly does. */
private fun xy(t: JsonElement): Pair<List<JsonElement>, List<JsonElement>> {
    var xs = values(t["x"])
    var ys = values(t["y"])
    if (xs.isEmpty() && ys.isNotEmpty()) xs = List(ys.size) { JsonPrimitive(it) }
    if (ys.isEmpty() && xs.isNotEmpty() && t["orientation"].str() == "h") ys = List(xs.size) { JsonPrimitive(it) }
    val n = minOf(xs.size, ys.size)
    return xs.take(n) to ys.take(n)
}

private fun traceColor(t: JsonElement, fallback: Int): Int =
    t.path("marker", "color").str()?.let { colorOr(it, fallback) } ?: t.path("line", "color").str()?.let { colorOr(it, fallback) } ?: fallback

/** Width and offset of one bar trace in its slot (Plotly's barmode "group" splits 0.8 of each category). */
private fun barSlot(mode: String, count: Int, slot: Int, positions: List<Double>): Pair<Double, Double> {
    val gaps = positions.filter { !it.isNaN() }.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }
    val step = gaps.minOrNull() ?: 1.0
    val full = 0.8 * step
    if (mode != "group" || count <= 1) return full to 0.0
    val w = full / count
    return w to (-full / 2 + w * slot + w / 2)
}

/** A Plotly colorscale ([[t, color], ...] or a few names) as stops. */
internal fun colorscale(e: JsonElement?): List<Pair<Double, Int>> {
    val arr = e.arr() ?: return when (e.str()?.lowercase()) {
        "blues" -> listOf(0.0 to parseColor("#f7fbff"), 1.0 to parseColor("#08306b"))
        "greys", "gray" -> listOf(0.0 to parseColor("#ffffff"), 1.0 to parseColor("#000000"))
        else -> VIRIDIS
    }
    return arr.mapNotNull { stop ->
        val p = stop.arr() ?: return@mapNotNull null
        val t = p.getOrNull(0).num() ?: return@mapNotNull null
        t to colorOr(p.getOrNull(1).str(), 0xff808080.toInt())
    }.ifEmpty { VIRIDIS }
}
