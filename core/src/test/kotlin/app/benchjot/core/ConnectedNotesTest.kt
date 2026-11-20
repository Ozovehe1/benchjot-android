package app.benchjot.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 3 of the context graph: connected notes. agent.json holds the web's own answers (agent.ts, other-notes.ts). */
class ConnectedNotesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val f = Fixtures.json("agent.json").jsonObject
    private fun s(e: kotlinx.serialization.json.JsonElement?) = e!!.jsonPrimitive.content

    @Test fun limitsAndWordsMatchTheWeb() {
        val l = f["notesReach"]!!.jsonObject
        assertEquals(NotesReach.FIND, l["find"]!!.jsonPrimitive.int)
        assertEquals(NotesReach.ASK_STEPS, l["askSteps"]!!.jsonPrimitive.int)
        assertEquals(NotesReach.BUDGET, l["budget"]!!.jsonPrimitive.int)
        assertEquals(NotesReach.DEPTH, l["depth"]!!.jsonPrimitive.int)
        assertEquals(s(f["readOnly"]), READ_ONLY_NOTE)
        f["askRequests"]!!.jsonArray.forEach { c -> c.jsonObject.let { assertEquals(s(it["text"]), askNoteRequest(s(it["question"]), s(it["from"]))) } }
    }

    @Test fun notesAreListedInTheWebsOrder() {
        f["otherNotes"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val from = k["from"]!!.jsonObject
            val rows = json.decodeFromJsonElement(ListSerializer(OtherNoteRow.serializer()), k["rows"]!!)
            val want = json.decodeFromJsonElement(ListSerializer(OtherNote.serializer()), k["list"]!!)
            val parent = from["parent_id"].takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val similar = k["similar"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(s(k["name"]), want, rankOtherNotes(s(from["id"]), parent, s(from["body"]), rows, k["matched"]!!.jsonPrimitive.boolean, k["max"]!!.jsonPrimitive.int, similar))
        }
    }

    private class Plain(private val o: OtherNotes?) : AgentNote {
        override suspend fun read() = Triple("Grain growth", "", emptyList<Cell>())
        override suspend fun add(language: String, code: String) = "x"
        override suspend fun setCode(id: String, code: String) {}
        override suspend fun run(id: String, where: String?) {}
        override suspend fun remove(id: String) {}
        override suspend fun setBody(text: String) {}
        override val others get() = o
    }

    private fun fakeOthers(found: List<OtherNote>, saved: SavedNote? = null) = object : OtherNotes {
        override suspend fun find(query: String, from: SearchFrom?) = if (query == "none") emptyList() else found
        override suspend fun open(id: String) = saved
    }

    @Test fun findNotesSaysItLikeTheWeb() = runTest {
        f["findNotes"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val found = json.decodeFromJsonElement(ListSerializer(OtherNote.serializer()), k["found"]!!)
            val reach = Reach(fakeOthers(found), { AgentReply() })
            val (result, action) = makeCall(AgentCall("find_notes", query = s(k["query"])), Plain(null), AgentUndo(), reach)
            assertEquals(s(k["result"]), result)
            assertEquals(s(k["action"]), action.text)
        }
    }

    @Test fun readNoteGivesTheGraphAndAskNoteRunsThatNotesOwnAgentReadOnly() = runTest {
        val code = "# Potts model\nprint('mean d 9.3')"
        val cells = listOf(Cell("x1", "u", "n", language = "python", content = code, createdAt = "", updatedAt = "", lastRun = LastRun(RunOutcome.Ok, "mean d 9.3\n", "", null, null, 40, "browser", codeHash(code), "2026-11-19T10:00:00.000Z")))
        val saved = SavedNote("Annealing at 600 C", "**Result** 9.3 nm.", cells, "success")
        val asked = mutableListOf<AgentRequest>()
        val reach = Reach(fakeOthers(listOf(OtherNote("a", "Annealing at 600 C", "success", "2026-11-18T09:00:00.000Z")), saved), { r ->
            asked += r
            if (asked.size == 1) AgentReply("", listOf(AgentCall.edit(1, "print(1)")), done = false)
            else AgentReply("It reached 9.3 nm.", emptyList(), done = true, finished = true)
        })
        makeCall(AgentCall("find_notes", query = ""), Plain(null), AgentUndo(), reach)
        val (read, _) = makeCall(AgentCall("read_note", note = "N1"), Plain(null), AgentUndo(), reach)
        assertEquals("Note N1 \"Annealing at 600 C\":\n${noteGraph(saved.title, saved.body, saved.outcome, saved.cells)}", read)
        assertTrue(asked.isEmpty())
        val (answer, action) = makeCall(AgentCall("ask_note", note = "N1", question = "What size?"), Plain(null), AgentUndo(), reach)
        assertEquals(askNoteRequest("What size?", ""), asked[0].request)
        assertEquals("Annealing at 600 C", asked[0].title)
        assertTrue(asked[1].steps.toString(), asked[1].steps.toString().contains(READ_ONLY_NOTE.take(40)))
        assertEquals("Note N1 \"Annealing at 600 C\" answers: It reached 9.3 nm.", answer)
        assertEquals("Asked note \"Annealing at 600 C\"", action.text)
        assertEquals(code, saved.cells[0].content)
        val (unknown, _) = makeCall(AgentCall("read_note", note = "N9"), Plain(null), AgentUndo(), reach)
        assertTrue(unknown.startsWith("There is no note N9"))
        val (nowhere, _) = makeCall(AgentCall("find_notes", query = ""), Plain(null), AgentUndo())
        assertTrue(nowhere.contains("can't be reached"))
    }

    @Test fun inTheLoopTheNotesOwnAgentReachesOtherNotes() = runTest {
        val replies = mutableListOf(
            AgentReply("Looking.", listOf(AgentCall("find_notes", query = "")), done = false),
            AgentReply("Earlier it reached 9.3 nm.", emptyList(), done = true, finished = true),
        )
        val note = Plain(fakeOthers(listOf(OtherNote("a", "Annealing", "success", "2026-11-18T09:00:00.000Z"))))
        val final = runAgent("compare with the annealing note", note, { replies.removeAt(0) }, {}, AgentUndo())
        assertTrue(final is AgentState.Done)
        assertEquals(listOf("Looked for notes · 1 found"), final.steps.flatMap { st -> st.actions.map { it.text } })
    }

    @Test fun proposalsWaitForApplyAndAreKeptWithTheTurn() = runTest {
        val saved = SavedNote("Annealing", "# Result\n9.3 nm", emptyList(), "success")
        val reach = Reach(fakeOthers(listOf(OtherNote("a", "Annealing", "success", "2026-11-18T09:00:00.000Z")), saved), { AgentReply() })
        makeCall(AgentCall("find_notes", query = ""), Plain(null), AgentUndo(), reach)
        val (ok, action) = makeCall(AgentCall("propose_change", note = "N1", after = "# Result", text = "See also [[Grain growth]]."), Plain(null), AgentUndo(), reach)
        assertTrue(ok.contains("until the user taps Apply"))
        assertEquals(ActionDetail("proposal", "See also [[Grain growth]].", noteId = "a", title = "Annealing", after = "# Result"), action.detail)
        assertEquals(action, action.kept().shown())
        val (bad, _) = makeCall(AgentCall("propose_change", note = "N1", after = "Nope", text = "x"), Plain(null), AgentUndo(), reach)
        assertTrue(bad.contains("has no line \"Nope\""))
        val nested = Reach(reach.others, reach.ask, depth = 1).also { it.refs.putAll(reach.refs) }
        val (no, _) = makeCall(AgentCall("propose_change", note = "N1", after = "", text = "x"), Plain(null), AgentUndo(), nested)
        assertTrue(no.startsWith("Only the note the user is working in"))
    }

    @Test fun askingDrawsOnOneBudget() = runTest {
        val saved = SavedNote("Annealing", "", emptyList(), null)
        var calls = 0
        val reach = Reach(fakeOthers(listOf(OtherNote("a", "Annealing", null, "2026-11-18T09:00:00.000Z")), saved), { calls++; AgentReply("", listOf(AgentCall.edit(1, "print($calls)")), done = false) })
        makeCall(AgentCall("find_notes", query = ""), Plain(null), AgentUndo(), reach)
        var last = ""
        repeat(6) { last = makeCall(AgentCall("ask_note", note = "N1", question = "q$it"), Plain(null), AgentUndo(), reach).first }
        assertTrue(calls <= NotesReach.BUDGET)
        assertTrue(last, last.contains("used up for this request"))
    }
}
