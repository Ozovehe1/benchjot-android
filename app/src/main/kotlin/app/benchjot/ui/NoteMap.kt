package app.benchjot.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.benchjot.core.DARK
import app.benchjot.core.Depth
import app.benchjot.core.EdgeKind
import app.benchjot.core.ERROR_TEXT
import app.benchjot.core.ErrorKind
import app.benchjot.core.FOCUS_RING_GAP
import app.benchjot.core.GraphNode
import app.benchjot.core.HIT_RADIUS
import app.benchjot.core.HOLLOW_STROKE
import app.benchjot.core.LIGHT
import app.benchjot.core.ListGroup
import app.benchjot.core.MapGraph
import app.benchjot.core.MapLens
import app.benchjot.core.MapView
import app.benchjot.core.NodeMark
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.PlacedNode
import app.benchjot.core.Pt
import app.benchjot.core.SourcedLink
import app.benchjot.core.aroundGraph
import app.benchjot.core.aroundList
import app.benchjot.core.arrowHead
import app.benchjot.core.buildGraph
import app.benchjot.core.displayTitle
import app.benchjot.core.edgeDash
import app.benchjot.core.edgeEnds
import app.benchjot.core.edgeWidth
import app.benchjot.core.fitView
import app.benchjot.core.hitNode
import app.benchjot.core.layoutGraph
import app.benchjot.core.listDate
import app.benchjot.core.mapInk
import app.benchjot.core.mapLabel
import app.benchjot.core.mapSlice
import app.benchjot.core.MapFilter
import app.benchjot.core.LabelBox
import app.benchjot.core.MarkBox
import app.benchjot.core.labelsToShow
import app.benchjot.core.showArrow
import app.benchjot.core.zoomAt
import app.benchjot.data.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

// The Research Map, as the web's note-map.tsx, in the phone's shape. Two lenses: Around a note
// (its neighbourhood, depth 1–3, the focus at the centre; the default when opened from a note)
// and Everything. Marks: hollow dot = unmarked, filled dot = successful, red dot = failed;
// size grows with connections. Edges are directed: links (solid), sub-notes (dashed), attempts
// (bold, always arrowed). Drag to pan, pinch to zoom, tap a note for its preview sheet (Open,
// Map around this). TalkBack reaches every note on the map, and "Map as list" shows the same map
// as 48dp rows. Layout runs on Dispatchers.Default, never the main thread.

private const val TAP_SLOP_DP = 8f
private const val ARROW_DP = 7f

/** What the screen shows: the whole map, the lens's part of it and where each note sits. */
private class Shown(val whole: MapGraph, val graph: MapGraph, val hops: Map<String, Int>?, val placed: List<PlacedNode>, val loose: List<GraphNode> = emptyList(), val hidden: Int = 0, val all: List<GraphNode> = graph.nodes) {
    val at = placed.associateBy { it.id }
    val byId = graph.nodes.associateBy { it.id }
}

/** Pure and off the main thread: graph, lens and the d3 layout (300 ticks). */
private fun compute(notes: List<NoteIndexItem>, links: List<SourcedLink>, lens: MapLens, focus: String?, depth: Int): Shown {
    val whole = buildGraph(notes, links)
    // A big map draws the most recent connected groups (the focus's first), up to MAP_MAX_NOTES (as on the web).
    if (lens == MapLens.Around && focus != null) {
        val a = aroundGraph(whole, focus, depth)
        val first = a.graph.nodes.filter { it.id == focus } + a.graph.nodes.filter { it.id != focus }
        val slice = mapSlice(MapGraph(first, a.graph.edges))
        return Shown(whole, slice.graph, a.hops, layoutGraph(slice.graph.nodes, slice.graph.edges, focus), hidden = slice.hidden, all = a.graph.nodes)
    }
    // Notes with no connection aren't scattered over the picture (where they'd look placed on purpose
    // and crowd the ones that connect): they're listed under it as "Not connected yet", as on the web.
    val loose = whole.nodes.filter { it.degree == 0 }
    val connected = whole.nodes.filter { it.degree > 0 }
    val slice = mapSlice(MapGraph(connected, whole.edges))
    return Shown(whole, slice.graph, null, layoutGraph(slice.graph.nodes, slice.graph.edges), loose, slice.hidden, connected)
}

