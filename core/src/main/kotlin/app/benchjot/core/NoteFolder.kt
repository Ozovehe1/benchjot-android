package app.benchjot.core

// Every note as a file of its own on the device (the app keeps them in Documents/Benchjot): which
// folder and name each note gets, and what it says. The pure part; the app writes the files when
// the notes change (app/data/NoteFolder.kt).
//
//   Benchjot/<project>/<title>.md   (notes in no project: Benchjot/Notes/<title>.md)
//
// A file is the note as Markdown (the export's note.md: text, each cell's code, output, tables and
// notes), so it opens in any editor, Obsidian included. Two notes with the same title in a folder
// are told apart by the start of their id.

/** One note's file: its folder under the device's Documents, its name, and its text. */
data class NoteFile(val dir: String, val name: String, val text: String)

/** A folder or file name that every file system takes: no separators or reserved characters, not empty, not too long. */
fun safeName(s: String, fallback: String): String =
    s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "-").replace(Regex("\\s+"), " ").trim().trim('.').take(80).ifEmpty { fallback }

const val NOTES_FOLDER = "Benchjot"

/** Every note's file, by note id. */
// A note's file text, made once per version of the note and its cells (rows are replaced, not
// changed in place), so a change re-makes one file's text, not every note's (web: the same).
private class Made(val note: Note, val cells: List<Cell>, val text: String)
private val made = java.util.concurrent.ConcurrentHashMap<String, Made>()
private fun fileText(d: LocalData, e: Note): String {
    val cells = d.cellsOf(e.id)
    made[e.id]?.let { k -> if (k.note === e && k.cells.size == cells.size && k.cells.indices.all { k.cells[it] === cells[it] }) return k.text }
    if (made.size > 2 * d.notes.size + 64) made.keys.retainAll(d.notes.keys)
    val export = cells.map { ExportCell(it.filename, it.language, it.content, it.noteBefore, it.noteAfter, it.lastRun, emptyMap()) }
    val text = markdownFiles(ExportNote(e.title, e.notes, e.outcome, export, e.updatedAt)).first().second.decodeToString()
    made[e.id] = Made(e, cells, text)
    return text
}

fun noteFiles(d: LocalData): Map<String, NoteFile> {
    val projects = d.projects.associate { it.id to safeName(it.name, "Project") }
    val out = LinkedHashMap<String, NoteFile>()
    val taken = HashSet<String>()
    for (e in d.liveNotes().sortedWith(compareBy<Note>({ it.createdAt }, { it.id }))) {
        val dir = "$NOTES_FOLDER/" + (e.projectId?.let(projects::get) ?: "Notes")
        val base = safeName(e.title, "Untitled")
        var name = "$base.md"
        if (!taken.add("$dir/$name".lowercase())) {
            name = "$base ${e.id.take(6)}.md"
            taken.add("$dir/$name".lowercase())
        }
        out[e.id] = NoteFile(dir, name, fileText(d, e))
    }
    return out
}
