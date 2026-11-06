package app.benchjot.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.CellNoteSide
import app.benchjot.core.Outcome
import app.benchjot.core.SAMPLE_ASSETS
import app.benchjot.core.SampleNote
import app.benchjot.core.cellNoteName
import app.benchjot.core.Snippet
import app.benchjot.core.fromLastRun
import app.benchjot.core.parseSampleNote

// A note shown read-only, built from the note screen's own pieces: the title type, the
// outcome row, the rendered body, the code block header, highlighted code, the saved
// output panel and its figures, tables and charts. No text fields, no Run, no data
// access. As the web's note-preview.tsx. Outputs read `files`; tapping one calls `onOpen`.

@Composable
fun NotePreview(title: String, notes: String, outcome: String?, snippets: List<Snippet>, files: OutputFiles?, onOpen: ((Int) -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().border(1.dp, t.line, RoundedCornerShape(6.dp)).padding(start = 16.dp, end = 16.dp, bottom = 4.dp).semantics { contentDescription = "Sample note" }) {
        // Tagged as a sample, apart from the landing's own words (the web's figcaption).
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) { SampleNoteTag() }
        Text(title, Modifier.fillMaxWidth().padding(top = 24.dp), style = Type.xl, color = t.fg)
        Outcome.of(outcome)?.let { o ->
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Outcome", style = Type.xs, color = t.muted)
                Text(o.label, style = Type.xs, color = t.fg)
            }
        }
        RenderedBody(notes, reserve = false)
        Column(Modifier.padding(top = 40.dp)) {
            snippets.forEachIndexed { i, s -> SnippetView(s, i, files, onOpen) }
        }
    }
}

@Composable
private fun SnippetView(snippet: Snippet, index: Int, files: OutputFiles?, onOpen: ((Int) -> Unit)?) {
    val run = remember(snippet) { snippet.lastRun?.let(::fromLastRun) }
    val outputs = snippet.lastRun?.outputs
    Column(Modifier.fillMaxWidth().topLine(t.line)) {
        RenderedCellNote(snippet.noteBefore, cellNoteName(CellNoteSide.Before, snippet.filename, index))
        SnippetHeader(snippet.filename, language = { LanguageLabel(snippet.language) })
        FoldedCode(snippet.content) { StaticCode(snippet.content, snippet.language, "Code ${index + 1}") }
        run?.let { (chunks, result) -> RunOutput(chunks, status = "", running = false, result = result) }
        if (outputs != null && files != null) RunOutputs(outputs.items, outputs.notes, files, onOpen)
        RenderedCellNote(snippet.noteAfter, cellNoteName(CellNoteSide.After, snippet.filename, index))
    }
}

/** The landing's example note, from the APK's assets (the web's sample, copied by scripts/web-fixtures.mjs). */
fun loadSampleNote(context: Context): SampleNote =
    parseSampleNote(context.assets.open("$SAMPLE_ASSETS/note.json").use { it.readBytes().decodeToString() })

/** Tags the landing's sample (the web's SampleNoteTag): "Sample note", small and muted. */
@Composable
fun SampleNoteTag() {
    Text("Sample note", Modifier.border(1.dp, t.line, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), style = androidx.compose.ui.text.TextStyle(fontFamily = Type.mono, fontSize = 11.sp, lineHeight = 16.sp), color = t.muted)
}
