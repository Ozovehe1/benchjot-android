package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals

class SampleNoteTest {
    private val web = Fixtures.json("sample_note.json").jsonObject
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sameNoteAsTheWebLanding() {
        val entry = web.getValue("entry").jsonObject
        assertEquals(entry.getValue("title").jsonPrimitive.content, SAMPLE_TITLE)
        assertEquals(entry.getValue("notes").jsonPrimitive.content, SAMPLE_NOTES)
        val s = web.getValue("snippets").jsonArray.single().jsonObject
        assertEquals(s.getValue("filename").jsonPrimitive.content, SAMPLE_SNIPPET.filename)
        assertEquals(s.getValue("language").jsonPrimitive.content, SAMPLE_SNIPPET.language)
        assertEquals(s.getValue("content").jsonPrimitive.content, SAMPLE_SNIPPET.content)
        assertEquals(json.decodeFromJsonElement(LastRun.serializer(), s.getValue("last_run")), SAMPLE_SNIPPET.lastRun)
    }

    @Test
    fun savedRunIsForThisCode() {
        assertEquals(codeHash(SAMPLE_SNIPPET.content), SAMPLE_SNIPPET.lastRun!!.codeHash)
    }
}
