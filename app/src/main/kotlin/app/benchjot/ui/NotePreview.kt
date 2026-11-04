package app.benchjot.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.benchjot.core.Snippet
import app.benchjot.core.fromLastRun

// A note shown read-only, built from the note screen's own pieces: the title type,
// the rendered body, the code block header, highlighted code and the saved output
// panel. No text fields, no Run, no data access. As the web's note-preview.tsx.

@Composable
fun NotePreview(title: String, notes: String, snippets: List<Snippet>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().border(1.dp, t.line, RoundedCornerShape(6.dp)).padding(start = 16.dp, end = 16.dp, bottom = 4.dp).semantics { contentDescription = "Example note" }) {
        Text(title, Modifier.fillMaxWidth().padding(top = 24.dp), style = Type.xl, color = t.fg)
        RenderedBody(notes, reserve = false)
        Column(Modifier.padding(top = 40.dp)) {
            snippets.forEachIndexed { i, s -> SnippetView(s, i) }
        }
    }
}

@Composable
private fun SnippetView(snippet: Snippet, index: Int) {
    val run = remember(snippet) { snippet.lastRun?.let(::fromLastRun) }
    Column(Modifier.fillMaxWidth().topLine(t.line)) {
        SnippetHeader(snippet.filename, language = { LanguageLabel(snippet.language) })
        StaticCode(snippet.content, snippet.language, "Code ${index + 1}")
        run?.let { (chunks, result) -> RunOutput(chunks, status = "", running = false, result = result) }
    }
}
