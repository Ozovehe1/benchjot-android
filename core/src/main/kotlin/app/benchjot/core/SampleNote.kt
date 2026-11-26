package app.benchjot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The example note on the signed-out landing: the web's src/lib/sample-note.ts, copied by
// scripts/web-fixtures.mjs into app/src/main/assets/sample/ (note.json + the run's output
// files, which the landing reads from the APK, never from Storage). CI fails on drift.

@Serializable
data class SampleNoteText(val title: String, val notes: String, val outcome: String? = null)

@Serializable
private data class SampleCell(
    val id: String,
    val filename: String,
    val language: String,
    val content: String,
    @kotlinx.serialization.SerialName("last_run") val lastRun: LastRun? = null,
    @kotlinx.serialization.SerialName("note_before") val noteBefore: String = "",
    @kotlinx.serialization.SerialName("note_after") val noteAfter: String = "",
)

@Serializable
private data class SampleFile(val note: SampleNoteText, val cells: List<SampleCell>)

class SampleNote(val note: SampleNoteText, val cells: List<Cell>)

/** Folder of the sample in the app's assets. */
const val SAMPLE_ASSETS = "sample"

private val json = Json { ignoreUnknownKeys = true }

fun parseSampleNote(text: String): SampleNote {
    val f = json.decodeFromString(SampleFile.serializer(), text)
    return SampleNote(
        f.note,
        f.cells.mapIndexed { i, s ->
            Cell(id = s.id, userId = "", noteId = "", filename = s.filename, language = s.language, content = s.content, position = i, lastRun = s.lastRun, noteBefore = s.noteBefore, noteAfter = s.noteAfter, createdAt = "", updatedAt = "")
        },
    )
}
