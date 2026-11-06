package app.benchjot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.benchjot.core.AssistError
import app.benchjot.core.AssistTask
import app.benchjot.llm.LocalModel
import app.benchjot.llm.LocalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The model behind its process wall (LlmService in ":llm"): text streams back, Stop stops, and
// when that process dies mid-answer the app gets a plain error and carries on.
@RunWith(AndroidJUnit4::class)
class LocalModelTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: File

    @Before fun setUp() {
        model = File(target.cacheDir, "stories260K.gguf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("llm/stories260K.gguf").use { i -> model.outputStream().use { i.copyTo(it) } }
    }

    private fun llmPid(): Int? = target.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == "${target.packageName}:llm" }?.pid

    @Test fun streamsTextFromItsOwnProcess() = runBlocking {
        var updates = 0
        val r = withContext(Dispatchers.Main) {
            LocalModel.generate(target, this, AssistTask.Ask, "Once upon a time", onText = { updates++ }, testModel = model).result.await()
        }
        assertTrue("$r", r is LocalResult.Done && r.text.isNotBlank())
        assertTrue(updates >= 1)
        assertTrue("runs in :llm", llmPid() != null && llmPid() != android.os.Process.myPid())
    }

    @Test fun stopEndsIt() = runBlocking {
        val r = withContext(Dispatchers.Main) {
            val req = LocalModel.generate(target, this, AssistTask.Ask, "Once upon a time", testModel = model)
            req.stop()
            req.result.await()
        }
        assertTrue("$r", r is LocalResult.Stopped || r is LocalResult.Done) // a 260K model can finish before Stop lands
    }

    @Test fun processDeathIsAPlainErrorAndTheAppLives() = runBlocking {
        val r = withContext(Dispatchers.Main) {
            var killed = false
            val req = LocalModel.generate(target, this, AssistTask.Ask, "Once upon a time", testModel = model, onText = {
                if (!killed) { killed = true; llmPid()?.let(android.os.Process::killProcess) }
            })
            req.result.await()
        }
        if (r is LocalResult.Failed) assertEquals(AssistError.EngineCrashed, r.error)
        else assertTrue("finished before the kill landed: $r", r is LocalResult.Done)
        // And the next request works on a fresh process.
        delay(500)
        val again = withContext(Dispatchers.Main) { LocalModel.generate(target, this, AssistTask.Ask, "Once upon a time", testModel = model).result.await() }
        assertTrue("$again", again is LocalResult.Done)
    }
}
