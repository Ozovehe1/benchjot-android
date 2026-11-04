package app.benchjot.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The note map: notes as nodes, links and parent -> child as edges. Mirrors the
// web's src/lib/graph.ts (edges, d3-force layout) and src/lib/pan-zoom.ts.

enum class EdgeKind { Link, Parent }

data class GraphEdge(val source: String, val target: String, val kind: EdgeKind)

/**
 * Edges between existing notes: one per parent -> child, and one per linked pair
 * (either direction, duplicates and self-links dropped). A pair that is both
 * parent/child and linked keeps only the parent edge.
 */
fun <T : TreeNode> buildEdges(nodes: List<T>, links: List<SourcedLink>): List<GraphEdge> {
    val ids = nodes.mapTo(HashSet()) { it.id }
    fun pair(a: String, b: String) = if (a < b) "$a $b" else "$b $a"
    val seen = HashSet<String>()
    val edges = mutableListOf<GraphEdge>()
    for (n in nodes) {
        val p = n.parentId ?: continue
        if (p !in ids) continue
        seen += pair(p, n.id)
        edges += GraphEdge(p, n.id, EdgeKind.Parent)
    }
    for (l in links) {
        val target = resolveLink(l.link, nodes)?.id ?: continue
        if (target == l.source || l.source !in ids) continue
        if (!seen.add(pair(l.source, target))) continue
        edges += GraphEdge(l.source, target, EdgeKind.Link)
    }
    return edges
}

data class PlacedNode(val id: String, val title: String, val x: Double, val y: Double)

/**
 * Positions for every node (graph units, centred near 0,0), deterministic for the
 * same input. A faithful port of the web's d3-force setup (forceSimulation with
 * link distance 80, many-body -220 with d3's Barnes–Hut quadtree, x/y 0.06,
 * collide 30, 300 ticks from d3's phyllotaxis start and seeded jiggle), so the
 * map lays out like the web's; GraphTest checks it against d3's own output.
 */
fun <T : NoteRef> layoutGraph(nodes: List<T>, edges: List<GraphEdge>): List<PlacedNode> {
    val sim = ForceLayout(nodes.size)
    val index = nodes.withIndex().associate { (i, node) -> node.id to i }
    val links = edges.mapNotNull { e -> val s = index[e.source]; val t = index[e.target]; if (s == null || t == null) null else s to t }
    sim.run(links, ticks = 300)
    return nodes.mapIndexed { i, node -> PlacedNode(node.id, node.title, sim.x[i], sim.y[i]) }
}

/** d3-force's simulation and the four forces the web uses, line for line. */
private class ForceLayout(val n: Int) {
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val vx = DoubleArray(n)
    val vy = DoubleArray(n)
    private var seed = 1L

