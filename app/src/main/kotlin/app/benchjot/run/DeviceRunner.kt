package app.benchjot.run

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.benchjot.core.RUN_TIMEOUT_MS
import app.benchjot.core.RunEvents
import app.benchjot.core.RunHandle
import app.benchjot.core.Stream
import app.benchjot.engine.JsRunner
import app.benchjot.engine.RuffProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

/**
 * Runs Python and JavaScript/TypeScript on the phone, for free, as the web does
 * in the browser, with native engines only (no WebView, no download):
 * Python in embedded CPython (PythonService, its own process), JS/TS in QuickJS
 * with the web's own sucrase transpile (JsRunner). Events reach the caller on
 * the main thread, in order. The 30 s limit counts from the moment the code starts.
 */
object DeviceRunner {
    private val js by lazy { JsRunner() }
    private val main = Handler(Looper.getMainLooper())

    /** Starts a run. Call from the main thread. */
    fun run(
        context: Context,
        scope: CoroutineScope,
        language: String,
        code: String,
        onStatus: (String) -> Unit,
        onOutput: (Stream, String) -> Unit,
        timeoutMs: Long = RUN_TIMEOUT_MS,
        /** Python: where its figures, tables and charts go (see PythonRunner). */
        outDir: File? = null,
    ): RunHandle {
        val status = onStatus
        val output = onOutput
        val events = object : RunEvents {
            override fun onStatus(text: String) = onMain { status(text) }
            override fun onOutput(stream: Stream, text: String) = onMain { output(stream, text) }
        }
        return when (language) {
            "python" -> PythonRunner.run(context, scope, code, events, timeoutMs, outDir)
            "javascript", "typescript" -> js.run(scope, language, code, events, timeoutMs).let { h ->
                // The engine prints from its own thread; finish only after its last output reached the main thread.
                RunHandle(scope.async(Dispatchers.Main) { h.done.await().also { drainMain() } }, h.stop)
            }
            else -> throw IllegalArgumentException("$language doesn't run on the device")
        }
    }

    private suspend fun drainMain() = suspendCancellableCoroutine<Unit> { c -> main.post { c.resume(Unit) } }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Ruff, unpacked by the installer from lib/<abi>/libruff.so. */
    fun ruff(context: Context): RuffProcess = RuffProcess(File(context.applicationInfo.nativeLibraryDir, "libruff.so"))
}
