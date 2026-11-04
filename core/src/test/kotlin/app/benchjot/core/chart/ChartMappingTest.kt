package app.benchjot.core.chart

import app.benchjot.core.Fixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every chart spec bj_capture saves on the web (Plotly, Altair / Vega-Lite, Bokeh) maps into the one model. */
class ChartMappingTest {
    private fun chart(case: String): Chart {
        val item = Fixtures.json("outputs/$case/manifest.json").jsonObject.getValue("items").jsonArray[0].jsonObject
        val lib = item.getValue("lib").jsonPrimitive.content
        val file = item.getValue("files").jsonObject.getValue("json").jsonPrimitive.content
        return when (val r = mapChart(lib, Fixtures.text("outputs/$case/$file"))) {
            is ChartResult.Ok -> r.chart
            is ChartResult.Unsupported -> throw AssertionError("$case: unsupported ${r.reason}")
        }
    }
    private inline fun <reified T : Series> only(c: Chart): List<T> = c.series.filterIsInstance<T>().also { assertEquals(c.series.size, it.size) }
    private fun d(vararg v: Double) = v

    @Test fun plotlyScatterLinesMarkersLegendTitles() {
        val c = chart("plotly-scatter")
        assertEquals("Loss", c.title); assertEquals("epoch", c.x.title); assertEquals("loss", c.y.title)
        val (train, v) = only<XYSeries>(c)
        assertEquals("train", train.name); assertTrue(train.lines && train.markers)
        assertEquals("val", v.name); assertTrue(!v.lines && v.markers)
        assertArrayEquals(d(10.0, 11.0, 12.0, 9.0), train.ys, 0.0)
        assertTrue(c.showLegend)
    }

    @Test fun plotlyExpressWideFormAndNumpyTypedArrays() {
        val c = chart("plotly-express")
        assertEquals(listOf("loss", "val"), only<XYSeries>(c).map { it.name })
        assertArrayEquals(d(0.9, 0.6, 0.45, 0.4, 0.38), c.series[0].let { (it as XYSeries).ys }, 1e-12)
        val n = only<XYSeries>(chart("plotly-numpy")).single()
        assertArrayEquals(d(0.0, 0.25, 0.5, 0.75, 1.0), n.xs, 1e-12)
        assertArrayEquals(d(0.0, 0.0625, 0.25, 0.5625, 1.0), n.ys, 1e-12)
    }

    @Test fun plotlyBarsVerticalAndHorizontalOnCategoryAxes() {
        val c = chart("plotly-bar")
        assertEquals(AxisKind.Category, c.x.kind); assertEquals(listOf("a", "b", "c"), c.x.categories)
        assertEquals(listOf(3.0, 1.0, 2.0), only<RectSeries>(c).single().cells.map { it.y1 })
        val h = chart("plotly-hbar")
        assertEquals(listOf("a", "b"), h.y.categories)
        assertEquals(listOf(2.0, 5.0), only<RectSeries>(h).single().cells.map { it.x1 })
    }

    @Test fun plotlyHistogramCountsEveryValue() {
        val cells = only<RectSeries>(chart("plotly-histogram")).single().cells
        assertEquals(9.0, cells.sumOf { it.y1 }, 0.0)
        assertTrue(cells.zipWithNext().all { (a, b) -> a.x1 == b.x0 })
    }

    @Test fun plotlyConfusionMatrixHeatmap() {
        val c = chart("plotly-heatmap")
        assertEquals("Confusion matrix", c.title)
        assertEquals(listOf("cat", "dog", "owl"), c.x.categories)
        assertTrue(c.y.reversed)
        val s = only<RectSeries>(c).single()
        assertTrue(s.heatmap); assertEquals(9, s.cells.size)
        assertEquals(listOf("5", "1", "0", "2", "7", "1", "0", "1", "9"), s.cells.map { it.text })
        assertTrue(s.cells.first().color != s.cells[1].color)
    }

    @Test fun plotlyBoxQuartilesAndOutliers() {
        val (a, b) = only<BoxSeries>(chart("plotly-box"))
        assertEquals("A", a.name); assertEquals(listOf(9.0), a.stats.outliers); assertEquals(2.5, a.stats.median, 0.0)
        assertEquals(3.0, b.stats.median, 0.0); assertEquals(1.0, b.pos - a.pos, 0.0)
    }

    @Test fun plotlyAreaAndPie() {
        val area = only<XYSeries>(chart("plotly-area")).single()
        assertArrayEquals(d(0.0, 0.0, 0.0), area.fillTo, 0.0)
        val pie = chart("plotly-pie")
        assertTrue(pie.isPie)
        assertEquals(listOf("a" to 50.0, "b" to 30.0, "c" to 20.0), (pie.series.single() as PieSeries).slices.map { it.label to it.value })
    }

    @Test fun plotlyScatter3dMapsToA3dChart() {
        // The web's "plotly-unsupported" fixture is a scatter3d, which Android now draws natively.
        val r = mapChart("plotly", Fixtures.text("outputs/plotly-unsupported/0.json"))
        val c = (r as ChartResult.Ok).chart
        assertTrue(c.is3d)
        val s = c.series.single() as Scatter3DSeries
        assertArrayEquals(d(1.0, 2.0), s.zs, 0.0)
    }

    @Test fun plotlyUnknownTracesStayUnsupportedWithTheirName() {
        val r = mapChart("plotly", """{"data":[{"type":"surface","z":[[1,2],[3,4]]}],"layout":{}}""")
        assertTrue(r is ChartResult.Unsupported && r.reason.contains("surface"))
    }

