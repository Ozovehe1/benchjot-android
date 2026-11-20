package app.benchjot.run

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.benchjot.core.RUN_TIMEOUT_MS
import app.benchjot.core.RunEvents
import app.benchjot.core.RunHandle
import app.benchjot.core.Stream
import app.benchjot.engine.RuffProcess
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Runs Python on the phone, for free, as the web does in the browser, with a native engine
 * (no WebView, no download): embedded CPython (PythonService, its own process). Events reach
 * the caller on the main thread, in order. Other languages run on the server (ServerRunner).
 */
object DeviceRunner {
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
            else -> throw IllegalArgumentException("$language doesn't run on the device")
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Ruff, unpacked by the installer from lib/<abi>/libruff.so. */
    fun ruff(context: Context): RuffProcess = RuffProcess(File(context.applicationInfo.nativeLibraryDir, "libruff.so"))
}
