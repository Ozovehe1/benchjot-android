package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map at scale (as the web's graph.test.ts): a slice of whole groups, labels that don't overlap, find and filters. */
class MapScaleTest {
    private fun node(id: String, title: String = id, outcome: Outcome? = null, project: String? = null) = GraphNode(id, title, outcome, project, null, 1)
    private fun link(a: String, b: String) = GraphEdge(a, b, EdgeKind.Link)

    @Test fun aBigMapDrawsWholeGroupsMostRecentFirst() {
        val g = MapGraph(listOf("a1", "b1", "c1", "a2", "b2", "c2", "a3", "b3", "b4").map { node(it) }, listOf(link("a1", "a2"), link("a1", "a3"), link("b1", "b2"), link("b1", "b3"), link("b1", "b4"), link("c1", "c2")))
        assertEquals(0, mapSlice(g, 9).hidden)
        val s = mapSlice(g, 7)
        assertEquals(setOf("a1", "a2", "a3", "b1", "b2", "b3", "b4"), s.graph.nodes.map { it.id }.toSet())
        assertEquals(5, s.graph.edges.size)
        assertEquals(2, s.hidden)
    }

    @Test fun sliceIsQuickForThousandsOfNotes() {
        val n = 20_000
        val g = MapGraph((0 until n).map { node("n$it") }, (0 until n - 1).map { link("n$it", "n${it + 1}") })
        val t = System.nanoTime()
        assertEquals(MAP_MAX_NOTES, mapSlice(g).graph.nodes.size)
        assertTrue(System.nanoTime() - t < 1_000_000_000L)
    }

    @Test fun labelsNeverOverlapEachOtherOrAnotherMark() {
        assertEquals(setOf("a"), labelsToShow(listOf(LabelBox("a", 0.0, 0.0, 100.0), LabelBox("b", 50.0, 5.0, 100.0))))
        assertEquals(setOf("a", "b"), labelsToShow(listOf(LabelBox("a", 0.0, 0.0, 100.0), LabelBox("b", 0.0, 20.0, 100.0))))
        val marks = listOf(MarkBox("a", -5.0, 9.0, 5.0), MarkBox("b", 40.0, 9.0, 5.0))
        assertEquals(emptySet<String>(), labelsToShow(listOf(LabelBox("a", 0.0, 0.0, 100.0)), 18.0, marks))
        assertEquals(setOf("a"), labelsToShow(listOf(LabelBox("a", 0.0, 0.0, 30.0)), 18.0, marks))
    }

    @Test fun findAndFiltersPickOutNotes() {
        val n = node("x", "Warmup sweep", Outcome.Success, "p")
        assertFalse(MapFilter().filtering)
        assertTrue(MapFilter().matches(n))
        assertTrue(MapFilter(find = "WARM").matches(n))
        assertFalse(MapFilter(find = "dropout").matches(n))
        assertFalse(MapFilter(marks = setOf(NodeMark.Failed)).matches(n))
        assertFalse(MapFilter(project = "q").matches(n))
        assertTrue(MapFilter(project = "p").filtering)
    }
}