/** The map's inks as Compose colours (core's mapInk, checked for WCAG AA in both themes). */
private class Inks(val note: Color, val failed: Color, val link: Color, val parent: Color, val derived: Color, val label: Color, val untitled: Color, val ring: Color, val bg: Color) {
    fun edge(k: EdgeKind) = when (k) { EdgeKind.Link -> link; EdgeKind.Parent -> parent; EdgeKind.Derived -> derived }
}

@Composable
private fun rememberInks(): Inks {
    val dark = t.dark
    val bg = t.bg
    val muted = t.muted
    return remember(dark, bg, muted) {
        val i = mapInk(if (dark) DARK else LIGHT)
        Inks(hex(i.note), hex(i.failed), hex(i.link), hex(i.parent), hex(i.derived), hex(i.label), hex(i.untitled), muted, bg)
    }
}

@Composable
fun NoteMapScreen(focus: String?, initialDepth: Int, onHome: () -> Unit, onOpenNote: (String) -> Unit, onMapAround: (String) -> Unit) {
    val notes = rememberLive(action = "load_map") { Backend.noteIndex() }
    val links = rememberLive(action = "load_map") { Backend.noteLinks() }
    val projects = rememberLive { Backend.projects() }
    var lens by rememberSaveable { mutableStateOf(if (focus != null) MapLens.Around else MapLens.Everything) }
    var depth by rememberSaveable { mutableIntStateOf(initialDepth.coerceIn(Depth.MIN, Depth.MAX)) }
    var asList by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    // Find and filters (overview first, then filter, then details on demand; as on the web): what
    // doesn't match fades, so the picture keeps its shape.
    var filter by remember { mutableStateOf(MapFilter()) }
    var centerOn by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // Kept while a new lens or depth lays out, so the screen never blanks.
    var shown by remember { mutableStateOf<Shown?>(null) }
    LaunchedEffect(notes.value, links.value, lens, depth) {
        val n = notes.value ?: return@LaunchedEffect
        val l = links.value ?: return@LaunchedEffect
        shown = withContext(Dispatchers.Default) { compute(n, l, lens, focus, depth) }
    }
    val s = shown
    val focusNode = s?.whole?.nodes?.firstOrNull { it.id == focus }
    val matches = remember(s, filter) { if (s != null && filter.filtering) s.graph.nodes.filter { filter.matches(it) }.mapTo(HashSet()) { it.id } else null }
    val pickNote = { id: String ->
        if (s?.byId?.containsKey(id) == true && !asList) { selected = id; centerOn = id to ((centerOn?.second ?: 0) + 1) } else onMapAround(id)
    }
    val loadError = notes.error ?: links.error

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageColumn {
                TopBar(onHome = onHome, back = focusNode?.let { f -> displayTitle(f.title) to { onOpenNote(f.id) } } ?: ("All notes" to onHome)) {
                    TextBtn(if (asList) "Map as picture" else "Map as list", { asList = !asList; selected = null }, icon = if (asList) Icons.Map else Icons.Note)
                }
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        if (focusNode != null && lens == MapLens.Around) {
                            append("Around ${displayTitle(focusNode.title)}")
                        } else {
                            append("Map")
                        }
                    },
                    Modifier.padding(top = 10.dp), style = Type.h1.copy(fontSize = 30.sp), color = t.fg, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (s != null) {
                    val nodes = s.graph.nodes.size + s.hidden + s.loose.size
                    Muted(
                        "%,d ${if (nodes == 1) "note" else "notes"}. Lines are links, sub-notes and attempts. Tap a note for its details.".format(nodes) +
                            if (s.hidden > 0 && !asList) " The picture shows the ${s.graph.nodes.size} most recently edited connected notes; Map as list has all of them." else "",
                        Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    if (s.whole.nodes.isNotEmpty()) MapToolbar(filter, { filter = it }, s.whole.nodes, projects.value.orEmpty(), matches?.size, pickNote) { id -> s.byId.containsKey(id) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (focus != null) {
                        Segmented(MapLens.entries.map { it to it.label }, lens, { lens = it; selected = null })
                    }
                    if (lens == MapLens.Around && focus != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Depth", Modifier.clearAndSetSemantics {}, style = Type.xs, color = t.muted)
                            Segmented((Depth.MIN..Depth.MAX).map { it to "$it" }, depth, { depth = it; selected = null }, name = { "Depth $it" })
                        }
                    }
                }
            }
            when {
                loadError != null -> PageColumn { Text(loadError, Modifier.padding(vertical = 20.dp), style = Type.sm, color = t.fg) }
                notes.value?.isEmpty() == true -> PageColumn { Muted("No notes yet.", Modifier.padding(vertical = 20.dp)) }
                s != null && lens == MapLens.Around && focus != null && focusNode == null ->
                    PageColumn { Text(ERROR_TEXT.getValue(ErrorKind.NotFound), Modifier.padding(vertical = 20.dp), style = Type.sm, color = t.fg) }
                s == null -> Unit
                asList -> if (s.graph.nodes.isNotEmpty() || s.loose.isNotEmpty()) MapList(s, lens, focus, depth, onOpenNote, filter)
                s.graph.nodes.isEmpty() -> if (s.loose.isNotEmpty()) PageColumn {
                    Notice(
                        "Nothing is connected yet. Write [[ and a note’s title in another note to link them, make a sub-note, or start a new attempt from a note’s menu; the connections appear here.",
                        modifier = Modifier.padding(vertical = 12.dp), error = false,
                    )
                }
                else -> MapCanvas(s, lens, focus, selected, onSelect = { selected = it }, modifier = Modifier.weight(1f), matches = matches, centerOn = centerOn)
            }
            if (s != null && !asList && s.loose.isNotEmpty()) LooseNotes(s.loose, onOpenNote)
        }
        val pick = selected?.let { id -> s?.byId?.get(id) }
        if (pick != null && !asList) {
            PreviewSheet(
                pick,
                projectName = projects.value?.firstOrNull { it.id == pick.projectId }?.name,
                isFocus = lens == MapLens.Around && pick.id == focus,
                onOpen = { onOpenNote(pick.id) },
                onMapAround = { onMapAround(pick.id) },
                onClose = { selected = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun MapCanvas(s: Shown, lens: MapLens, focus: String?, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier, matches: Set<String>? = null, centerOn: Pair<String, Int>? = null) {
    val density = LocalDensity.current
    val dp = density.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var view by remember { mutableStateOf(MapView(1.0, 0.0, 0.0)) }
    var fitted by remember { mutableStateOf("") }
    var refit by remember { mutableIntStateOf(0) }
    val inks = rememberInks()
    val hit = HIT_RADIUS * dp
    val slop = TAP_SLOP_DP * dp
    val label = remember(density) { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp } }

    Column(modifier) {
        Hairline()
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { size = it }) {
            if (size == IntSize.Zero) return@Box
            // Fit the map when it (or the screen) first becomes known, and on Show all.
            val key = "${System.identityHashCode(s)}:${size.width}x${size.height}:$refit"
            LaunchedEffect(key) {
                // Graph units are CSS px on the web; here they are dp, so fit in dp and scale to px.
                // Room on the right for the labels and at the bottom for the key and zoom buttons.
                val w = size.width / dp.toDouble()
                val fit = fitView(s.placed, w, size.height / dp.toDouble(), 32.0, extraRight = kotlin.math.min(150.0, w * 0.4), extraBottom = 72.0)
                view = MapView(fit.k * dp, fit.x * dp, fit.y * dp)
                fitted = key
            }
            if (fitted != key) return@Box
            // Centre on a note found with Find.
            LaunchedEffect(centerOn) {
                val id = centerOn?.first ?: return@LaunchedEffect
                val p = s.at[id] ?: return@LaunchedEffect
                val k = maxOf(view.k, dp.toDouble())
                view = MapView(k, size.width / 2.0 - p.x * k, size.height / 2.0 - p.y * k)
            }
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "Map of ${s.graph.nodes.size} ${if (s.graph.nodes.size == 1) "note" else "notes"}. Tap a note for its preview, or use Map as list." }
                    .pointerInput(s) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var moved = false
                            var multi = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size > 1) multi = true
                                if (pressed.isEmpty()) break
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid()
                                if (hypot((pressed[0].position.x - down.position.x).toDouble(), (pressed[0].position.y - down.position.y).toDouble()) > slop) moved = true
                                var v = view
                                if (zoom != 1f && c != Offset.Unspecified) v = zoomAt(v, zoom.toDouble(), c.x.toDouble(), c.y.toDouble())
                                v = v.pan(pan.x.toDouble(), pan.y.toDouble())
                                view = v
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } while (true)
                            if (!moved && !multi) onSelect(hitNode(s.placed, view, down.position.x.toDouble(), down.position.y.toDouble(), hit)?.id)
                        }
                    },
            ) {
                val v = view
                fun at(id: String) = s.at.getValue(id).let { Pt(v.sx(it.x), v.sy(it.y)) }
                fun rim(n: GraphNode) = n.radius * dp
                val zoom = v.k / dp // the web's view.k: CSS px per graph unit
                // Labels that fit without overlapping each other or another note's mark, the most
                // important first: selected, the focus, what Find picked out, then the most connected.
                fun text(n: GraphNode) = if (n.id == selected) displayTitle(n.title) else mapLabel(n.title)
                val labelled = run {
                    val rank = { n: GraphNode -> if (n.id == selected) 0 else if (lens == MapLens.Around && n.id == focus) 1 else if (matches?.contains(n.id) == true) 2 else 3 }
                    val items = s.graph.nodes.asSequence()
                        .filter { matches == null || it.id in matches || it.id == selected }
                        .sortedWith(compareBy<GraphNode>(rank).thenByDescending { it.degree })
                        .map { n -> val p = at(n.id); LabelBox(n.id, p.x + rim(n) + 6 * dp, p.y - 9 * dp, label.measureText(text(n)).toDouble() + 6 * dp) }
                        .filter { it.x < size.width && it.x + it.w > 0 && it.y < size.height && it.y + 18 * dp > 0 }
                        .toList()
                    val marks = s.graph.nodes.map { n -> val p = at(n.id); MarkBox(n.id, p.x, p.y, rim(n) + 2 * dp) }
                    labelsToShow(items, 18.0 * dp, marks)
                }
                val dim = { id: String -> matches != null && id !in matches && id != selected }
                for (e in s.graph.edges) {
                    val a = s.byId.getValue(e.source)
                    val b = s.byId.getValue(e.target)
                    val (from, to) = edgeEnds(at(e.source), at(e.target), rim(a), rim(b)) ?: continue
                    val color = inks.edge(e.kind).let { if (matches != null && (e.source !in matches || e.target !in matches)) it.copy(alpha = 0.14f) else it }
                    drawLine(
                        color, from.offset(), to.offset(), strokeWidth = (edgeWidth(e.kind) * dp).toFloat(),
                        pathEffect = edgeDash(e.kind)?.let { d -> PathEffect.dashPathEffect(d.map { (it * dp).toFloat() }.toFloatArray()) },
                    )
                    if (showArrow(e.kind, lens, zoom)) {
                        drawArrow(arrowHead(from, to, ARROW_DP * dp.toDouble()), color)
                        if (e.mutual) drawArrow(arrowHead(to, from, ARROW_DP * dp.toDouble()), color)
                    }
                }
                for (n in s.graph.nodes) {
                    val p = at(n.id).offset()
                    val r = (n.radius * dp).toFloat()
                    val isFocus = lens == MapLens.Around && n.id == focus
                    val ringR = (rim(n) + FOCUS_RING_GAP * dp).toFloat()
                    if (isFocus || n.id == selected) drawCircle(if (n.id == selected) inks.note else inks.ring, ringR, p, style = Stroke(HOLLOW_STROKE.toFloat() * dp))
                    val faded = dim(n.id)
                    if (faded) drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(p.x - ringR, p.y - ringR, p.x + ringR, p.y + ringR), androidx.compose.ui.graphics.Paint().apply { alpha = 0.2f })
                    drawNodeMark(n.mark, p, r, inks)
                    if (faded) drawContext.canvas.restore()
                    if (n.id in labelled) {
                        label.typeface = if (isFocus || n.id == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        drawLabel(text(n), p + Offset(ringR, 4 * dp), label, if (n.title.isBlank()) inks.untitled else inks.label, inks.bg)
                    }
                }
            }
            // One invisible 48dp target per note, for TalkBack: each note is reachable and named
            // ("Tokenizer test, failed, 4 connections"); a double tap opens its preview. Touch passes through.
            for (n in s.graph.nodes) {
                val isFocus = lens == MapLens.Around && n.id == focus
                Box(
                    Modifier
                        .offset { s.at.getValue(n.id).let { p -> IntOffset((view.sx(p.x) - hit).roundToInt(), (view.sy(p.y) - hit).roundToInt()) } }
                        .size((2 * HIT_RADIUS).dp)
                        .semantics {
                            contentDescription = if (isFocus) "${n.label}, this note" else n.label
                            role = Role.Button
                            onClick(label = "Preview") { onSelect(n.id); true }
                        },
                )
            }
            // The key (bottom left, plain text) and the zoom buttons (bottom right).
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f, fill = false).background(t.bg.copy(alpha = 0.8f)).padding(horizontal = 4.dp)) { Legend(inks) }
                Column(Modifier.background(t.bg, RoundedCornerShape(6.dp)).border(1.dp, t.line, RoundedCornerShape(6.dp))) {
                    val mid = { Pair(size.width / 2.0, size.height / 2.0) }
                    MapControl(Icons.Plus, "Zoom in") { val (cx, cy) = mid(); view = zoomAt(view, ZOOM_STEP, cx, cy) }
                    Hairline()
                    MapControl(Icons.Minus, "Zoom out") { val (cx, cy) = mid(); view = zoomAt(view, 1 / ZOOM_STEP, cx, cy) }
                    Hairline()
                    MapControl(Icons.Fit, "Show all") { refit++ }
                }
            }
        }
    }
}

