package app.benchjot.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.benchjot.core.EdgeKind
import app.benchjot.core.MapView
import app.benchjot.core.buildEdges
import app.benchjot.core.fitView
import app.benchjot.core.hitNode
import app.benchjot.core.layoutGraph
import app.benchjot.core.mapLabel
import app.benchjot.core.zoomAt
import app.benchjot.data.Backend
import kotlin.math.hypot

// The note map, as the web's note-map.tsx: every note as a dot, links as solid
// lines, parent -> sub-note as dashed lines. Drag to pan, pinch to zoom, tap a
// note to open it. Drawn in screen coordinates so dots, lines and labels stay the
// same size at any zoom. Monochrome: the theme's fg / muted / faint.

private const val TAP_SLOP_DP = 8f
private const val HIT_RADIUS_DP = 24f // a 48dp tap target around each dot

@Composable
fun NoteMapScreen(onHome: () -> Unit, onOpenNote: (String) -> Unit) {
    val notes = rememberLive { Backend.noteIndex() }
    val links = rememberLive { Backend.noteLinks() }
    val graph = remember(notes.value, links.value) {
        val n = notes.value ?: return@remember null
        val l = links.value ?: return@remember null
        val edges = buildEdges(n, l)
        val placed = layoutGraph(n, edges)
        Triple(edges, placed, placed.associateBy { it.id })
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var view by remember { mutableStateOf(MapView(1.0, 0.0, 0.0)) }
    var fitted by remember { mutableStateOf("") }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize()) {
        PageColumn {
            TopBar(onHome = onHome, back = "All notes" to onHome) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(dashed = false, "Link")
                    Legend(dashed = true, "Sub-note")
                }
            }
        }
        Hairline()
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { size = it }) {
            when {
                notes.error != null || links.error != null -> Text("Could not load the map.", Modifier.padding(20.dp), style = Type.sm, color = t.fg)
                notes.value?.isEmpty() == true -> Text("No notes yet.", Modifier.padding(20.dp), style = Type.sm, color = t.muted)
            }
            val g = graph ?: return@Box
            if (size == IntSize.Zero || g.second.isEmpty()) return@Box
            // Fit the whole map when it (or the screen) first becomes known.
            val key = "${g.second.size}:${size.width}x${size.height}"
            LaunchedEffect(key) {
                // Graph units are CSS px on the web; here they are dp, so fit in dp and scale to px.
                val fit = fitView(g.second, size.width / density.density.toDouble(), size.height / density.density.toDouble())
                view = MapView(fit.k * density.density, fit.x * density.density, fit.y * density.density)
                fitted = key
            }
            if (fitted != key) return@Box
            val fg = t.fg
            val muted = t.muted
            val faint = t.faint
            val bg = t.bg
            val dot = with(density) { 5.dp.toPx() }
            val hit = with(density) { HIT_RADIUS_DP.dp.toPx() }.toDouble()
            val slop = with(density) { TAP_SLOP_DP.dp.toPx() }
            val label = remember(fg, bg, density) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 12.dp.toPx() } }
            }
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "Map of ${g.second.size} notes"; role = Role.Image }
                    .pointerInput(g) {
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
                            if (!moved && !multi) {
                                hitNode(g.second, view, down.position.x.toDouble(), down.position.y.toDouble(), hit)?.let { onOpenNote(it.id) }
                            }
                        }
                    },
            ) {
                val v = view
                fun at(id: String) = g.third[id]!!.let { Offset(v.sx(it.x).toFloat(), v.sy(it.y).toFloat()) }
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                for (e in g.first) {
                    drawLine(
                        if (e.kind == EdgeKind.Link) muted else faint, at(e.source), at(e.target),
                        strokeWidth = 1.dp.toPx(), pathEffect = if (e.kind == EdgeKind.Parent) dash else null,
                    )
                }
                for (n in g.second) {
                    val p = Offset(v.sx(n.x).toFloat(), v.sy(n.y).toFloat())
                    drawCircle(fg, dot, p)
                    drawLabel(mapLabel(n.title), p + Offset(9.dp.toPx(), 4.dp.toPx()), label, if (n.title.isBlank()) muted else fg, bg)
                }
            }
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

@Composable
private fun Legend(dashed: Boolean, name: String) {
    val color = if (dashed) t.faint else t.muted
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(18.dp, 2.dp)) {
            drawLine(color, Offset(0f, 1.dp.toPx()), Offset(18.dp.toPx(), 1.dp.toPx()), 1.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null)
        }
        Text(name, style = Type.xs, color = t.muted)
    }
}
