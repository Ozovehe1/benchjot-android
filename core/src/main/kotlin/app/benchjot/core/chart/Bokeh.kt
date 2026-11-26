package app.benchjot.core.chart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Bokeh json_item() -> Chart. One figure; glyphs Line, Scatter / Circle, VBar, HBar, Quad,
// Rect (heatmaps), VArea, Wedge (pies). Data from ColumnDataSources (lists or numpy
// ndarrays, gzip or not). Layouts (rows, columns, grids), widgets and other glyphs are
// UnsupportedChart.

/** Bokeh's default glyph colour and the Category10 colours after it. */
private val BOKEH_COLORS = listOf("#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf").map(::parseColor)
private val GLYPHS = setOf("Line", "Scatter", "Circle", "VBar", "HBar", "Quad", "Rect", "VArea", "Wedge")

private class Doc(root: JsonElement) {
    val byId = HashMap<String, JsonObject>()
    init { index(root) }
    private fun index(e: JsonElement?) {
        when (e) {
            is JsonObject -> {
                if (e["type"].str() == "object" && e["id"].str() != null && e["attributes"] != null) byId[e["id"].str()!!] = e
                e.values.forEach(::index)
            }
            is kotlinx.serialization.json.JsonArray -> e.forEach(::index)
            else -> {}
        }
    }
    /** An object or a {"id": ...} reference to one. */
    fun resolve(e: JsonElement?): JsonObject? {
        val o = e.obj() ?: return null
        if (o["attributes"] == null && o["id"].str() != null && o.size == 1) return byId[o["id"].str()]
        return o
    }
    fun attrs(e: JsonElement?) = resolve(e)?.get("attributes").obj() ?: JsonObject(emptyMap())
}