private const val ZOOM_STEP = 1.25

/** Notes with no connection yet: listed under the picture, each opening its note. */
@Composable
private fun LooseNotes(nodes: List<GraphNode>, onOpenNote: (String) -> Unit) {
    val inks = rememberInks()
    Column(Modifier.fillMaxWidth().topLine(t.line).padding(vertical = 10.dp)) {
        PageColumn { Eyebrow("Not connected yet · ${nodes.size}") }
        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            nodes.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).plainClick { onOpenNote(n.id) }.semantics { contentDescription = n.label },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MarkIcon(n.mark, inks)
                    Text(displayTitle(n.title), Modifier.clearAndSetSemantics {}, style = Type.body, color = if (n.title.isBlank()) t.muted else t.fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MapControl(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).plainClick(onClick = onClick).semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        BjIcon(icon, tint = t.muted)
    }
}

private fun Pt.offset() = Offset(x.toFloat(), y.toFloat())

private fun DrawScope.drawArrow(points: List<Pt>, color: Color) {
    val path = Path().apply {
        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        lineTo(points[1].x.toFloat(), points[1].y.toFloat())
        lineTo(points[2].x.toFloat(), points[2].y.toFloat())
        close()
    }
    drawPath(path, color)
}

/** The web's NodeMark: a filled dot (successful), a red dot (failed) or a hollow ring (unmarked). */
private fun DrawScope.drawNodeMark(mark: NodeMark, c: Offset, r: Float, inks: Inks) {
    when (mark) {
        NodeMark.Success -> drawCircle(inks.note, r, c)
        NodeMark.Failed -> drawCircle(inks.failed, r, c)
        NodeMark.Unmarked -> {
            val w = (HOLLOW_STROKE * density).toFloat()
            drawCircle(inks.bg, r - w / 2, c)
            drawCircle(inks.note, r - w / 2, c, style = Stroke(w))
        }
    }
}

