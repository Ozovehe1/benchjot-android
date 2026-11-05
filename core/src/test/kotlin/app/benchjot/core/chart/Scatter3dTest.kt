package app.benchjot.core.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Scatter3dTest {
    private val theme = ChartTheme(0xff161616.toInt(), 0xffe8e6e3.toInt(), 0xff8a8885.toInt(), 0xff2a2a2a.toInt())
    private val chart = Chart(
        "Embedding", Axis("x"), Axis("y"),
        listOf(
            Scatter3DSeries("a", PLOTLY_COLORS[0], doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 2.0, 4.0)),
            Scatter3DSeries("b", PLOTLY_COLORS[1], doubleArrayOf(2.0), doubleArrayOf(2.0), doubleArrayOf(0.0)),
        ),
        z = Axis("z"),
    )

    private fun circles(s: Scene) = s.ops.filterIsInstance<Op.Circle>()

    @Test fun drawsEveryVisiblePointAndTheCube() {
        val s = layout(chart, 400f, 400f, theme)
        assertEquals(4, circles(s).size)
        assertTrue(s.ops.filterIsInstance<Op.Line>().size >= 12)
        assertEquals(listOf(0, 1), s.legend.map { it.second })
        assertEquals(3, circles(layout(chart, 400f, 400f, theme, hidden = setOf(1))).size)
    }

    @Test fun dragTurnsTheCameraAndMovesPoints() {
        val s = layout(chart, 400f, 400f, theme)
        val turned = rotate(Camera(), 80f, 0f, s.plot)
        assertNotEquals(Camera().yaw, turned.yaw, 1e-9)
        val before = circles(s).map { it.x to it.y }.toSet()
        val after = circles(layout(chart, 400f, 400f, theme, camera = turned)).map { it.x to it.y }.toSet()
        assertNotEquals(before, after)
        // Pitch is clamped short of the poles.
        assertTrue(rotate(Camera(), 0f, 1e6f, s.plot).pitch <= 1.5)
    }

    @Test fun tapFindsTheNearestPointWithItsXYZ() {
        val cam = Camera(yaw = 0.3, pitch = 0.4, zoom = 1.2)
        val s = layout(chart, 400f, 400f, theme, camera = cam)
        val r = range3d(chart, emptySet())
        val p = Projection(cam, s.plot)
        fun n(v: Double, a: Int) = (v - r[a].first) / (r[a].second - r[a].first) * 2 - 1
        val q = p.at(n(1.0, 0), n(1.0, 1), n(2.0, 2))
        val h = hitTest(chart, s, null, emptySet(), q.first + 2, q.second - 2, camera = cam)
        assertNotNull(h)
        assertEquals(0, h!!.series); assertEquals(1, h.index)
        assertEquals(listOf("a", "x: 1", "y: 1", "z: 2"), h.lines)
    }

    @Test fun zoomIsBounded() {
        assertEquals(8.0, zoom3d(Camera(), 100.0).zoom, 0.0)
        assertEquals(0.4, zoom3d(Camera(), 0.001).zoom, 0.0)
    }
}
