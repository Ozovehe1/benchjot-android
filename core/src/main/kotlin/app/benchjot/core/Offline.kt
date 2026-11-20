package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Offline first, the pure part. The phone keeps its own copy of the account's notes, code cells
// and projects (LocalData), and every change is made there first and queued (the outbox) to be
// sent to the server in order when there is a connection. So a note opens, changes and saves with
// no network at all; nothing waits on the server. What the server sends back replaces the copy,
// except for anything a queued change still has to reach it with (the phone's edit wins until it
// is sent: one person's account, the latest edit is the one they made). The app keeps LocalData in
// a file and sends the outbox (app/data/Offline.kt).

/** A change waiting to reach the server, in the order it was made. Ids are made on the phone. */
@Serializable
sealed interface Op {
    @Serializable @SerialName("note.create") data class CreateNote(val note: Note) : Op
    /** Only the keys present are written (title, notes, project_id, parent_id, outcome, pinned_at, deleted_at; null clears). */
    @Serializable @SerialName("note.patch") data class PatchNote(val id: String, val fields: JsonObject) : Op
    @Serializable @SerialName("note.delete") data class DeleteNote(val id: String) : Op
    @Serializable @SerialName("cells.create") data class CreateCells(val rows: List<Cell>) : Op
    /** language, content, note_before, note_after or last_run. */
    @Serializable @SerialName("cell.patch") data class PatchCell(val id: String, val fields: JsonObject) : Op
    @Serializable @SerialName("cell.delete") data class DeleteCell(val id: String) : Op
    @Serializable @SerialName("project.create") data class CreateProject(val project: Project) : Op
    @Serializable @SerialName("project.rename") data class RenameProject(val id: String, val name: String) : Op
    @Serializable @SerialName("project.delete") data class DeleteProject(val id: String) : Op
}

/** The phone's copy of the account, and the changes still to send. */
@Serializable
data class LocalData(
    val notes: Map<String, Note> = emptyMap(),
    val cells: Map<String, Cell> = emptyMap(),
    val projects: List<Project> = emptyList(),
    val username: String? = null,
    val outbox: List<Op> = emptyList(),
    /** When the whole account was last read from the server (ISO); null before the first time. */
    val syncedAt: String? = null,
)

private val opJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { this[key] !is JsonNull }?.contentOrNull

/** The ids a queued change is about (a note, a cell, a project), and the note a cell change is in. */
fun Op.ids(data: LocalData): Set<String> = when (this) {
    is Op.CreateNote -> setOf(note.id)
    is Op.PatchNote -> setOf(id)
    is Op.DeleteNote -> setOf(id)
    is Op.CreateCells -> rows.flatMap { listOf(it.id, it.noteId) }.toSet()
    is Op.PatchCell -> setOfNotNull(id, data.cells[id]?.noteId)
    is Op.DeleteCell -> setOfNotNull(id, data.cells[id]?.noteId)
    is Op.CreateProject -> setOf(project.id)
    is Op.RenameProject -> setOf(id)
    is Op.DeleteProject -> setOf(id)
}

/** Every id a queued change still has to reach the server with. */
fun LocalData.pendingIds(): Set<String> = outbox.flatMap { it.ids(this) }.toSet()

/** Keys that change where a note shows, not what it says: they leave its edit time (as the server's notes_touch). */
private val NOT_EDITS = setOf("pinned_at", "deleted_at")

private fun Note.patched(f: JsonObject, now: String) = copy(
    title = if ("title" in f) f.str("title").orEmpty() else title,
    notes = if ("notes" in f) f.str("notes").orEmpty() else notes,
    projectId = if ("project_id" in f) f.str("project_id") else projectId,
    parentId = if ("parent_id" in f) f.str("parent_id") else parentId,
    outcome = if ("outcome" in f) f.str("outcome") else outcome,
    pinnedAt = if ("pinned_at" in f) f.str("pinned_at") else pinnedAt,
    deletedAt = if ("deleted_at" in f) f.str("deleted_at") else deletedAt,
    updatedAt = if (f.keys.all { it in NOT_EDITS }) updatedAt else now,
)

