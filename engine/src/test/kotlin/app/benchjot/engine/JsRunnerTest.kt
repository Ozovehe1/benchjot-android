package app.benchjot.engine

import app.benchjot.core.OutputBuffer
import app.benchjot.core.RunEvents
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The checks the old WebView runner test made against the web's workers, now
// against the native engine: output, top-level await, error lines, no network,
// TypeScript, syntax errors, the time limit, Stop.
class JsRunnerTest {
    companion object {
        private val runner = JsRunner()

        @JvmStatic @AfterClass fun close() = runner.close()
    }

    private class Run(val result: RunResult, val out: OutputBuffer, val started: Boolean) {
        val text get() = out.text()
    }

    private fun run(language: String, code: String, timeoutMs: Long = 30_000, stopAfterMs: Long? = null): Run = runBlocking {
        val out = OutputBuffer()
        var started = false
        val events = object : RunEvents {
            override fun onStart() { started = true }
            override fun onOutput(stream: Stream, text: String) = out.add(stream, text)
        }
        val h = runner.run(this, language, code, events, timeoutMs)
        if (stopAfterMs != null) { delay(stopAfterMs); h.stop() }
        Run(h.done.await(), out, started)
    }

    @Test fun printsAndAwaitsAtTopLevel() {
        val r = run("javascript", "console.log(1 + 1, {a: 1})\nawait new Promise(r => setTimeout(r, 10))\nconsole.log('after')")
        assertEquals(RunOutcome.Ok, r.result.outcome)
        assertEquals("2 {\"a\":1}\nafter\n", r.text)
    }

    @Test fun printsSixTimesSeven() {
        val r = run("javascript", "console.log(6*7)")
        assertEquals("42\n", r.text)
        assertEquals(RunOutcome.Ok, r.result.outcome)
    }

    @Test fun stderrAndShapes() {
        val r = run("javascript", "console.error('bad'); console.log([1, 'a', null], 10n, undefined, 'x')")
        assertEquals(listOf(Stream.Stderr, Stream.Stdout), r.out.chunks.map { it.stream })
        assertEquals("bad\n[1,\"a\",null] \"10n\" undefined x\n", r.text) // JSON.stringify with the bigint replacer, as the web
    }

    @Test fun errorPointsAtTheUsersLine() {
        val r = run("javascript", "const a = 1\nnull.x")
        assertEquals(RunOutcome.Error, r.result.outcome)
        assertEquals(2, r.result.error?.line)
        assertTrue(r.result.error!!.text, r.result.error!!.text.startsWith("TypeError"))
    }

    @Test fun thrownErrorKeepsItsMessage() {
        val r = run("javascript", "console.log('before')\n\nthrow new Error('boom')")
        assertEquals("Error: boom", r.result.error?.text)
        assertEquals(3, r.result.error?.line)
        assertEquals("before\n", r.text)
    }

    @Test fun hasNoNetwork() {
        val r = run("javascript", "fetch('https://example.com')")
        assertEquals(RunOutcome.Error, r.result.outcome)
        assertEquals("Error: fetch is not available when running here", r.result.error?.text)
        val x = run("javascript", "new XMLHttpRequest()")
        assertTrue(x.result.error!!.text, x.result.error!!.text.contains("not available"))
    }

    @Test fun typescriptIsTranspiled() {
        val r = run("typescript", "const n: number = 2\ninterface A { b: string }\nconsole.log(n * 21)")
        assertEquals(RunOutcome.Ok, r.result.outcome)
        assertEquals("42\n", r.text)
    }

    @Test fun typescriptErrorLineSurvivesTranspiling() {
        val r = run("typescript", "type T = { a: number }\nconst t: T = { a: 1 }\n(t as any).f()")
        assertEquals(3, r.result.error?.line)
    }

    @Test fun syntaxErrorLikeTheWeb() {
        val r = run("typescript", "const = ;")
        assertEquals(RunOutcome.Error, r.result.outcome)
        assertEquals(1, r.result.error?.line)
        assertEquals("SyntaxError: Unexpected token (line 1:7)", r.result.error?.text)
        assertEquals(0L, r.result.ms)
        assertTrue(!r.started)
    }

    @Test fun stopKillsABusyLoop() {
        val r = run("javascript", "while (true) {}", stopAfterMs = 500)
        assertEquals(RunOutcome.Stopped, r.result.outcome)
        // and the next run is fine
        assertEquals("fresh\n", run("javascript", "console.log('fresh')").text)
    }

    @Test fun timeLimitStopsARunaway() {
        val r = run("javascript", "console.log('go'); while (true) {}", timeoutMs = 700)
        assertEquals(RunOutcome.Timeout, r.result.outcome)
        assertEquals("go\n", r.text)
    }

    @Test fun pendingTimersDoNotHoldTheRun() {
        val r = run("javascript", "setInterval(() => console.log('tick'), 5)\nconsole.log('end')", timeoutMs = 3_000)
        assertEquals(RunOutcome.Ok, r.result.outcome)
        assertEquals("end\n", r.text)
    }

    @Test fun eachRunIsFresh() {
        run("javascript", "globalThis.leak = 1")
        assertEquals("undefined\n", run("javascript", "console.log(typeof leak)").text)
    }

    @Test fun stackNormalising() {
        assertEquals("at f (snippet.js:3:1)", JsRunner.normalizeStack("at f (snippet.js:3)"))
        assertEquals("at f (snippet.js:3:9)", JsRunner.normalizeStack("at f (snippet.js:3:9)"))
        assertEquals("at f (snippet.js:12:1)", JsRunner.normalizeStack("at f (snippet.js:12)"))
    }
}
