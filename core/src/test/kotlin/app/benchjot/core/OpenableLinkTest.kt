package app.benchjot.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenableLinkTest {
    @Test
    fun sameAnswersAsTheWeb() {
        val cases = Fixtures.json("openable_links.json").jsonArray.map { it.jsonObject }
        assert(cases.isNotEmpty())
        for (c in cases) {
            val url = c.getValue("url").jsonPrimitive.content
            assertEquals("url: $url", c.getValue("open").jsonPrimitive.boolean, isOpenableLink(url))
        }
    }

    @Test
    fun neverOpensScriptOrAppSchemes() {
        for (url in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "content://x/y", "benchjot-note:abc")) assertFalse(url, isOpenableLink(url))
    }
}
