package app.benchjot.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewTest {
    private fun view(text: String, active: Int = -1, renders: (String) -> Boolean = { false }) = liveView(text, liveSpans(text, active, active), renders)

    @Test
    fun hidesMarkersOffTheActiveLine() {
        val v = view("# Title\n**bold** and [[Note|0b6c2c5e-8f0a-4e7a-9a59-9a3c1b7d2e10]]")
        assertEquals("Title\nbold and Note", v.text)
        assertTrue(DisplaySpan(0, 5, LiveKind.H1) in v.spans)
        assertTrue(DisplaySpan(6, 10, LiveKind.Bold) in v.spans)
        assertTrue(DisplaySpan(15, 19, LiveKind.Wikilink) in v.spans)
    }

    @Test
    fun activeLineKeepsItsSource() {
        val text = "# Title\n**bold**"
        assertEquals("Title\n**bold**", view(text, active = 10).text)
        assertEquals("# Title\nbold", view(text, active = 2).text)
    }

    @Test
    fun listMarkersBecomeGlyphPlaceholders() {
        val text = "- a\n  - [ ] b\n- [x] c\n1. d\n> q"
        val v = view(text)
        assertEquals("${GLYPH_PLACEHOLDER}a\n  ${GLYPH_PLACEHOLDER}b\n${GLYPH_PLACEHOLDER}c\n1. d\n${GLYPH_PLACEHOLDER}q", v.text)
        assertEquals(listOf(LiveKind.Bullet, LiveKind.CheckOpen, LiveKind.CheckDone, LiveKind.Quote), v.glyphs.map { it.kind })
        // Each checkbox knows its source line, for toggleCheck.
        val boxes = v.glyphs.filter { it.kind == LiveKind.CheckOpen || it.kind == LiveKind.CheckDone }
        assertEquals("- a\n  - [x] b\n- [x] c\n1. d\n> q", toggleCheck(text, boxes[0].sourceLine))
        assertEquals("- a\n  - [ ] b\n- [ ] c\n1. d\n> q", toggleCheck(text, boxes[1].sourceLine))
    }

    @Test
    fun mathOffTheActiveLineBecomesOneRenderedFormula() {
        val text = "E = \$mc^2\$ and \$x\$\nnext"
        assertEquals(listOf("mc^2", "x"), renderedMath(text, liveSpans(text, -1, -1)))
        val v = view(text) { it == "mc^2" }
        assertEquals("E = ${MATH_PLACEHOLDER} and x\nnext", v.text)
        assertEquals(listOf(LiveGlyph(4, LiveKind.Math, 0, "mc^2")), v.glyphs)
        assertTrue(DisplaySpan(10, 11, LiveKind.Math) in v.spans) // a formula that doesn't render stays styled source
        // The placeholder maps back to the formula's opening `$`, so a tap there puts the cursor in the formula.
        assertEquals(4, v.sourceOffset(4))
        assertEquals(5, v.displayOffset(10))
        assertEquals(10, v.displayOffset(16))
    }

    @Test
    fun mathOnTheActiveLineStaysSource() {
        val text = "E = \$mc^2\$"
        assertEquals(emptyList<String>(), renderedMath(text, liveSpans(text, 0, 0)))
        assertEquals(text, view(text, 0) { true }.text)
    }

    @Test
    fun storedTextIsNeverChanged() {
        // The view is a function of the text; nothing in it writes back.
        val text = "## H\n- [ ] x\n`c` \$m\$"
        val before = String(text.toCharArray())
        view(text); view(text, 3)
        assertEquals(before, text)
    }

    /** The offset mapping invariants a text field needs, on every shared case and every caret line. */
    @Test
    fun offsetMappingIsMonotonicAndInBounds() {
        val texts = Fixtures.json("live_markdown.json").jsonObject.getValue("liveSpans").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content } +
            listOf("", "\n\n", "- [ ] a\n\n> b\n---\n```\n# x\n```\n| a | b |")
        for (text in texts) {
            for (active in listOf(-1) + (0..text.length)) for (renders in listOf(false, true)) {
                val v = view(text, active) { renders }
                var last = 0
                for (s in 0..text.length) {
                    val d = v.displayOffset(s)
                    assertTrue("$text @$active: $s -> $d", d in 0..v.text.length && d >= last)
                    last = d
                }
                assertEquals(v.text.length, v.displayOffset(text.length))
                last = 0
                for (d in 0..v.text.length) {
                    val s = v.sourceOffset(d)
                    assertTrue("$text @$active: $d -> $s", s in 0..text.length && s >= last)
                    last = s
                }
                // A source offset on an active line round-trips (the cursor never lands in hidden text there).
                if (active >= 0) {
                    val l = lineAt(classifyLines(text), active)
                    for (s in l.from..l.to) assertEquals("$text @$active: $s", s, v.sourceOffset(v.displayOffset(s)))
                }
                for (sp in v.spans) assertTrue(sp.from < sp.to && sp.to <= v.text.length)
            }
        }
    }

    @Test
    fun activeFixtureCasesKeepTheirLine() {
        for (c in Fixtures.json("live_markdown.json").jsonObject.getValue("liveSpans").jsonArray.map { it.jsonObject }) {
            val text = c.getValue("text").jsonPrimitive.content
            val a = c.getValue("activeFrom").jsonPrimitive.int
            if (a < 0) continue
            val l = lineAt(classifyLines(text), a)
            val v = liveView(text, liveSpans(text, a, c.getValue("activeTo").jsonPrimitive.int))
            val d0 = v.displayOffset(l.from)
            assertEquals(text.substring(l.from, l.to), v.text.substring(d0, v.displayOffset(l.to)))
        }
    }
}
