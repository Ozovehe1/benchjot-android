package app.benchjot.core

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

// The Research Map model: notes as nodes; links, parent -> sub-note and origin -> attempt
// (derived_from) as directed edges. Pure functions only, a port of the web's src/lib/graph.ts.
// It is a shared contract: both apps are checked against the same fixture
// (core/src/test/resources/web/map_model.json, from the web's tests/fixtures). Change the
// fixture's rules first, then both apps. Layout and pan/zoom are in Graph.kt.

enum class EdgeKind(val value: String) {
    Link("link"),
    Parent("parent"),
    Derived("derived");

    companion object {
        fun of(value: String): EdgeKind = requireNotNull(entries.firstOrNull { it.value == value }) { "unknown edge kind $value" }
    }
}

enum class MapLens(val value: String, val label: String) { Around("around", "Around"), Everything("everything", "Everything") }

/** A note's mark: hollow circle (unmarked), filled circle (successful), diamond (failed). Shape alone carries the outcome. */
enum class NodeMark(val value: String) { Unmarked("unmarked"), Success("success"), Failed("failed") }

/** The facts a note brings to the map beyond its id, title and parent (NoteIndexItem has them). */
interface MapNote : TreeNode {
    val derivedFrom: String?
    val outcome: String?
    val projectId: String?
    val createdAt: String?
}

/** A directed edge. `mutual`: a link that also goes target -> source (arrows at both ends). */
data class GraphEdge(val source: String, val target: String, val kind: EdgeKind, val mutual: Boolean = false)

data class GraphNode(
    override val id: String,
    override val title: String,
    val outcome: Outcome?,
    val projectId: String?,
    val createdAt: String?,
    /** Kept edges touching the note in the whole map, so its size is the same in every lens. */
    val degree: Int,
) : NoteRef {
    val radius: Double get() = nodeRadius(degree)
    val mark: NodeMark get() = markFor(outcome)
    val label: String get() = nodeLabel(title, outcome, degree)
}

data class MapGraph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

object MapRadius { const val BASE = 4.0; const val PER_SQRT_DEGREE = 1.5; const val MAX = 10.0 }

/** Screen units around a node that count as a tap on it: a 48 target whatever the drawn size. */
const val HIT_RADIUS = 24.0

object Depth { const val MIN = 1; const val MAX = 3; const val DEFAULT = 1 }

private fun pairKey(a: String, b: String) = if (a < b) "$a $b" else "$b $a"

/**
 * Edges between existing notes. Structural edges first, in note order: parent -> child, then
 * origin -> attempt. Then links in link order: dropped when unresolved, a self-link, from a
 * missing note, on a pair that already has a structural edge, or repeated; a reverse link
 * marks the first one mutual.
 */
fun <T : MapNote> buildEdges(nodes: List<T>, links: List<SourcedLink>): List<GraphEdge> {
    val ids = nodes.mapTo(HashSet()) { it.id }
    val structural = HashSet<String>()
    val edges = mutableListOf<GraphEdge>()
    for (n in nodes) {
        val p = n.parentId
        if (p != null && p != n.id && p in ids) {
            structural += pairKey(p, n.id)
            edges += GraphEdge(p, n.id, EdgeKind.Parent)
        }
        val d = n.derivedFrom
        if (d != null && d != n.id && d in ids) {
            structural += pairKey(d, n.id)
            edges += GraphEdge(d, n.id, EdgeKind.Derived)
        }
    }
    val linkAt = HashMap<String, Int>() // "source target" -> index in edges
    for (l in links) {
        val target = resolveLink(l.link, nodes)?.id ?: continue
        if (target == l.source || l.source !in ids || pairKey(l.source, target) in structural) continue
        if ("${l.source} $target" in linkAt) continue
        val reverse = linkAt["$target ${l.source}"]
        if (reverse != null) {
            edges[reverse] = edges[reverse].copy(mutual = true)
            continue
        }
        linkAt["${l.source} $target"] = edges.size
        edges += GraphEdge(l.source, target, EdgeKind.Link)
    }
    return edges
}

