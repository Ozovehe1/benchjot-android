package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The online assistant's pure parts, case for case with the web's assistant.test.ts where they overlap. */
class AssistantOnlineTest {
    private val js = "const xs = [1, 2, 3];\nconsole.log(xs.map((x) => x * 2));"
    private val py = "import numpy as np\nprint(np.arange(3) * 2)"

    @Test fun cellsCarryOnlyAnErrorRunsError() {
        fun cell(run: LastRun?) = Cell(id = "s", userId = "u", noteId = "e", language = "python", content = "x", lastRun = run, createdAt = "", updatedAt = "")
        fun run(outcome: RunOutcome, errorLine: String?) = LastRun(outcome, "", "Traceback\nValueError: bad", errorLine, null, 1, "browser", "h", "t")
        val cells = assistantCells(listOf(cell(null), cell(run(RunOutcome.Ok, null)), cell(run(RunOutcome.Error, "ValueError: bad")), cell(run(RunOutcome.Error, null))))
        assertEquals(listOf("", "", "ValueError: bad", "Traceback\nValueError: bad"), cells.map { it.error })
    }

    @Test fun languageIsTheLastCellsUnlessPlainText() {
        assertEquals("r", assistantLanguage(listOf("python", "r")))
        assertEquals(DEFAULT_LANGUAGE, assistantLanguage(listOf("r", "text")))
        assertEquals(DEFAULT_LANGUAGE, assistantLanguage(emptyList()))
    }

    @Test fun noteContextFencesCellsAndTheirErrors() {
        val (text, cut) = noteContext("Body", listOf(AssistantCell("python", "print(1)", "ZeroDivisionError")))
        assertFalse(cut)
        assertEquals("Body\n\n```python\nprint(1)\n```\nLast error:\n```\nZeroDivisionError\n```", text)
    }

    @Test fun longErrorKeepsItsLastLines() {
        val error = (1..2_000).joinToString("\n") { "line $it" }
        val (text, _) = noteContext("", listOf(AssistantCell("python", "x", error)))
        assertTrue(text.contains("Last error (only its last lines):"))
        assertTrue(text.trimEnd('`', '\n').endsWith("line 2000"))
        assertFalse(text.contains("\nline 1\n"))
    }

    @Test fun longNoteIsCutAtALineStartAndSaysSo() {
        val body = (1..20_000).joinToString("\n") { "row $it" }
        val (text, cut) = noteContext(body, emptyList())
        assertTrue(cut)
        assertTrue(text.length <= AssistantLimits.MAX_CONTEXT_CHARS)
        assertTrue(text.endsWith("\n" + NOTE_CUT))
        assertTrue(text.startsWith("row 1\nrow 2\n"))
        text.removeSuffix("\n" + NOTE_CUT).split("\n").forEach { assertTrue(it, it.matches(Regex("row \\d+"))) }
    }

    @Test fun cellsCarryTheirNotesAndASuccessfulRunsOutput() {
        val items = listOf(OutputItem("image", source = "matplotlib", title = " Loss "), OutputItem("table", columns = listOf("a", "b"), rows = listOf(listOf(kotlinx.serialization.json.JsonPrimitive(1), kotlinx.serialization.json.JsonPrimitive("x,y"))), totalRows = 3, totalColumns = 2, title = "ignored"), OutputItem("chart", lib = "plotly"))
        val ok = LastRun(RunOutcome.Ok, "acc 0.9\n", "warn", null, null, 1, "browser", "h", "t", outputs = SavedOutputs(items = items, dir = "d"))
        val s = Cell(id = "s", userId = "u", noteId = "e", language = "python", content = "x", lastRun = ok, noteBefore = "before", noteAfter = "after", createdAt = "", updatedAt = "")
        val cell = assistantCells(listOf(s)).single()
        val table = "Table · 3 × 2 (first 1 of 3 rows):\n```csv\na,b\n1,\"x,y\"\n```"
        assertEquals(AssistantCell("python", "x", "", "before", "after", "acc 0.9\n", listOf("a figure \"Loss\"", "a table, 3 rows × 2 columns (columns: a, b)", "an interactive Plotly chart"), listOf(table)), cell)
        val (text, _) = noteContext("", listOf(cell))
        assertEquals("\n\nNote before run:\nbefore\n```python\nx\n```\nOutput:\n```\nacc 0.9\n```\nShown in the note: a figure \"Loss\"; a table, 3 rows × 2 columns (columns: a, b); an interactive Plotly chart.\n$table\nNote after run:\nafter", text)
    }

    @Test fun theHistorySizeCountsSummaryAndTurns() {
        val h = assistantHistory("s".repeat(1_000), listOf(HistoryTurn("ask", "q", AssistantReply("prose", text = "a"))))
        assertEquals(1_002, historySize(h))
    }

    @Test fun storedAnswersKeepOnlyTheirKindsFields() {
        assertEquals(setOf("kind", "code", "summary", "language"), AssistantReply("code", code = "x", language = "python").stored().keys)
        assertEquals(setOf("kind", "text"), AssistantReply("prose", text = "t").stored().keys)
        assertEquals(false, SavedTurn("i", "ask", "q", AssistantReply("prose")).readable)
        assertEquals(false, SavedTurn("i", "run", "q", AssistantReply("prose", text = "t")).readable)
        assertEquals(true, SavedTurn("i", "write", "q", AssistantReply("code", code = "x")).readable)
    }

    @Test fun assistantErrorsUseTheSharedTable() {
        assertEquals("The Assistant has hit its limit for now. Try again in a minute.", userText(ErrorFacts(status = 429, code = "rate_limited", retryAfter = 30.0), "assistant"))
        assertEquals("The answer would be too long for the Assistant. Ask for something smaller.", userText(ErrorFacts(status = 413, code = "too_large"), "assistant"))
    }
}
