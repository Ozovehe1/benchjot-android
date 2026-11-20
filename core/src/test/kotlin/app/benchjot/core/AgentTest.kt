package app.benchjot.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice agent: agent.json holds the web's own answers (lib/ai/agent.ts); the loop runs on a fake note. */
class AgentTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val f = Fixtures.json("agent.json").jsonObject
    private fun s(e: JsonElement?) = e!!.jsonPrimitive.content
    private val cell = Cell.serializer()

    @Test fun limitsMatchTheWeb() {
        val l = f["limits"]!!.jsonObject
        assertEquals(AgentLimits.STEPS, l["steps"]!!.jsonPrimitive.int)
        assertEquals(AgentLimits.CALLS_PER_STEP, l["callsPerStep"]!!.jsonPrimitive.int)
        assertEquals(AgentLimits.MAX_CODE_CHARS, l["maxCodeChars"]!!.jsonPrimitive.int)
        assertEquals(AgentLimits.MAX_RESULT_CHARS, l["maxResultChars"]!!.jsonPrimitive.int)
        assertEquals(AgentLimits.MAX_HISTORY_CODE_CHARS, l["maxHistoryCodeChars"]!!.jsonPrimitive.int)
        assertEquals(AgentLimits.MAX_MESSAGE_CHARS, l["maxMessageChars"]!!.jsonPrimitive.int)
    }

    @Test fun theNoteIsReadWithNumberedCellsLikeTheWeb() {
        f["context"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val cells = json.decodeFromJsonElement(ListSerializer(cell), k["cells"]!!)
            val max = k["max"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.int ?: AssistantLimits.MAX_CONTEXT_CHARS
            assertEquals(s(k["name"]), s(k["text"]), noteContext(s(k["body"]), agentCells(cells), max).first)
        }
    }

    @Test fun theContextGraphIsBuiltLikeTheWeb() {
        (f["graphs"]!!.jsonArray + f["graphExtra"]!!.jsonArray).forEach { c ->
            val k = c.jsonObject
            val cells = json.decodeFromJsonElement(ListSerializer(cell), k["cells"]!!)
            val outcome = k["outcome"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.content
            assertEquals(s(k["title"]), s(k["text"]), noteGraph(s(k["title"]), s(k["body"]), outcome, cells))
        }
    }

    @Test fun outputsAreSaidAsShownLikeTheWeb() {
        f["shown"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            assertEquals(s(k["text"]), shownLabel(json.decodeFromJsonElement(OutputItem.serializer(), k["item"]!!)))
        }
    }

    @Test fun runsReportLikeTheWeb() {
        f["reports"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            assertEquals(s(k["text"]), runReport(k["n"]!!.jsonPrimitive.int, json.decodeFromJsonElement(cell, k["cell"]!!)))
        }
        f["timings"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val ms = k["ms"]!!.jsonPrimitive.int.toLong()
            assertEquals("$ms ms", s(k["text"]), runReport(1, cell("a", "", run(RunOutcome.Ok, ms = ms))))
        }
    }

    @Test fun stepsGoBackLikeTheWeb() {
        f["steps"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val input = json.decodeFromJsonElement(AgentStep.serializer(), k["input"]!!)
            val sent = sentStep(input.message, input.calls, input.results)
            assertEquals(json.decodeFromJsonElement(AgentStep.serializer(), k["expected"]!!), sent)
            assertEquals(k["size"]!!.jsonPrimitive.int, stepsSize(listOf(sent)))
        }
        // As sent: absent fields are left out, as the web's schema expects.
        val wire = Json { explicitNulls = false }
        assertEquals("""{"tool":"run_cell","cell":2}""", wire.encodeToString(AgentCall.serializer(), AgentCall.run(2)))
    }

    @Test fun refusesFakeCodeLikeTheWeb() {
        val cases = f["smells"]!!.jsonArray
        assertEquals(8, cases.size)
        cases.forEach { c ->
            val k = c.jsonObject
            val want = k["smell"].let { if (it is kotlinx.serialization.json.JsonNull) null else s(it) }
            assertEquals(s(k["code"]), want, fakeSmell(s(k["code"])))
        }
    }

    @Test fun runsGoWhereTheWebSendsThem() {
        val device: Set<String> = f["device_python"]!!.jsonArray.map { s(it) }.toSet()
        assertEquals(device, DEVICE_PYTHON)
        val cases = f["server_imports"]!!.jsonArray
        assertEquals(7, cases.size)
        cases.forEach { c ->
            val k = c.jsonObject
            val want: List<String> = k["server"]!!.jsonArray.map { s(it) }
            assertEquals(s(k["code"]), want, serverOnlyImports(s(k["code"])))
        }
        assertEquals("server", runPlace("python", "import torch", null))
        assertEquals("server", runPlace("python", "import torch", "device"))
        assertEquals(null, runPlace("python", "import numpy", null))
        assertEquals(null, runPlace("r", "import torch", null))
    }

    @Test fun aServerOnlyLibraryRunsOnTheServerAndAMissingOneMovesThere() = runTest {
        val note = FakeNote(listOf(cell("u1", "import torch\nprint(1)"), cell("u2", "import numpy\nprint(2)")))
        val r = makeCall(AgentCall.run(1), note, AgentUndo())
        assertEquals(listOf<String?>("server"), note.ranWhere)
        assertTrue(r.second.text.startsWith("Ran cell 1 on the server"))
        note.ranWhere.clear()
        var first = true
        val missing = object : AgentNote by note {
            override suspend fun run(id: String, where: String?) {
                note.run(id, where)
                if (first) { first = false; note.cells.replaceAll { if (it.id == id) it.copy(lastRun = it.lastRun!!.copy(outcome = RunOutcome.Error, errorLine = "ModuleNotFoundError: No module named 'pyfoo'")) else it } }
            }
        }
        makeCall(AgentCall.run(2), missing, AgentUndo())
        assertEquals(listOf(null, "server"), note.ranWhere)
    }

    @Test fun summariesLikeTheWeb() {
        f["summaries"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val steps = k["steps"]!!.jsonArray.map { st ->
                val o = st.jsonObject
                AgentDone(s(o["message"]), o["actions"]!!.jsonArray.map { a -> a.jsonObject.let { AgentAction(s(it["text"]), s(it["tone"])) } })
            }
            assertEquals(s(k["text"]), agentSummary(steps))
        }
    }

    // ------------------------------------------------------------------ the loop

    private fun run(outcome: RunOutcome, stdout: String = "", errorLine: String? = null, ms: Long = 300) =
        LastRun(outcome, stdout, "", errorLine, null, ms, "browser", "h", "2026-11-19T10:00:00.000Z")
    private fun cell(id: String, content: String, lastRun: LastRun? = null) =
        Cell(id, "u", "e", content = content, lastRun = lastRun, createdAt = "", updatedAt = "")

    /** A note in memory: a run of code containing "boom" fails. */
    private class FakeNote(initial: List<Cell>) : AgentNote {
        val cells = initial.toMutableList()
        var next = 1
        var onRun: () -> Unit = {}
        val ranWhere = mutableListOf<String?>()
        var body = "body"
        override suspend fun read() = Triple("T", body, cells.toList())
        override suspend fun setBody(text: String) { body = text }
        override suspend fun add(language: String, code: String): String {
            val id = "new${next++}"
            cells += Cell(id, "u", "e", language = language, content = code, createdAt = "", updatedAt = "")
            return id
        }
        override suspend fun setCode(id: String, code: String) { cells.replaceAll { if (it.id == id) it.copy(content = code) else it } }
        override suspend fun run(id: String, where: String?) {
            ranWhere += where
            onRun()
            cells.replaceAll {
                if (it.id != id) it
                else it.copy(lastRun = if ("boom" in it.content) LastRun(RunOutcome.Error, "", "", "NameError: boom", null, 40, "browser", codeHash(it.content), "t") else LastRun(RunOutcome.Ok, "ran ${it.content}\n", "", null, null, 1200, "browser", codeHash(it.content), "t"))
            }
        }
        override suspend fun remove(id: String) { cells.removeAll { it.id == id } }
    }

    @Test fun addsRunsFixesAndFinishesThenUndoes() = runTest {
        val note = FakeNote(listOf(cell("u1", "print('mine')")))
        val replies = ArrayDeque(listOf(
            AgentReply("Writing it.", listOf(AgentCall.add("python", "boom"), AgentCall.run(2)), false),
            AgentReply("Fixing the name.", listOf(AgentCall.edit(2, "print(2)"), AgentCall.run(2)), false),
            AgentReply("It prints 2.", emptyList(), true, finished = true),
        ))
        val asked = mutableListOf<AgentRequest>()
        val states = mutableListOf<AgentState>()
        val undo = AgentUndo()
        val end = runAgent("print two", note, { asked += it; replies.removeFirst() }, { states += it }, undo)
        assertTrue(end is AgentState.Done)
        assertEquals(listOf(listOf("Added cell 2 · Python", "Ran cell 2 · failed: NameError: boom"), listOf("Changed cell 2", "Ran cell 2 · 1.2 s"), emptyList()), end.steps.map { st -> st.actions.map { it.text } })
        assertEquals(listOf("fail", "ok"), end.steps.flatMap { it.actions }.filter { it.text.startsWith("Ran") }.map { it.tone })
        assertEquals(listOf("print('mine')", "print(2)"), note.cells.map { it.content })
        assertTrue(asked[1].steps[0].results[1].contains("NameError: boom"))
        assertTrue(asked[1].note.contains("[Cell 2 · you wrote this cell]")) // it knows which cells are its own
        assertEquals("python", asked[0].language)
        assertTrue(states.any { it is AgentState.Working && it.doing == "Running cell 2…" })
        assertEquals(listOf("new1"), undo.added)
        assertTrue(undo.changed.isEmpty())
        undoAgent(note, undo)
        assertEquals(listOf("print('mine')"), note.cells.map { it.content })
    }

    @Test fun theUsersOwnCellIsUndoneToTheirCode() = runTest {
        val note = FakeNote(listOf(cell("u1", "print('mine')")))
        val undo = AgentUndo()
        makeCall(AgentCall.edit(1, "a"), note, undo)
        makeCall(AgentCall.edit(1, "b"), note, undo)
        assertEquals(listOf("u1" to "print('mine')"), undo.changed)
        undoAgent(note, undo)
        assertEquals("print('mine')", note.cells[0].content)
    }

    @Test fun aMissingCellOrAFailedCallGoesBackAsTheResult() = runTest {
        val note = FakeNote(emptyList())
        assertEquals("There is no cell 4; the note has 0 code cells." to AgentAction("No cell 4", "fail"), makeCall(AgentCall.run(4), note, AgentUndo()))
        val broken = object : AgentNote by note { override suspend fun add(language: String, code: String): String = throw IllegalStateException("the new cell didn't appear") }
        assertEquals("That call failed: the new cell didn't appear" to AgentAction("Writing a Python cell failed", "fail"), makeCall(AgentCall.add("python", "x"), broken, AgentUndo()))
    }

    @Test fun theStepCapEndsARequestThatNeverFinishes() = runTest {
        val note = FakeNote(listOf(cell("u1", "x")))
        var asked = 0
        val end = runAgent("go", note, { asked++; AgentReply("Trying.", listOf(AgentCall.edit(1, "print($asked)"), AgentCall.run(1)), false) }, {}, AgentUndo())
        assertEquals(AgentPatience.MAX_STEPS, asked)
        assertTrue(end is AgentState.Done) // its last step is told to finish: it ends with an answer, never "go on"
    }

    @Test fun itCantFinishWhileACellItWroteHasntRun() = runTest {
        val note = FakeNote(emptyList())
        val replies = ArrayDeque(listOf(
            AgentReply("Adding it.", listOf(AgentCall.add("python", "boom")), false),
            AgentReply("Done!", emptyList(), true, finished = true),
        ) + List(3) { AgentReply("It can't run: boom isn't defined anywhere.", emptyList(), true, finished = true) })
        val asked = mutableListOf<AgentRequest>()
        val end = runAgent("go", note, { asked += it; replies.removeFirst() }, {}, AgentUndo())
        assertTrue(end is AgentState.Done)
        assertTrue(asked[2].steps.last().results[0].startsWith("Not finished: cell 1 hasn't been run"))
        // Sent back each time it tries to finish (AgentChecks.CHECKS), then it ends saying plainly what isn't done.
        assertEquals(2 + AgentChecks.CHECKS, asked.size)
        assertTrue(end.steps.last().message.contains("can't run"))
        assertTrue(end.steps.last().message.contains("**Not finished:** cell 1 hasn't been run"))
    }

    @Test fun theReportedStopIsSentBackAndTheCellRuns() = runTest {
        // It changed a cell, said it would run it, and stopped (20 Nov 2026). Now it's sent back and runs it.
        val note = FakeNote(listOf(cell("u1", "print(1)")))
        note.run("u1", null)
        note.ranWhere.clear()
        val replies = ArrayDeque(listOf(
            AgentReply("Changing cell 1.", listOf(AgentCall.edit(1, "print(2)")), false),
            AgentReply("Running cell 1 again.", emptyList(), true), // announced, no call, no finish
            AgentReply("Running it.", listOf(AgentCall.run(1)), false),
            AgentReply("It prints 2.", emptyList(), true, finished = true),
        ))
        val asked = mutableListOf<AgentRequest>()
        val end = runAgent("fix it", note, { asked += it; replies.removeFirst() }, {}, AgentUndo())
        assertTrue(end is AgentState.Done)
        assertEquals(listOf(NO_CALL_RESULT), asked[2].steps.last().results)
        assertEquals(listOf<String?>(null), note.ranWhere)
        assertEquals("It prints 2.", end.steps.last().message)
    }

    @Test fun finishingAfterAChangeWithoutRunningIsSentBack() = runTest {
        val note = FakeNote(listOf(cell("u1", "print(1)")))
        note.run("u1", null)
        val replies = ArrayDeque(listOf(
            AgentReply("Changing it.", listOf(AgentCall.edit(1, "print(2)")), false),
            AgentReply("Done.", emptyList(), true, finished = true),
            AgentReply("Running.", listOf(AgentCall.run(1)), false),
            AgentReply("It prints 2.", emptyList(), true, finished = true),
        ))
        val asked = mutableListOf<AgentRequest>()
        val end = runAgent("go", note, { asked += it; replies.removeFirst() }, {}, AgentUndo())
        assertTrue(asked[2].steps.last().results[0].startsWith("Not finished: cell 1 was changed after its last run"))
        assertEquals("It prints 2.", end.steps.last().message)
    }

    @Test fun aPlainAnswerNeedsNoFinish() = runTest {
        val note = FakeNote(listOf(cell("u1", "print(1)")))
        var asked = 0
        val end = runAgent("what does cell 1 print?", note, { asked++; AgentReply("It prints 1.", emptyList(), true) }, {}, AgentUndo())
        assertEquals(1, asked)
        assertEquals("It prints 1.", end.steps.last().message)
    }

    @Test fun fakeCodeIsNotPutInTheNote() = runTest {
        val note = FakeNote(emptyList())
        val (result, action) = makeCall(AgentCall.add("python", "plt.title('boundaries (placeholder)')"), note, AgentUndo())
        assertTrue(result.startsWith("Not put in the note"))
        assertEquals("fail", action.tone)
        assertTrue(note.cells.isEmpty())
    }

    @Test fun writesInTheNoteAndUndoTakesItBack() = runTest {
        val note = FakeNote(emptyList()).apply { body = "Question\n\n**Result** _(after the runs)_\n\n## Method\nSpirals." }
        val start = note.body
        val undo = AgentUndo()
        assertEquals("Wrote in the note under “Result (after the runs)”", makeCall(AgentCall.write("Result (after the runs)", "Depth helps on spirals."), note, undo).second.text)
        assertEquals("Question\n\n**Result** _(after the runs)_\n\nDepth helps on spirals.\n\n## Method\nSpirals.", note.body)
        makeCall(AgentCall.replaceText("Spirals.", "Two spirals."), note, undo)
        assertTrue(note.body.endsWith("Two spirals."))
        assertTrue(makeCall(AgentCall.replaceText("nowhere", ""), note, undo).first.contains("isn't in the note's text"))
        undoAgent(note, undo)
        assertEquals(start, note.body)
    }

    @Test fun deletesOnlyCellsItAdded() = runTest {
        val note = FakeNote(listOf(cell("u1", "print('mine')")))
        val undo = AgentUndo()
        assertTrue(makeCall(AgentCall.delete(1), note, undo).first.contains("isn't one you added"))
        makeCall(AgentCall.add("python", "print(2)"), note, undo)
        makeCall(AgentCall.delete(2), note, undo)
        assertEquals(listOf("u1"), note.cells.map { it.id })
        assertTrue(undo.added.isEmpty())
    }

    @Test fun stopIsCancellationAndIsHeard() = runTest {
        val note = FakeNote(listOf(cell("u1", "x")))
        val states = mutableListOf<AgentState>()
        val job: Job = launch {
            note.onRun = { this.cancel() }
            runAgent("go", note, { AgentReply("Running.", listOf(AgentCall.run(1), AgentCall.run(1)), false) }, { states += it }, AgentUndo())
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(states.last() is AgentState.Stopped)
        assertEquals(1, states.last().steps.single().actions.size) // the second call never ran
    }

    @Test fun aFailedStepCarriesTheStepsSoFar() = runTest {
        val note = FakeNote(emptyList())
        val e = runCatching { runAgent("go", note, { throw IllegalStateException("429") }, {}, AgentUndo()) }.exceptionOrNull()
        assertTrue(e is AgentFailure && e.cause.message == "429" && e.steps.isEmpty())
    }

    private class Slow(override val status: Int?, override val code: String?, override val retryAfter: Double? = null) : Exception("slow"), StatusFailure

    @Test fun aSlowBusyOrDroppedAiIsWaitedOutAndTheWorkGoesOn() = runTest {
        val note = FakeNote(emptyList())
        var n = 0
        val states = mutableListOf<AgentState>()
        val ask: suspend (AgentRequest) -> AgentReply = {
            n++
            when {
                n == 1 -> AgentReply("Adding it.", listOf(AgentCall.add("python", "print(1)"), AgentCall.run(1)), false, plan = listOf("Add", "Check"), planDone = 0)
                n <= 9 -> throw listOf(Slow(502, "server"), Slow(504, "timeout"), java.io.IOException("reset"))[n % 3]
                else -> AgentReply("Done: cell 1 prints 1.", emptyList(), true, finished = true)
            }
        }
        val end = runAgent("go", note, ask, { states += it }, AgentUndo())
        assertTrue(end is AgentState.Done)
        assertEquals(10, n)
        assertEquals(1, note.cells.size) // nothing done twice
        assertEquals(AgentPlan(listOf("Add", "Check"), 2), end.plan)
        assertTrue(states.none { it is AgentState.Working && Regex("busy|again|error|fail", RegexOption.IGNORE_CASE).containsMatchIn(it.doing) }) // a hiccup isn't shown
        assertTrue(end.steps.flatMap { it.actions }.none { it.tone == "fail" }) // a hiccup isn't marked as a failure
    }

    @Test fun afterATimeoutItsAskedForAShorterReply() = runTest {
        val note = FakeNote(emptyList())
        val asked = mutableListOf<AgentRequest>()
        runAgent("go", note, { asked += it; if (asked.size == 1) throw Slow(504, "timeout") else AgentReply("Done.", emptyList(), true, finished = true) }, {}, AgentUndo())
        assertTrue(asked[0].steps.isEmpty())
        assertEquals(listOf(SLOW_RESULT), asked[1].steps.last().results)
    }

    @Test fun workThatNeedsMoreStepsGoesOnByItselfAndEndsWithAnAnswer() = runTest {
        val note = FakeNote(listOf(cell("u1", "x")))
        val sizes = mutableListOf<Int>()
        val asked = mutableListOf<AgentRequest>()
        val end = runAgent("go", note, { asked += it; sizes += it.steps.size; AgentReply("Running.", listOf(AgentCall.edit(1, "print(${asked.size})"), AgentCall.run(1)), false) }, {}, AgentUndo())
        assertEquals(AgentPatience.MAX_STEPS, asked.size)
        assertEquals(AgentLimits.STEPS - 1, sizes.max()) // the server's limit is never passed
        assertEquals(listOf(LAST_STEP_RESULT), asked.last().steps.last().results) // its last step is told to finish
        assertTrue(end is AgentState.Done && end.steps.last().actions.isEmpty())
    }

    @Test fun onlyWhatWaitingCantFixStopsIt() {
        assertEquals(null, retryWait(Slow(403, null), 0, 0))
        assertEquals(null, retryWait(Slow(401, "session"), 0, 0))
        assertEquals(null, retryWait(Slow(429, "rate_limited", 3600.0), 0, 0)) // today's limit: waiting a minute won't help
        assertEquals(20, retryWait(Slow(429, "rate_limited", 20.0), 0, 0)) // a per-minute limit is waited out
        assertEquals(null, retryWait(Slow(429, null), 0, AgentPatience.LIMIT_TRIES))
        assertEquals(60, retryWait(Slow(502, "server"), 9, 0))
        assertTrue(passingFailure(java.io.IOException("reset")))
    }

    @Test fun aCutReplyIsToldToWriteLess() = runTest {
        val note = FakeNote(emptyList())
        val replies = ArrayDeque(listOf(AgentReply("Writing a long cell", emptyList(), false, cut = true), AgentReply("Nothing to change.", emptyList(), true, finished = true)))
        val asked = mutableListOf<AgentRequest>()
        val end = runAgent("go", note, { asked += it; replies.removeFirst() }, {}, AgentUndo())
        assertTrue(end is AgentState.Done)
        assertEquals(listOf(CUT_RESULT), asked[1].steps.single().results)
        // The half reply isn't shown: only the whole one after it.
        assertEquals(listOf("Nothing to change."), end.steps.map { it.message })
    }

    // The fixes of 20 Nov 2026 (the web's tests/agent-fixes.test.ts).
    @Test fun runCellPassesWhere() = runTest {
        val note = FakeNote(listOf(cell("u1", "print(1)")))
        makeCall(AgentCall("run_cell", cell = 1, where = "server"), note, AgentUndo())
        makeCall(AgentCall.run(1), note, AgentUndo())
        assertEquals(listOf("server", null), note.ranWhere)
    }

    @Test fun noStepNotesInTheAnswer() = runTest {
        val note = FakeNote(emptyList())
        val replies = ArrayDeque(listOf(
            AgentReply("Adding it.", listOf(AgentCall.add("python", "print(1)")), false),
            AgentReply("Done.", emptyList(), true, finished = true), // the cell hasn't run: sent back to check
            AgentReply("Running it.", listOf(AgentCall.run(1)), false),
            AgentReply("It prints 1.", emptyList(), true, finished = true),
        ))
        val doing = mutableListOf<String>()
        val end = runAgent("go", note, { replies.removeFirst() }, { if (it is AgentState.Working) doing += it.doing }, AgentUndo())
        assertTrue(end.steps.flatMap { s -> s.actions.map { it.text } }.none { "Checking" in it || "cut off" in it })
        assertTrue("Checking its work…" in doing)
        assertEquals("Done in the note: Added cell 1 · Python; Ran cell 1 · 1.2 s.\n\nIt prints 1.", agentSummary(end.steps))
    }

    @Test fun summaryListsEachThingOnceAndNeverDoublesDone() {
        val steps = listOf(
            AgentDone("a", listOf(AgentAction("Ran cell 2 · 1.2 s"))),
            AgentDone("Done in the note: ran it.\n\nIt works.", listOf(AgentAction("Ran cell 2 · 3.0 s"))),
        )
        assertEquals("Done in the note: Ran cell 2 · 3.0 s.\n\nIt works.", agentSummary(steps))
        assertEquals("Added cell 2; x" to "It works.", splitSummary("Done in the note: Added cell 2.\n\nDone in the note: x.\n\nIt works."))
    }

    @Test fun undoSkipsCellsTheUserDeletedAndResumesAfterAFailure() = runTest {
        val note = FakeNote(listOf(cell("u1", "a"), cell("u2", "b")))
        val undo = AgentUndo()
        makeCall(AgentCall.add("python", "x"), note, undo)
        makeCall(AgentCall.edit(1, "A"), note, undo)
        makeCall(AgentCall.edit(2, "B"), note, undo)
        note.cells.removeAll { it.id == "u1" } // the user deletes cell 1
        var fail = true
        val flaky = object : AgentNote by note {
            override suspend fun setCode(id: String, code: String) { if (fail) { fail = false; throw java.io.IOException("offline") } else note.setCode(id, code) }
        }
        assertTrue(runCatching { undoAgent(flaky, undo) }.isFailure)
        assertTrue(undo.added.isEmpty()) // already removed, not tried again
        undoAgent(flaky, undo)
        assertEquals(listOf("b"), note.cells.map { it.content })
        assertTrue(undo.isEmpty)
    }

    @Test fun undoPutsBackTextItRemoved() = runTest {
        val note = FakeNote(emptyList()).apply { body = "Intro\n\nOld result.\n\nEnd" }
        val undo = AgentUndo()
        makeCall(AgentCall.replaceText("Old result.", "New result."), note, undo)
        undoAgent(note, undo)
        assertEquals("Intro\n\nOld result.\n\nEnd", note.body)
    }

    @Test fun undoSurvivesTheAppBeingClosed() = runTest {
        // A request adds a cell, changes one, writes in the note; what's kept with the turn is enough to undo it later.
        val note = FakeNote(listOf(cell("u1", "print('mine')"))).apply { body = "Intro" }
        val undo = AgentUndo()
        makeCall(AgentCall.add("python", "print(2)"), note, undo)
        makeCall(AgentCall.edit(1, "print('changed')"), note, undo)
        makeCall(AgentCall.write("", "Its summary."), note, undo)
        val end = AgentState.Done(listOf(AgentDone("Done.", listOf(AgentAction("Added cell 2 · Python", "plain", "new1", ActionDetail("code", "print(2)", language = "python"))))), AgentPlan(listOf("a", "b"), 2))
        val reply = keptReply(end, undo, 41, "python")
        assertEquals("Done.", reply.answer)
        assertEquals(41, reply.log!!.secs)
        // Stored as the web reads it: no nulls, the detail in the web's shape; and read back the same.
        val stored = reply.stored().toString()
        assertTrue(!stored.contains("null"))
        assertTrue(stored.contains("\"detail\":{\"kind\":\"code\",\"language\":\"python\",\"code\":\"print(2)\"}"))
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(AssistantReply.serializer(), stored)
        assertEquals(ActionDetail("code", "print(2)", language = "python"), back.log!!.actions.single().shown().detail)
        undoAgent(note, undoFromKept(back.changes!!))
        assertEquals(listOf("print('mine')"), note.cells.map { it.content })
        assertEquals("Intro", note.body.trim())
    }

    @Test fun itsOwnCellsAreMarked() {
        assertEquals(listOf("[Cell 1]", "[Cell 2 · you wrote this cell]"), agentCells(listOf(cell("u1", "a"), cell("n9", "b")), setOf("n9")).map { it.label })
    }
}
