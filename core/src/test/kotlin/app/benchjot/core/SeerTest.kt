package app.benchjot.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 of the context graph: the seer. agent.json holds the web's own answers (outputs.ts, assistant.ts). */
class SeerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val f = Fixtures.json("agent.json").jsonObject

    @Test fun labelsSayWhatTheSeerSawLikeTheWeb() {
        f["produced"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val item = json.decodeFromJsonElement(OutputItem.serializer(), k["item"]!!)
            assertEquals(k["text"]!!.jsonPrimitive.content, producedLabel(item))
            assertEquals(k["short"]!!.jsonPrimitive.content, producedLabel(item, 30))
        }
    }

    @Test fun itLooksAtTheSameOutputsAsTheWeb() {
        f["toSee"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val o = json.decodeFromJsonElement(SavedOutputs.serializer(), k["outputs"]!!)
            val see = json.decodeFromJsonElement(ListSerializer(SeeItem.serializer()), k["see"]!!)
            assertEquals(see, toSee(o))
            val kept = withSeen(o, see.map { Seen(it.index, " seen ${it.index} ") })
            val webSeen = k["kept"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject["seen"]?.jsonPrimitive?.content }
            assertEquals(webSeen, kept.items.map { it.seen })
        }
    }

    private fun figureRun(content: String, at: String) = LastRun(RunOutcome.Ok, "done\n", "", null, null, 9, "browser", codeHash(content), at,
        SavedOutputs(items = listOf(OutputItem("image", source = "matplotlib", title = "Loss", files = mapOf("png" to "0.png"))), dir = "d"))

    private class SeeingNote(initial: List<Cell>) : AgentNote {
        val cells = initial.toMutableList()
        val looked = mutableListOf<String>()
        override suspend fun read() = Triple("T", "b", cells.toList())
        override suspend fun add(language: String, code: String) = "x"
        override suspend fun setCode(id: String, code: String) {}
        override suspend fun remove(id: String) {}
        override suspend fun setBody(text: String) {}
        var runAt = "2026-11-20T12:00:00.000Z"
        var makes: (Cell) -> LastRun? = { null }
        override suspend fun run(id: String, where: String?) { cells.replaceAll { if (it.id == id) it.copy(lastRun = makes(it)) else it } }
        override val sees = true
        override suspend fun see(id: String) {
            looked += id
            cells.replaceAll { c -> if (c.id == id) c.copy(lastRun = c.lastRun!!.copy(outputs = withSeen(c.lastRun!!.outputs!!, listOf(Seen(0, "The loss falls to 0.3."))))) else c }
        }
    }

    private fun cell(id: String, content: String, run: LastRun? = null) = Cell(id, "u", "e", language = "python", content = content, createdAt = "", updatedAt = "", lastRun = run)

    @Test fun afterARunWithAPictureTheReportSaysWhatItShows() = runTest {
        val note = SeeingNote(listOf(cell("a", "plot()")))
        note.makes = { figureRun(it.content, note.runAt) }
        val (result, _) = makeCall(AgentCall.run(1), note, AgentUndo())
        assertEquals(listOf("a"), note.looked)
        assertTrue(result, result.contains("(it shows: The loss falls to 0.3.)"))
        val plain = SeeingNote(listOf(cell("b", "print(1)")))
        plain.makes = { LastRun(RunOutcome.Ok, "1\n", "", null, null, 3, "browser", codeHash(it.content), plain.runAt) }
        makeCall(AgentCall.run(1), plain, AgentUndo())
        assertEquals(emptyList<String>(), plain.looked)
    }

    @Test fun beforeTheFirstStepItLooksAtTheNewestCurrentRuns() = runTest {
        val note = SeeingNote(listOf(
            cell("old", "a", figureRun("a", "2026-11-20T08:00:00.000Z")),
            cell("new", "b", figureRun("b", "2026-11-20T11:00:00.000Z")),
            cell("mid", "c", figureRun("c", "2026-11-20T10:00:00.000Z")),
            cell("stale", "d", figureRun("older code", "2026-11-20T11:30:00.000Z")),
        ))
        val states = mutableListOf<String>()
        var asked = ""
        runAgent("what do the plots show?", note, { r -> asked = r.note; AgentReply("Looked.", emptyList(), done = true, finished = true) }, { s -> if (s is AgentState.Working) states += s.doing }, AgentUndo())
        assertEquals(2, SEE_AT_START)
        assertEquals(listOf("new", "mid"), note.looked)
        assertEquals("Looking at the figures…", states.first())
        assertTrue(asked.contains("it shows: The loss falls to 0.3."))
    }
}