    init {
        val initialAngle = PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val r = 10 * sqrt(0.5 + i)
            val a = i * initialAngle
            x[i] = r * StrictMath.cos(a)
            y[i] = r * StrictMath.sin(a)
        }
    }

    // d3-force's lcg, behind jiggle()
    private fun random(): Double { seed = (1664525L * seed + 1013904223L) % 4294967296L; return seed / 4294967296.0 }
    private fun jiggle() = (random() - 0.5) * 1e-6

    fun run(links: List<Pair<Int, Int>>, ticks: Int) {
        val count = IntArray(n)
        for ((s, t) in links) { count[s]++; count[t]++ }
        val bias = links.map { (s, t) -> count[s].toDouble() / (count[s] + count[t]) }
        val strength = links.map { (s, t) -> 1.0 / min(count[s], count[t]) }
        var alpha = 1.0
        val alphaDecay = 1 - StrictMath.pow(0.001, 1.0 / 300)
        repeat(ticks) {
            alpha += (0 - alpha) * alphaDecay
            link(links, bias, strength, alpha)
            manyBody(alpha)
            for (i in 0 until n) vx[i] += (0 - x[i]) * 0.06 * alpha
            for (i in 0 until n) vy[i] += (0 - y[i]) * 0.06 * alpha
            collide()
            for (i in 0 until n) {
                vx[i] *= 0.6; x[i] += vx[i]
                vy[i] *= 0.6; y[i] += vy[i]
            }
        }
    }

    private fun link(links: List<Pair<Int, Int>>, bias: List<Double>, strength: List<Double>, alpha: Double) {
        for ((k, st) in links.withIndex()) {
            val (s, t) = st
            var dx = x[t] + vx[t] - x[s] - vx[s]
            if (dx == 0.0) dx = jiggle()
            var dy = y[t] + vy[t] - y[s] - vy[s]
            if (dy == 0.0) dy = jiggle()
            var l = sqrt(dx * dx + dy * dy)
            l = (l - 80) / l * alpha * strength[k]
            dx *= l; dy *= l
            var b = bias[k]
            vx[t] -= dx * b; vy[t] -= dy * b
            b = 1 - b
            vx[s] += dx * b; vy[s] += dy * b
        }
    }

    private fun manyBody(alpha: Double) {
        val tree = QuadTree.of(n, { x[it] }, { y[it] })
        tree.visitAfter { q, _, _, _, _ ->
            var strength = 0.0
            if (q.isInternal) {
                var weight = 0.0; var qx = 0.0; var qy = 0.0
                for (c in q.children) {
                    if (c == null) continue
                    val w = kotlin.math.abs(c.value)
                    if (w != 0.0) { strength += c.value; weight += w; qx += w * c.x; qy += w * c.y }
                }
                q.x = qx / weight; q.y = qy / weight
            } else {
                q.x = x[q.data]; q.y = y[q.data]
                var leaf: QuadTree.Node? = q
                while (leaf != null) { strength += -220.0; leaf = leaf.next }
            }
            q.value = strength
        }
        for (i in 0 until n) {
            tree.visit { q0, x1, _, x2, _ ->
                if (q0.value == 0.0) return@visit true
                var dx = q0.x - x[i]
                var dy = q0.y - y[i]
                var w = x2 - x1
                var l = dx * dx + dy * dy
                if (w * w / 0.81 < l) {
                    if (dx == 0.0) { dx = jiggle(); l += dx * dx }
                    if (dy == 0.0) { dy = jiggle(); l += dy * dy }
                    if (l < 1) l = sqrt(l)
                    vx[i] += dx * q0.value * alpha / l
                    vy[i] += dy * q0.value * alpha / l
                    return@visit true
                } else if (q0.isInternal) return@visit false
                if (q0.data != i || q0.next != null) {
                    if (dx == 0.0) { dx = jiggle(); l += dx * dx }
                    if (dy == 0.0) { dy = jiggle(); l += dy * dy }
                    if (l < 1) l = sqrt(l)
                }
                var q: QuadTree.Node? = q0
                while (q != null) {
                    if (q.data != i) {
                        w = -220.0 * alpha / l
                        vx[i] += dx * w; vy[i] += dy * w
                    }
                    q = q.next
                }
                false
            }
        }
    }

    private fun collide() {
        val radius = 30.0
        val tree = QuadTree.of(n, { x[it] + vx[it] }, { y[it] + vy[it] })
        tree.visitAfter { q, _, _, _, _ ->
            q.r = if (q.isInternal) q.children.maxOf { it?.r ?: 0.0 } else radius
        }
        for (i in 0 until n) {
            val ri = radius; val ri2 = ri * ri
            val xi = x[i] + vx[i]
            val yi = y[i] + vy[i]
            tree.visit { q, x0, y0, x1, y1 ->
                var rj = q.r
                var r = ri + rj
                if (!q.isInternal) {
                    val j = q.data
                    if (j > i) {
                        var dx = xi - x[j] - vx[j]
                        var dy = yi - y[j] - vy[j]
                        var l = dx * dx + dy * dy
                        if (l < r * r) {
                            if (dx == 0.0) { dx = jiggle(); l += dx * dx }
                            if (dy == 0.0) { dy = jiggle(); l += dy * dy }
                            l = sqrt(l)
                            l = (r - l) / l
                            dx *= l; dy *= l
                            rj *= rj
                            r = rj / (ri2 + rj)
                            vx[i] += dx * r; vy[i] += dy * r
                            r = 1 - r
                            vx[j] -= dx * r; vy[j] -= dy * r
                        }
                    }
                    return@visit false
                }
                x0 > xi + r || x1 < xi - r || y0 > yi + r || y1 < yi - r
            }
        }
    }
}

