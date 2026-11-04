package app.benchjot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.benchjot.core.OutputBuffer
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.fixableCount
import app.benchjot.run.DeviceRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The native engines on a real Android (the CI emulator): embedded CPython in its
// own process, QuickJS, and the bundled Ruff binary. The JS engine's full suite
// also runs on the JVM (engine/src/test); this checks it works on Android too.
@RunWith(AndroidJUnit4::class)
class DeviceRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Ran(val result: RunResult, val out: String)

    private fun run(language: String, code: String, timeoutMs: Long = 30_000, stopAfterStartMs: Long? = null): Ran = runBlocking {
        val out = OutputBuffer()
        var started = false
        val result = withContext(Dispatchers.Main) {
            val h = DeviceRunner.run(context, this, language, code, onStatus = { if (it.isEmpty()) started = true }, onOutput = out::add, timeoutMs = timeoutMs)
            if (stopAfterStartMs != null) {
                var waited = 0
                while (!started && waited < 120_000) { delay(50); waited += 50 }
                delay(stopAfterStartMs)
                h.stop()
            }
            h.done.await()
        }
        Ran(result, out.text())
    }

    @Test fun python_printsAndFinishes() {
        val r = run("python", "import sys\nprint(6*7)\nprint('warn', file=sys.stderr)")
        assertEquals(RunOutcome.Ok, r.result.outcome)
        assertEquals("42\nwarn\n", r.out)
    }

    @Test fun python_errorPointsAtTheLine() {
        val r = run("python", "x = 1\n1/0")
        assertEquals(RunOutcome.Error, r.result.outcome)
        assertEquals(2, r.result.error?.line)
        val text = r.result.error!!.text
        assertTrue(text, text.startsWith("Traceback (most recent call last):\n  File \"<exec>\", line 2") && text.trimEnd().endsWith("ZeroDivisionError: division by zero"))
    }

    @Test fun python_numpyIsBundled() {
        val r = run("python", "import numpy as np\nprint(int(np.arange(4).sum()))")
        assertEquals(r.result.error?.text, RunOutcome.Ok, r.result.outcome)
        assertEquals("6\n", r.out)
    }

    @Test fun python_freshNamespaceAndNoStdin() {
        run("python", "leak = 1")
        assertEquals("False\n", run("python", "print('leak' in globals())").out)
        assertTrue(run("python", "input()").result.error!!.text.contains("OSError"))
    }

    @Test fun python_stopKillsABusyLoopAndTheNextRunWorks() {
        val r = run("python", "while True: pass", stopAfterStartMs = 500)
        assertEquals(RunOutcome.Stopped, r.result.outcome)
        val again = run("python", "print('fresh')")
        assertEquals("fresh\n", again.out)
    }

    @Test fun python_timeLimit() {
        val r = run("python", "print('go', flush=True)\nwhile True: pass", timeoutMs = 2_000)
        assertEquals(RunOutcome.Timeout, r.result.outcome)
        assertEquals("go\n", r.out)
    }

    @Test fun javascript_andTypescript() {
        assertEquals("42\n", run("javascript", "console.log(6*7)").out)
        assertEquals("42\n", run("typescript", "const n: number = 21\nconsole.log(n * 2)").out)
        assertEquals(2, run("javascript", "const a = 1\nnull.x").result.error?.line)
    }

    @Test fun javascript_stop() {
        assertEquals(RunOutcome.Stopped, run("javascript", "while (true) {}", stopAfterStartMs = 300).result.outcome)
    }

    @Test fun ruff_runsOnAndroid() = runBlocking {
        val ruff = DeviceRunner.ruff(context)
        assertTrue("libruff.so is not in ${context.applicationInfo.nativeLibraryDir}", ruff.available)
        assertEquals(2, fixableCount(ruff.check("import os\nimport sys\nprint(1)\n")))
        assertEquals("print(1)\n", ruff.fixAll("import os\nimport sys\nprint(1)\n"))
    }
}
