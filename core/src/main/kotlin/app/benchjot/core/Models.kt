package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Row types, as the web's src/lib/schemas.ts and the Supabase tables define them.
// Limits mirror the web's zod contracts; Limits.check* fail fast with the same messages.

@Serializable
data class Project(val id: String, val name: String)

@Serializable
data class Note(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val title: String = "",
    val notes: String = "",
    val outcome: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** The note this one is an attempt from. */
    @SerialName("derived_from") val derivedFrom: String? = null,
    /** When it was pinned (pinned notes come first); null when it isn't. */
    @SerialName("pinned_at") val pinnedAt: String? = null,
    /** When it went to Recently Deleted (removed for good 30 days later); null when it hasn't. */
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class NoteListItem(
    val id: String,
    val title: String = "",
    val outcome: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("pinned_at") val pinnedAt: String? = null,
)

/**
 * Every note's id, title and parent (for links and the tree), plus what the map draws:
 * outcome, project, creation time and the note it was derived from.
 */
@Serializable
data class NoteIndexItem(
    override val id: String,
    override val title: String = "",
    @SerialName("parent_id") override val parentId: String? = null,
    override val outcome: String? = null,
    @SerialName("project_id") override val projectId: String? = null,
    @SerialName("created_at") override val createdAt: String? = null,
    @SerialName("derived_from") override val derivedFrom: String? = null,
) : MapNote

/** The columns Backend.noteIndex() selects: NoteIndexItem's keys, as the web's NOTE_INDEX_COLUMNS. */
val NOTE_INDEX_COLUMNS = listOf("id", "title", "parent_id", "derived_from", "outcome", "project_id", "created_at")

/** One [[link]] in a note body, as note_links() returns it. */
@Serializable
data class NoteLinkRow(val source: String, val label: String, val target: String? = null)

@Serializable
data class Cell(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("note_id") val noteId: String,
    val filename: String = "",
    val language: String = DEFAULT_LANGUAGE,
    val content: String = "",
    val position: Int = 0,
    @SerialName("last_run") val lastRun: LastRun? = null,
    /** The cell's "Note before run" and "Note after run" (Markdown, written only by the user; CellNotes.kt). */
    @SerialName("note_before") val noteBefore: String = "",
    @SerialName("note_after") val noteAfter: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * The position after a note's last cell (the web's endPosition). Not the count: once a cell is deleted
 * the positions have a gap, and a new cell at the count would share a position with the last one.
 */
fun endPosition(cells: List<Cell>): Int = cells.maxOfOrNull { it.position + 1 } ?: 0

class ValidationError(message: String) : IllegalArgumentException(message)

object Limits {
    fun title(v: String) = v.also { if (it.length > 200) throw ValidationError("Title is over 200 characters") }
    fun notes(v: String) = v.also { if (it.length > 200_000) throw ValidationError("Note is over 200k characters") }
    fun cellNote(v: String) = v.also { if (it.length > 100_000) throw ValidationError("Cell note is over 100k characters") }
    fun projectName(v: String): String {
        val t = v.trim()
        if (t.isEmpty()) throw ValidationError("Name the project")
        if (t.length > 120) throw ValidationError("Name is over 120 characters")
        return t
    }
    fun code(v: String) = v.also { if (it.length > 1_048_576) throw ValidationError("Code is over 1 MB") }
    fun filename(v: String): String = v.trim().also { if (it.length > 200) throw ValidationError("File name is over 200 characters") }
    fun password(v: String) = v.also { if (it.length < 8) throw ValidationError("Password needs at least 8 characters.") }
}