fun fromBokeh(item: JsonElement): Chart {
    val roots = item.path("doc", "roots").arr() ?: throw UnsupportedChart("a Bokeh item without a document")
    val doc = Doc(item)
    val fig = roots.mapNotNull { doc.resolve(it) }.firstOrNull() ?: throw UnsupportedChart("an empty Bokeh document")
    if (fig["name"].str() != "Figure") throw UnsupportedChart("Bokeh ${fig["name"].str()} layouts")
    val a = fig["attributes"].obj()!!
    val title = doc.attrs(a["title"])["text"].str().orEmpty()
    fun axisLabel(side: String) = a[side].arr()?.firstNotNullOfOrNull { doc.attrs(it)["axis_label"].str() }.orEmpty()
    val xFactors = doc.attrs(a["x_range"])["factors"].arr()?.map { it.text() }
    val yFactors = doc.attrs(a["y_range"])["factors"].arr()?.map { it.text() }
    val xa = AxisBuilder(forceCategory = xFactors != null).apply { xFactors?.let { see(it.map(::JsonPrimitive)) } }
    val ya = AxisBuilder(forceCategory = yFactors != null).apply { yFactors?.let { see(it.map(::JsonPrimitive)) } }

    // Legend labels by renderer id.
    val names = HashMap<String, String>()
    for (c in a["center"].arr().orEmpty()) {
        val legend = doc.resolve(c) ?: continue
        if (legend["name"].str() != "Legend") continue
        for (li in legend.path("attributes", "items").arr().orEmpty()) {
            val la = doc.attrs(li)
            val label = la["label"].let { it["value"].str() ?: it.str() ?: it["field"].str() }.orEmpty()
            for (r in la["renderers"].arr().orEmpty()) r["id"].str()?.let { names[it] = label }
        }
    }

    // Glyphs map their values once every glyph has been seen, so category axes know all factors.
    val series = mutableListOf<() -> Series>()
    var auto = 0
    for (rr in a["renderers"].arr().orEmpty()) {
        val r = doc.resolve(rr) ?: continue
        if (r["name"].str() != "GlyphRenderer") throw UnsupportedChart("Bokeh ${r["name"].str()} renderers")
        val ra = r["attributes"].obj()!!
        if (ra["visible"] == JsonPrimitive(false)) continue
        val glyph = doc.resolve(ra["glyph"]) ?: continue
        val kind = glyph["name"].str().orEmpty()
        if (kind !in GLYPHS) throw UnsupportedChart("Bokeh $kind glyphs")
        val g = glyph["attributes"].obj()!!
        val data = columns(doc.attrs(ra["data_source"])["data"])
        val name = names[r["id"].str()].orEmpty()
        val fallback = BOKEH_COLORS[auto++ % BOKEH_COLORS.size]
        val n = data.values.maxOfOrNull { it.size } ?: 1
        fun spec(key: String): List<JsonElement> {
            val v = g[key] ?: return emptyList()
            v["field"].str()?.let { return data[it] ?: emptyList() }
            val value = v["value"] ?: v
            return List(n) { value }
        }
        fun color(key: String): List<Int> = spec(key).map { e -> e.str()?.let { colorOr(it, fallback) } ?: fallback }.ifEmpty { listOf(fallback) }
        val fill = color("fill_color")
        val main = if (kind == "Line") color("line_color").first() else fill.first()
        val legend = name.isNotEmpty()
        fun nums(key: String) = spec(key).map { it.num() ?: Double.NaN }

        when (kind) {
            "Line", "Scatter", "Circle" -> {
                val xs = spec("x"); val ys = spec("y")
                xa.see(xs); ya.see(ys)
                series += { XYSeries(name, main, xs.map(xa::pos).toDoubleArray(), ys.map(ya::pos).toDoubleArray(), lines = kind == "Line", markers = kind != "Line", labels = xs.indices.map { "${xs[it].text()}, ${ys[it].text()}" }, inLegend = legend) }
            }
            "VArea" -> {
                val xs = spec("x"); val y1 = nums("y1"); val y2 = nums("y2")
                xa.see(xs)
                series += { XYSeries(name, main, xs.map(xa::pos).toDoubleArray(), y2.toDoubleArray(), lines = true, fillTo = y1.toDoubleArray(), labels = xs.indices.map { "${xs[it].text()}, ${fmt(y2[it])}" }, inLegend = legend) }
            }
            "VBar", "HBar" -> {
                val h = kind == "HBar"
                val cats = spec(if (h) "y" else "x"); val top = nums(if (h) "right" else "top")
                val bottom = nums(if (h) "left" else "bottom").ifEmpty { List(cats.size) { 0.0 } }
                val width = nums(if (h) "height" else "width").ifEmpty { List(cats.size) { 0.8 } }
                (if (h) ya else xa).see(cats)
                series += {
                    val axis = if (h) ya else xa
                    RectSeries(name, main, cats.indices.map { k ->
                        val p = axis.pos(cats[k]); val w = width.getOrElse(k) { 0.8 } / 2; val b = bottom.getOrElse(k) { 0.0 }.let { if (it.isNaN()) 0.0 else it }
                        val tip = listOfNotNull(name.ifEmpty { null }, "${cats[k].text()}: ${fmt(top[k])}")
                        if (h) Cell(b, top[k], p - w, p + w, fill.getOrElse(k) { main }, tip) else Cell(p - w, p + w, b, top[k], fill.getOrElse(k) { main }, tip)
                    }, legend)
                }
            }
            "Quad" -> {
                val l = nums("left"); val rgt = nums("right"); val t = nums("top"); val b = nums("bottom")
                series += { RectSeries(name, main, l.indices.map { k -> Cell(l[k], rgt[k], b.getOrElse(k) { 0.0 }, t[k], fill.getOrElse(k) { main }, listOfNotNull(name.ifEmpty { null }, "${fmt(l[k])} – ${fmt(rgt[k])}", "top: ${fmt(t[k])}")) }, legend) }
            }
            "Rect" -> {
                val xs = spec("x"); val ys = spec("y"); val w = nums("width"); val h = nums("height")
                xa.see(xs); ya.see(ys)
                series += {
                    RectSeries(name, main, xs.indices.map { k ->
                        val cx = xa.pos(xs[k]); val cy = ya.pos(ys[k]); val hw = w.getOrElse(k) { 1.0 } / 2; val hh = h.getOrElse(k) { 1.0 } / 2
                        Cell(cx - hw, cx + hw, cy - hh, cy + hh, fill.getOrElse(k) { main }, listOf("x: ${xs[k].text()}", "y: ${ys[k].text()}"))
                    }, legend, heatmap = true)
                }
            }
            "Wedge" -> {
                val s = nums("start_angle"); val e = nums("end_angle")
                val labels = (g["legend_field"].str()?.let { data[it] } ?: emptyList()).map { it.text() }
                series += { PieSeries(name, s.indices.map { k -> Slice(labels.getOrElse(k) { names.values.elementAtOrNull(k) ?: "${k + 1}" }, (e[k] - s[k]).let { if (it < 0) it + 2 * Math.PI else it } / (2 * Math.PI) * 100, fill.getOrElse(k) { main }) }) }
            }
        }
    }
    if (series.isEmpty()) throw UnsupportedChart("a Bokeh figure without glyphs")
    val built = series.map { it() }
    if (built.any { it is PieSeries } && built.any { it !is PieSeries }) throw UnsupportedChart("Bokeh wedges mixed with other glyphs")
    return Chart(title, xa.axis(axisLabel("below")), ya.axis(axisLabel("left")), built, showLegend = built.count { it.inLegend } > 1 || built.any { it is PieSeries })
}

/** A ColumnDataSource's data: {"type": "map", "entries": [[name, values], ...]} or a plain object. */
private fun columns(data: JsonElement?): Map<String, List<JsonElement>> {
    val entries = data["entries"].arr()
    if (entries != null) return entries.mapNotNull { e -> e.arr()?.let { it[0].text() to values(it[1]) } }.toMap()
    return data.obj()?.mapValues { values(it.value) } ?: emptyMap()
}
