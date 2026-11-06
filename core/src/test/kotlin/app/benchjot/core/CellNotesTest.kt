package app.benchjot.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cell notes against the web's hand-written cases (cell_notes.json, copied by scripts/web-fixtures.mjs). */
class CellNotesTest {
    private val fixture = Fixtures.json("cell_notes.json").jsonObject

    private fun JsonElement.str(key: String) = jsonObject.getValue(key).jsonPrimitive.content
    private fun JsonElement.strOrNull(key: String) = jsonObject[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
    private fun JsonElement.cases(key: String): JsonArray = jsonObject.getValue(key).jsonArray

    private fun cells(case: JsonElement, entryId: String) = case.cases("snippets").mapIndexed { i, c ->
        Snippet(
            id = "cell-$i", userId = "", entryId = entryId, content = c.str("content"), position = c.jsonObject.getValue("position").jsonPrimitive.int,
            noteBefore = c.str("note_before"), noteAfter = c.str("note_after"), createdAt = "", updatedAt = "",
        )
    }

    @Test fun labelsAreTheWebs() {
        val labels = fixture.getValue("labels")
        assertEquals(labels.str("before"), CellNoteSide.Before.label)
        assertEquals(labels.str("after"), CellNoteSide.After.label)
        assertEquals("+ Note before run", CellNoteSide.Before.addLabel)
    }

    @Test fun collapseRule() {
        for (c in fixture.cases("collapse")) {
            val text = c.str("text")
            assertEquals("collapsed(${text.replace("\n", "\\n")})", c.jsonObject.getValue("collapsed").jsonPrimitive.boolean, isCollapsedNote(text))
        }
    }

    @Test fun linksFromBodyAndCellNotes() {
        for (c in fixture.cases("links")) {
            val entry = c.jsonObject.getValue("entry")
            val id = entry.str("id")
            val expected = c.cases("links").map { SourcedLink(it.str("source"), it.str("label"), it.strOrNull("id")) }
            assertEquals(c.str("name"), expected, noteLinksOf(id, entry.str("notes"), cells(c, id)))
        }
    }

    @Test fun searchMatchesCellNotes() {
        for (c in fixture.cases("search")) {
            val entry = c.jsonObject.getValue("entry")
            val got = matchesSearch(c.str("query"), entry.str("title"), entry.str("notes"), entry.strOrNull("outcome"), cells(c, entry.str("id")))
            assertEquals(c.str("name"), c.jsonObject.getValue("match").jsonPrimitive.boolean, got)
        }
    }

    @Test fun accessibleNameIsLabelAndFile() {
        assertEquals("Note before run, train.py", cellNoteName(CellNoteSide.Before, "train.py", 0))
        assertEquals("Note after run, Code 2", cellNoteName(CellNoteSide.After, "  ", 1))
    }

    @Test fun oldRowsWithoutTheColumnsDecodeAsEmptyNotes() {
        val row = """{"id":"s","user_id":"u","entry_id":"e","content":"print(1)","created_at":"2026-11-06T00:00:00Z","updated_at":"2026-11-06T00:00:00Z"}"""
        val s = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snippet.serializer(), row)
        assertEquals("" to "", s.noteBefore to s.noteAfter)
        val withNotes = row.replace("}", ""","note_before":"why","note_after":"what"}""")
        val n = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(Snippet.serializer(), withNotes)
        assertEquals("why" to "what", n.noteBefore to n.noteAfter)
    }

    @Test fun limitIs100kAsTheColumnCheck() {
        Limits.cellNote("x".repeat(100_000))
        val e = runCatching { Limits.cellNote("x".repeat(100_001)) }.exceptionOrNull()
        assertEquals(ValidationError::class, e!!::class)
    }
}
