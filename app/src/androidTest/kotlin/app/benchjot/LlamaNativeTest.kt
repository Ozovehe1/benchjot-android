package app.benchjot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.benchjot.core.GenerateStatus
import app.benchjot.core.Utf8Stream
import app.benchjot.llm.LlamaNative
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// llama.cpp inside the app, on real Android (x86_64 emulator, and armeabi-v7a under its ARM
// translation): the library loads, a model loads, text comes out, and every way a request
// can end reports the right status. Uses a 1.2 MB story model (scripts/fetch-test-model.sh),
// not the real coder, so it runs in seconds.
@RunWith(AndroidJUnit4::class)
class LlamaNativeTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: File
    private var handle = 0L

    @Before fun setUp() {
        model = File(target.cacheDir, "stories260K.gguf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("llm/stories260K.gguf").use { input ->
            model.outputStream().use { input.copyTo(it) }
        }
        handle = LlamaNative.load(model.path, 256, 2)
        assertNotEquals("load failed: ${LlamaNative.lastError()}", 0L, handle)
    }

    @After fun tearDown() { if (handle != 0L) LlamaNative.free(handle) }

    private fun generate(prompt: String, maxTokens: Int, keepGoing: (Int) -> Boolean = { true }): Pair<GenerateStatus, String> {
        val text = Utf8Stream()
        val out = StringBuilder()
        var pieces = 0
        val code = LlamaNative.generate(handle, prompt.toByteArray(), maxTokens) { bytes ->
            out.append(text.push(bytes))
            keepGoing(++pieces)
        }
        out.append(text.finish())
        return GenerateStatus.of(code) to out.toString()
    }

    @Test fun writesText() {
        val (status, text) = generate("Once upon a time", 24)
        assertTrue("status $status", status == GenerateStatus.Limit || status == GenerateStatus.Done)
        assertTrue("no text", text.isNotBlank())
        assertTrue("system info: ${LlamaNative.systemInfo()}", LlamaNative.systemInfo().isNotEmpty())
    }

    @Test fun sameInputSameOutput() {
        assertEquals(generate("Once upon a time", 16).second, generate("Once upon a time", 16).second)
    }

    @Test fun sinkCanStop() {
        val (status, _) = generate("Once upon a time", 200) { it < 3 }
        assertEquals(GenerateStatus.Stopped, status)
    }

    @Test fun stopFromAnotherThread() {
        val stopper = Thread { Thread.sleep(50); LlamaNative.stop(handle) }
        stopper.start()
        val (status, _) = generate("Once upon a time", 200) { Thread.sleep(20); true } // slow enough to be stopped
        stopper.join()
        assertEquals(GenerateStatus.Stopped, status)
    }

    @Test fun promptLongerThanContextIsRefused() {
        val (status, text) = generate("Once upon a time ".repeat(200), 8)
        assertEquals(GenerateStatus.PromptTooLong, status)
        assertEquals("", text)
    }

    @Test fun badFileFailsCleanly() {
        val junk = File(target.cacheDir, "junk.gguf").apply { writeText("not a model") }
        assertEquals(0L, LlamaNative.load(junk.path, 256, 1))
        assertTrue(LlamaNative.lastError().isNotEmpty())
        assertEquals(0L, LlamaNative.load(File(target.cacheDir, "missing.gguf").path, 256, 1))
    }
}
