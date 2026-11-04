package app.benchjot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.Segment
import app.benchjot.core.parseLinks
import app.benchjot.core.resolveLink
import app.benchjot.core.NoteLink
import com.mikepenz.markdown.m3.Markdown

private const val NOTE_SCHEME = "benchjot-note:"

/** [[links]] become Markdown links to the note they resolve to; a link to a missing note stays plain text. */
fun linkifyNotes(markdown: String, notes: List<NoteIndexItem>): String = parseLinks(markdown).joinToString("") { s ->
    when (s) {
        is Segment.Text -> s.text
        is Segment.Link -> {
            val target = resolveLink(NoteLink(s.label, s.id), notes)
            val label = s.label.replace("[", "\\[").replace("]", "\\]")
            if (target != null) "[$label]($NOTE_SCHEME${target.id})" else label
        }
    }
}

/** The note preview: Markdown with tappable note links (the web's markdown.tsx + remark-note-links). */
@Composable
fun NoteMarkdown(text: String, notes: List<NoteIndexItem>, onOpenNote: (String) -> Unit) {
    val system = LocalUriHandler.current
    val handler = remember(system, onOpenNote) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(NOTE_SCHEME)) onOpenNote(uri.removePrefix(NOTE_SCHEME)) else runCatching { system.openUri(uri) }
            }
        }
    }
    val content = remember(text, notes) { linkifyNotes(text, notes) }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(content = content)
    }
}
