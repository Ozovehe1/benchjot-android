package app.benchjot.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benchjot.core.DATA_FILE_MAX
import app.benchjot.core.DATA_QUOTA
import app.benchjot.core.openLine
import app.benchjot.data.Accounts
import app.benchjot.data.DATA_SOURCES
import app.benchjot.data.DataLibrary
import app.benchjot.data.Dataset
import app.benchjot.data.FoundDataset
import app.benchjot.data.FoundFile
import app.benchjot.data.WebApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Data (the web's components/lab/data.tsx): the user's datasets, which any cell opens by name on the
// phone, the browser or the server; one search across public data platforms; and Sources, the
// accounts runs read from (Hugging Face).

/** Results shown at a time, and a dataset's files shown before "Show all". */
private const val PAGE = 6
private const val FILES_PAGE = 5

private fun mb(b: Long) = when {
    b >= 1_048_576 -> if (b >= 10 * 1_048_576) "${b / 1_048_576} MB" else "%.1f MB".format(java.util.Locale.US, b / 1_048_576.0)
    b >= 1024 -> "${Math.round(b / 1024.0)} KB"
    else -> "$b B"
}

private fun dayOf(iso: String): String = runCatching {
    val d = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) }
}.getOrDefault("")

@Composable
fun DataScreen(onHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by DataLibrary.files.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Dataset?>(null) }
    LaunchedEffect(Unit) { runCatching { DataLibrary.refresh() }.onFailure { problem = Accounts.message(it, "load") } }

    fun act(label: String, work: suspend () -> Unit) {
        busy = label; problem = null
        scope.launch {
            try { work() } catch (e: Exception) { problem = Accounts.message(e, "upload") } finally { busy = null }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        act("Adding…") {
            for (u in uris) {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val n = context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "data"
                    n to (context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0))
                }
                DataLibrary.add(name, bytes)
            }
        }
    }

    PageColumn(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(onHome = onHome, back = "All notes" to onHome)
        Text("Data", Modifier.padding(top = 8.dp), style = Type.h1, color = t.fg)
        Muted("Datasets you or the Assistant added. Any cell opens one by its name, on the phone, in the browser or on the server.", Modifier.padding(top = 8.dp))

        // Add
        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledBtn("Upload files", { pick.launch(arrayOf("*/*")) }, enabled = busy == null, variant = ButtonVariant.Neutral, icon = Icons.Upload, small = true)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UnderlineField(
                url, { url = it }, "Or paste a file’s https address", Modifier.weight(1f),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                actions = KeyboardActions(onDone = { if (url.isNotBlank()) act("Downloading…") { WebApi.dataFetch(url.trim()); url = ""; DataLibrary.refresh() } }),
            )
            TextBtn("Add", { act("Downloading…") { WebApi.dataFetch(url.trim()); url = ""; DataLibrary.refresh() } }, enabled = url.isNotBlank() && busy == null)
        }
        busy?.let { Working(it, Modifier.padding(top = 12.dp)) }
        problem?.let { Notice(it, onDismiss = { problem = null }) }

        // Files
        val list = files
        val used = list.orEmpty().sumOf { it.size }
        Row(Modifier.fillMaxWidth().padding(top = 32.dp), verticalAlignment = Alignment.Bottom) {
            Eyebrow("Files", Modifier.weight(1f))
            if (list != null) Text("${mb(used)} of ${mb(DATA_QUOTA)} · up to ${mb(DATA_FILE_MAX)} a file", style = Type.xs, color = t.faint)
        }
        if (list != null) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(t.tint, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth((used.toFloat() / DATA_QUOTA).coerceIn(0f, 1f)).height(4.dp).background(t.muted, RoundedCornerShape(2.dp)))
        }
        when {
            list == null -> Working("Loading…", Modifier.padding(top = 12.dp))
            list.isEmpty() -> Muted("No datasets yet. Upload a CSV, Excel, Parquet or any file, paste an address, or search the platforms below. The Assistant saves what it downloads here too.", Modifier.padding(top = 12.dp).fillMaxWidth().card().padding(16.dp))
            else -> Column(Modifier.padding(top = 12.dp).fillMaxWidth().card()) {
                list.forEachIndexed { i, f ->
                    FileRow(f, onRemove = { removing = f })
                    if (i < list.lastIndex) Hairline()
                }
            }
        }

        DatasetSearch { scope.launch { runCatching { DataLibrary.refresh() } } }

        // Sources
        Column(Modifier.padding(top = 40.dp)) {
            Eyebrow("Sources")
            Muted("Data platforms your runs read from directly, with your own account. Public data needs none.", Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().card().padding(horizontal = 16.dp)) { ConnectionsBlock() }
        }
        Box(Modifier.height(64.dp))
    }

    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${f.name}?") },
            text = { Text("Cells that open it will say it’s missing.") },
            confirmButton = { TextButton({ removing = null; act("Removing…") { DataLibrary.remove(f.name) } }) { Text("Remove") } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(f: Dataset, onRemove: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.name, style = Type.output, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(mb(f.size), dayOf(f.updatedAt)).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.xs, color = t.faint)
        }
        TextBtn(if (copied) "Copied" else "Copy code", {
            clipboard.setText(AnnotatedString(openLine(f.name))); copied = true
            scope.launch { delay(1500); copied = false }
        }, style = Type.xs)
        Box(Modifier.size(44.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) { BjIcon(Icons.Trash, tint = t.faint, size = 16.dp) }
    }
}

