package app.benchjot.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MathTextTest {
    @Test fun findsTheSameMathAsRemarkMath() {
        for (case in Fixtures.json("math.json").jsonArray.map { it.jsonObject }) {
            val md = case["md"]!!.jsonPrimitive.content
            val want = case["pieces"]!!.jsonArray.map { it.jsonObject }.map { it["tex"]!!.jsonPrimitive.content to it["display"]!!.jsonPrimitive.boolean }
            assertEquals("in: $md", want, extractMath(md).pieces.map { it.tex to it.display })
        }
    }

    @Test fun placeholdersReplaceTheMath() {
        val doc = extractMath("Energy \$E = mc^2\$ here.\n\n\$\$\nx\n\$\$\nafter")
        assertEquals("Energy ![math](bjmath:0) here.\n\n\n![math](bjmath:1)\n\nafter", doc.markdown)
        assertEquals("\$E = mc^2\$", doc.pieces[0].source)
        assertEquals(doc.pieces[1], mathPieceAt("bjmath:1", doc))
        assertNull(mathPieceAt("https://x", doc))
        assertNull(mathPieceAt("bjmath:9", doc))
    }

    @Test fun leavesCodeAndPlainTextAlone() {
        val md = "```py\nx = \"\$a\$\"\n```\n\n`\$b\$` and \\\$c\\\$ and \$5 alone"
        assertEquals(md, extractMath(md).markdown)
    }
}
