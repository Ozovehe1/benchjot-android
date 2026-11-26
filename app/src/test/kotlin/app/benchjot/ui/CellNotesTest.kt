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

/** A cell's "Note before run" / "Note after run": the collapse rule as the screen applies it, and one shared format bar. */
class CellNotesTest {
    private fun block(text: String) = MarkdownBlockState(text) { 0L }

    private fun MarkdownBlockState.type(s: String) {
        for (ch in s) {
            val c = value.selection.start
            onValueChange(TextFieldValue(text.substring(0, c) + ch + text.substring(c), TextRange(c + 1)))
        }
    }

    @Test
    fun emptyNoteIsTheRowUntilTappedAndCollapsesOnBlurWhenEmpty() {
        // An old note: both empty, so both are the "+ Note …" row; nothing changes until a tap.
        assertFalse(showsEditor(open = false, text = ""))
        assertFalse(showsEditor(open = false, text = " \n\t"))
        // Tap opens it; the editor's first focus report on attach (not focused yet) keeps it open.
        assertTrue(showsEditor(open = true, text = ""))
        assertTrue(openAfterFocus(open = true, wasFocused = false, focused = false, text = ""))
        // Focus keeps it open while typing, even when cleared again.
        assertTrue(openAfterFocus(open = true, wasFocused = false, focused = true, text = ""))
        assertTrue(openAfterFocus(open = false, wasFocused = false, focused = true, text = "x"))
        // Blur while empty or whitespace: back to the row.
        assertFalse(openAfterFocus(open = true, wasFocused = true, focused = false, text = ""))
        assertFalse(openAfterFocus(open = true, wasFocused = true, focused = false, text = "  \n"))
        // Blur with text: it stays (it shows because it has text, whatever `open` is).
        assertTrue(openAfterFocus(open = true, wasFocused = true, focused = false, text = "why"))
        assertTrue(showsEditor(open = false, text = "why"))
    }

    @Test
    fun theOneFormatBarActsOnlyOnTheFocusedBlock() {
        val bar = FormatBarController()
        val body = block("body")
        val before = block("")
        val after = block("result")
        bar.focused(before, true)
        before.type("why")
        before.onValueChange(TextFieldValue("why", TextRange(0, 3)))
        bar.target!!.apply(MarkdownFormat.Bold)
        assertEquals("**why**", before.text)
        assertEquals("body", body.text)
        assertEquals("result", after.text)
        // Focus moves to the note after run: the bar follows it; undo there never touches the other blocks.
        bar.focused(before, false)
        bar.focused(after, true)
        assertSame(after, bar.target)
        bar.target!!.apply(MarkdownFormat.Heading)
        bar.target!!.undo()
        assertEquals("result", after.text)
        assertEquals("**why**", before.text)
        // [[link]] completion goes to the block last typed in.
        bar.focused(after, false)
        assertNull(bar.target)
        assertSame(after, bar.last)
    }
}