/** Label text with a background-coloured halo (the web's stroke + paint-order), so lines never cut through it. */
private fun DrawScope.drawLabel(text: String, at: Offset, paint: Paint, color: Color, halo: Color) {
    val canvas = drawContext.canvas.nativeCanvas
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3.dp.toPx()
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = halo.toArgb()
    canvas.drawText(text, at.x, at.y, paint)
    paint.style = Paint.Style.FILL
    paint.color = color.toArgb()
    canvas.drawText(text, at.x, at.y, paint)
}

/** A note's mark at list and legend size (14dp box, radius 4.5 as on the web). */
@Composable
private fun MarkIcon(mark: NodeMark, inks: Inks) {
    Canvas(Modifier.size(14.dp).clearAndSetSemantics {}) { drawNodeMark(mark, center, 4.5.dp.toPx(), inks) }
}

@Composable
private fun Legend(inks: Inks, modifier: Modifier = Modifier) {
    @Composable fun item(name: String, icon: @Composable () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { icon(); Text(name, style = Type.xs, color = t.muted) }
    }
    @Composable fun line(k: EdgeKind) {
        val color = inks.edge(k)
        Canvas(Modifier.size(18.dp, 8.dp).clearAndSetSemantics {}) {
            val y = size.height / 2
            drawLine(color, Offset(0f, y), Offset(size.width - 4.dp.toPx(), y), (edgeWidth(k) * density).toFloat(),
                pathEffect = edgeDash(k)?.let { d -> PathEffect.dashPathEffect(d.map { (it * density).toFloat() }.toFloatArray()) })
            if (k == EdgeKind.Derived) drawArrow(arrowHead(Pt(0.0, y.toDouble()), Pt(size.width.toDouble(), y.toDouble()), 5.dp.toPx().toDouble()), color)
        }
    }
    Row(
        modifier.horizontalScroll(rememberScrollState()).heightIn(min = 48.dp).semantics { contentDescription = "Key" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("Successful") { MarkIcon(NodeMark.Success, inks) }
        item("Failed") { MarkIcon(NodeMark.Failed, inks) }
        item("Unmarked") { MarkIcon(NodeMark.Unmarked, inks) }
        item("Link") { line(EdgeKind.Link) }
        item("Sub-note") { line(EdgeKind.Parent) }
        item("Attempt") { line(EdgeKind.Derived) }
    }
}

/** Tap on a note: what it is and where to go next, over the map's lower edge. */
@Composable
private fun PreviewSheet(n: GraphNode, projectName: String?, isFocus: Boolean, onOpen: () -> Unit, onMapAround: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val inks = rememberInks()
    Column(modifier.fillMaxWidth().background(t.bg).topLine(t.line).semantics { contentDescription = "Preview: ${n.label}" }) {
        PageColumn(Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkIcon(n.mark, inks)
                Text(displayTitle(n.title), Modifier.semantics { heading() }, style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (n.title.isBlank()) t.muted else t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val outcome = n.outcome?.label ?: "Unmarked"
            val connections = if (n.degree == 1) "1 connection" else "${n.degree} connections"
            val facts = listOfNotNull(outcome, projectName, n.createdAt?.let { runCatching { "Created ${listDate(it)}" }.getOrNull() }, connections)
            Text(facts.joinToString(" · "), Modifier.padding(top = 4.dp), style = Type.xs, color = t.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TextBtn("Open", onOpen, strong = true)
                if (!isFocus) TextBtn("Map around this", onMapAround)
                TextBtn("Close", onClose)
            }
        }
    }
}

/** The map as rows: every note (Everything), or the focus's neighbours by how they connect (Around). Each row a 48dp link. */
@Composable
private fun MapList(s: Shown, lens: MapLens, focus: String?, depth: Int, onOpenNote: (String) -> Unit, filter: MapFilter = MapFilter()) {
    val inks = rememberInks()
    val keep = { n: GraphNode -> !filter.filtering || filter.matches(n) }
    @Composable fun row(n: GraphNode) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).plainClick(role = Role.Button) { onOpenNote(n.id) }.clearAndSetSemantics {
                contentDescription = n.label; role = Role.Button; onClick { onOpenNote(n.id); true }
            },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MarkIcon(n.mark, inks)
            Text(displayTitle(n.title), Modifier.weight(1f), style = Type.body, color = if (n.title.isBlank()) t.muted else t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${n.degree}", style = Type.xs, color = t.muted)
        }
    }
    // A lazy list: only the rows in view are made, however many notes there are.
    val groups: List<ListGroup>? = if (lens == MapLens.Around && focus != null) aroundList(s.whole, focus, depth) else null
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().topLine(t.line), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        if (groups != null) {
            if (groups.isEmpty()) item { PageColumn { Muted("Nothing connects to this note yet.", Modifier.padding(vertical = 20.dp)) } }
            groups.forEach { g ->
                val nodes = g.nodes.filter(keep)
                if (nodes.isEmpty()) return@forEach
                item(key = "g:" + g.group) { PageColumn { Text(g.group, Modifier.padding(top = 24.dp).semantics { heading() }, style = Type.xs, color = t.muted) } }
                items(nodes.size, key = { "${g.group}:${nodes[it].id}" }) { i -> PageColumn { if (i > 0) Hairline(); row(nodes[i]) } }
            }
        } else {
            val nodes = (s.all + s.loose).filter(keep)
            items(nodes.size, key = { nodes[it].id }) { i -> PageColumn { if (i > 0) Hairline(); row(nodes[i]) } }
        }
    }
}