private fun Cell.patched(f: JsonObject, now: String) = copy(
    language = if ("language" in f) f.str("language") ?: language else language,
    content = if ("content" in f) f.str("content").orEmpty() else content,
    noteBefore = if ("note_before" in f) f.str("note_before").orEmpty() else noteBefore,
    noteAfter = if ("note_after" in f) f.str("note_after").orEmpty() else noteAfter,
    lastRun = if ("last_run" in f) f["last_run"].let { v -> if (v == null || v is JsonNull) null else opJson.decodeFromJsonElement(LastRun.serializer(), v) } else lastRun,
    updatedAt = now,
)

/**
 * Makes a change on the phone's copy and queues it. A patch right after another patch of the same
 * note or cell joins it (typing makes many; one send is enough).
 */
fun LocalData.apply(op: Op, now: String): LocalData {
    val changed = when (op) {
        is Op.CreateNote -> copy(notes = notes + (op.note.id to op.note))
        is Op.PatchNote -> notes[op.id]?.let { copy(notes = notes + (op.id to it.patched(op.fields, now))) } ?: this
        is Op.DeleteNote -> copy(
            notes = (notes - op.id).mapValues { (_, e) -> if (e.parentId == op.id) e.copy(parentId = null) else e },
            cells = cells.filterValues { it.noteId != op.id },
        )
        is Op.CreateCells -> copy(cells = cells + op.rows.associateBy { it.id })
        is Op.PatchCell -> cells[op.id]?.let { copy(cells = cells + (op.id to it.patched(op.fields, now))) } ?: this
        is Op.DeleteCell -> copy(cells = cells - op.id)
        is Op.CreateProject -> copy(projects = (projects + op.project).sortedBy { it.name.lowercase() })
        is Op.RenameProject -> copy(projects = projects.map { if (it.id == op.id) it.copy(name = op.name) else it }.sortedBy { it.name.lowercase() })
        is Op.DeleteProject -> copy(
            projects = projects.filter { it.id != op.id },
            notes = notes.mapValues { (_, e) -> if (e.projectId == op.id) e.copy(projectId = null) else e },
        )
    }
    val last = changed.outbox.lastOrNull()
    val queued = when {
        op is Op.PatchNote && last is Op.PatchNote && last.id == op.id -> changed.outbox.dropLast(1) + Op.PatchNote(op.id, JsonObject(last.fields + op.fields))
        op is Op.PatchCell && last is Op.PatchCell && last.id == op.id -> changed.outbox.dropLast(1) + Op.PatchCell(op.id, JsonObject(last.fields + op.fields))
        else -> changed.outbox + op
    }
    return changed.copy(outbox = queued)
}

/** The first queued change reached the server (or can never): it leaves the queue. */
fun LocalData.sent(): LocalData = copy(outbox = outbox.drop(1))

/** A note as the server has it now; kept unless a queued change still has to reach it. */
fun LocalData.withServerNote(e: Note): LocalData =
    if (e.id in pendingIds()) this else copy(notes = notes + (e.id to e))

/** A note's cells as the server has them now; kept unless a queued change touches that note or its cells. */
fun LocalData.withServerCells(noteId: String, rows: List<Cell>): LocalData {
    val pending = pendingIds()
    if (noteId in pending || cells.values.any { it.noteId == noteId && it.id in pending }) return this
    return copy(cells = cells.filterValues { it.noteId != noteId } + rows.associateBy { it.id })
}

/**
 * The whole account as the server has it now (every note, cell and project). Anything a queued
 * change still has to reach it with is kept as the phone has it. A row the copy already has at the
 * same edit time stays the same object, so the store writes only rows that changed (web: the same).
 */
fun LocalData.withServerAccount(notes: List<Note>, cells: List<Cell>, projects: List<Project>, at: String): LocalData {
    val pending = pendingIds()
    val keptNotes = this.notes.filterKeys { it in pending }
    val keptCells = this.cells.filter { (id, s) -> id in pending || s.noteId in pending }
    val deletedHere = outbox.mapNotNull { (it as? Op.DeleteNote)?.id ?: (it as? Op.DeleteCell)?.id }.toSet()
    val have = this
    return copy(
        notes = notes.filter { it.id !in deletedHere }.associate { e -> e.id to (have.notes[e.id]?.takeIf { it.updatedAt == e.updatedAt } ?: e) } + keptNotes,
        cells = cells.filter { it.id !in deletedHere && it.noteId !in deletedHere }.associate { s -> s.id to (have.cells[s.id]?.takeIf { it.updatedAt == s.updatedAt } ?: s) } + keptCells,
        projects = if (projectsWait()) this.projects else projects.sortedBy { it.name.lowercase() },
        syncedAt = at,
    )
}

