package app.benchjot.core.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {
    private val theme = ChartTheme(0xff161616.toInt(), 0xffe8e6e3.toInt(), 0xff8a8885.toInt(), 0xff2a2a2a.toInt())
    private val chart = Chart(
        "Loss", Axis("epoch"), Axis("loss"),
        listOf(
            XYSeries("train", PLOTLY_COLORS[0], doubleArrayOf(1.0, 2.0, 3.0, 4.0), doubleArrayOf(0.9, 0.6, 0.5, 0.45), markers = true),
            XYSeries("val", PLOTLY_COLORS[1], doubleArrayOf(1.0, 2.0, 3.0, 4.0), doubleArrayOf(1.0, 0.7, 0.6, 0.58), markers = true),
        ),
    )

    private fun pointPx(scene: Scene, view: Viewport, x: Double, y: Double): Pair<Float, Float> {
        val p = scene.plot
        return (p.l + ((x - view.x0) / (view.x1 - view.x0)).toFloat() * p.w) to (p.b - ((y - view.y0) / (view.y1 - view.y0)).toFloat() * p.h)
    }

    @Test fun tapOnAPointShowsItsValues() {
        val s = layout(chart, 400f, 300f, theme)
        val v = fullRange(chart, emptySet())
        val (x, y) = pointPx(s, v, 2.0, 0.6)
        val h = hitTest(chart, s, null, emptySet(), x + 3, y - 2)
        assertNotNull(h)
        assertEquals(0, h!!.series); assertEquals(1, h.index)
        assertEquals(listOf("train", "2, 0.6"), h.lines)
        // The tooltip is drawn with those lines.
        val texts = layout(chart, 400f, 300f, theme, hover = h).ops.filterIsInstance<Op.Text>().map { it.text }
        assertTrue("2, 0.6" in texts)
        assertNull(hitTest(chart, s, null, emptySet(), s.plot.l + s.plot.w / 2, s.plot.t + 2))
    }

    @Test fun legendTapTargetsHideASeries() {
        val s = layout(chart, 400f, 300f, theme)
        assertEquals(listOf(0, 1), s.legend.map { it.second })
        val (box, idx) = s.legend[1]
        assertEquals(1, legendAt(s, box.l + 2, box.t + box.h / 2))
        // Hidden series are not hit and drop out of the range.
        val hidden = setOf(idx)
        val v = fullRange(chart, hidden)
        assertTrue(v.y1 < 1.0)
        val s2 = layout(chart, 400f, 300f, theme, hidden = hidden)
        val (x, y) = pointPx(s2, v, 2.0, 0.7)
        assertTrue(hitTest(chart, s2, null, hidden, x, y)?.series != 1)
    }

    @Test fun pinchZoomKeepsTheFocusPointAndPanFollowsTheFinger() {
        val s = layout(chart, 400f, 300f, theme)
        val v = fullRange(chart, emptySet())
        val cx = s.plot.l + s.plot.w / 2
        val cy = s.plot.t + s.plot.h / 2
        val z = zoom(v, s.plot, 2.0, cx, cy)
        assertEquals((v.x1 - v.x0) / 2, z.x1 - z.x0, 1e-9)
        assertEquals((v.x0 + v.x1) / 2, (z.x0 + z.x1) / 2, 1e-9)
        val p = pan(z, s.plot, s.plot.w / 4, 0f)
        assertEquals(z.x0 - (z.x1 - z.x0) / 4, p.x0, 1e-9)
    }

    @Test fun ticksAreRound() {
        assertEquals(listOf(0.0, 0.2, 0.4, 0.6000000000000001, 0.8, 1.0).map { Math.round(it * 10) / 10.0 }, ticks(0.0, 1.0, 5).map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf(10.0, 20.0, 30.0), ticks(5.0, 34.0, 3))
    }

    @Test fun pieSlicesAreTappable() {
        val pie = Chart(series = listOf(PieSeries("", listOf(Slice("a", 70.0, PLOTLY_COLORS[0]), Slice("b", 30.0, PLOTLY_COLORS[1])))))
        val s = layout(pie, 300f, 300f, theme)
        val cx = (s.plot.l + s.plot.r) / 2; val cy = (s.plot.t + s.plot.b) / 2
        assertEquals(listOf("a", "70 (70%)"), hitTest(pie, s, null, emptySet(), cx + 10, cy - 30)?.lines) // first slice starts at 12 o'clock, clockwise
        assertEquals("b", hitTest(pie, s, null, emptySet(), cx - 30, cy - 10)?.lines?.first())
        assertEquals(2, s.legend.size)
    }

    @Test fun svgExportHasTheData() {
        val svg = toSvg(layout(chart, 400f, 300f, theme))
        assertTrue(svg.startsWith("<svg")); assertTrue(svg.contains("<polyline")); assertTrue(svg.contains(">Loss</text>"))
    }

    @Test fun histogramAndBoxStats() {
        val (edges, counts) = histogram(listOf(1.0, 1.0, 2.0, 2.0, 2.0, 3.0, 4.0, 4.0, 5.0), 5)
        assertEquals(9, counts.sum()); assertEquals(1.0, edges.first(), 0.0); assertEquals(5.0, edges.last(), 0.0)
        val b = boxStats(listOf(1.0, 2.0, 2.0, 3.0, 4.0, 9.0))
        assertEquals(2.5, b.median, 0.0); assertEquals(listOf(9.0), b.outliers); assertEquals(4.0, b.max, 0.0)
    }
}
