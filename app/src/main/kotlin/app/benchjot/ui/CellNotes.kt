package app.benchjot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.benchjot.core.Autosaver
import app.benchjot.core.CellNoteSide
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.Outcome
import app.benchjot.core.SaveRegistry
import app.benchjot.core.Snippet
import app.benchjot.core.cellNoteName
import app.benchjot.core.isCollapsedNote
import app.benchjot.data.Accounts
import app.benchjot.data.Backend
import kotlinx.coroutines.launch

// A code cell's "Note before run" (above the cell) and "Note after run" (below its output), as the
// web's cell notes (core CellNotes.kt). Each is a MarkdownBlockState like the body, so the shared
// format bar, undo and [[link]] autocomplete reach it; one autosaver writes both columns. Code
// cells stay orthogonal: SnippetBlock only shows the two slots it is given.

private data class CellNoteText(val before: String, val after: String)

/** One code cell with its "Note before run" above and "Note after run" below its output (the web's CodeCell). */
@Composable
fun CodeCell(
    snippet: Snippet,
    index: Int,
    outcome: Outcome?,
    pro: Boolean,
    registry: SaveRegistry,
    preview: Boolean,
    notes: List<NoteIndexItem>,
    onOpenNote: (String) -> Unit,
    onOpenOutput: (snippetId: String, index: Int) -> Unit,
) {
    val before = remember(snippet.id) { MarkdownBlockState(snippet.noteBefore) }
    val after = remember(snippet.id) { MarkdownBlockState(snippet.noteAfter) }
    val saver = remember(snippet.id) {
        Autosaver(Backend.scope, CellNoteText(snippet.noteBefore, snippet.noteAfter), registry, describe = { Accounts.message(it, "save_note") }) { v ->
            Backend.updateCellNotes(snippet.id, v.before, v.after)
        }
    }
    LaunchedEffect(before.text, after.text) { saver.update(CellNoteText(before.text, after.text)) }
    DisposableEffect(saver) { onDispose { Backend.scope.launch { saver.flush(); registry.remove(saver) } } }

    SnippetBlock(
        snippet, index, outcome, pro, registry,
        before = { CellNote(before, CellNoteSide.Before, snippet.filename, index, preview, notes, onOpenNote) },
        after = { CellNote(after, CellNoteSide.After, snippet.filename, index, preview, notes, onOpenNote) },
        onBeforeRemove = saver::flush,
        onOpenOutput = onOpenOutput,
    )
}

/**
 * One cell note. Editing: empty, it is the compact 48dp "+ Note …" row; a tap opens the live editor
 * with the cursor in it, and leaving it empty collapses it again. Preview: only a note with text shows.
 */
@Composable
private fun CellNote(state: MarkdownBlockState, side: CellNoteSide, filename: String, index: Int, preview: Boolean, notes: List<NoteIndexItem>, onOpenNote: (String) -> Unit) {
    val name = cellNoteName(side, filename, index)
    if (preview) {
        RenderedCellNote(state.text, name, notes, onOpenNote)
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    var focusOnOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    if (!showsEditor(open, state.text)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).plainClick { open = true; focusOnOpen = true }.semantics { contentDescription = name },
            contentAlignment = Alignment.CenterStart,
        ) { Text(side.addLabel, style = Type.sm, color = t.muted) }
        return
    }
    MarkdownBlockEditor(
        state, name, placeholder = side.label, minHeight = 48.dp,
        modifier = Modifier.padding(vertical = 8.dp).onFocusChanged { f -> open = openAfterFocus(open, focused, f.isFocused, state.text); focused = f.isFocused },
    )
    LaunchedEffect(focusOnOpen) { if (focusOnOpen) { runCatching { state.focus.requestFocus() }; focusOnOpen = false } }
}

/** Whether a cell note shows its editor rather than the "+ Note …" row: while open, or whenever it has text. */
internal fun showsEditor(open: Boolean, text: String): Boolean = open || !isCollapsedNote(text)

/**
 * `open` after a focus report: focus opens it; a blur (focus lost after having it) while empty or
 * whitespace collapses it; anything else (the first report on attach, a blur with text) keeps it.
 */
internal fun openAfterFocus(open: Boolean, wasFocused: Boolean, focused: Boolean, text: String): Boolean = when {
    focused -> true
    wasFocused && isCollapsedNote(text) -> false
    else -> open
}

/** A cell note read-only (the editor's Preview and the landing's sample): rendered Markdown, nothing when empty. */
@Composable
fun RenderedCellNote(text: String, name: String, notes: List<NoteIndexItem> = emptyList(), onOpenNote: (String) -> Unit = {}) {
    if (isCollapsedNote(text)) return
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = name }) { NoteMarkdown(text, notes, onOpenNote) }
}
