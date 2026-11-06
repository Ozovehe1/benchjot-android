package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Row types, as the web's src/lib/schemas.ts and the Supabase tables define them.
// Limits mirror the web's zod contracts; Limits.check* fail fast with the same messages.

@Serializable
data class Project(val id: String, val name: String)

@Serializable
data class Entry(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val title: String = "",
    val notes: String = "",
    val outcome: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /** The note this one is an attempt from; absent until the Research Map migration is applied. */
    @SerialName("derived_from") val derivedFrom: String? = null,
)

@Serializable
data class EntryListItem(
    val id: String,
    val title: String = "",
    val outcome: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * Every note's id, title and parent (for links and the tree), plus what the map draws:
 * outcome, project, creation time and the note it was derived from. `derived_from` is
 * missing until the Research Map migration is applied; it then reads as null.
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
data class Snippet(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("entry_id") val entryId: String,
    val filename: String = "",
    val language: String = DEFAULT_LANGUAGE,
    val content: String = "",
    val position: Int = 0,
    @SerialName("last_run") val lastRun: LastRun? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

class ValidationError(message: String) : IllegalArgumentException(message)

object Limits {
    fun title(v: String) = v.also { if (it.length > 200) throw ValidationError("Title is over 200 characters") }
    fun notes(v: String) = v.also { if (it.length > 200_000) throw ValidationError("Note is over 200k characters") }
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