/** d3-quadtree: just what d3-force uses (addAll, cover, visit, visitAfter), same order of operations. */
private class QuadTree private constructor(private val px: (Int) -> Double, private val py: (Int) -> Double) {
    class Node(val isInternal: Boolean, val data: Int = -1) {
        val children = arrayOfNulls<Node>(if (isInternal) 4 else 0)
        var next: Node? = null
        var x = 0.0; var y = 0.0; var value = 0.0; var r = 0.0
    }

    private var x0 = Double.NaN; private var y0 = Double.NaN; private var x1 = Double.NaN; private var y1 = Double.NaN
    private var root: Node? = null

    companion object {
        fun of(n: Int, px: (Int) -> Double, py: (Int) -> Double): QuadTree = QuadTree(px, py).apply { addAll(n) }
    }

    private fun addAll(n: Int) {
        if (n == 0) return
        val xz = DoubleArray(n) { px(it) }
        val yz = DoubleArray(n) { py(it) }
        val minX = xz.min(); val maxX = xz.max(); val minY = yz.min(); val maxY = yz.max()
        cover(minX, minY); cover(maxX, maxY)
        for (i in 0 until n) add(xz[i], yz[i], i)
    }

    private fun cover(x: Double, y: Double) {
        var x0 = this.x0; var y0 = this.y0; var x1 = this.x1; var y1 = this.y1
        if (x0.isNaN()) {
            x0 = kotlin.math.floor(x); x1 = x0 + 1
            y0 = kotlin.math.floor(y); y1 = y0 + 1
        } else {
            var z = (x1 - x0).let { if (it == 0.0) 1.0 else it }
            var node = root
            while (x0 > x || x >= x1 || y0 > y || y >= y1) {
                val i = (if (y < y0) 2 else 0) or (if (x < x0) 1 else 0)
                val parent = Node(true)
                parent.children[i] = node
                node = parent
                z *= 2
                when (i) {
                    0 -> { x1 = x0 + z; y1 = y0 + z }
                    1 -> { x0 = x1 - z; y1 = y0 + z }
                    2 -> { x1 = x0 + z; y0 = y1 - z }
                    3 -> { x0 = x1 - z; y0 = y1 - z }
                }
            }
            if (root?.isInternal == true) root = node
        }
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1
    }

    private fun add(x: Double, y: Double, d: Int) {
        val leaf = Node(false, d)
        var node = root ?: run { root = leaf; return }
        var x0 = this.x0; var y0 = this.y0; var x1 = this.x1; var y1 = this.y1
        var parent: Node? = null
        var i = 0
        while (node.isInternal) {
            val xm = (x0 + x1) / 2; val ym = (y0 + y1) / 2
            val right = x >= xm; if (right) x0 = xm else x1 = xm
            val bottom = y >= ym; if (bottom) y0 = ym else y1 = ym
            parent = node
            i = (if (bottom) 2 else 0) or (if (right) 1 else 0)
            node = node.children[i] ?: run { parent.children[i] = leaf; return }
        }
        val xp = px(node.data); val yp = py(node.data)
        if (x == xp && y == yp) {
            leaf.next = node
            if (parent != null) parent.children[i] = leaf else root = leaf
            return
        }
        var j: Int
        do {
            val fresh = Node(true)
            if (parent != null) parent.children[i] = fresh else root = fresh
            parent = fresh
            val xm = (x0 + x1) / 2; val ym = (y0 + y1) / 2
            val right = x >= xm; if (right) x0 = xm else x1 = xm
            val bottom = y >= ym; if (bottom) y0 = ym else y1 = ym
            i = (if (bottom) 2 else 0) or (if (right) 1 else 0)
            j = (if (yp >= ym) 2 else 0) or (if (xp >= xm) 1 else 0)
        } while (i == j)
        parent.children[j] = node
        parent.children[i] = leaf
    }

