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

/** helper.json: the web's own answers (src/lib/helper.ts) on the shared cases (tests/fixtures/helper.ts). */
class HelperParityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val f = Fixtures.json("helper.json").jsonObject
    private fun s(e: JsonElement?) = e!!.jsonPrimitive.content

    @Test fun limitsMatchTheWeb() {
        val l = f["limits"]!!.jsonObject
        assertEquals(HelperLimits.MAX_REQUEST_CHARS, l["maxRequestChars"]!!.jsonPrimitive.int)
        assertEquals(HelperLimits.MAX_CONTEXT_CHARS, l["maxContextChars"]!!.jsonPrimitive.int)
        assertEquals(HelperLimits.MAX_ERROR_CHARS, l["maxErrorChars"]!!.jsonPrimitive.int)
        assertEquals(HelperLimits.MAX_OUTPUT_CHARS, l["maxOutputChars"]!!.jsonPrimitive.int)
        val h = f["history_limits"]!!.jsonObject
        assertEquals(HelperHistoryLimits.DAYS, h["days"]!!.jsonPrimitive.int)
        assertEquals(HelperHistoryLimits.TURNS, h["turns"]!!.jsonPrimitive.int)
        assertEquals(HelperHistoryLimits.MAX_SUMMARY_CHARS, h["maxSummaryChars"]!!.jsonPrimitive.int)
        assertEquals(HelperHistoryLimits.MAX_STORED_SUMMARY_CHARS, h["maxStoredSummaryChars"]!!.jsonPrimitive.int)
        assertEquals(HelperHistoryLimits.MAX_TURN_REQUEST_CHARS, h["maxTurnRequestChars"]!!.jsonPrimitive.int)
        assertEquals(HelperHistoryLimits.MAX_TURN_ANSWER_CHARS, h["maxTurnAnswerChars"]!!.jsonPrimitive.int)
    }

    @Test fun theNoteIsReadLikeTheWeb() {
        val cases = f["context"]!!.jsonArray
        assertEquals(7, cases.size)
        cases.forEach { c ->
            val k = c.jsonObject
            val name = s(k["name"])
            val snippets = json.decodeFromJsonElement(ListSerializer(Snippet.serializer()), k["snippets"]!!)
            val cells = helperCells(snippets)
            assertEquals(name, json.decodeFromJsonElement(ListSerializer(HelperCell.serializer()), k["cells"]!!), cells)
            val max = k["max"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.int ?: HelperLimits.MAX_CONTEXT_CHARS
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
            val h = helperHistory(s(k["summary"]), turns)
            assertEquals(name, json.decodeFromJsonElement(HelperHistory.serializer(), k["expected"]!!), h)
            assertEquals(name, k["size"]!!.jsonPrimitive.int, historySize(h))
        }
    }

    @Test fun thePhoneHasTheWebsRules() {
        val rules = f["rules"]!!.jsonObject
        assertEquals(setOf("python", "javascript", "r"), rules.keys)
        rules.forEach { (language, r) ->
            assertEquals(language, s(r.jsonObject["write"]), helperRules(AssistTask.Write, language))
            assertEquals(language, s(r.jsonObject["ask"]), helperRules(AssistTask.Ask, language))
        }
        f["earlier"]!!.jsonArray.forEach { e -> assertEquals(s(e.jsonObject["block"]), earlierBlock(s(e.jsonObject["summary"]))) }
    }
}
