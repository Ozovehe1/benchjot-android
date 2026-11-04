package app.benchjot.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import app.benchjot.core.chart.Camera
import app.benchjot.core.chart.Chart
import app.benchjot.core.chart.rotate
import app.benchjot.core.chart.zoom3d
import app.benchjot.core.chart.ChartTheme
import app.benchjot.core.chart.Hover
import app.benchjot.core.chart.Op
import app.benchjot.core.chart.Scene
import app.benchjot.core.chart.Viewport
import app.benchjot.core.chart.describe
import app.benchjot.core.chart.fullRange
import app.benchjot.core.chart.hitTest
import app.benchjot.core.chart.layout
import app.benchjot.core.chart.legendAt
import app.benchjot.core.chart.pan
import app.benchjot.core.chart.zoom
import kotlin.math.max

// Native interactive charts (no WebView): the core Scene drawn on an Android Canvas, on
// screen and for PNG / JPG / WebP downloads alike. Pinch to zoom and drag to pan the axes,
// tap or long-press a point for its values, tap a legend entry to hide / show its series,
// double-tap to reset.

/** Draws scene ops on any Android Canvas (the screen through Compose, or a Bitmap for export). */
fun drawOps(canvas: Canvas, ops: List<Op>) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    var clipped = false
    for (op in ops) {
        paint.reset(); paint.isAntiAlias = true
        when (op) {
            is Op.Clip -> { canvas.save(); canvas.clipRect(op.l, op.t, op.r, op.b); clipped = true }
            Op.Unclip -> if (clipped) { canvas.restore(); clipped = false }
            is Op.Line -> {
                paint.style = Paint.Style.STROKE; paint.color = op.color; paint.strokeWidth = op.width
                paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
                if (op.pts.size == 4) canvas.drawLine(op.pts[0], op.pts[1], op.pts[2], op.pts[3], paint)
                else canvas.drawPath(polyline(op.pts, close = false), paint)
            }
            is Op.Fill -> { paint.style = Paint.Style.FILL; paint.color = op.color; canvas.drawPath(polyline(op.pts, close = true), paint) }
            is Op.Rect -> {
                paint.style = Paint.Style.FILL; paint.color = op.color
                canvas.drawRoundRect(RectF(op.l, op.t, op.r, op.b), op.radius, op.radius, paint)
                op.stroke?.let { paint.style = Paint.Style.STROKE; paint.color = it; paint.strokeWidth = 1f; canvas.drawRoundRect(RectF(op.l, op.t, op.r, op.b), op.radius, op.radius, paint) }
            }
            is Op.Circle -> { paint.style = Paint.Style.FILL; paint.color = op.color; canvas.drawCircle(op.x, op.y, op.r, paint) }
            is Op.Text -> {
                paint.color = op.color; paint.textSize = op.size
                if (op.bold) paint.typeface = bold
                paint.textAlign = when (op.align) {
                    app.benchjot.core.chart.Align.Left -> Paint.Align.LEFT
                    app.benchjot.core.chart.Align.Center -> Paint.Align.CENTER
                    app.benchjot.core.chart.Align.Right -> Paint.Align.RIGHT
                }
                if (op.rotate) { canvas.save(); canvas.rotate(-90f, op.x, op.y); canvas.drawText(op.text, op.x, op.y, paint); canvas.restore() }
                else canvas.drawText(op.text, op.x, op.y, paint)
            }
            is Op.Wedge -> {
                paint.style = Paint.Style.FILL; paint.color = op.color
                val outer = RectF(op.cx - op.r, op.cy - op.r, op.cx + op.r, op.cy + op.r)
                val path = Path().apply {
                    if (op.hole > 0) {
                        arcTo(outer, op.start - 90, op.sweep, true)
                        arcTo(RectF(op.cx - op.hole, op.cy - op.hole, op.cx + op.hole, op.cy + op.hole), op.start - 90 + op.sweep, -op.sweep)
                    } else { moveTo(op.cx, op.cy); arcTo(outer, op.start - 90, op.sweep) }
                    close()
                }
                canvas.drawPath(path, paint)
                op.gap?.let { paint.style = Paint.Style.STROKE; paint.color = it; paint.strokeWidth = 2f; canvas.drawPath(path, paint) }
            }
        }
    }
    if (clipped) canvas.restore()
}

