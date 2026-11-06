package app.benchjot.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.benchjot.core.MarkdownFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec's "must not break": body tools never reach code cells, and code input is never rewritten. */
class NoteBlocksTest {
    private var now = 0L
    private fun block(text: String) = MarkdownBlockState(text) { now }

    /** Types `s` into a Markdown block one key at a time at its cursor, as the text field reports it. */
    private fun MarkdownBlockState.type(s: String) {
        for (ch in s) {
            val v = value
            val c = v.selection.start
            onValueChange(TextFieldValue(v.text.substring(0, c) + ch + v.text.substring(c), TextRange(c + 1)))
            now += 50
        }
    }

    /** Types `s` into a code cell one key at a time, through the code editor's own change path. */
    private fun CodeState.type(s: String, indent: String = "    ") {
        for (ch in s) {
            val v = field
            val c = v.selection.start
            change(TextFieldValue(v.text.substring(0, c) + ch + v.text.substring(c), TextRange(c + 1)), indent)
        }
    }

    @Test
    fun codeInputIsNeverRewritten() {
        for (input in listOf("# comment", "- 1", "[] ", "- [ ] x", "1. a", "> q", "**b**", "---")) {
            val code = CodeState("")
            code.type(input)
            assertEquals(input, code.text)
            // Enter after a list-looking line adds only the newline (no list continuation in code).
            code.type("\n")
            assertEquals("$input\n", code.text)
        }
    }

    @Test
    fun bodyCommandsNeverChangeSnippets() {
        val snippets = listOf("# comment\n- 1\n[] \n", "print(\"**not bold**\")\n- [ ] x", "")
        val cells = snippets.map { CodeState(it) }
        val bar = FormatBarController()
        val body = block("Title\n- [ ] task\nsome text")
        bar.focused(body, true)
        for (f in MarkdownFormat.entries) {
            bar.target!!.apply(f)
            bar.target!!.undo()
            bar.target!!.redo()
        }
        body.toggleCheckAt(6)
        body.type("\n[] x")
        body.undo()
        assertEquals(snippets, cells.map { it.text })
        // Focus moving to a code cell leaves the bar with no target: nothing can be applied to code.
        bar.focused(body, false)
        assertNull(bar.target)
        assertSame(body, bar.last)
    }

    @Test
    fun enterContinuesAListAndEndsItOnAnEmptyItem() {
        val b = block("")
        b.type("- a\n")
        assertEquals("- a\n- ", b.text)
        b.type("\n")
        assertEquals("- a\n", b.text)
        b.type("1. x\ny")
        assertEquals("- a\n1. x\n2. y", b.text)
    }

    @Test
    fun checkShortcutBecomesAChecklistItem() {
        val b = block("")
        b.type("[] buy")
        assertEquals("- [ ] buy", b.text)
    }

    @Test
    fun undoRedoPerBlockWithTypingCoalesced() {
        val a = block("")
        val other = block("untouched")
        assertFalse(a.canUndo)
        a.type("hello")
        a.apply(MarkdownFormat.Heading)
        assertEquals("# hello", a.text)
        assertTrue(a.canUndo)
        a.undo()
        assertEquals("hello", a.text)
        a.undo()
        assertEquals("", a.text)
        assertFalse(a.canUndo)
        assertTrue(a.canRedo)
        a.redo()
        assertEquals("hello", a.text)
        assertEquals("untouched", other.text)
        assertFalse(other.canUndo)
    }

    @Test
    fun checkboxTapFlipsOnlyThatLine() {
        val b = block("- [ ] a\n- [ ] b")
        b.toggleCheckAt(8)
        assertEquals("- [ ] a\n- [x] b", b.text)
        b.toggleCheckAt(0)
        assertEquals("- [x] a\n- [x] b", b.text)
        b.undo()
        assertEquals("- [ ] a\n- [x] b", b.text)
    }
}
