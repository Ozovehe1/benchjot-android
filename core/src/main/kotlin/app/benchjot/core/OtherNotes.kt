package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Which of the user's other notes the Assistant's find_notes lists, and in what order: the web's
// src/lib/notes/other-notes.ts (parity-checked). Pure: the rows come from the server or the phone's copy.

/** A note as find_notes reads it. */
@Serializable
data class OtherNoteRow(
    val id: String,
    val title: String = "",
    val outcome: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("parent_id") val parentId: String? = null,
    /** false: found only by meaning, not by the word search. */
    val matched: Boolean? = null,
)

/** Reciprocal rank fusion's constant: how much a lower place in one list still counts. */
private const val FUSE_K = 60

/** One note as find_notes lists it; [why] says how it relates to the note asking. */
@Serializable
data class OtherNote(val id: String, val title: String, val outcome: String?, @SerialName("updated_at") val updatedAt: String, val why: String? = null)

/** At most this many notes per find_notes. */
const val OTHER_NOTES_MAX = 8

/** The notes a note links to ([[Title|id]]), in the order they appear, at most 50. */
fun linkedIds(body: String): List<String> = parseLinks(body).mapNotNull { (it as? Segment.Link)?.id }.distinct().take(50)

/**
 * The list find_notes gives (the web's rankOtherNotes). [similar]: the ids search by meaning found,
 * closest first. Words searched ([matched]): word and meaning matches fused by their places in the
 * two lists, so a note found both ways comes first. No words: the notes this one links to, its
 * parent, the notes under it, the notes closest in meaning to it, then the most recently edited.
 * Never the note itself; each note once; at most [max].
 */
fun rankOtherNotes(fromId: String, fromParent: String?, body: String, rows: List<OtherNoteRow>, matched: Boolean, max: Int = OTHER_NOTES_MAX, similar: List<String> = emptyList()): List<OtherNote> {
    val out = mutableListOf<OtherNote>()
    val seen = mutableSetOf(fromId)
    fun take(r: OtherNoteRow, why: String? = null) {
        if (r.id in seen || out.size >= max) return
        seen += r.id
        out += OtherNote(r.id, r.title, r.outcome, r.updatedAt, why)
    }
    val byId = rows.associateBy { it.id }
    val close = similar.filter { it in byId }
    if (matched) {
        val words = rows.filter { it.matched != false }.map { it.id }
        val score = LinkedHashMap<String, Double>()
        words.forEachIndexed { i, id -> score[id] = (score[id] ?: 0.0) + 1.0 / (FUSE_K + i + 1) }
        close.forEachIndexed { i, id -> score[id] = (score[id] ?: 0.0) + 1.0 / (FUSE_K + i + 1) }
        fun place(id: String) = words.indexOf(id).let { if (it < 0) 999 else it + 1 }
        score.keys.sortedWith(compareByDescending<String> { score[it]!! }.thenBy { place(it) }.thenBy { it })
            .forEach { id -> take(byId.getValue(id), if (id in words) "matches" else "close in meaning") }
        return out
    }
    val newest = rows.sortedWith(compareByDescending<OtherNoteRow> { it.updatedAt }.thenBy { it.id })
    linkedIds(body).forEach { id -> byId[id]?.let { take(it, "linked from this note") } }
    fromParent?.let { p -> byId[p]?.let { take(it, "this note's parent") } }
    newest.filter { it.parentId == fromId }.forEach { take(it, "a note under this one") }
    close.forEach { take(byId.getValue(it), "close in meaning to this note") }
    newest.forEach { take(it) }
    return out
}
