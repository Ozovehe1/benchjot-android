package app.benchjot.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTest {
    private val web = Fixtures.json("error_messages.json").jsonObject
    private fun JsonObject.strings() = mapValues { it.value.jsonPrimitive.content }

    @Test
    fun sameTableAsTheWeb() {
        assertEquals(web.getValue("text").jsonObject.strings(), ERROR_TEXT.mapKeys { it.key.id })
        assertEquals(web.getValue("retry").jsonPrimitive.content, RETRY_TEXT)
        assertEquals(web.getValue("actions").jsonObject.strings(), ACTION_TEXT)
        assertEquals(
            web.getValue("overrides").jsonObject.mapValues { it.value.jsonObject.strings() },
            ACTION_OVERRIDES.mapValues { a -> a.value.mapKeys { it.key.id } },
        )
    }

    @Test
    fun sameAnswersAsTheWeb() {
        val cases = web.getValue("cases").jsonArray.map { it.jsonObject }
        assert(cases.isNotEmpty())
        for (c in cases) {
            val f = c.getValue("facts").jsonObject
            val facts = ErrorFacts(
                status = f["status"]?.jsonPrimitive?.intOrNull,
                code = f["code"]?.jsonPrimitive?.contentOrNull,
                message = f["message"]?.jsonPrimitive?.contentOrNull,
                retryAfter = f["retryAfter"]?.jsonPrimitive?.doubleOrNull,
            )
            val action = c["action"]?.jsonPrimitive?.contentOrNull
            val name = c.getValue("name").jsonPrimitive.content
            assertEquals(name, c.getValue("kind").jsonPrimitive.content, errorKind(facts).id)
            assertEquals(name, c.getValue("text").jsonPrimitive.content, userText(facts, action))
        }
    }
}
