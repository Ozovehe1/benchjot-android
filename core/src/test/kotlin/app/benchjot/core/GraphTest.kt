package app.benchjot.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GraphTest {
    private val fx = Fixtures.json("graph.json").jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.contentOrNull
    private val nodes = fx["nodes"]!!.jsonArray.map { it.jsonObject }.map { NoteIndexItem(it.s("id")!!, it.s("title")!!, it.s("parent_id")) }
    private val links = fx["links"]!!.jsonArray.map { it.jsonObject }.map { SourcedLink(it.s("source")!!, it.s("label")!!, it.s("id")) }

    @Test fun edgesMatchTheWeb() {
        val want = fx["edges"]!!.jsonArray.map { it.jsonObject }.map {
            GraphEdge(it.s("source")!!, it.s("target")!!, if (it.s("kind") == "parent") EdgeKind.Parent else EdgeKind.Link)
        }
        assertEquals(want, buildEdges(nodes, links))
    }

    @Test fun layoutIsCloseToTheWebsAndDeterministic() {
        val edges = buildEdges(nodes, links)
        val placed = layoutGraph(nodes, edges)
        assertEquals(placed, layoutGraph(nodes, edges))
        val web = fx["placed"]!!.jsonArray.map { it.jsonObject }.associate { it.s("id")!! to (it["x"]!!.jsonPrimitive.double to it["y"]!!.jsonPrimitive.double) }
        val worst = placed.maxOf { p -> web[p.id]!!.let { (x, y) -> hypot(p.x - x, p.y - y) } }
        println("map layout: worst distance from the web's d3 positions = %.2f".format(worst))
        assertTrue("worst $worst", worst < 0.01) // a line-for-line port: d3's positions (fixture rounded to 3 places)
        // no two dots on top of each other (collide radius 30)
        for (a in placed) for (b in placed) if (a !== b) assertTrue(hypot(a.x - b.x, a.y - b.y) > 20)
    }

    @Test fun panZoom() {
        val v = MapView(1.0, 0.0, 0.0)
        val z = zoomAt(v, 2.0, 100.0, 50.0)
        assertEquals(MapView(2.0, -100.0, -50.0), z)
        assertEquals(MAX_ZOOM, zoomAt(v, 100.0, 0.0, 0.0).k, 0.0)
        assertEquals(MIN_ZOOM, zoomAt(v, 0.001, 0.0, 0.0).k, 0.0)
        val f = fitView(listOf(PlacedNode("a", "", -100.0, 0.0), PlacedNode("b", "", 100.0, 0.0)), 400.0, 800.0)
        assertEquals(1.5, f.k, 1e-9) // capped
        assertEquals(200.0, f.sx(0.0), 1e-9)
        assertEquals(MapView(1.0, 50.0, 60.0), fitView(emptyList(), 100.0, 120.0))
    }

    @Test fun tapsHitTheNearestDotWithin24() {
        val pts = listOf(PlacedNode("a", "", 0.0, 0.0), PlacedNode("b", "", 30.0, 0.0))
        val v = MapView(1.0, 0.0, 0.0)
        assertEquals("a", hitNode(pts, v, 10.0, 5.0, 24.0)?.id)
        assertEquals("b", hitNode(pts, v, 20.0, 0.0, 24.0)?.id)
        assertNull(hitNode(pts, v, 0.0, 30.0, 24.0))
    }

    @Test fun labels() {
        assertEquals("Untitled", mapLabel("  "))
        assertEquals("A".repeat(23) + "…", mapLabel("A".repeat(30)))
        assertEquals("A".repeat(24), mapLabel("A".repeat(24)))
    }
}
