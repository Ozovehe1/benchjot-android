package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {
    private fun s(t: String, c: Int = t.length) = Snapshot(t, c, c)

    /** Types `word` one key at a time, `gap` ms apart, starting at `t0`. */
    private fun UndoHistory.type(word: String, t0: Long, gap: Long = 100): Long {
        var t = t0
        for (ch in word) { record(s(current.text + ch), EditKind.Typing, t); t += gap }
        return t
    }

    @Test
    fun emptyHistoryCannotUndoOrRedo() {
        val h = UndoHistory(s(""))
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        assertNull(h.undo()); assertNull(h.redo())
    }

    @Test
    fun quickTypingIsOneStep() {
        val h = UndoHistory(s(""))
        h.type("hello", 0)
        assertEquals("hello", h.current.text)
        assertEquals(s(""), h.undo())
        assertFalse(h.canUndo)
        assertEquals(s("hello"), h.redo())
    }

    @Test
    fun aPauseStartsANewStep() {
        val h = UndoHistory(s(""), windowMillis = 1000)
        val t = h.type("ab", 0)
        h.type("cd", t + 5000)
        assertEquals("ab", h.undo()!!.text)
        assertEquals("", h.undo()!!.text)
    }

    @Test
    fun aNewLineStartsANewStep() {
        val h = UndoHistory(s(""))
        h.type("one\ntwo", 0)
        assertEquals("one", h.undo()!!.text)
        assertEquals("", h.undo()!!.text)
    }

    @Test
    fun backspacesCoalesceToo() {
        val h = UndoHistory(s("abcd"))
        h.record(s("abc"), EditKind.Typing, 0)
        h.record(s("ab"), EditKind.Typing, 100)
        assertEquals("abcd", h.undo()!!.text)
    }

    @Test
    fun commandsAreAlwaysTheirOwnStep() {
        val h = UndoHistory(s(""))
        h.type("b", 0)
        h.record(Snapshot("**b**", 2, 3), EditKind.Command, 50)
        h.type("x", 60)
        assertEquals("**b**", h.undo()!!.text)
        assertEquals(Snapshot("b", 1, 1), h.undo())
        assertEquals("", h.undo()!!.text)
    }

    @Test
    fun pasteIsItsOwnStep() {
        val h = UndoHistory(s("a"))
        h.record(s("a pasted text"), EditKind.Typing, 0)
        h.type("!", 10)
        assertEquals("a pasted text", h.undo()!!.text)
        assertEquals("a", h.undo()!!.text)
    }

    @Test
    fun selectionOnlyChangesAddNoStep() {
        val h = UndoHistory(s("abc"))
        h.record(Snapshot("abc", 0, 3), EditKind.Typing, 0)
        assertFalse(h.canUndo)
        assertEquals(Snapshot("abc", 0, 3), h.current)
    }

    @Test
    fun aNewEditClearsRedo() {
        val h = UndoHistory(s(""))
        h.type("a", 0)
        h.undo()
        assertTrue(h.canRedo)
        h.type("b", 5000)
        assertFalse(h.canRedo)
    }

    @Test
    fun typingAfterUndoDoesNotJoinTheUndoneStep() {
        val h = UndoHistory(s(""))
        h.type("ab", 0)
        h.undo()
        h.type("c", 150)
        assertEquals("", h.undo()!!.text)
    }

    @Test
    fun historyIsCapped() {
        val h = UndoHistory(s(""), limit = 3)
        repeat(10) { h.record(s("x".repeat(it + 1)), EditKind.Command, it.toLong()) }
        var n = 0
        while (h.undo() != null) n++
        assertEquals(3, n)
    }

    @Test
    fun emojiIsOneKey() {
        assertTrue(isTypingStep("a", "a😀"))
        assertTrue(isTypingStep("a😀", "a"))
        assertFalse(isTypingStep("a", "abc"))
    }

    @Test
    fun resetForgetsEverything() {
        val h = UndoHistory(s(""))
        h.type("abc", 0)
        h.reset(s("new"))
        assertFalse(h.canUndo); assertEquals("new", h.current.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAZeroLimit() { UndoHistory(s(""), limit = 0) }
}
