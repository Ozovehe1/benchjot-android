package app.benchjot.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Test

/** assistant.json: the web's own answers (src/lib/assistant.ts) on the shared cases (tests/fixtures/assistant.ts). */
class AssistantParityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val f = Fixtures.json("assistant.json").jsonObject
    private fun s(e: JsonElement?) = e!!.jsonPrimitive.content

    @Test fun limitsMatchTheWeb() {
        val l = f["limits"]!!.jsonObject
        assertEquals(AssistantLimits.MAX_REQUEST_CHARS, l["maxRequestChars"]!!.jsonPrimitive.int)
        assertEquals(AssistantLimits.MAX_CONTEXT_CHARS, l["maxContextChars"]!!.jsonPrimitive.int)
        assertEquals(AssistantLimits.MAX_ERROR_CHARS, l["maxErrorChars"]!!.jsonPrimitive.int)
        assertEquals(AssistantLimits.MAX_OUTPUT_CHARS, l["maxOutputChars"]!!.jsonPrimitive.int)
        val h = f["history_limits"]!!.jsonObject
        assertEquals(AssistantHistoryLimits.DAYS, h["days"]!!.jsonPrimitive.int)
        assertEquals(AssistantHistoryLimits.TURNS, h["turns"]!!.jsonPrimitive.int)
        assertEquals(AssistantHistoryLimits.MAX_SUMMARY_CHARS, h["maxSummaryChars"]!!.jsonPrimitive.int)
        assertEquals(AssistantHistoryLimits.MAX_STORED_SUMMARY_CHARS, h["maxStoredSummaryChars"]!!.jsonPrimitive.int)
        assertEquals(AssistantHistoryLimits.MAX_TURN_REQUEST_CHARS, h["maxTurnRequestChars"]!!.jsonPrimitive.int)
        assertEquals(AssistantHistoryLimits.MAX_TURN_ANSWER_CHARS, h["maxTurnAnswerChars"]!!.jsonPrimitive.int)
    }

    @Test fun theNoteIsReadLikeTheWeb() {
        val cases = f["context"]!!.jsonArray
        assertEquals(7, cases.size)
        cases.forEach { c ->
            val k = c.jsonObject
            val name = s(k["name"])
            val rows = json.decodeFromJsonElement(ListSerializer(Cell.serializer()), k["rows"]!!)
            val cells = assistantCells(rows)
            assertEquals(name, json.decodeFromJsonElement(ListSerializer(AssistantCell.serializer()), k["cells"]!!), cells)
            val max = k["max"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.int ?: AssistantLimits.MAX_CONTEXT_CHARS
            val (text, cut) = noteContext(s(k["body"]), cells, max)
            assertEquals(name, s(k["text"]), text)
            assertEquals(name, k["cut"]!!.jsonPrimitive.boolean, cut)
        }
    }

    @Test fun theHistoryIsSentLikeTheWeb() {
        f["history"]!!.jsonArray.forEach { c ->
            val k = c.jsonObject
            val name = s(k["name"])
            val turns = json.decodeFromJsonElement(ListSerializer(HistoryTurn.serializer()), k["turns"]!!)
            val h = assistantHistory(s(k["summary"]), turns)
            assertEquals(name, json.decodeFromJsonElement(AssistantHistory.serializer(), k["expected"]!!), h)
            assertEquals(name, k["size"]!!.jsonPrimitive.int, historySize(h))
        }
    }

    @Test fun textItWritesGoesWhereTheWebPutsIt() {
        val cases = f["writes"]!!.jsonArray
        assertEquals(6, cases.size)
        cases.forEach { c ->
            val k = c.jsonObject
            val (placed, under) = placeNoteText(s(k["body"]), NoteWrite(s(k["text"]), s(k["after"])))
            assertEquals(s(k["after"]), s(k["placed"]), placed)
            assertEquals(s(k["after"]), k["under"].let { if (it is JsonNull) null else s(it) }, under)
            assertEquals(s(k["after"]), s(k["undone"]), removeNoteText(placed, s(k["text"])))
        }
    }

    @Test fun whatItWroteGoesBackWithTheHistory() {
        val h = assistantHistory("", listOf(HistoryTurn("ask", "Insert it", AssistantReply("prose", text = "Added.", write = NoteWrite("x".repeat(5_000))))))
        assertEquals(AssistantHistoryLimits.MAX_TURN_ANSWER_CHARS, h.turns[0].answer.wrote.length)
        assertEquals(null, h.turns[0].answer.write)
        assertEquals(6 + 9 + AssistantHistoryLimits.MAX_TURN_ANSWER_CHARS, historySize(h))
    }
}
