package app.benchjot.core

// A code cell's two notes, as the web's cell notes: "Note before run" sits directly above the
// cell, "Note after run" directly below its run output. Markdown in the body's format, written
// only by the user (the helper never writes them) and stored on the snippet row (note_before,
// note_after). Their [[links]] count wherever the body's do: note_links() and search_entries()
// read them in SQL; noteLinksOf() and searchHaystack() are the same rules here, checked against
// the web's answers in cell_notes.json.

enum class CellNoteSide(val label: String) {
    Before("Note before run"),
    After("Note after run");

    /** This side's text on a cell. */
    fun of(cell: Snippet): String = when (this) { Before -> cell.noteBefore; After -> cell.noteAfter }

    /** The compact row an empty note shows as. */
    val addLabel: String get() = "+ $label"
}

/** An empty or whitespace-only note collapses to its "+ Note …" row. */
fun isCollapsedNote(text: String): Boolean = text.isBlank()

/** The accessible name of a cell's note: its label and the cell's file name ("Code N" when it has none, as the code editor). */
fun cellNoteName(side: CellNoteSide, filename: String, index: Int): String {
    require(index >= 0) { "index must be >= 0" }
    return "${side.label}, ${filename.trim().ifEmpty { "Code ${index + 1}" }}"
}

/** A note's Markdown texts in reading order: the body, then each cell's before and after note (cells by position). */
fun noteTexts(body: String, cells: List<Snippet>): List<String> =
    listOf(body) + cells.sortedBy { it.position }.flatMap { listOf(it.noteBefore, it.noteAfter) }

/** Every [[link]] in note `entryId`: the body's first, then the cell notes in reading order (the rows note_links() returns for it). */
fun noteLinksOf(entryId: String, body: String, cells: List<Snippet>): List<SourcedLink> =
    noteTexts(body, cells).flatMap { text -> parseLinks(text).filterIsInstance<Segment.Link>().map { SourcedLink(entryId, it.label, it.id) } }

/** The text search_entries() looks in: title, body, outcome word, then each cell's code and both notes. */
fun searchHaystack(title: String, notes: String, outcome: String?, cells: List<Snippet>): String {
    val outcomeWord = when (outcome) { "success" -> "successful"; "failed" -> "failed"; else -> "" }
    val code = cells.joinToString(" ") { "${it.content} ${it.noteBefore} ${it.noteAfter}" }
    return "$title $notes $outcomeWord $code".lowercase()
}

/** Whether search_entries() returns this note for `query`: every term is somewhere in the haystack. */
fun matchesSearch(query: String, title: String, notes: String, outcome: String?, cells: List<Snippet>): Boolean {
    val haystack = searchHaystack(title, notes, outcome, cells)
    return searchTerms(query).all { haystack.contains(it) }
}
