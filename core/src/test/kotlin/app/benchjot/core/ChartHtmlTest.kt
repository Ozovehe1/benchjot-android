package app.benchjot.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChartHtmlTest {
    // The web's own pages on shared specs (scripts/web-fixtures.mjs writes chart_html.json).
    private val cases = Json.parseToJsonElement(File("src/test/resources/web/chart_html.json").readText()).jsonArray

    @Test fun samePageAsTheWeb() {
        for (c in cases) {
            val o = c.jsonObject
            fun s(k: String) = o[k]!!.jsonPrimitive.content
            val html = chartHtml(s("lib"), s("version"), s("spec"), s("title"))
            assertEquals(s("lib"), s("html"), html)
            assertFalse(html.substringAfter("const spec = ").contains("</script><!--"))
        }
    }
}