/** Find datasets: one search across Hugging Face, Zenodo, OpenML, Harvard Dataverse and figshare (no sign-in). */
@Composable
private fun DatasetSearch(onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<FoundDataset>?>(null) }
    var missed by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var only by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    /** Results shown: a few at a time, more on request, so the list never takes the whole screen. */
    var limit by remember { mutableStateOf(PAGE) }
    val names = DATA_SOURCES.values.toList()

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true; error = null; open = null; only = null; asked = q; limit = PAGE
        scope.launch {
            try { val r = WebApi.dataSearch(q); results = r.results; missed = r.missed } catch (e: Exception) { error = Accounts.message(e, "load"); results = null } finally { searching = false }
        }
    }

    Column(Modifier.padding(top = 40.dp)) {
        Eyebrow("Find datasets")
        Muted("One search across ${names.dropLast(1).joinToString(", ")} and ${names.last()}. Public datasets, no sign-in. Add a file and every cell can open it.", Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UnderlineField(query, { query = it }, "iris, titanic, air quality…", Modifier.weight(1f), keyboard = KeyboardOptions(imeAction = ImeAction.Search), actions = KeyboardActions(onSearch = { search() }))
            TextBtn("Search", { search() }, enabled = query.isNotBlank() && !searching)
        }
        if (searching) Working("Searching ${names.size} platforms…", Modifier.padding(top = 12.dp))
        error?.let { Notice(it, onDismiss = { error = null }) }
        val all = results
        if (all != null && !searching) {
            if (missed.isNotEmpty()) Muted("${missed.joinToString(", ") { DATA_SOURCES[it] ?: it }} didn’t answer this time; showing the others.", Modifier.padding(top = 10.dp), style = Type.xs, color = t.faint)
            if (all.isEmpty()) Muted("No dataset titles match “$asked”. Try fewer or other words. OpenML matches exact dataset names only, such as iris or mnist_784.", Modifier.padding(top = 12.dp))
            else {
                Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("All ${all.size}", only == null) { only = null; limit = PAGE }
                    for ((id, name) in DATA_SOURCES) {
                        val n = all.count { it.source == id }
                        if (n > 0) Chip("$name $n", only == id) { only = id; limit = PAGE }
                    }
                }
                val matching = all.filter { only == null || it.source == only }
                val shown = matching.take(limit)
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().card()) {
                    shown.forEachIndexed { i, d ->
                        val key = "${d.source}:${d.id}"
                        Row(Modifier.fillMaxWidth().clickable { open = if (open == key) null else key }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, style = Type.sm, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(DATA_SOURCES[d.source] ?: d.source, d.detail).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.xs, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (open == key) "Hide" else "Files", style = Type.xs, color = t.muted)
                        }
                        if (open == key) DatasetFiles(d, onAdded)
                        if (i < shown.lastIndex) Hairline()
                    }
                }
                if (matching.size > shown.size) TextBtn("Show ${minOf(PAGE, matching.size - shown.size)} more · ${shown.size} of ${matching.size}", { limit += PAGE }, Modifier.padding(top = 4.dp), style = Type.sm)
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, style = Type.xs, color = if (on) t.bg else t.muted,
        modifier = Modifier.background(if (on) t.fg else t.tint, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun DatasetFiles(d: FoundDataset, onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var files by remember(d.id) { mutableStateOf<List<FoundFile>?>(null) }
    var error by remember(d.id) { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(setOf<String>()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var allFiles by remember(d.id) { mutableStateOf(false) }
    LaunchedEffect(d.source, d.id) {
        runCatching { WebApi.dataFiles(d.source, d.id).files }.onSuccess { files = it }.onFailure { error = Accounts.message(it, "load") }
    }
    Column(Modifier.fillMaxWidth().background(t.tint.copy(alpha = 0.4f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        val list = files
        when {
            error != null -> Muted(error!!, style = Type.xs)
            list == null -> Working("Reading its files…")
            list.isEmpty() -> Muted("No data files here (tables, arrays or data archives).", style = Type.xs)
            else -> (if (allFiles) list else list.take(FILES_PAGE)).forEach { f ->
                val tooBig = f.size > DATA_FILE_MAX
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = Type.output, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (f.size > 0) mb(f.size) else "Size not given") + if (tooBig) " · over 50 MB" else "", style = Type.xs, color = t.faint)
                    }
                    when {
                        f.url in added -> Text("Added", style = Type.xs, color = t.ok)
                        tooBig -> Text("Server cell", style = Type.xs, color = t.faint)
                        else -> TextBtn(if (adding == f.url) "Adding…" else "Add", {
                            adding = f.url; problem = null
                            scope.launch {
                                try { WebApi.dataFetch(f.url, f.name.substringAfterLast('/')); added = added + f.url; onAdded() } catch (e: Exception) { problem = Accounts.message(e, "upload") } finally { adding = null }
                            }
                        }, enabled = adding == null, style = Type.xs)
                    }
                }
            }
        }
        if (!allFiles && (files?.size ?: 0) > FILES_PAGE) TextBtn("Show all ${files!!.size} files", { allFiles = true }, style = Type.xs)
        problem?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.xs, color = t.fail) }
        if (d.page.startsWith("https://")) TextBtn("More about it on ${DATA_SOURCES[d.source] ?: d.source}", { uri.openUri(d.page) }, style = Type.xs, underline = true)
    }
}
