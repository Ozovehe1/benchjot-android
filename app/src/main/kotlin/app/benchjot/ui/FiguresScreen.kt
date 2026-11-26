package app.benchjot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benchjot.core.Figure
import app.benchjot.core.LastRun
import app.benchjot.core.displayTitle
import app.benchjot.core.figuresOf
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Figures (the web's components/lab/figures.tsx): every visual from every experiment's latest runs in
// one place, newest first. Filter by kind or by words (a title, a note, what the Assistant saw in it);
// a tap opens the note at its cell.

@Serializable
private data class FigureCell(val id: String, @SerialName("note_id") val noteId: String, @SerialName("last_run") val lastRun: LastRun? = null)

/** The most recent cells read for figures (the web's FIGURE_CELLS). */
private const val FIGURE_CELLS = 300L
private val KINDS = listOf(null to "All", "figure" to "Plots", "chart" to "Charts", "animation" to "Animations", "image" to "Images")

@Composable
fun FiguresScreen(onHome: () -> Unit, onOpen: (noteId: String, cellId: String) -> Unit) {
    var figures by remember { mutableStateOf<List<Figure>?>(null) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching {
            val index = Backend.noteIndex()
            titles = index.associate { it.id to displayTitle(it.title) }
            val rows = Backend.client.from("cells").select(Columns.list("id", "note_id", "last_run")) {
                filter { filterNot("last_run", io.github.jan.supabase.postgrest.query.filter.FilterOperator.IS, null) }
                order("updated_at", Order.DESCENDING)
                limit(FIGURE_CELLS)
            }.decodeList<FigureCell>()
            figures = figuresOf(rows.map { Triple(it.id, it.noteId, it.lastRun) }).filter { it.noteId in titles }
        }.onFailure { error = Accounts.message(it, "load_figures") }
    }
    val q = query.trim().lowercase()
    val shown = figures.orEmpty().filter { f ->
        (kind == null || f.kind == kind) && (q.isEmpty() || listOf(f.title, f.seen.orEmpty(), titles[f.noteId].orEmpty()).joinToString(" ").lowercase().contains(q))
    }

    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(2) }) {
            Column {
                TopBar(onHome = onHome, back = "All notes" to onHome)
                Text("Figures", Modifier.padding(top = 8.dp), style = Type.h1, color = t.fg)
                Muted(figures?.let { "${shown.size} ${if (shown.size == 1) "visual" else "visuals"} from your experiments’ latest runs" } ?: "Every plot, chart and animation your runs made", Modifier.padding(top = 6.dp))
                UnderlineField(query, { query = it }, "Search titles, notes, what’s in them", Modifier.padding(top = 16.dp))
                Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, label) in KINDS) {
                        val on = kind == k
                        Text(label, style = Type.xs, color = if (on) t.bg else t.muted, modifier = Modifier.background(if (on) t.fg else t.tint, RoundedCornerShape(50)).clickable { kind = k }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                error?.let { Notice(it, onDismiss = { error = null }) }
                when {
                    figures == null && error == null -> Working("Loading…", Modifier.padding(top = 16.dp))
                    figures != null && shown.isEmpty() -> Muted(if (figures!!.isEmpty()) "No figures yet. Run a cell that plots something, and it appears here." else "Nothing matches.", Modifier.padding(top = 16.dp))
                }
            }
        }
        items(shown, key = { it.key }) { f ->
            Column(Modifier.clickable { onOpen(f.noteId, f.cellId) }) {
                val image = rememberOutputImage(f.item.copy(files = mapOf("png" to f.file)), remember(f.outputs) { OutputFiles.Saved(f.outputs) }, 720)
                Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(10.dp)).background(Color.White)) {
                    image?.let { Image(it, f.title.ifEmpty { "Figure" }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                Text(f.title.ifEmpty { if (f.kind == "animation") "Animation" else "Figure" }, Modifier.padding(top = 8.dp), style = Type.sm, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(titles[f.noteId].orEmpty(), style = Type.xs, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
