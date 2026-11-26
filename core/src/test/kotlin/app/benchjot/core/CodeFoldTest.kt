package app.benchjot.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeFoldTest {
    @Test
    fun sameAsTheWeb() {
        val web = Fixtures.json("code_fold.json").jsonObject
        assertEquals(web.getValue("fold_lines").jsonPrimitive.int, FOLD_LINES)
        for (c in web.getValue("cases").jsonArray.map { it.jsonObject }) {
            val code = c.getValue("code").jsonPrimitive.content
            assertEquals(c.getValue("lines").jsonPrimitive.int, lineCount(code))
            assertEquals(c.getValue("folds").jsonPrimitive.boolean, folds(code))
            assertEquals(c.getValue("closed").jsonPrimitive.content, foldLabel(code, false))
            assertEquals(c.getValue("open").jsonPrimitive.content, foldLabel(code, true))
        }
    }
}
