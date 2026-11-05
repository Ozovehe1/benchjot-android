package app.benchjot.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownFormatTest {
    @Test
    fun sameAnswersAsTheWeb() {
        val cases = Fixtures.json("markdown_format.json").jsonArray.map { it.jsonObject }
        assertEquals(MarkdownFormat.entries.map { it.id }, Fixtures.json("markdown_format_buttons.json").jsonArray.map { it.jsonPrimitive.content })
        for (c in cases) {
            fun edit(k: String) = c.getValue(k).jsonObject.let { FormatEdit(it.getValue("text").jsonPrimitive.content, it.getValue("start").jsonPrimitive.int, it.getValue("end").jsonPrimitive.int) }
            val input = edit("input")
            val format = MarkdownFormat.entries.single { it.id == c.getValue("format").jsonPrimitive.content }
            assertEquals(c.toString(), edit("output"), applyFormat(input.text, input.start, input.end, format))
        }
    }
}
