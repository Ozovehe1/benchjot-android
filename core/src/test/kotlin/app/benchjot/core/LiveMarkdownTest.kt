package app.benchjot.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The web's live-view cases (tests/fixtures/live_markdown.json), answered by core. */
class LiveMarkdownTest {
    private val fx = Fixtures.json("live_markdown.json").jsonObject
    private fun cases(k: String) = fx.getValue(k).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
    private fun edit(e: JsonElement): FormatEdit? = if (e is JsonNull) null else e.jsonObject.let { FormatEdit(it.str("text"), it.int("start"), it.int("end")) }

    @Test
    fun liveSpans() {
        val all = cases("liveSpans")
        for (c in all) {
            val want = c.getValue("spans").jsonArray.map { it.jsonObject.let { s -> LiveSpan(s.int("from"), s.int("to"), LiveKind.of(s.str("kind"))) } }
            assertEquals(c.str("name"), want, liveSpans(c.str("text"), c.int("activeFrom"), c.int("activeTo")))
        }
        // Every kind is in at least one shared case.
        assertEquals(LiveKind.entries.toSet(), all.flatMap { c -> c.getValue("spans").jsonArray.map { LiveKind.of(it.jsonObject.str("kind")) } }.toSet())
    }

    @Test
    fun toggleCheck() {
        for (c in cases("toggleCheck")) assertEquals(c.toString(), c.str("output"), toggleCheck(c.str("text"), c.int("lineStart")))
    }

    @Test
    fun continueList() {
        for (c in cases("continueList")) {
            val i = c.getValue("input").jsonObject
            assertEquals(c.toString(), edit(c.getValue("output")), continueList(i.str("text"), i.int("caret")))
        }
    }

    @Test
    fun checkShortcut() {
        for (c in cases("checkShortcut")) {
            val i = c.getValue("input").jsonObject
            assertEquals(c.toString(), edit(c.getValue("output")), expandCheckShortcut(i.str("text"), i.int("caret")))
        }
    }

    @Test
    fun formatState() {
        for (c in cases("formatState")) {
            val i = c.getValue("input").jsonObject
            val e = c.getValue("expected").jsonObject
            val want = FormatState(
                heading = e.int("heading"),
                list = e.getValue("list").let { if (it is JsonNull) null else ListKind.entries.single { k -> k.id == it.jsonPrimitive.content } },
                quote = e.getValue("quote").jsonPrimitive.boolean,
                bold = e.getValue("bold").jsonPrimitive.boolean, italic = e.getValue("italic").jsonPrimitive.boolean,
                strike = e.getValue("strike").jsonPrimitive.boolean, code = e.getValue("code").jsonPrimitive.boolean,
                math = e.getValue("math").jsonPrimitive.boolean, link = e.getValue("link").jsonPrimitive.boolean,
            )
            assertEquals(c.toString(), want, formatState(i.str("text"), i.int("start"), i.int("end")))
        }
    }

    @Test
    fun outOfRangeInputsAreSafe() {
        assertNull(continueList("- a", -1))
        assertNull(continueList("- a", 99))
        assertNull(expandCheckShortcut("[] ", 99))
        assertEquals("- [x] a", toggleCheck("- [ ] a", -5))
        assertEquals(emptyList<LiveSpan>(), liveSpans("", 0, 0))
    }
}