    @Test fun plotlyScatter3dNumericColorsAndTitles() {
        val spec = """{"data":[{"type":"scatter3d","mode":"markers","name":"pts","x":[0,1,2],"y":[0,1,4],"z":[1,2,3],"marker":{"color":[0,5,10]}}],
            "layout":{"scene":{"xaxis":{"title":{"text":"a"}},"yaxis":{"title":{"text":"b"}},"zaxis":{"title":{"text":"c"}}}}}"""
        val c = (mapChart("plotly", spec) as ChartResult.Ok).chart
        assertEquals(listOf("a", "b", "c"), listOf(c.x.title, c.y.title, c.z.title))
        val s = c.series.single() as Scatter3DSeries
        assertTrue(s.markers && !s.lines)
        assertEquals(3, s.colors!!.size)
        assertTrue(s.colors[0] != s.colors[2])
    }

    @Test fun vegaLineScatterByColorBar() {
        val line = chart("altair-line")
        assertEquals("Loss", line.title); assertEquals("epoch", line.x.title)
        val s = only<XYSeries>(line).single(); assertTrue(s.lines && s.markers)
        val byColor = only<XYSeries>(chart("altair-scatter-color"))
        assertEquals(listOf("loss", "val"), byColor.map { it.name }); assertTrue(byColor.all { !it.lines && it.markers })
        val bar = chart("altair-bar")
        assertEquals(listOf("a", "b", "c"), bar.x.categories)
        assertEquals(listOf(3.0, 1.0, 2.0), only<RectSeries>(bar).single().cells.map { it.y1 })
    }

    @Test fun vegaHistogramHeatmapAreaPieBoxplot() {
        val hist = only<RectSeries>(chart("altair-histogram")).single().cells
        assertEquals(9.0, hist.sumOf { it.y1 }, 0.0)
        val heat = chart("altair-heatmap")
        assertEquals(4, only<RectSeries>(heat).single().cells.size); assertTrue(heat.y.reversed)
        assertTrue(only<XYSeries>(chart("altair-area")).single().fillTo != null)
        val pie = (chart("altair-pie").series.single() as PieSeries).slices
        assertEquals(listOf("a" to 70.0, "b" to 30.0), pie.map { it.label to it.value })
        val boxes = only<BoxSeries>(chart("altair-boxplot"))
        assertEquals(listOf("a", "b"), boxes.map { it.name }); assertEquals(listOf(9.0), boxes[0].stats.outliers)
    }

    @Test fun bokehLineScatterLegendAndColors() {
        val c = chart("bokeh-line")
        assertEquals("Loss", c.title); assertEquals("epoch", c.x.title)
        val (line, pts) = only<XYSeries>(c)
        assertEquals("train", line.name); assertTrue(line.lines && !line.markers); assertEquals(parseColor("#1f77b4"), line.color)
        assertEquals("val", pts.name); assertTrue(pts.markers); assertEquals(parseColor("#ff7f0e"), pts.color)
    }

    @Test fun bokehNumpyGzipArrays() {
        val s = only<XYSeries>(chart("bokeh-numpy")).single()
        assertArrayEquals(d(0.0, 1.0, 2.0, 3.0), s.xs, 0.0); assertArrayEquals(d(0.0, 2.0, 4.0, 6.0), s.ys, 0.0)
    }

    @Test fun bokehBarsQuadsRectsAreaWedges() {
        val bar = chart("bokeh-vbar")
        assertEquals(listOf("a", "b", "c"), bar.x.categories)
        assertEquals(listOf(3.0, 1.0, 2.0), only<RectSeries>(bar).single().cells.map { it.y1 })
        assertEquals(listOf(2.0, 5.0, 1.0), only<RectSeries>(chart("bokeh-histogram")).single().cells.map { it.y1 })
        val heat = only<RectSeries>(chart("bokeh-heatmap")).single()
        assertEquals(4, heat.cells.size); assertEquals(parseColor("#08306b"), heat.cells[0].color)
        assertArrayEquals(d(1.0, 3.0, 2.0), only<XYSeries>(chart("bokeh-area")).single().ys, 0.0)
        val pie = (chart("bokeh-pie").series.single() as PieSeries).slices
        assertEquals(listOf(50.0, 50.0), pie.map { it.value })
    }

    @Test fun everyCapturedChartMaps() {
        val cases = Fixtures.json("output_formats.json").jsonObject.keys.filter { it.startsWith("plotly") || it.startsWith("altair") || it.startsWith("bokeh") }
        val unsupported = cases.filter { case ->
            val item = Fixtures.json("outputs/$case/manifest.json").jsonObject.getValue("items").jsonArray[0].jsonObject
            mapChart(item.getValue("lib").jsonPrimitive.content, Fixtures.text("outputs/$case/0.json")) is ChartResult.Unsupported
        }
        // "plotly-unsupported" is the web's scatter3d case, drawn natively here since Scatter3d.kt.
        assertEquals(emptyList<String>(), unsupported)
    }

    @Test fun colorsParse() {
        assertEquals(0xff636efa.toInt(), parseColor("#636efa"))
        assertEquals(0x80ff0000.toInt(), parseColor("rgba(255,0,0,0.5)"))
        assertEquals(0xff000080.toInt(), parseColor("navy"))
        assertEquals(0xffaabbcc.toInt(), parseColor("#abc"))
    }
}