/** Drawn radius for a node with `degree` edges: grows with √degree, capped. */
fun nodeRadius(degree: Int): Double = min(MapRadius.MAX, MapRadius.BASE + MapRadius.PER_SQRT_DEGREE * sqrt(maxOf(0, degree).toDouble()))

fun markFor(outcome: Outcome?): NodeMark = when (outcome) {
    null -> NodeMark.Unmarked
    Outcome.Success -> NodeMark.Success
    Outcome.Failed -> NodeMark.Failed
}

private fun outcomeWord(m: NodeMark) = when (m) { NodeMark.Success -> "successful"; NodeMark.Failed -> "failed"; NodeMark.Unmarked -> "unmarked" }

/** The node's accessible name: "Tokenizer test, failed, 4 connections". */
fun nodeLabel(title: String, outcome: Outcome?, degree: Int): String =
    "${displayTitle(title)}, ${outcomeWord(markFor(outcome))}, $degree ${if (degree == 1) "connection" else "connections"}"

/** The whole map (the Everything lens): every note with its degree. */
fun <T : MapNote> buildGraph(notes: List<T>, links: List<SourcedLink>): MapGraph {
    val edges = buildEdges(notes, links)
    val degree = HashMap<String, Int>()
    for (e in edges) { degree.merge(e.source, 1, Int::plus); degree.merge(e.target, 1, Int::plus) }
    val nodes = notes.map { GraphNode(it.id, it.title, Outcome.of(it.outcome), it.projectId, it.createdAt, degree[it.id] ?: 0) }
    return MapGraph(nodes, edges)
}

private fun clampDepth(d: Int) = d.coerceIn(Depth.MIN, Depth.MAX)

/** Hops from `focus` to every note within `depth`, over every edge, ignoring direction. Empty for an unknown focus. */
fun hopsFrom(graph: MapGraph, focus: String, depth: Int): Map<String, Int> {
    val hops = LinkedHashMap<String, Int>()
    if (graph.nodes.none { it.id == focus }) return hops
    val next = HashMap<String, MutableList<String>>()
    for (e in graph.edges) {
        next.getOrPut(e.source) { mutableListOf() } += e.target
        next.getOrPut(e.target) { mutableListOf() } += e.source
    }
    hops[focus] = 0
    var frontier = listOf(focus)
    var d = 1
    while (d <= clampDepth(depth) && frontier.isNotEmpty()) {
        val found = mutableListOf<String>()
        for (id in frontier) for (m in next[id].orEmpty()) if (m !in hops) { hops[m] = d; found += m }
        frontier = found
        d++
    }
    return hops
}

/** The Around lens: the focus note's neighbourhood (degrees stay those of the whole map). */
data class AroundGraph(val graph: MapGraph, val hops: Map<String, Int>)

fun aroundGraph(graph: MapGraph, focus: String, depth: Int): AroundGraph {
    val hops = hopsFrom(graph, focus, depth)
    return AroundGraph(
        MapGraph(graph.nodes.filter { it.id in hops }, graph.edges.filter { it.source in hops && it.target in hops }),
        hops,
    )
}

/** The `depth` nav value: a whole number clamped to 1–3; anything else (or below 1) gives 1. */
fun parseDepth(value: String?): Int {
    if (value.isNullOrEmpty() || !Regex("^-?\\d+$").matches(value)) return Depth.DEFAULT
    val n = value.toBigInteger()
    return if (n < Depth.MIN.toBigInteger()) Depth.DEFAULT else n.min(Depth.MAX.toBigInteger()).toInt()
}

/** Whether an edge gets an arrowhead: lineage always; links and sub-notes when close enough to read. */
fun showArrow(kind: EdgeKind, lens: MapLens, zoom: Double): Boolean = kind == EdgeKind.Derived || lens == MapLens.Around || zoom >= 1

val LIST_GROUPS = listOf("Parent", "Sub-notes", "Links to", "Linked from", "Derived from", "Attempts from this", "Further away")

data class ListGroup(val group: String, val nodes: List<GraphNode>)

/**
 * "Map as list" for the Around lens: the focus's neighbours by how they connect, then
 * everything further out. Items in note order; empty groups left out.
 */