private fun LocalData.projectsWait() = outbox.any { it is Op.CreateProject || it is Op.RenameProject || it is Op.DeleteProject }

/** What changed in the account since the copy was last synced (the server's sync_changes). */
@Serializable
data class Changes(
    val at: String,
    val complete: Boolean = true,
    val notes: List<Note> = emptyList(),
    val cells: List<Cell> = emptyList(),
    val deleted: List<Deleted> = emptyList(),
) {
    @Serializable data class Deleted(val kind: String, val id: String)
}

/**
 * The account's changes since the last sync, applied to the copy (web: withServerChanges): changed
 * rows replace theirs, deleted ones go (a note's cells with it), and anything a queued change still
 * has to reach the server with stays as it is here. Projects come whole. Costs what changed.
 */
fun LocalData.withServerChanges(c: Changes, projects: List<Project>): LocalData {
    val pending = pendingIds()
    val gone = c.deleted.filter { it.kind != "project" && it.id !in pending }.mapTo(HashSet()) { it.id }
    val notes = if (c.notes.isEmpty() && gone.isEmpty()) this.notes else HashMap(this.notes).apply {
        gone.forEach { remove(it) }
        for (e in c.notes) if (e.id !in pending && e.id !in gone) put(e.id, this@withServerChanges.notes[e.id]?.takeIf { it.updatedAt == e.updatedAt } ?: e)
    }
    val cells = if (c.cells.isEmpty() && gone.isEmpty()) this.cells else HashMap(this.cells).apply {
        gone.forEach { remove(it) }
        if (gone.isNotEmpty()) values.removeAll { it.noteId in gone && it.id !in pending }
        for (s in c.cells) if (s.id !in pending && s.noteId !in pending && s.id !in gone && s.noteId !in gone) put(s.id, this@withServerChanges.cells[s.id]?.takeIf { it.updatedAt == s.updatedAt } ?: s)
    }
    return copy(notes = notes, cells = cells, projects = if (projectsWait()) this.projects else projects.sortedBy { it.name.lowercase() }, syncedAt = c.at)
}

// Every note's cells, grouped once per version of the cells (the map is replaced, never changed in
// place, so the object is the version). Without it, reading one note's cells scanned every cell in
// the account, and anything done per note (search, the notes folder) grew with notes × cells.
private object Grouped {
    private var of: Map<String, Cell>? = null
    private var groups: Map<String, List<Cell>> = emptyMap()
    @Synchronized fun get(cells: Map<String, Cell>): Map<String, List<Cell>> {
        if (of !== cells) {
            groups = cells.values.groupBy { it.noteId }.mapValues { (_, v) -> v.sortedWith(compareBy<Cell>({ it.position }, { it.createdAt })) }
            of = cells
        }
        return groups
    }
}

/** A note's cells in order. */
fun LocalData.cellsOf(noteId: String): List<Cell> = Grouped.get(cells)[noteId] ?: emptyList()

// Per note, kept until the note or one of its cells changes: what a search looks in, and its links.
private class PerNote(val note: Note, val cells: List<Cell>) {
    val text: String by lazy {
        buildString {
            append(note.title).append('\n').append(note.notes)
            cells.forEach { append('\n').append(it.content).append('\n').append(it.noteBefore).append('\n').append(it.noteAfter).append('\n').append(it.filename) }
        }.lowercase()
    }
    val links: List<SourcedLink> by lazy { noteLinksOf(note.id, note.notes, cells) }
}
private val perNote = java.util.concurrent.ConcurrentHashMap<String, PerNote>()
private fun LocalData.perNote(e: Note): PerNote {
    val cells = cellsOf(e.id)
    val kept = perNote[e.id]
    if (kept != null && kept.note === e && kept.cells.size == cells.size && kept.cells.indices.all { kept.cells[it] === cells[it] }) return kept
    if (perNote.size > 2 * notes.size + 64) perNote.keys.retainAll(notes.keys)
    return PerNote(e, cells).also { perNote[e.id] = it }
}

/** The notes not in Recently Deleted: everything but that one screen sees only these. */
fun LocalData.liveNotes(): Sequence<Note> = notes.values.asSequence().filter { it.deletedAt == null }

