package app.benchjot.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A request left mid-way is picked up again from its last step (the web's agent-resume tests). */
class ResumeTest {
    private class Plain : AgentNote {
        var body = "b"
        var saw = 0
        override suspend fun read() = Triple("T", body, emptyList<Cell>())
        override suspend fun add(language: String, code: String) = "x"
        override suspend fun setCode(id: String, code: String) {}
        override suspend fun run(id: String, where: String?) {}
        override suspend fun remove(id: String) {}
        override suspend fun setBody(text: String) { body = text }
        override val sees = true
        override suspend fun see(id: String) { saw++ }
    }

    @Test fun aCheckpointComesBeforeEveryStep() = runTest {
        val points = mutableListOf<AgentCheckpoint>()
        val replies = mutableListOf(
            AgentReply("Writing.", listOf(AgentCall.write("", "Line one.")), done = false, plan = listOf("Write", "Check")),
            AgentReply("Done.", emptyList(), done = true, finished = true),
        )
        val final = runAgent("write", Plain(), { replies.removeAt(0) }, {}, AgentUndo(), onCheckpoint = { points += it })
        assertTrue(final is AgentState.Done)
        assertEquals(listOf(0, 1), points.map { it.step })
        assertEquals(1, points[1].sent.size)
        assertEquals(listOf("Write", "Check"), points[1].plan?.steps)
    }

    @Test fun pickedUpAgainItGoesOnFromThatStep() = runTest {
        val note = Plain()
        val resume = AgentCheckpoint(
            3,
            listOf(AgentStep("Writing.", listOf(AgentCall.write("", "Line one.")), listOf("Wrote it at the end of the note."))),
            listOf(AgentDone("Writing.", listOf(AgentAction("Wrote in the note")))),
            1, 0, AgentPlan(listOf("Write", "Check"), 1),
        )
        val asked = mutableListOf<AgentRequest>()
        val states = mutableListOf<String>()
        val final = runAgent("write", note, { asked += it; AgentReply("Both lines are in.", emptyList(), done = true, finished = true) }, { if (it is AgentState.Working) states += it.doing }, AgentUndo(), resume = resume)
        assertTrue(final is AgentState.Done)
        assertEquals("Picking up where it left off…", states.first())
        assertEquals("Wrote it at the end of the note.", asked[0].steps.single().results.single())
        assertEquals(listOf("Writing.", "Both lines are in."), final.steps.map { it.message })
        assertEquals(0, note.saw)
    }

    @Test fun aCheckpointIsKeptAsJsonAndReadsBackTheSame() {
        val c = AgentCheckpoint(2, listOf(AgentStep("m", listOf(AgentCall.run(1)), listOf("r"))), listOf(AgentDone("m", listOf(AgentAction("Ran cell 1", "ok", "c1", ActionDetail("output", "1"))))), 1, 2, AgentPlan(listOf("a"), 0))
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val back = json.decodeFromString(KeptCheckpoint.serializer(), json.encodeToString(KeptCheckpoint.serializer(), c.kept())).shown()
        assertEquals(c, back)
    }
}