fun aroundList(graph: MapGraph, focus: String, depth: Int): List<ListGroup> {
    val around = aroundGraph(graph, focus, depth)
    val groups = LIST_GROUPS.associateWith { LinkedHashSet<String>() }
    fun add(g: String, id: String) { groups.getValue(g) += id }
    for (e in around.graph.edges) {
        val out = e.source == focus
        val inn = e.target == focus
        if (!out && !inn) continue
        val other = if (out) e.target else e.source
        when (e.kind) {
            EdgeKind.Parent -> add(if (out) "Sub-notes" else "Parent", other)
            EdgeKind.Derived -> add(if (out) "Attempts from this" else "Derived from", other)
            EdgeKind.Link -> {
                if (out || e.mutual) add("Links to", other)
                if (inn || e.mutual) add("Linked from", other)
            }
        }
    }
    for ((id, h) in around.hops) if (h >= 2) add("Further away", id)
    return LIST_GROUPS.map { g -> ListGroup(g, around.graph.nodes.filter { it.id in groups.getValue(g) }) }.filter { it.nodes.isNotEmpty() }
}

// ------------------------------------------------------------------ geometry (screen units)
data class Pt(val x: Double, val y: Double)

/** An edge drawn between the rims of its two marks, not their centres, so arrowheads sit on the rim. Null when they overlap. */
fun edgeEnds(a: Pt, b: Pt, ra: Double, rb: Double): Pair<Pt, Pt>? {
    val dx = b.x - a.x; val dy = b.y - a.y
    val len = hypot(dx, dy)
    if (len <= ra + rb) return null
    val ux = dx / len; val uy = dy / len
    return Pt(a.x + ux * ra, a.y + uy * ra) to Pt(b.x - ux * rb, b.y - uy * rb)
}

/** An arrowhead triangle with its tip at `tip`, pointing away from `from`: tip, then the two back corners. */
fun arrowHead(from: Pt, tip: Pt, size: Double = 7.0): List<Pt> {
    val len = hypot(tip.x - from.x, tip.y - from.y).let { if (it == 0.0) 1.0 else it }
    val ux = (tip.x - from.x) / len; val uy = (tip.y - from.y) / len
    val bx = tip.x - ux * size; val by = tip.y - uy * size
    val w = size * 0.5
    return listOf(tip, Pt(bx - uy * w, by + ux * w), Pt(bx + uy * w, by - ux * w))
}

// ------------------------------------------------------------------ colours
/**
 * The map's inks from a palette, as the fixture's marks and edgeStyle name them. Marks and
 * lines need 3:1 on the background (WCAG 1.4.11), labels 4.5:1 (1.4.3); MapModelTest checks both themes.
 */
data class MapInk(val note: String, val failed: String, val link: String, val parent: String, val derived: String, val label: String, val untitled: String)

fun mapInk(p: Palette) = MapInk(note = p.fg, failed = p.fail, link = p.muted, parent = p.faint, derived = p.fg, label = p.fg, untitled = p.muted)

/** Width and dash (on, off) of an edge kind, in dp. */
fun edgeWidth(kind: EdgeKind) = if (kind == EdgeKind.Derived) 1.5 else 1.0
fun edgeDash(kind: EdgeKind): List<Double>? = if (kind == EdgeKind.Parent) listOf(4.0, 4.0) else null

/** Stroke width of the unmarked (hollow) circle, dp. */
const val HOLLOW_STROKE = 1.5

/** A diamond's half-diagonal for radius r: about the same area as the circle. */
fun diamondHalf(r: Double) = r * 1.25

/** Where an edge meets a mark: the circle's radius, or the diamond's half-diagonal. */
fun rimRadius(mark: NodeMark, r: Double) = if (mark == NodeMark.Failed) diamondHalf(r) else r

/** The Around lens's focus ring and label sit this far outside the mark (dp). */
const val FOCUS_RING_GAP = 5.0

// ------------------------------------------------------------------ data
/** True when a database error says `name` (a column or function) doesn't exist yet: a client newer than the schema. */
fun isMissingFromSchema(message: String?, name: String): Boolean {
    if (message == null || !message.contains(name)) return false
    return listOf("does not exist", "Could not find", "42703", "42883", "PGRST202", "PGRST204", "schema cache").any { message.contains(it) }
}
