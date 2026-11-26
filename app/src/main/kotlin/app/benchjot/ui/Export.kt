package app.benchjot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.benchjot.core.Note
import app.benchjot.core.ExportCell
import app.benchjot.core.ExportFormat
import app.benchjot.core.ExportNote
import app.benchjot.core.Cell
import app.benchjot.core.exportBaseName
import app.benchjot.core.markdownToHtml
import app.benchjot.core.toMarkdownZip
import app.benchjot.core.toNotebook
import app.benchjot.core.toWebPage
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import app.benchjot.data.OutputStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Export note (as the web's More → Export note): the whole note as a Jupyter notebook, one web page,
// or Markdown with its files, with every cell's saved output, figures and tables (core NoteExport,
// checked against the web's own exports). Saved to Downloads/Benchjot; older phones get the share sheet.

/** The note as saved now (the screen flushed its edits first), with each saved run's files downloaded (a missing one is left out). */
private suspend fun exportNote(opened: Note, openedCells: List<Cell>): ExportNote {
    val note = runCatching { Backend.note(opened.id) }.getOrNull() ?: opened
    val saved = runCatching { Backend.cells(opened.id) }.getOrNull() ?: openedCells
    val cells = saved.map { s ->
        val outputs = s.lastRun?.outputs
        val files = LinkedHashMap<String, ByteArray>()
        if (outputs != null) for (name in outputs.items.flatMap { it.files.values }.distinct()) {
            runCatching { OutputStore.bytes(outputs, name) }.getOrNull()?.let { files[name] = it }
        }
        ExportCell(s.filename, s.language, s.content, s.noteBefore, s.noteAfter, s.lastRun, files)
    }
    return ExportNote(note.title, note.notes, note.outcome, cells, java.time.Instant.now().toString())
}

@Composable
fun ExportDialog(note: Note, cells: List<Cell>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<ExportFormat?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun export(format: ExportFormat) {
        busy = format
        message = null
        scope.launch {
            try {
                val made = exportNote(note, cells)
                val bytes = withContext(Dispatchers.Default) {
                    when (format) {
                        ExportFormat.Ipynb -> toNotebook(made).toByteArray()
                        ExportFormat.Html -> toWebPage(made, ::markdownToHtml).toByteArray()
                        ExportFormat.Md -> toMarkdownZip(made)
                    }
                }
                val name = "${exportBaseName(made.title)}.${format.ext}"
                message = if (saveToDownloads(context, name, format.mime, bytes) != null) "Saved to Downloads/Benchjot/$name"
                else { share(context, name, format.mime, bytes); null }
            } catch (e: Exception) {
                message = Accounts.message(e, "download")
            } finally {
                busy = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.bg,
        title = { Text("Export note", style = Type.xl, color = t.fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("With every cell’s output, figures and tables.", style = Type.sm, color = t.muted)
                ExportFormat.entries.forEach { f ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        TextBtn(if (busy == f) "Preparing…" else f.label, { export(f) }, strong = true, enabled = busy == null)
                        Text(f.detail, style = Type.xs, color = t.faint)
                    }
                }
                message?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.sm, color = t.fg) }
            }
        },
        confirmButton = { TextBtn("Done", onDismiss) },
    )
}