/** Find a note, and show only some outcomes or one project: the rest fades (web: MapToolbar). */
@Composable
private fun MapToolbar(filter: MapFilter, onFilter: (MapFilter) -> Unit, notes: List<GraphNode>, projects: List<app.benchjot.core.Project>, matched: Int?, onPick: (String) -> Unit, inPicture: (String) -> Boolean) {
    val inks = rememberInks()
    val q = filter.find.trim().lowercase()
    val found = remember(q, notes) { if (q.isEmpty()) emptyList() else notes.filter { it.title.lowercase().contains(q) }.take(6) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).background(t.raise, RoundedCornerShape(12.dp)).border(1.dp, t.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BjIcon(Icons.Search, tint = t.faint, size = 16.dp)
            androidx.compose.foundation.text.BasicTextField(
                filter.find, { onFilter(filter.copy(find = it)); picking = true },
                Modifier.weight(1f).semantics { contentDescription = "Find a note on the map" },
                textStyle = Type.sm.copy(color = t.fg), singleLine = true, cursorBrush = androidx.compose.ui.graphics.SolidColor(t.fg),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { found.firstOrNull()?.let { onPick(it.id); picking = false } }),
                decorationBox = { inner -> Box { if (filter.find.isEmpty()) Text("Find a note", style = Type.sm, color = t.faint); inner() } },
            )
            if (filter.find.isNotEmpty()) Box(Modifier.size(32.dp).plainClick { onFilter(filter.copy(find = "")) }.semantics { contentDescription = "Clear" }, contentAlignment = Alignment.Center) {
                BjIcon(Icons.X, tint = t.muted, size = 14.dp)
            }
        }
        if (picking && found.isNotEmpty()) Column(Modifier.fillMaxWidth().card(radius = 12.dp)) {
            found.forEach { n ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).plainClick { onPick(n.id); picking = false }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MarkIcon(n.mark, inks)
                    Text(displayTitle(n.title), Modifier.weight(1f), style = Type.sm, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!inPicture(n.id)) Text("Map around it", style = Type.xs, color = t.faint)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((m, word) in listOf(NodeMark.Success to "Successful", NodeMark.Failed to "Failed", NodeMark.Unmarked to "Unmarked")) {
                val on = m in filter.marks
                Row(
                    Modifier.heightIn(min = 40.dp)
                        .plainClick {
                            val next = if (on && filter.marks.size > 1) filter.marks - m else filter.marks + m
                            onFilter(filter.copy(marks = next))
                        }
                        .semantics { contentDescription = "$word notes"; selected = on }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(word, style = Type.sm.copy(textDecoration = if (on) null else androidx.compose.ui.text.style.TextDecoration.LineThrough), color = if (on) t.fg else t.faint)
                }
            }
            if (projects.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.heightIn(min = 40.dp).background(t.raise, RoundedCornerShape(12.dp)).border(1.dp, t.line, RoundedCornerShape(12.dp)).plainClick { open = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BjIcon(Icons.Folder, tint = t.muted, size = 14.dp)
                        Text(projects.firstOrNull { it.id == filter.project }?.name ?: "All projects", style = Type.xs, color = t.fg, maxLines = 1)
                    }
                    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = t.raise) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("All projects", style = Type.sm, color = t.fg) }, onClick = { onFilter(filter.copy(project = null)); open = false })
                        projects.forEach { p -> androidx.compose.material3.DropdownMenuItem(text = { Text(p.name, style = Type.sm, color = t.fg) }, onClick = { onFilter(filter.copy(project = p.id)); open = false }) }
                    }
                }
            }
        }
        if (matched != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$matched ${if (matched == 1) "match" else "matches"} in the picture", Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = Type.xs, color = t.muted)
            TextBtn("Clear", { onFilter(MapFilter()) }, style = Type.xs)
        }
    }
}

