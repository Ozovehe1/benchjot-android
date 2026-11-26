package app.benchjot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.benchjot.core.Logged
import app.benchjot.core.chartDefault
import app.benchjot.core.differences
import app.benchjot.core.formatLogged
import app.benchjot.core.loggedColumns
import app.benchjot.core.loggedNumber
import app.benchjot.core.numericColumn
import app.benchjot.data.ExperimentRun
import app.benchjot.data.Experiments
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Experiment tracking on the phone (the web's components/outputs/logged-values.tsx and
// components/notes/runs-table.tsx): what a run logged, under its output, and the note's runs side by
// side, with a chart of one logged number across runs. Only what the code logged: nothing inferred.

/** What a run logged with log(), under its output. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoggedValues(logged: Logged) {
    if (logged.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        Eyebrow("Logged")
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, v) in logged) {
                Row {
                    Text(k, style = Type.output, color = t.muted)
                    Spacer(Modifier.width(6.dp))
                    Text(formatLogged(v), style = Type.output, color = t.fg)
                }
            }
        }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("d MMM")
private fun local(iso: String) = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()) }.getOrNull()
private fun clock(iso: String) = local(iso)?.format(CLOCK) ?: ""
private fun day(iso: String): String {
    val d = local(iso)?.toLocalDate() ?: return ""
    val today = LocalDate.now()
    return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(DATE) }
}
private val OUTCOME = mapOf("error" to "failed", "timeout" to "ran out of time", "stopped" to "stopped")

/** The note's Runs: every run of its cells that called log(), newest first. Nothing when no runs and no Python. */
@Composable
fun RunsSection(noteId: String, cellIds: List<String>, hasPython: Boolean) {
    val runs by Experiments.runs(noteId).collectAsState()
    LaunchedEffect(noteId) { runCatching { Experiments.refresh(noteId) } }
    val all = runs ?: return
    val scope = rememberCoroutineScope()
    var sortBy by remember(noteId) { mutableStateOf<String?>(null) }
    var desc by remember(noteId) { mutableStateOf(true) }
    var picked by remember(noteId) { mutableStateOf(listOf<String>()) }
    var metric by remember(noteId) { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ExperimentRun?>(null) }

    if (all.isEmpty()) {
        if (!hasPython) return
        Column(Modifier.padding(top = 40.dp)) {
            Eyebrow("Runs")
            Spacer(Modifier.height(6.dp))
            Muted("Track a run: add log(accuracy=acc, lr=lr) to a cell. Each run that logs shows here, side by side, so you can see what changed.")
        }
        return
    }

    val logs = all.map { it.logged }
    val columns = loggedColumns(logs)
    val numeric = columns.filter { numericColumn(logs, it) }
    val sorted = all.sortedWith { a, b ->
        val c = sortBy
        if (c == null) return@sortedWith if (desc) b.ranAt.compareTo(a.ranAt) else a.ranAt.compareTo(b.ranAt)
        val x = a.logged[c]; val y = b.logged[c]
        when {
            x == null && y == null -> b.ranAt.compareTo(a.ranAt)
            x == null -> 1
            y == null -> -1
            else -> {
                val nx = loggedNumber(x); val ny = loggedNumber(y)
                val r = if (nx != null && ny != null) nx.compareTo(ny) else formatLogged(x).compareTo(formatLogged(y))
                if (desc) -r else r
            }
        }
    }
    val pair = picked.mapNotNull { id -> all.firstOrNull { it.id == id } }
    val differ = if (pair.size == 2) differences(pair[0].logged, pair[1].logged).toSet() else emptySet()
    val chartMetric = metric?.takeIf { it in numeric } ?: chartDefault(numeric)

    Column(Modifier.padding(top = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Runs")
            Spacer(Modifier.width(6.dp))
            Text("${all.size}", style = Type.eyebrow, color = t.faint)
        }
        Spacer(Modifier.height(4.dp))
        Muted(
            "Every run of a cell that called log(…), with what it logged, so you can see what changed between runs." +
                if (all.size > 1) (if (pair.size == 2) " Bold: what differs between the two ticked runs." else " Tick two to compare them.") else "",
        )
        Spacer(Modifier.height(12.dp))

        if (chartMetric != null && all.size >= 2) {
            MetricChart(all, chartMetric, numeric) { metric = it }
            Spacer(Modifier.height(12.dp))
        }

        Column(Modifier.fillMaxWidth().card().horizontalScroll(rememberScrollState())) {
            // Header
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                HeaderCell("Run" + arrow(sortBy == null, desc), 88.dp) { if (sortBy == null) desc = !desc else { sortBy = null; desc = true } }
                HeaderCell("Cell", 44.dp) {}
                for (c in columns) HeaderCell(c + arrow(sortBy == c, desc), 104.dp, end = c in numeric, mono = true) {
                    if (sortBy == c) desc = !desc else { sortBy = c; desc = true }
                }
                Spacer(Modifier.width(44.dp))
            }
            Hairline()
            for ((i, r) in sorted.withIndex()) {
                val on = r.id in picked
                Row(Modifier.background(if (on) t.tint else t.raise).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = on,
                        onCheckedChange = { picked = if (on) picked - r.id else (picked.takeLast(1) + r.id) },
                        colors = CheckboxDefaults.colors(checkedColor = t.fg, uncheckedColor = t.faint, checkmarkColor = t.bg),
                        modifier = Modifier.width(48.dp),
                    )
                    Column(Modifier.width(88.dp).padding(horizontal = 6.dp)) {
                        Text(clock(r.ranAt), style = Type.sm, color = t.fg)
                        Row {
                            Text(day(r.ranAt), style = Type.xs, color = t.faint)
                            OUTCOME[r.outcome]?.let { Text("  $it", style = Type.xs, color = t.fail) }
                        }
                    }
                    Text(cellIds.indexOf(r.cellId).takeIf { it >= 0 }?.let { "${it + 1}" } ?: "—", style = Type.sm, color = t.muted, modifier = Modifier.width(44.dp).padding(horizontal = 6.dp))
                    for (c in columns) {
                        val bold = on && c in differ
                        Text(
                            formatLogged(r.logged[c]),
                            style = Type.output.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (bold) t.fg else t.fg.copy(alpha = 0.9f),
                            textAlign = if (c in numeric) TextAlign.End else TextAlign.Start,
                            maxLines = 1,
                            modifier = Modifier.width(104.dp).padding(horizontal = 6.dp),
                        )
                    }
                    Box(Modifier.size(44.dp).clickable { removing = r }, contentAlignment = Alignment.Center) {
                        BjIcon(Icons.Trash, tint = t.faint, size = 16.dp)
                    }
                }
                if (i < sorted.lastIndex) Hairline()
            }
        }
    }

    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this run?") },
            text = { Text("It goes from the table. The cell and its output stay.") },
            confirmButton = { TextButton({ removing = null; scope.launch { runCatching { Experiments.delete(noteId, r.id) } } }) { Text("Remove") } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

private fun arrow(on: Boolean, desc: Boolean) = if (on) (if (desc) " ↓" else " ↑") else ""

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp, end: Boolean = false, mono: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = (if (mono) Type.output else Type.xs).copy(fontSize = Type.xs.fontSize),
        color = t.muted,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = Modifier.width(width).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/** One logged number across runs, oldest to newest: a 2dp line, a marker per run, the value of a tapped run. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricChart(runs: List<ExperimentRun>, metric: String, options: List<String>, onMetric: (String) -> Unit) {
    val points = runs.mapNotNull { r -> loggedNumber(r.logged[metric])?.let { r.ranAt to it } }.sortedBy { it.first }
    if (points.size < 2) return
    var picked by remember(metric, points.size) { mutableStateOf<Int?>(null) }
    val values = points.map { it.second }
    var lo = values.min(); var hi = values.max()
    if (lo == hi) { val pad = kotlin.math.abs(lo) * 0.05 + if (lo == 0.0) 1.0 else 0.0; lo -= pad; hi += pad }
    val ink = t.fg
    val rule = t.line
    val ring = t.raise
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metric, style = Type.output, color = t.fg)
            Text(" over ${points.size} runs", style = Type.sm, color = t.muted)
        }
        if (options.size > 1) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in options) {
                    val on = m == metric
                    Text(
                        m, style = Type.output.copy(fontSize = Type.xs.fontSize), color = if (on) t.bg else t.muted,
                        modifier = Modifier.background(if (on) t.fg else t.tint, RoundedCornerShape(50)).clickable { onMetric(m) }.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Canvas(
                Modifier.fillMaxWidth().height(140.dp).pointerInput(points.size) {
                    detectTapGestures { o ->
                        val step = (size.width - 24f) / (points.size - 1)
                        picked = (((o.x - 12f) / step) + 0.5f).toInt().coerceIn(0, points.lastIndex)
                    }
                },
            ) {
                val pad = 12f
                val bottom = size.height - 18f
                fun x(i: Int) = pad + i * (size.width - 2 * pad) / (points.size - 1)
                fun y(v: Double) = (pad + (hi - v) * (bottom - pad) / (hi - lo)).toFloat()
                drawLine(rule, Offset(pad, bottom), Offset(size.width - pad, bottom), 1f)
                val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.second)) else lineTo(x(i), y(p.second)) } }
                drawPath(path, ink.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                picked?.let { i -> drawLine(rule, Offset(x(i), pad), Offset(x(i), bottom), 1f) }
                points.forEachIndexed { i, p ->
                    val r = if (picked == i) 5.dp.toPx() else 4.dp.toPx()
                    drawCircle(ring, r + 2.dp.toPx(), Offset(x(i), y(p.second)))
                    drawCircle(ink, r, Offset(x(i), y(p.second)))
                }
            }
            Text(formatLogged(kotlinx.serialization.json.JsonPrimitive(hi)), style = Type.output.copy(fontSize = Type.xs.fontSize), color = t.faint, modifier = Modifier.align(Alignment.TopEnd))
            Text(formatLogged(kotlinx.serialization.json.JsonPrimitive(lo)), style = Type.output.copy(fontSize = Type.xs.fontSize), color = t.faint, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 18.dp))
        }
        val shown = picked?.let { points.getOrNull(it) }
        Text(
            if (shown != null) "$metric ${formatLogged(kotlinx.serialization.json.JsonPrimitive(shown.second))} · ${day(shown.first)} ${clock(shown.first)}" else "Tap the chart to read a run's value.",
            style = Type.xs, color = if (shown != null) t.fg else t.faint, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