    private class Quad(val node: Node, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    /** Pre-order; the callback returns true to skip a node's children. */
    fun visit(callback: (Node, Double, Double, Double, Double) -> Boolean) {
        val quads = ArrayDeque<Quad>()
        root?.let { quads.addLast(Quad(it, x0, y0, x1, y1)) }
        while (quads.isNotEmpty()) {
            val q = quads.removeLast()
            if (!callback(q.node, q.x0, q.y0, q.x1, q.y1) && q.node.isInternal) {
                val xm = (q.x0 + q.x1) / 2; val ym = (q.y0 + q.y1) / 2
                q.node.children[3]?.let { quads.addLast(Quad(it, xm, ym, q.x1, q.y1)) }
                q.node.children[2]?.let { quads.addLast(Quad(it, q.x0, ym, xm, q.y1)) }
                q.node.children[1]?.let { quads.addLast(Quad(it, xm, q.y0, q.x1, ym)) }
                q.node.children[0]?.let { quads.addLast(Quad(it, q.x0, q.y0, xm, ym)) }
            }
        }
    }

    /** Post-order. */
    fun visitAfter(callback: (Node, Double, Double, Double, Double) -> Unit) {
        val quads = ArrayDeque<Quad>()
        val next = ArrayDeque<Quad>()
        root?.let { quads.addLast(Quad(it, x0, y0, x1, y1)) }
        while (quads.isNotEmpty()) {
            val q = quads.removeLast()
            if (q.node.isInternal) {
                val xm = (q.x0 + q.x1) / 2; val ym = (q.y0 + q.y1) / 2
                q.node.children[0]?.let { quads.addLast(Quad(it, q.x0, q.y0, xm, ym)) }
                q.node.children[1]?.let { quads.addLast(Quad(it, xm, q.y0, q.x1, ym)) }
                q.node.children[2]?.let { quads.addLast(Quad(it, q.x0, ym, xm, q.y1)) }
                q.node.children[3]?.let { quads.addLast(Quad(it, xm, ym, q.x1, q.y1)) }
            }
            next.addLast(q)
        }
        while (next.isNotEmpty()) { val q = next.removeLast(); callback(q.node, q.x0, q.y0, q.x1, q.y1) }
    }
}

// ------------------------------------------------------------------ pan & zoom
/** Screen transform for the map: screen = graph * k + (x, y). */
data class MapView(val k: Double, val x: Double, val y: Double) {
    fun sx(gx: Double) = gx * k + x
    fun sy(gy: Double) = gy * k + y
    fun pan(dx: Double, dy: Double) = copy(x = x + dx, y = y + dy)
}

const val MIN_ZOOM = 0.2
const val MAX_ZOOM = 4.0

/** Zooms by `factor` keeping the screen point (px, py) fixed. */
fun zoomAt(v: MapView, factor: Double, px: Double, py: Double): MapView {
    val k = (v.k * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val f = k / v.k
    return MapView(k, px - (px - v.x) * f, py - (py - v.y) * f)
}

/** A view that fits the points into a width x height box with `pad` margin, never zoomed past 1.5. */
fun fitView(points: List<PlacedNode>, width: Double, height: Double, pad: Double = 48.0): MapView {
    if (points.isEmpty()) return MapView(1.0, width / 2, height / 2)
    val x0 = points.minOf { it.x }; val x1 = points.maxOf { it.x }
    val y0 = points.minOf { it.y }; val y1 = points.maxOf { it.y }
    val k = min((width - 2 * pad) / max(x1 - x0, 1.0), (height - 2 * pad) / max(y1 - y0, 1.0)).coerceIn(MIN_ZOOM, 1.5)
    return MapView(k, width / 2 - (x0 + x1) / 2 * k, height / 2 - (y0 + y1) / 2 * k)
}

/** The note under a tap at (px, py), within `radius` screen units of its dot (the web's 48 px target), nearest first. */
fun hitNode(points: List<PlacedNode>, v: MapView, px: Double, py: Double, radius: Double): PlacedNode? =
    points.map { it to kotlin.math.hypot(v.sx(it.x) - px, v.sy(it.y) - py) }
        .filter { it.second <= radius }
        .minByOrNull { it.second }?.first

const val MAP_LABEL_MAX = 24

/** A dot's label: the display title, cut to 24 characters with an ellipsis. */
fun mapLabel(title: String): String {
    val t = displayTitle(title)
    return if (t.length > MAP_LABEL_MAX) t.take(MAP_LABEL_MAX - 1) + "…" else t
}
