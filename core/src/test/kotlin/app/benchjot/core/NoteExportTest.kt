package app.benchjot.core

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteExportTest {
    // The web's own exports of a shared note (scripts/web-fixtures.mjs writes note_export.json).
    private val f = Json.parseToJsonElement(File("src/test/resources/web/note_export.json").readText()).jsonObject
    private val json = Json { ignoreUnknownKeys = true }

    private val note: ExportNote = f["note"]!!.jsonObject.let { n ->
        fun s(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
        ExportNote(
            title = s(n, "title"), body = s(n, "body"), outcome = n["outcome"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, exportedAt = s(n, "exportedAt"),
            cells = n["cells"]!!.jsonArray.map { c ->
                val o = c.jsonObject
                ExportCell(
                    s(o, "filename"), s(o, "language"), s(o, "code"), s(o, "noteBefore"), s(o, "noteAfter"),
                    run = o["run"]?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement(LastRun.serializer(), it) },
                    files = o["files"]!!.jsonObject.mapValues { (_, v) -> Base64.getDecoder().decode(v.jsonPrimitive.content) },
                )
            },
        )
    }
    private fun render(md: String) = "<p>${md.replace("&", "&amp;").replace("<", "&lt;")}</p>"

    @Test fun sameNotebookAsTheWeb() = assertEquals(f["ipynb"]!!.jsonPrimitive.content, toNotebook(note))

    @Test fun sameWebPageAsTheWeb() = assertEquals(f["html"]!!.jsonPrimitive.content, toWebPage(note, ::render))

    @Test fun sameMarkdownFilesAsTheWeb() {
        val want = f["md"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to Base64.getDecoder().decode(it.jsonObject["data"]!!.jsonPrimitive.content) }
        val got = markdownFiles(note)
        assertEquals(want.map { it.first }, got.map { it.first })
        want.zip(got).forEach { (w, g) -> assertArrayEquals(w.first, w.second, g.second) }
    }

    @Test fun sameNamesAndSummaries() {
        assertEquals(f["base"]!!.jsonPrimitive.content, exportBaseName(note.title))
        assertEquals(f["summaries"]!!.jsonArray.map { it.jsonPrimitive.content }, note.cells.mapNotNull { it.run }.map(::exportRunSummary))
    }
}
