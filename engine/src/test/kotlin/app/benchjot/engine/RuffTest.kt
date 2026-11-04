package app.benchjot.engine

import app.benchjot.core.RuffEdit
import app.benchjot.core.RuffPosition
import app.benchjot.core.RuffSettings
import app.benchjot.core.applyRuffEdits
import app.benchjot.core.fixableCount
import app.benchjot.core.ruffOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RuffTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/web/ruff.json")!!.readText()).jsonObject
    private val binary = File(System.getProperty("ruff.bin") ?: "build/ruff-host/ruff")

    @Test fun settingsMatchTheWeb() {
        val s = fixture["settings"]!!.jsonObject
        assertEquals(RuffSettings.LINE_LENGTH, s["line-length"]!!.jsonPrimitive.int)
        assertEquals(RuffSettings.INDENT_WIDTH, s["indent-width"]!!.jsonPrimitive.int)
        assertEquals(RuffSettings.SELECT, s["lint"]!!.jsonObject["select"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun pinnedVersionIsTheWebs() {
        val lock = File(System.getProperty("user.dir")).resolve("../scripts/ruff.lock").readLines()
            .first { it.startsWith("version=") }.removePrefix("version=")
        assertEquals(fixture["version"]!!.jsonPrimitive.content, lock)
    }

    @Test fun columnsCountCharacters() {
        val text = "s = \"🌍\"; x\nab"
        assertEquals(text.indexOf("; x"), ruffOffset(text, RuffPosition(1, 8)))
        assertEquals(text.indexOf("ab") + 1, ruffOffset(text, RuffPosition(2, 2)))
        assertEquals(text.length, ruffOffset(text, RuffPosition(9, 1)))
    }

    @Test fun overlappingEditsAreSkipped() {
        val e = { r: Int, c: Int, r2: Int, c2: Int, s: String -> RuffEdit(s, RuffPosition(r, c), RuffPosition(r2, c2)) }
        // Later edits go first; one reaching into an applied edit is dropped (the web's rule).
        assertEquals("abYd", applyRuffEdits("abcd", listOf(e(1, 2, 1, 4, "X"), e(1, 3, 1, 4, "Y"))))
    }

    @Test fun binaryFixesLikeTheWebsWasm() = runBlocking {
        assumeTrue("no Ruff binary at $binary (scripts/fetch-ruff.sh host)", binary.canExecute())
        val ruff = RuffProcess(binary)
        for (sample in fixture["samples"]!!.jsonArray.map { it.jsonObject }) {
            val code = sample["code"]!!.jsonPrimitive.content
            assertEquals("fixable in: $code", sample["fixable"]!!.jsonPrimitive.int, fixableCount(ruff.check(code)))
            assertEquals("fixed: $code", sample["fixed"]!!.jsonPrimitive.content, ruff.fixAll(code))
        }
    }
}
