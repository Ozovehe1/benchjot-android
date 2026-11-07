package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The online helper's pure parts, case for case with the web's helper.test.ts where they overlap. */
class HelperOnlineTest {
    private val js = "const xs = [1, 2, 3];\nconsole.log(xs.map((x) => x * 2));"
    private val py = "import numpy as np\nprint(np.arange(3) * 2)"

    @Test fun placeLabelsAndDefault() {
        assertEquals(listOf("On phone", "Online helper"), HelperPlace.entries.map { it.label })
        assertEquals(HelperPlace.Phone, HelperPlace.of(null))
        assertEquals(HelperPlace.Phone, HelperPlace.of("nope"))
        assertEquals(HelperPlace.Online, HelperPlace.of("online"))
    }

    @Test fun cellsCarryOnlyAnErrorRunsError() {
        fun snippet(run: LastRun?) = Snippet(id = "s", userId = "u", entryId = "e", language = "python", content = "x", lastRun = run, createdAt = "", updatedAt = "")
        fun run(outcome: RunOutcome, errorLine: String?) = LastRun(outcome, "", "Traceback\nValueError: bad", errorLine, null, 1, "browser", "h", "t")
        val cells = helperCells(listOf(snippet(null), snippet(run(RunOutcome.Ok, null)), snippet(run(RunOutcome.Error, "ValueError: bad")), snippet(run(RunOutcome.Error, null))))
        assertEquals(listOf("", "", "ValueError: bad", "Traceback\nValueError: bad"), cells.map { it.error })
    }

    @Test fun languageIsTheLastCellsUnlessPlainText() {
        assertEquals("r", helperLanguage(listOf("python", "r")))
        assertEquals(DEFAULT_LANGUAGE, helperLanguage(listOf("r", "text")))
        assertEquals(DEFAULT_LANGUAGE, helperLanguage(emptyList()))
    }

    @Test fun noteContextFencesCellsAndTheirErrors() {
        val (text, cut) = noteContext("Body", listOf(HelperCell("python", "print(1)", "ZeroDivisionError")))
        assertFalse(cut)
        assertEquals("Body\n\n```python\nprint(1)\n```\nLast error:\n```\nZeroDivisionError\n```", text)
    }

    @Test fun longErrorKeepsItsLastLines() {
        val error = (1..2_000).joinToString("\n") { "line $it" }
        val (text, _) = noteContext("", listOf(HelperCell("python", "x", error)))
        assertTrue(text.contains("Last error (only its last lines):"))
        assertTrue(text.trimEnd('`', '\n').endsWith("line 2000"))
        assertFalse(text.contains("\nline 1\n"))
    }

    @Test fun longNoteIsCutAtALineStartAndSaysSo() {
        val body = (1..5_000).joinToString("\n") { "row $it" }
        val (text, cut) = noteContext(body, emptyList())
        assertTrue(cut)
        assertTrue(text.length <= HelperLimits.MAX_CONTEXT_CHARS)
        assertTrue(text.endsWith("\n" + AssistBudget.NOTE_CUT))
        assertTrue(text.startsWith("row 1\nrow 2\n"))
        text.removeSuffix("\n" + AssistBudget.NOTE_CUT).split("\n").forEach { assertTrue(it, it.matches(Regex("row \\d+"))) }
    }

    @Test fun requestMapsTaskAndCapsWhatIsTyped() {
        val r = helperRequest(AssistTask.Ask, "  why?  ", "python", "T", "Body", emptyList())
        assertEquals(HelperRequest("ask", "why?", "python", "T", "Body"), r)
        assertEquals("write", helperRequest(AssistTask.Write, "x".repeat(3_000), "r", "T", "", emptyList()).task)
        assertEquals(HelperLimits.MAX_REQUEST_CHARS, helperRequest(AssistTask.Write, "x".repeat(3_000), "r", "T", "", emptyList()).request.length)
        assertThrows(IllegalArgumentException::class.java) { helperRequest(AssistTask.Fix, "x", "python", "T", "", emptyList()) }
    }

    @Test fun pythonCellNeverGetsJavaScript() {
        assertEquals("javascript", otherLanguage("python", js))
        assertNull(otherLanguage("python", py))
        assertEquals("python", otherLanguage("javascript", py))
        assertNull(otherLanguage("typescript", js)) // JS and TS count as one
        assertNull(otherLanguage("r", js)) // only Python vs JS/TS is trusted
        val e = assertThrows(FriendlyException::class.java) { readHelperReply(AssistTask.Write, "python", HelperReply("code", code = js, language = "python")) }
        assertEquals("The helper wrote JavaScript, not Python, so it was left out. Try again.", e.message)
    }

    @Test fun readsCodeAndProse() {
        assertEquals(HelperAnswer.Code(py, "Doubles."), readHelperReply(AssistTask.Write, "python", HelperReply("code", code = "$py\n\n", summary = " Doubles. ", language = "python")))
        assertEquals(HelperAnswer.Prose("It says 3."), readHelperReply(AssistTask.Ask, "python", HelperReply("prose", text = " It says 3. ")))
    }

    @Test fun emptyAnswersAreRefusedInPlainWords() {
        assertEquals(AssistError.NoCode.text, assertThrows(FriendlyException::class.java) { readHelperReply(AssistTask.Write, "python", HelperReply("code", code = " ")) }.message)
        assertEquals(HELPER_NO_ANSWER, assertThrows(FriendlyException::class.java) { readHelperReply(AssistTask.Ask, "python", HelperReply("prose", text = "")) }.message)
        assertEquals(HELPER_NO_ANSWER, assertThrows(FriendlyException::class.java) { readHelperReply(AssistTask.Ask, "python", HelperReply("code", code = py)) }.message)
    }

    @Test fun helperErrorsUseTheSharedTable() {
        assertEquals("The helper has hit its limit for now. Try again in a minute.", userText(ErrorFacts(status = 429, code = "rate_limited", retryAfter = 30.0), "helper"))
        assertEquals("The answer would be too long for the helper. Ask for something smaller.", userText(ErrorFacts(status = 413, code = "too_large"), "helper"))
    }
}
