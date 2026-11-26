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

    @Test fun backgroundRuns() {
        val started = "2026-11-19T10:00:00Z"
        val t0 = java.time.Instant.parse(started).toEpochMilli()
        val run = BackgroundRun("r1", "running", started, 3600)
        assertEquals("Starting server · 0:25", runStatusText(run, t0 + 25_000, null))
        val live = run.copy(live = RunLive("running", 42.0, "epoch 3: loss 0.412"))
        assertEquals("Running · 0:44 · epoch 3: loss 0.412", runStatusText(live, t0 + 50_000, t0 + 48_000))
        assertEquals("Saving results…", runStatusText(run.copy(live = RunLive("saving")), t0, t0))
        assertEquals("Reconnecting… · 1:05", runStatusText(run, t0 + 65_000, null, reconnecting = true))
        assertEquals("1:02:03", elapsedText(3_723_000))
        assertEquals(2_000L, pollDelay(10_000)); assertEquals(10_000L, pollDelay(20 * 60_000))

        val done = run.copy(status = "done", notice = "[Benchjot] Ran on a T4 GPU.\n", result = ServerRunResponse(outcome = RunOutcome.Ok, stdout = "acc 0.97\n", ms = 4_000))
        val (chunks, r) = backgroundResult(done, t0 + 30_000)
        assertEquals(listOf(Chunk(Stream.Stderr, "[Benchjot] Ran on a T4 GPU.\n[Benchjot] Server ready in 26 s; your code ran for 4 s.\n"), Chunk(Stream.Stdout, "acc 0.97\n")), chunks)
        assertEquals(RunResult(RunOutcome.Ok, null, 4_000, "r1"), r)
        assertEquals(RunOutcome.Stopped, backgroundResult(run.copy(status = "stopped"), t0 + 1_000).second.outcome)
        assertEquals("The server run ended without sending its result.", backgroundResult(run.copy(status = "failed"), t0).second.error?.text)
        assertTrue(runsInBackground("python") && !runsInBackground("r"))
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
        assertTrue(runsOnServer("julia") && !runsOnDevice("julia") && runsOnDevice("python") && !runsOnServer("rust") && !runsOnDevice("javascript"))
    }
}
