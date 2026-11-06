package app.benchjot.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Research Map model against the web's parity fixture (map_model.json, the web's tests/fixtures). */
class MapModelTest {
    private val fx = Fixtures.json("map_model.json").jsonObject
    private val rules = fx["rules"]!!.jsonObject
    private val expect = fx["expect"]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.contentOrNull
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    private val notes = fx.o("input")["notes"]!!.jsonArray.map { it.jsonObject }.map {
        NoteIndexItem(it.s("id")!!, it.s("title")!!, it.s("parent_id"), it.s("outcome"), it.s("project_id"), it.s("created_at"), it.s("derived_from"))
    }
    private val links = fx.o("input")["links"]!!.jsonArray.map { it.jsonObject }.map { SourcedLink(it.s("source")!!, it.s("label")!!, it.s("id")) }
    private val graph = buildGraph(notes, links)

    @Test fun directedDeduplicatedEdges() {
        val want = expect["edges"]!!.jsonArray.map { it.jsonObject }.map {
            GraphEdge(it.s("source")!!, it.s("target")!!, EdgeKind.of(it.s("kind")!!), it["mutual"]!!.jsonPrimitive.boolean)
        }
        assertEquals(want, graph.edges)
    }

    @Test fun nodesHaveDegreeSizeMarkAndLabel() {
        val want = expect["nodes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(want.map { it.s("id") }, graph.nodes.map { it.id })
        graph.nodes.zip(want).forEach { (n, w) ->
            assertEquals(n.id, w["degree"]!!.jsonPrimitive.int, n.degree)
            assertEquals(w["radius"]!!.jsonPrimitive.double, n.radius, 1e-5)
            assertEquals(w.s("mark"), n.mark.value)
            assertEquals(w.s("label"), n.label)
        }
        for (c in expect["radiusCap"]!!.jsonArray.map { it.jsonObject }) assertEquals(c["radius"]!!.jsonPrimitive.double, nodeRadius(c["degree"]!!.jsonPrimitive.int), 0.0)
        // what the map keeps of each note, for the preview
        assertEquals(Outcome.Success, graph.nodes[0].outcome)
        assertEquals("90000000-0000-4000-8000-000000000001", graph.nodes[0].projectId)
        assertEquals(notes[0].createdAt, graph.nodes[0].createdAt)
    }

    @Test fun constantsAndWordingMatchTheFixture() {
        val r = rules.o("radius")
        assertEquals(r["base"]!!.jsonPrimitive.double, MapRadius.BASE, 0.0)
        assertEquals(r["perSqrtDegree"]!!.jsonPrimitive.double, MapRadius.PER_SQRT_DEGREE, 0.0)
        assertEquals(r["max"]!!.jsonPrimitive.double, MapRadius.MAX, 0.0)
        assertEquals(rules["hitRadius"]!!.jsonPrimitive.double, HIT_RADIUS, 0.0)
        val d = rules.o("depth")
        assertEquals(listOf(d["min"], d["max"], d["default"]).map { it!!.jsonPrimitive.int }, listOf(Depth.MIN, Depth.MAX, Depth.DEFAULT))
        assertEquals(rules["listGroups"]!!.jsonArray.map { it.jsonPrimitive.content }, LIST_GROUPS)
        val errors = rules.o("errors")
        assertEquals(errors.s("loadMap"), ACTION_TEXT["load_map"])
        assertEquals(errors.s("newAttempt"), ACTION_TEXT["new_attempt"])
        // styles: the fixture's marks / edgeStyle are what mapInk, edgeWidth and edgeDash draw
        val marks = rules.o("marks")
        assertEquals(HOLLOW_STROKE, marks.o("unmarked")["strokeWidth"]!!.jsonPrimitive.double, 0.0)
        assertEquals(false, marks.o("unmarked")["fill"]!!.jsonPrimitive.boolean)
        assertEquals("diamond", marks.o("failed").s("shape"))
        for (k in EdgeKind.entries) {
            val st = rules.o("edgeStyle").o(k.value)
            assertEquals(st["width"]!!.jsonPrimitive.double, edgeWidth(k), 0.0)
            assertEquals(st["dash"].let { if (it is kotlinx.serialization.json.JsonArray) it.map { x -> x.jsonPrimitive.double } else null }, edgeDash(k))
        }
    }

    @Test fun parsesDepth() {
        for (c in expect["parseDepth"]!!.jsonArray.map { it.jsonObject }) assertEquals(c.toString(), c["out"]!!.jsonPrimitive.int, parseDepth(c.s("in")))
        assertEquals(3, parseDepth("99999999999999999999"))
    }

    @Test fun decidesArrows() {
        for (c in expect["arrows"]!!.jsonArray.map { it.jsonObject }) {
            val lens = MapLens.entries.first { it.value == c.s("lens") }
            assertEquals(c.toString(), c["show"]!!.jsonPrimitive.boolean, showArrow(EdgeKind.of(c.s("kind")!!), lens, c["zoom"]!!.jsonPrimitive.double))
        }
    }

    @Test fun aroundLensAndItsList() {
        for (c in expect["around"]!!.jsonArray.map { it.jsonObject }) {
            val focus = c.s("focus")!!
            val depth = c["depth"]!!.jsonPrimitive.int
            val a = aroundGraph(graph, focus, depth)
            val want = c["nodes"]!!.jsonArray.map { it.jsonArray.let { p -> p[0].jsonPrimitive.content to p[1].jsonPrimitive.int } }
            assertEquals(c.toString(), want, a.graph.nodes.map { it.id to a.hops.getValue(it.id) })
            assertEquals(c["edgeCount"]!!.jsonPrimitive.int, a.graph.edges.size)
            val list = c["list"]!!.jsonArray.map { it.jsonObject }.map { g -> g.s("group")!! to g["ids"]!!.jsonArray.map { it.jsonPrimitive.content } }
            assertEquals(list, aroundList(graph, focus, depth).map { g -> g.group to g.nodes.map { it.id } })
        }
    }

    @Test fun tokensAndContrast() {
        val tokens = fx.o("tokens")
        val min = rules.o("minContrast")
        val markMin = min["mark"]!!.jsonPrimitive.double
        val textMin = min["text"]!!.jsonPrimitive.double
        for ((name, p) in listOf("dark" to DARK, "light" to LIGHT)) {
            val t = tokens.o(name)
            val mine = mapOf("bg" to p.bg, "fg" to p.fg, "muted" to p.muted, "faint" to p.faint, "fail" to p.fail, "ok" to p.ok)
            for ((k, v) in t) assertEquals("$name $k", v.jsonPrimitive.content.lowercase(), mine.getValue(k).lowercase())
            val ink = mapInk(p)
            for (mark in listOf(ink.note, ink.failed, ink.link, ink.parent, ink.derived, p.ok)) {
                assertTrue("$name mark $mark: ${contrastRatio(mark, p.bg)}", contrastRatio(mark, p.bg) >= markMin)
            }
            for (text in listOf(ink.label, ink.untitled)) assertTrue("$name text $text", contrastRatio(text, p.bg) >= textMin)
        }
        assertEquals(21.0, contrastRatio("#000000", "#ffffff"), 1e-9)
        assertTrue(contrastRatio("#8a8885", "#e8e6e3") < 3) // the old light `ok` the fixture replaced
    }

    @Test fun edgeGeometry() {
        val (from, to) = edgeEnds(Pt(0.0, 0.0), Pt(100.0, 0.0), 5.0, 10.0)!!
        assertEquals(Pt(5.0, 0.0), from)
        assertEquals(Pt(90.0, 0.0), to)
        val (tip, l, r) = arrowHead(from, to, 8.0)
        assertEquals(Pt(90.0, 0.0), tip)
        assertEquals(82.0, l.x, 1e-9); assertEquals(82.0, r.x, 1e-9)
        assertEquals(8.0, l.y - r.y, 1e-9) // base width = size
        assertNull(edgeEnds(Pt(0.0, 0.0), Pt(3.0, 0.0), 5.0, 5.0))
    }

    @Test fun missingSchemaIsRecognised() {
        assertTrue(isMissingFromSchema("column entries.derived_from does not exist", "derived_from"))
        assertTrue(isMissingFromSchema("PGRST202 Could not find the function public.new_attempt(p_from) in the schema cache", "new_attempt"))
        assertFalse(isMissingFromSchema("insert violates foreign key constraint entries_derived_from_fkey", "derived_from"))
        assertFalse(isMissingFromSchema("column entries.title does not exist", "derived_from"))
        assertFalse(isMissingFromSchema(null, "derived_from"))
    }
}
