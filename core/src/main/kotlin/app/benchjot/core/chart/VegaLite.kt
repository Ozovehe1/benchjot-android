package app.benchjot.core.chart

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Vega-Lite spec (Altair's chart.to_dict()) -> Chart. Single views and layers with inline
// data (data.values or datasets): point / circle / square, line (+ point), area, bar
// (with count / sum / mean and bin), rect (heatmap), arc (pie), boxplot; split by a
// nominal color field. Facets, concatenation, repeat and transforms are UnsupportedChart.

fun fromVegaLite(spec: JsonElement): Chart {
    for (k in listOf("facet", "hconcat", "vconcat", "concat", "repeat")) if (spec[k] != null) throw UnsupportedChart("Vega-Lite $k views")
    val title = spec["title"].titleText()
    val layers = spec["layer"].arr()?.mapNotNull { it.obj() }?.map { inherit(spec, it) } ?: listOf(spec)
    val xa = AxisBuilder()
    val ya = AxisBuilder()
    val prepared = layers.map { layer ->
        if (layer["transform"] != null) throw UnsupportedChart("Vega-Lite transforms")
        val mark = layer["mark"].let { it.str() ?: it["type"].str() } ?: throw UnsupportedChart("a Vega-Lite view without a mark")
        val enc = layer["encoding"].obj() ?: JsonObject(emptyMap())
        val rows = data(spec, layer)
        Layer(mark, layer["mark"].obj(), enc, rows).also { it.seeAxes(xa, ya) }
    }
    if (prepared.any { it.mark == "arc" }) {
        if (prepared.size > 1) throw UnsupportedChart("Vega-Lite arcs layered with other marks")
        return Chart(title, series = listOf(prepared.single().pie()))
    }
    val series = mutableListOf<Series>()
    var colorIndex = 0
    for (l in prepared) series += l.series(xa, ya) { colorIndex++ }
    val x = prepared.first().enc["x"]
    val y = prepared.first().enc["y"]
    return Chart(title, xa.axis(axisTitle(x)), ya.axis(axisTitle(y), reversed = prepared.any { it.mark == "rect" } && ya.categorical), series)
}

private fun inherit(parent: JsonElement, layer: JsonObject): JsonObject {
    val enc = (parent["encoding"].obj() ?: JsonObject(emptyMap())) + (layer["encoding"].obj() ?: JsonObject(emptyMap()))
    return JsonObject(layer + mapOf("encoding" to JsonObject(enc)) + (if (layer["data"] == null && parent["data"] != null) mapOf("data" to parent["data"]!!) else emptyMap()))
}

private fun data(root: JsonElement, layer: JsonElement): List<JsonObject> {
    val d = layer["data"] ?: root["data"] ?: throw UnsupportedChart("a Vega-Lite chart without data")
    val rows = d["values"].arr() ?: d["name"].str()?.let { root.path("datasets", it).arr() } ?: throw UnsupportedChart("Vega-Lite data from a URL")
    return rows.mapNotNull { it.obj() }
}

private fun axisTitle(ch: JsonElement?): String {
    if (ch == null) return ""
    ch["title"]?.let { return it.text() }
    val agg = ch["aggregate"].str()
    val field = ch["field"].str().orEmpty()
    return when {
        agg == "count" -> "Count of Records"
        agg != null -> "${agg.replaceFirstChar(Char::titlecase)} of $field"
        ch["bin"] != null -> "$field (binned)"
        else -> field
    }
}

private class Layer(val mark: String, val markDef: JsonObject?, val enc: JsonObject, val rows: List<JsonObject>) {
    val x = enc["x"]
    val y = enc["y"]
    fun field(ch: JsonElement?) = ch["field"].str()
    fun type(ch: JsonElement?) = ch["type"].str() ?: if (ch["aggregate"] != null) "quantitative" else "nominal"
    fun discrete(ch: JsonElement?) = type(ch) in setOf("nominal", "ordinal") || (type(ch) == "temporal" && rows.any { r -> field(ch)?.let { r[it] }.let { it is JsonPrimitive && it.isString } })
    val colorField = enc["color"]?.let { field(it) }
    val colorQuant = enc["color"]?.let { type(it) == "quantitative" } ?: false
    val binned = x["bin"] != null && x["bin"] != JsonPrimitive(false)

    fun seeAxes(xa: AxisBuilder, ya: AxisBuilder) {
        if (mark == "arc" || binned) return
        if (discrete(x)) field(x)?.let { f -> xa.see(rows.mapNotNull { it[f] }.map { JsonPrimitive(it.text()) }) }
        if (discrete(y)) field(y)?.let { f -> ya.see(rows.mapNotNull { it[f] }.map { JsonPrimitive(it.text()) }) }
    }

    fun pie(): PieSeries {
        val theta = field(enc["theta"]) ?: throw UnsupportedChart("a Vega-Lite arc without theta")
        val label = colorField
        val groups = LinkedHashMap<String, Double>()
        for (r in rows) groups[label?.let { r[it].text() } ?: ""] = (groups[label?.let { r[it].text() } ?: ""] ?: 0.0) + (r[theta].num() ?: 0.0)
        return PieSeries("", groups.entries.mapIndexed { i, (k, v) -> Slice(k, v, VEGA_COLORS[i % VEGA_COLORS.size]) }, hole = ((markDef?.get("innerRadius").num() ?: 0.0) / 100.0).coerceIn(0.0, 0.9))
    }

    fun series(xa: AxisBuilder, ya: AxisBuilder, nextColor: () -> Int): List<Series> {
        val fixed = (enc["color"]?.get("value") ?: markDef?.get("color")).str()
        if (mark == "rect") return listOf(heatmap(xa, ya))
        if (mark == "boxplot") return boxes(xa, ya, nextColor)
        // Split into one series per color value (nominal), else one series.
        val groups: Map<String, List<JsonObject>> = if (colorField != null && !colorQuant) rows.groupBy { it[colorField].text() } else mapOf("" to rows)
        return groups.entries.map { (name, rs) ->
            val color = fixed?.let { colorOr(it, VEGA_COLORS[0]) } ?: VEGA_COLORS[nextColor() % VEGA_COLORS.size]
            when (mark) {
                "bar" -> bars(name, color, rs, xa, ya)
                "point", "circle", "square", "line", "area", "trail" -> xy(name, color, rs, xa, ya)
                else -> throw UnsupportedChart("Vega-Lite $mark marks")
            }
        }
    }

    private fun value(ch: JsonElement?, r: JsonObject, axis: AxisBuilder): Double = field(ch)?.let { f -> r[f]?.let { if (discrete(ch)) axis.pos(JsonPrimitive(it.text())) else it.num() } } ?: Double.NaN

    private fun xy(name: String, color: Int, rs: List<JsonObject>, xa: AxisBuilder, ya: AxisBuilder): XYSeries {
        val pts = rs.map { value(x, it, xa) to value(y, it, ya) }.filter { !it.first.isNaN() && !it.second.isNaN() }
            .let { if (mark == "line" || mark == "area") it.sortedBy { p -> p.first } else it }
        val xs = pts.map { it.first }.toDoubleArray()
        val ys = pts.map { it.second }.toDoubleArray()
        val withPoints = markDef?.get("point")?.let { it != JsonPrimitive(false) } ?: false
        return XYSeries(
            name, color, xs, ys,
            lines = mark == "line" || mark == "area" || mark == "trail",
            markers = mark !in setOf("line", "area", "trail") || withPoints,
            fillTo = if (mark == "area") DoubleArray(xs.size) else null,
            labels = pts.map { (a, b) -> "${label(x, a, xa)}, ${label(y, b, ya)}" },
            inLegend = name.isNotEmpty(),
        )
    }

    private fun label(ch: JsonElement?, v: Double, axis: AxisBuilder) = if (discrete(ch)) axis.categories.getOrElse(v.toInt()) { fmt(v) } else fmt(v)

    private fun aggregate(ch: JsonElement?, rs: List<JsonObject>): Double {
        val agg = ch["aggregate"].str()
        val f = field(ch)
        val vs = if (f != null) rs.mapNotNull { it[f].num() } else emptyList()
        return when (agg) {
            "count" -> rs.size.toDouble()
            "sum" -> vs.sum()
            "mean", "average" -> if (vs.isEmpty()) 0.0 else vs.average()
            "min" -> vs.minOrNull() ?: 0.0
            "max" -> vs.maxOrNull() ?: 0.0
            "median" -> if (vs.isEmpty()) 0.0 else boxStats(vs).median
            null -> vs.firstOrNull() ?: 0.0
            else -> throw UnsupportedChart("Vega-Lite aggregate $agg")
        }
    }

    private fun bars(name: String, color: Int, rs: List<JsonObject>, xa: AxisBuilder, ya: AxisBuilder): RectSeries {
        val tipName = name.ifEmpty { null }
        if (binned) {
            val f = field(x) ?: throw UnsupportedChart("a binned Vega-Lite x without a field")
            val bin = x["bin"]
            val (edges, _) = histogram(rs.mapNotNull { it[f].num() }, bin["maxbins"].num()?.toInt() ?: 10, size = bin["step"].num())
            val cells = (0 until edges.size - 1).mapNotNull { k ->
                val inBin = rs.filter { r -> r[f].num()?.let { v -> v >= edges[k] && (v < edges[k + 1] || (k == edges.size - 2 && v <= edges[k + 1])) } ?: false }
                val v = aggregate(y, inBin)
                Cell(edges[k], edges[k + 1], 0.0, v, color, listOfNotNull(tipName, "${fmt(edges[k])} – ${fmt(edges[k + 1])}", "${axisTitle(y)}: ${fmt(v)}"))
            }
            return RectSeries(name, color, cells, inLegend = name.isNotEmpty())
        }
        val horizontal = discrete(y) && !discrete(x)
        val cat = if (horizontal) y else x
        val axis = if (horizontal) ya else xa
        val groups = rs.groupBy { r -> field(cat)?.let { r[it].text() } ?: "" }
        val cells = groups.map { (k, g) ->
            val p = axis.pos(k)
            val v = aggregate(if (horizontal) x else y, g)
            val tip = listOfNotNull(tipName, "$k: ${fmt(v)}")
            if (horizontal) Cell(0.0, v, p - 0.4, p + 0.4, color, tip) else Cell(p - 0.4, p + 0.4, 0.0, v, color, tip)
        }
        return RectSeries(name, color, cells, inLegend = name.isNotEmpty())
    }

    private fun heatmap(xa: AxisBuilder, ya: AxisBuilder): RectSeries {
        val cf = colorField
        val vs = cf?.let { f -> rows.mapNotNull { it[f].num() } } ?: emptyList()
        val lo = vs.minOrNull() ?: 0.0
        val hi = vs.maxOrNull() ?: 1.0
        val cells = rows.map { r ->
            val cx = value(x, r, xa)
            val cy = value(y, r, ya)
            val v = cf?.let { r[it].num() } ?: Double.NaN
            val c = if (v.isNaN()) VEGA_COLORS[0] else colorAt(VIRIDIS, if (hi == lo) 0.5 else (v - lo) / (hi - lo))
            Cell(cx - 0.5, cx + 0.5, cy - 0.5, cy + 0.5, c, listOfNotNull("${field(x)}: ${r[field(x) ?: ""].text()}", "${field(y)}: ${r[field(y) ?: ""].text()}", cf?.let { "$it: ${fmt(v)}" }))
        }
        return RectSeries("", VEGA_COLORS[0], cells, inLegend = false, heatmap = true)
    }

    private fun boxes(xa: AxisBuilder, ya: AxisBuilder, nextColor: () -> Int): List<Series> {
        val horizontal = discrete(y) && !discrete(x)
        val cat = if (horizontal) y else x
        val num = field(if (horizontal) x else y) ?: throw UnsupportedChart("a Vega-Lite boxplot without a value field")
        val axis = if (horizontal) ya else xa
        val color = VEGA_COLORS[nextColor() % VEGA_COLORS.size]
        return rows.groupBy { r -> field(cat)?.let { r[it].text() } ?: "" }.map { (k, g) ->
            BoxSeries(k, color, axis.pos(k), boxStats(g.mapNotNull { it[num].num() }), horizontal)
        }
    }
}
