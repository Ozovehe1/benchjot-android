package app.benchjot.core

// Note links, written in a note's body as [[Title|<note id>]]. Mirrors the web's
// src/lib/links.ts. The body is the single source of truth: links are derived from
// it on read (here for rendering, in note_links() in SQL for "Linked from").
// The id is what a link points at; the title is only a fallback label.
//
// LINK_PATTERN is the pattern exactly as note_links() writes it (Postgres syntax).
// LinksTest checks it against the migration copied from the web repo.

private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
const val LINK_PATTERN = """\[\[([^]|[\n]+)(?:\|($UUID))?\]\]"""

/** Java regex needs the leading ] and the [ inside the bracket escaped; Postgres needs them bare. */
internal val LINK_REGEX = Regex(LINK_PATTERN.replace("[^]|[", """[^\]|\["""))

data class NoteLink(val label: String, val id: String?)

/** A link and the note whose body it is in. */
data class SourcedLink(val source: String, val label: String, val id: String?) {
    val link get() = NoteLink(label, id)
}

sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Link(val raw: String, val label: String, val id: String?) : Segment
}

/** Splits text into plain runs and links, in order. Joining every text/raw gives the input back. */
fun parseLinks(text: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var last = 0
    for (m in LINK_REGEX.findAll(text)) {
        val at = m.range.first
        if (at > last) out += Segment.Text(text.substring(last, at))
        out += Segment.Link(m.value, m.groupValues[1].trim(), m.groups[2]?.value?.lowercase())
        last = m.range.last + 1
    }
    if (last < text.length) out += Segment.Text(text.substring(last))
    return out
}

/** The link to insert for a note. Characters that would end the link early are replaced. */
fun formatLink(title: String, id: String): String {
    val label = title.replace(Regex("""[\[\]|\s]+"""), " ").trim().ifEmpty { "Untitled" }
    return "[[$label|$id]]"
}

interface NoteRef {
    val id: String
    val title: String
}

/** The note a link points at: by id when it has one, else by title (case-insensitive). */
fun <T : NoteRef> resolveLink(link: NoteLink, notes: List<T>): T? {
    if (link.id != null) return notes.firstOrNull { it.id == link.id }
    val want = link.label.lowercase()
    return notes.firstOrNull { it.title.trim().lowercase() == want }
}

data class LinkQuery(val start: Int, val query: String)

/** An unfinished link being typed at the caret: `[[` then a query on the same line, with no `]` or `|` yet. */
fun linkQueryAt(text: String, caret: Int): LinkQuery? {
    val before = text.substring(0, caret.coerceIn(0, text.length))
    val start = before.lastIndexOf("[[")
    if (start < 0) return null
    val query = before.substring(start + 2)
    if (Regex("""[\[\]|\n]""").containsMatchIn(query) || query.length > 200) return null
    return LinkQuery(start, query)
}

data class Completed(val text: String, val caret: Int)

/** Replaces the typed `[[query` (from start to the caret) with the full link. */
fun completeLink(text: String, start: Int, caret: Int, note: NoteRef): Completed {
    val tail = text.substring(caret).removePrefix("]]")
    val link = formatLink(note.title, note.id)
    return Completed(text.substring(0, start) + link + tail, start + link.length)
}

/** Notes whose title contains the query (case-insensitive), title-prefix matches first. */
fun <T : NoteRef> linkSuggestions(query: String, notes: List<T>, excludeId: String, limit: Int = 6): List<T> {
    val q = query.trim().lowercase()
    return notes
        .filter { it.id != excludeId && it.title.isNotBlank() }
        .map { it to it.title.lowercase().indexOf(q) }
        .filter { it.second >= 0 }
        .sortedBy { if (it.second == 0) 0 else 1 } // stable: keeps the given (most recent) order
        .take(limit)
        .map { it.first }
}

/** Notes that link to `id`, once each, in the order of `notes`; never the note itself. */
fun <T : NoteRef> backlinksTo(id: String, links: List<SourcedLink>, notes: List<T>): List<T> {
    val from = links.filter { it.source != id && resolveLink(it.link, notes)?.id == id }.map { it.source }.toSet()
    return notes.filter { it.id in from }
}
