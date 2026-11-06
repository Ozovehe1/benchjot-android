package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The token budget, with stand-in tokenizers (the real one, llama.cpp's, runs only on the
// device: LlamaNativeTest). Every Ready plan is checked against the one rule that matters:
// prompt + answer + margin never exceed the 4,096-token context.
class AssistBudgetTest {
    private val ctx = OnDeviceModel.CONTEXT_TOKENS
    private val code4 = TokenCounter { (it.length + 3) / 4 }   // typical code
    private val code3 = TokenCounter { (it.length + 2) / 3 }   // the old chars/3 guess
    private val perChar = TokenCounter { it.length }           // pessimistic: one token per char

    private fun fits(plan: AssistPlan, counter: TokenCounter): AssistPlan.Ready {
        assertTrue("not ready: $plan", plan is AssistPlan.Ready)
        val r = plan as AssistPlan.Ready
        assertEquals("counted the prompt it returns", counter.count(r.prompt), r.promptTokens)
        assertTrue("${r.promptTokens} + ${r.maxNewTokens} overflows", r.promptTokens + r.maxNewTokens + AssistBudget.SAFETY_TOKENS <= ctx)
        assertTrue("answer starved: ${r.maxNewTokens}", r.maxNewTokens >= minOf(AssistBudget.MIN_REPLY_TOKENS, AssistTask.Fix.maxNewTokens))
        return r
    }

    /** [n] distinct lines of exactly [width] chars: "x007 = 1111…". */
    private fun program(n: Int, width: Int = 50) = (0 until n).joinToString("\n") { "x%03d = ".format(it).padEnd(width, '1') }

    private fun traceback(line: Int, lines: Int = 3) =
        (List(lines - 2) { "  File \"lib$it.py\", line ${it + 900}, in helper" } + "  File \"main.py\", line $line, in <module>" + "NameError: name 'y' is not defined")
            .joinToString("\n")

    @Test fun normalFixFitsWithTheFullAnswer() {
        val r = fits(AssistBudget.plan(AssistInput.Fix("python", "print(y)", traceback(1)), code4), code4)
        assertFalse(r.trimmed)
        assertNull(r.window)
        assertEquals(AssistTask.Fix.maxNewTokens, r.maxNewTokens)
        assertTrue(r.prompt.contains("print(y)") && r.prompt.contains("NameError"))
    }

    @Test fun worstCaseSixThousandCharsOfCodeFits() {
        val code = program(100) + "\n" + "z".repeat(AssistTask.Fix.maxInputChars - program(100).length - 1)
        assertEquals(AssistTask.Fix.maxInputChars, code.length)
        val error = "e".repeat(ASSIST_ERROR_TAIL_CHARS - 40) + "\n" + traceback(100)
        // Typical code: the whole program fits, with room to rewrite all of it.
        val typical = fits(AssistBudget.plan(AssistInput.Fix("python", code, error), code4), code4)
        assertNull(typical.window)
        assertTrue(typical.maxNewTokens >= code4.count(code) + AssistBudget.FIX_SUMMARY_TOKENS)
        // Denser tokens (the old chars/3 guess, the case that overflowed by ~165): still never past the context.
        fits(AssistBudget.plan(AssistInput.Fix("python", code, error), code3), code3)
    }

    @Test fun hugeErrorTailKeepsItsEnd() {
        val error = traceback(2, lines = 400) // ~16,000 chars of frames
        val r = fits(AssistBudget.plan(AssistInput.Fix("python", "import x\nprint(y)", error), perChar), perChar)
        assertTrue(r.prompt.contains("NameError: name 'y' is not defined"))
        assertTrue(r.prompt.contains("only its last lines"))
        assertFalse(r.prompt.contains("line 900,")) // the front of the error went first
        assertNull(r.window) // the code was never cut for the error's sake
    }

    @Test fun trimsTheErrorThenTheCodeToWholeLinesAroundTheError() {
        val code = program(120) // 6,119 chars: far too big at one token per char
        val r = fits(AssistBudget.plan(AssistInput.Fix("python", code, traceback(100, lines = 40)), perChar), perChar)
        assertNotNull(r.window)
        val w = r.window!!
        assertTrue(r.trimmed)
        assertTrue("window $w keeps line 100", 99 in w.first..w.last)
        assertEquals(120, w.totalLines)
        assertTrue(r.prompt.contains("These are lines ${w.first + 1}–${w.last + 1} of a 120-line program"))
        val lines = code.split("\n")
        (w.first..w.last).forEach { assertTrue("line $it shown whole", r.prompt.contains(lines[it])) }
        if (w.first > 0) assertFalse(r.prompt.contains("x%03d = ".format(w.first - 1)))
        if (w.last < 119) assertFalse(r.prompt.contains("x%03d = ".format(w.last + 1)))
        assertTrue(r.maxNewTokens >= perChar.count(lines.subList(w.first, w.last + 1).joinToString("\n")))
        // The answer for the window goes back into the whole program.
        val fixed = w.splice(code, "FIXED")!!
        assertEquals(120 - (w.last - w.first + 1) + 1, fixed.split("\n").size)
        assertEquals(lines.take(w.first) + "FIXED" + lines.drop(w.last + 1), fixed.split("\n"))
    }

    @Test fun cursorLineWinsOverTheErrorsLine() {
        val r = fits(AssistBudget.plan(AssistInput.Fix("python", program(120), traceback(100), focusLine = 5), perChar), perChar)
        assertTrue(4 in r.window!!.first..r.window!!.last)
    }

    @Test fun impossibleIsTooLarge() {
        // One 6,000-char line can't be windowed without cutting it mid-line.
        assertTrue(AssistBudget.plan(AssistInput.Fix("python", "y".repeat(6_000), traceback(1)), perChar) is AssistPlan.TooLarge)
        assertTrue(AssistBudget.plan(AssistInput.Write("python", "w".repeat(4_000)), perChar) is AssistPlan.TooLarge)
        assertTrue(AssistBudget.plan(AssistInput.Ask("T", "note", "q".repeat(5_000)), perChar) is AssistPlan.TooLarge)
        assertTrue(AssistBudget.plan(AssistInput.Raw(AssistTask.Ask, "r".repeat(4_000)), perChar) is AssistPlan.TooLarge)
    }

    @Test fun askKeepsTheNotesStartAndSaysItWasCut() {
        val note = program(160, width = 49) // 7,999 chars
        val r = fits(AssistBudget.plan(AssistInput.Ask("Run 3", note, "What changed?"), perChar), perChar)
        assertTrue(r.trimmed)
        assertTrue(r.prompt.contains(AssistBudget.NOTE_CUT))
        assertTrue(r.prompt.contains("x000 = "))
        assertFalse(r.prompt.contains("x159 = "))
        assertEquals(AssistTask.Ask.maxNewTokens, r.maxNewTokens)
    }

    @Test fun writeAndRawGetTheirOwnCaps() {
        assertEquals(AssistTask.Write.maxNewTokens, fits(AssistBudget.plan(AssistInput.Write("python", "plot a sine"), code4), code4).maxNewTokens)
        assertEquals(AssistTask.Ask.maxNewTokens, fits(AssistBudget.plan(AssistInput.Raw(AssistTask.Ask, "Once upon a time"), code4), code4).maxNewTokens)
    }

    @Test fun neverOverflowsAcrossSizes() {
        for (counter in listOf(code4, code3, perChar)) for (n in listOf(1, 2, 10, 40, 80, 120)) for (e in listOf(3, 30, 300)) {
            val plan = AssistBudget.plan(AssistInput.Fix("python", program(n), traceback(n, lines = e)), counter)
            if (plan is AssistPlan.Ready) fits(plan, counter)
        }
    }

    @Test fun errorTailCutsAtLineStarts() {
        val error = (1..200).joinToString("\n") { "frame $it ".padEnd(29, '.') }
        val (lines, cut) = AssistBudget.errorTail(error)
        assertTrue(cut)
        assertEquals("frame 200 ".padEnd(29, '.'), lines.last())
        assertTrue(lines.all { it.length == 29 })
        assertTrue(lines.joinToString("\n").length <= ASSIST_ERROR_TAIL_CHARS)
        assertEquals(listOf("a", "b") to false, AssistBudget.errorTail("a\nb\n"))
    }

    @Test fun windowsStayInsideTheProgram() {
        assertEquals(CodeWindow(0, 4, 10), AssistBudget.windowAround(0, 5, 10))
        assertEquals(CodeWindow(5, 9, 10), AssistBudget.windowAround(9, 5, 10))
        assertEquals(CodeWindow(4, 6, 10), AssistBudget.windowAround(5, 3, 10))
        assertEquals(12, AssistBudget.lastLineNumber("File \"a\", line 3\nFile \"b\", line 12\nErr"))
        assertNull(AssistBudget.lastLineNumber("SyntaxError"))
        assertEquals("a\nB\nC\nX\nd", CodeWindow(1, 2, 4).splice("a\nb\nc\nd", "B\nC\nX"))
        assertNull(CodeWindow(1, 2, 4).splice("a\nb", "B"))
    }
}
