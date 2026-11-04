package app.benchjot.run

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.benchjot.BuildConfig
import app.benchjot.core.Chunk
import app.benchjot.core.RUN_TIMEOUT_MS
import app.benchjot.core.RunError
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.Stream
import app.benchjot.core.deviceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A run in progress: wait on `done`, or `stop()` it. The same shape as the web's RunHandle. */
class RunHandle(val done: Deferred<RunResult>, val stop: () -> Unit)

/**
 * Runs Python and JavaScript/TypeScript on the phone, for free, as the web does in the
 * browser: one headless WebView hosts the web app's own workers (Pyodide for Python,
 * a locked-down worker for JS). See assets/runner.html. The 30 s limit counts from
 * the moment the code starts (Python's first load doesn't count), as on the web.
 */
object DeviceRunner {
    private var web: WebView? = null
    private val ready = CompletableDeferred<Unit>()
    private val listeners = ConcurrentHashMap<Int, Channel<JSONObject>>()
    private var nextId = 1

    private object Bridge {
        @JavascriptInterface
        fun post(message: String) {
            val msg = runCatching { JSONObject(message) }.getOrNull() ?: return
            if (msg.optString("type") == "ready") { ready.complete(Unit); return }
            listeners[msg.optInt("id", -1)]?.trySend(msg)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensure(context: Context): WebView = web ?: WebView(context.applicationContext).also { w ->
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(Bridge, "BJ")
        val html = context.assets.open("runner.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL(BuildConfig.WEB_URL + "/", html, "text/html", "utf-8", null)
        web = w
    }

    private fun js(s: String) = JSONObject.quote(s)

    /** Starts a run. Call from the main thread. */
    fun run(
        context: Context,
        scope: CoroutineScope,
        language: String,
        code: String,
        onStatus: (String) -> Unit,
        onOutput: (Stream, String) -> Unit,
    ): RunHandle {
        val w = ensure(context)
        val id = nextId++
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        listeners[id] = events
        val stopEvent = JSONObject().put("type", "_stop")
        val done = scope.async(Dispatchers.Main) {
            var started = 0L
            fun elapsed() = if (started == 0L) 0L else System.currentTimeMillis() - started
            fun kill() = w.evaluateJavascript("bjKill($id)", null)
            try {
                if (withTimeoutOrNull(30_000) { ready.await() } == null) {
                    return@async RunResult(RunOutcome.Error, RunError("The runner didn't start.", null), 0)
                }
                w.evaluateJavascript("bjRun($id, ${js(language)}, ${js(code)})", null)
                while (true) {
                    val ev = if (started == 0L) events.receive() else {
                        val left = started + RUN_TIMEOUT_MS - System.currentTimeMillis()
                        withTimeoutOrNull(left.coerceAtLeast(1)) { events.receive() }
                            ?: run { kill(); return@async RunResult(RunOutcome.Timeout, null, elapsed()) }
                    }
                    when (ev.optString("type")) {
                        "_stop" -> { kill(); return@async RunResult(RunOutcome.Stopped, null, elapsed()) }
                        "status" -> onStatus(ev.optString("text"))
                        "start" -> { started = System.currentTimeMillis(); onStatus("") }
                        "out" -> onOutput(if (ev.optString("stream") == "stderr") Stream.Stderr else Stream.Stdout, ev.optString("text"))
                        "done" -> return@async RunResult(RunOutcome.Ok, null, elapsed())
                        "error" -> return@async RunResult(RunOutcome.Error, deviceError(language, ev.optString("text")), elapsed())
                        "syntax" -> {
                            val line = ev.optInt("line", 1)
                            val text = "SyntaxError: ${ev.optString("text")} (line $line:${ev.optInt("column", 1)})"
                            return@async RunResult(RunOutcome.Error, RunError(text, line), 0)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                RunResult(RunOutcome.Error, null, 0)
            } finally {
                listeners.remove(id)
            }
        }
        return RunHandle(done) { events.trySend(stopEvent) }
    }
}

/** Collects what a run printed, capped like the web (200k characters). */
class OutputBuffer(private val onChunk: (Chunk) -> Unit) {
    val chunks = mutableListOf<Chunk>()
    private var size = 0
    fun add(stream: Stream, text: String) {
        if (size > app.benchjot.core.MAX_OUTPUT) return
        size += text.length
        val chunk = Chunk(stream, if (size > app.benchjot.core.MAX_OUTPUT) "$text\n[output truncated]\n" else text)
        chunks += chunk
        onChunk(chunk)
    }
}