/** Recently Deleted: the notes waiting to be removed for good, most recently deleted first. */
fun LocalData.recentlyDeleted(): List<Note> =
    notes.values.filter { it.deletedAt != null }.sortedWith(compareByDescending<Note> { it.deletedAt }.thenBy { it.id })

/** Every [[link]] in the copy, as the server's note_links() finds them: in note text and cell notes (web: localLinks). */
fun LocalData.localLinks(): List<SourcedLink> = liveNotes().flatMap { perNote(it).links }.toList()

/** Every note for the tree, links and map, most recently edited first (as Backend.noteIndex). */
fun LocalData.index(): List<NoteIndexItem> =
    liveNotes().sortedWith(compareByDescending<Note> { it.updatedAt }.thenBy { it.id })
        .map { NoteIndexItem(it.id, it.title, it.parentId, it.outcome, it.projectId, it.createdAt, it.derivedFrom) }
        .toList()

/**
 * The list a search shows, from the phone's copy (as search_notes): every term in the title, the
 * note or a cell's code or notes (any case), in the scope, pinned first (latest pin on top), then
 * most recently edited first. Recently Deleted's notes aren't in it.
 */
fun LocalData.search(terms: List<String>, projectId: String?, unfiled: Boolean, outcome: String?): List<NoteListItem> {
    val words = terms.map { it.lowercase() }
    return liveNotes()
        .filter { e -> projectId == null || e.projectId == projectId }
        .filter { e -> !unfiled || e.projectId == null }
        .filter { e ->
            when (outcome) {
                null -> true
                "unmarked" -> e.outcome == null
                else -> e.outcome == outcome
            }
        }
        .filter { e ->
            if (words.isEmpty()) return@filter true
            val text = perNote(e).text
            words.all { it in text }
        }
        .sortedWith(compareBy<Note> { it.pinnedAt == null }.thenByDescending { it.pinnedAt }.thenByDescending { it.updatedAt }.thenBy { it.id })
        .map { NoteListItem(it.id, it.title, it.outcome, it.parentId, it.updatedAt, it.pinnedAt) }
        .toList()
}

/** Whether a failure only means "not now" (no connection, timeout, the server busy): the change waits and is sent again. */
fun isTransient(f: ErrorFacts): Boolean = when (errorKind(f)) {
    ErrorKind.Offline, ErrorKind.Timeout, ErrorKind.Server, ErrorKind.RateLimited, ErrorKind.Session -> true
    else -> false
}

/** Where a search found a note, to show under it (the web's SearchHit): the line, and the cell it's in (null: the note's own text). */
data class SearchHit(val where: String, val cellId: String?, val cell: Int?, val filename: String, val lineNo: Int, val line: String)

private const val HIT_MAX = 120
private fun cutAround(line: String, at: Int): String {
    val t = line.trim()
    if (t.length <= HIT_MAX) return t
    val start = maxOf(0, minOf(at - 30, t.length - HIT_MAX))
    return (if (start > 0) "…" else "") + t.substring(start, start + HIT_MAX).trim() + (if (start + HIT_MAX < t.length) "…" else "")
}

/**
 * Where a search matched a note (web: searchHit): the first line with one of the words in the code
 * cells, else in the note, else in a cell's notes. Null when only the title matched. Any case.
 */
fun LocalData.searchHit(noteId: String, terms: List<String>): SearchHit? {
    val words = terms.map { it.lowercase() }.filter { it.isNotEmpty() }
    val e = notes[noteId] ?: return null
    if (words.isEmpty()) return null
    fun find(text: String): Pair<Int, String>? {
        text.split("\n").forEachIndexed { i, l ->
            val low = l.lowercase()
            val at = words.map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return@forEachIndexed
            return (i + 1) to cutAround(l, at - (l.length - l.trimStart().length))
        }
        return null
    }
    val cells = cellsOf(noteId)
    cells.forEachIndexed { i, s -> find(s.content)?.let { (n, l) -> return SearchHit("code", s.id, i + 1, s.filename, n, l) } }
    find(e.notes)?.let { (n, l) -> return SearchHit("note", null, null, "", n, l) }
    cells.forEachIndexed { i, s -> find("${s.noteBefore}\n${s.noteAfter}")?.let { (n, l) -> return SearchHit("cell note", s.id, i + 1, s.filename, n, l) } }
    return null
}