private fun polyline(pts: FloatArray, close: Boolean) = Path().apply {
    if (pts.size >= 2) moveTo(pts[0], pts[1])
    var i = 2
    while (i + 1 < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    if (close) close()
}

/** The chart drawn into a bitmap (PNG / JPG / WebP downloads): light colours on white, like the web's downloads. */
fun renderChart(chart: Chart, width: Int, height: Int, density: Float, hidden: Set<Int>, view: Viewport?, background: Int = 0xffffffff.toInt(), camera: Camera = Camera()): Pair<Bitmap, Scene> {
    val theme = ChartTheme(background, 0xff161616.toInt(), 0xff5c5a57.toInt(), 0xffd0cecb.toInt())
    val scene = layout(chart, width.toFloat(), height.toFloat(), theme, density, view, hidden, camera = camera)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply { drawColor(background); drawOps(this, scene.ops) }
    return bmp to scene
}

/** What the viewer needs to export the chart as the user sees it (hidden series, zoom). */
class ChartState(val chart: Chart) {
    var hidden by mutableStateOf(chart.hidden)
    var view by mutableStateOf<Viewport?>(null)
    var hover by mutableStateOf<Hover?>(null)
    /** 3D charts: where they're seen from (drag turns it). */
    var camera by mutableStateOf(Camera())
}

/**
 * A chart from the normalized model. `interactive` false: drawn still (inline in a note,
 * where taps go to the viewer). True: zoom, pan, tooltips, legend toggles, double-tap reset.
 */
@Composable
fun ChartCanvas(state: ChartState, interactive: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val theme = ChartTheme(t.bg.toArgb(), t.fg.toArgb(), t.muted.toArgb(), t.line.toArgb())
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else w * 0.66f
        val chart = state.chart
        val scene = remember(chart, w, h, theme, state.view, state.hidden, state.hover, state.camera) {
            layout(chart, w, h, theme, density, state.view, state.hidden, state.hover, compact = !interactive, camera = state.camera)
        }
        val gestures = if (!interactive) Modifier else Modifier
            .pointerInput(chart, w, h) {
                detectTransformGestures { centroid, panBy, zoomBy, _ ->
                    if (chart.isPie) return@detectTransformGestures
                    val s = layout(chart, w, h, theme, density, state.view, state.hidden, camera = state.camera)
                    if (chart.is3d) {
                        // 3D: drag turns the camera, pinch moves it closer.
                        var c = rotate(state.camera, panBy.x, panBy.y, s.plot)
                        if (zoomBy != 1f) c = zoom3d(c, zoomBy.toDouble())
                        state.camera = c
                        state.hover = null
                        return@detectTransformGestures
                    }
                    var v = state.view ?: fullRange(chart, state.hidden)
                    if (zoomBy != 1f) v = zoom(v, s.plot, zoomBy.toDouble(), centroid.x, centroid.y)
                    v = pan(v, s.plot, panBy.x, panBy.y, chart.x.reversed, chart.y.reversed)
                    state.view = v
                    state.hover = null
                }
            }
            .pointerInput(chart, w, h) {
                fun tap(x: Float, y: Float) {
                    val s = layout(chart, w, h, theme, density, state.view, state.hidden, camera = state.camera)
                    val legend = legendAt(s, x, y, density)
                    if (legend != null) {
                        state.hidden = if (legend in state.hidden) state.hidden - legend else state.hidden + legend
                        state.hover = null
                        return
                    }
                    state.hover = hitTest(chart, s, state.view, state.hidden, x, y, density, state.camera)
                }
                detectTapGestures(
                    onTap = { tap(it.x, it.y) },
                    onLongPress = { tap(it.x, it.y) },
                    onDoubleTap = { state.view = null; state.camera = Camera(); state.hover = null },
                )
            }
        Box(
            Modifier.fillMaxSize().then(gestures).testTag("chart")
                .semantics {
                    contentDescription = describe(chart, state.hidden)
                    stateDescription = state.hover?.lines?.joinToString(", ") ?: ""
                }
                .drawBehind { drawIntoCanvas { drawOps(it.nativeCanvas, scene.ops) } },
        )
    }
}

/** Inline height for a chart: two thirds of the width, at most 320 dp. */
fun inlineChartHeight(widthDp: Float) = max(200f, minOf(widthDp * 0.66f, 320f))
