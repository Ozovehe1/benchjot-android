package app.benchjot.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The web's hand-written format cases (tests/fixtures/markdown_format.json), answered by core. */
class MarkdownFormatTest {
    private fun edit(o: JsonObject) = FormatEdit(o.getValue("text").jsonPrimitive.content, o.getValue("start").jsonPrimitive.int, o.getValue("end").jsonPrimitive.int)

    @Test
    fun sameAnswersAsTheWeb() {
        val cases = Fixtures.json("markdown_format.json").jsonArray.map { it.jsonObject }
        for (c in cases) {
            val input = edit(c.getValue("input").jsonObject)
            val format = MarkdownFormat.of(c.getValue("format").jsonPrimitive.content)
            assertEquals(c.toString(), edit(c.getValue("output").jsonObject), applyFormat(input.text, input.start, input.end, format))
        }
        // Every command is covered by at least one shared case.
        assertEquals(MarkdownFormat.entries.toSet(), cases.map { MarkdownFormat.of(it.getValue("format").jsonPrimitive.content) }.toSet())
    }

    @Test
    fun buttonsInTheWebsOrder() {
        assertEquals(Fixtures.json("markdown_format_buttons.json").jsonArray.map { it.jsonPrimitive.content }, MarkdownFormat.entries.map { it.id })
        assertEquals(Fixtures.json("markdown_format_sheet.json").jsonArray.map { it.jsonPrimitive.content }, SHEET_FORMATS.map { it.id })
    }

    @Test
    fun selectionOutOfRangeIsClamped() {
        assertEquals(FormatEdit("ab****", 4, 4), applyFormat("ab", 9, 9, MarkdownFormat.Bold))
        assertEquals(FormatEdit("a **b** c", 4, 5), applyFormat("a b c", 3, 2, MarkdownFormat.Bold))
    }
}
