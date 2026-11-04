package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunTest {
    @Test fun codeHashMatchesTheWeb() {
        for (case in Fixtures.json("codehash.json").jsonArray.map { it.jsonObject }) {
            assertEquals(case["hash"]!!.jsonPrimitive.content, codeHash(case["code"]!!.jsonPrimitive.content))
        }
    }

    @Test fun pythonTracebacks() {
        val tb = """Traceback (most recent call last):
  File "/lib/python312.zip/_pyodide/_base.py", line 573, in eval_code_async
    await CodeRunner(
  File "<exec>", line 2, in <module>
  File "<exec>", line 5, in f
ZeroDivisionError: division by zero"""
        assertEquals(5, pythonErrorLine(tb))
        assertEquals(
            "Traceback (most recent call last):\n  File \"<exec>\", line 2, in <module>\n  File \"<exec>\", line 5, in f\nZeroDivisionError: division by zero",
            cleanPythonTraceback(tb),
        )
        assertNull(pythonErrorLine("nothing"))
    }

    @Test fun jsStacks() {
        assertEquals(3, jsErrorLine("TypeError: x\n    at eval (snippet.js:4:7)"))
        assertNull(jsErrorLine("TypeError: x\n    at eval (snippet.js:1:7)"))
        assertEquals(RunError("TypeError: x", 3), deviceError("javascript", "TypeError: x\n    at eval (snippet.js:4:7)"))
    }

    @Test fun summaries() {
        assertEquals("Running…", runSummary(null, true, ""))
        assertEquals("Loading Python…", runSummary(null, true, "Loading Python…"))
        assertEquals("1.23 s", runSummary(RunResult(RunOutcome.Ok, null, 1234), false, ""))
        assertEquals("Error on line 2 · 0.05 s", runSummary(RunResult(RunOutcome.Error, RunError("x", 2), 50), false, ""))
        assertEquals("Stopped after 30 s (time limit)", runSummary(RunResult(RunOutcome.Timeout, null, 30000), false, ""))
    }

    @Test fun lastRunRoundTrip() {
        val chunks = listOf(Chunk(Stream.Stdout, "a\n"), Chunk(Stream.Stderr, "w\n"), Chunk(Stream.Stdout, "b\n"))
        val run = toLastRun(chunks, RunResult(RunOutcome.Error, RunError("Boom", 3), 12), "browser", "code", Instant.parse("2026-11-04T10:00:00.123Z"))
        assertEquals("a\nb\n", run.stdout)
        assertEquals("w\n", run.stderr)
        assertEquals("2026-11-04T10:00:00.123Z", run.ranAt)
        assertEquals(codeHash("code"), run.codeHash)
        val json = Json.encodeToString(LastRun.serializer(), run)
        assertTrue(json, json.contains("\"error_line\":\"Boom\"") && json.contains("\"outcome\":\"error\"") && json.contains("\"code_hash\""))
        val (shown, result) = fromLastRun(Json.decodeFromString(LastRun.serializer(), json))
        assertEquals("a\nb\nw\nBoom", outputText(shown, result.error?.text))
        assertEquals(3, result.error?.line)
    }

    @Test fun truncatesLongOutput() {
        val t = truncate("x".repeat(MAX_SAVED_STREAM + 10))
        assertTrue(t.endsWith("[output truncated: saved the first 64 KB of 64 KB]\n"))
        assertEquals("short", truncate("short"))
    }

    @Test fun serverAnswers() {
        val (okChunks, ok) = toRunResult(ServerRunResponse(RunOutcome.Ok, "out", "warn", 0, 10))
        assertEquals(listOf(Chunk(Stream.Stdout, "out"), Chunk(Stream.Stderr, "warn")), okChunks)
        assertNull(ok.error)
        val (errChunks, err) = toRunResult(ServerRunResponse(RunOutcome.Error, "", "", 2, 10, null))
        assertEquals(emptyList<Chunk>(), errChunks)
        assertEquals("Exited with code 2", err.error?.text)
        assertTrue(runsOnServer("rust") && !runsOnDevice("rust") && runsOnDevice("typescript"))
    }
}
