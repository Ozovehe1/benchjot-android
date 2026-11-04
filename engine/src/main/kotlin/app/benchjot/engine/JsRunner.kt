package app.benchjot.engine

import app.benchjot.core.RUN_TIMEOUT_MS
import app.benchjot.core.RunError
import app.benchjot.core.RunEvents
import app.benchjot.core.RunHandle
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.Stream
import app.benchjot.core.deviceError
import app.benchjot.core.syntaxError
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The web's transpile() result (src/lib/run/transpile.ts), as its bundled copy returns it. */
@Serializable
data class Transpiled(val ok: Boolean, val code: String = "", val issue: Issue? = null) {
    @Serializable
    data class Issue(val message: String, val line: Int = 1, val column: Int = 1)
}

/**
 * JavaScript and TypeScript on the device, as the web runs them in a Web Worker:
 * the code goes through the web's own transpile() (sucrase, bundled from the web
 * repo), then runs in a fresh QuickJS runtime per run with console wired to the
 * output and no network. The 30 s limit counts from the start; Stop interrupts
 * the engine even inside a busy loop.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class JsRunner(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val json = Json { ignoreUnknownKeys = true }
    private val transpilerLock = Mutex()
    private var transpiler: QuickJs? = null

    /** The web's transpile(), run in a long-lived QuickJS that holds sucrase. */
    suspend fun transpile(code: String, language: String): Transpiled = transpilerLock.withLock {
        require(language == "javascript" || language == "typescript") { "not a JS language: $language" }
        val js = transpiler ?: QuickJs.create(dispatcher).also {
            it.evaluate<Any?>(resource("transpile.js"), "transpile.js")
            transpiler = it
        }
        // A JSON string is a valid JS string literal, so the code goes in as one.
        val out = js.evaluate<String>("bjTranspile(${Json.encodeToString(String.serializer(), code)}, \"$language\")", "transpile-call.js")
        json.decodeFromString(Transpiled.serializer(), out)
    }

    fun run(scope: CoroutineScope, language: String, code: String, events: RunEvents, timeoutMs: Long = RUN_TIMEOUT_MS): RunHandle {
        val stopped = CompletableDeferred<Unit>()
        val done = scope.async(dispatcher) {
            val t = transpile(code, language)
            if (!t.ok) {
                val issue = t.issue ?: Transpiled.Issue("Could not parse the code")
                return@async syntaxError(issue.message, issue.line, issue.column)
            }
            execute(this, t.code, events, timeoutMs, stopped)
        }
        return RunHandle(done) { stopped.complete(Unit) }
    }

    private suspend fun execute(scope: CoroutineScope, source: String, events: RunEvents, timeoutMs: Long, stopped: CompletableDeferred<Unit>): RunResult {
        val finished = CompletableDeferred<String?>() // null = ok, else the error text
        val js = QuickJs.create(dispatcher)
        var started = 0L
        fun elapsed() = if (started == 0L) 0L else System.currentTimeMillis() - started
        try {
            js.function("__bj_out") { args ->
                val stream = if (args.getOrNull(0) == "stderr") Stream.Stderr else Stream.Stdout
                events.onOutput(stream, args.getOrNull(1)?.toString().orEmpty())
                null
            }
            js.function("__bj_done") { finished.complete(null); null }
            js.function("__bj_fail") { args -> finished.complete(args.getOrNull(0)?.toString() ?: "Error"); null }
            js.asyncFunction("__bj_sleep") { args -> delay((args.getOrNull(0) as? Number)?.toLong() ?: 0L); null }
            js.evaluate<Any?>(resource("prelude.js"), "prelude.js")

            events.onStart()
            events.onStatus("") // as the web's runner on "start"
            started = System.currentTimeMillis()
            // One wrapper line on top (offset 1) so top-level await works, as on the web.
            val program = "(async () => {\n$source\n})().then(() => __bj_done(), (e) => __bj_fail(e && e.stack ? String(e) + \"\\n\" + e.stack : String(e)));"
            val evaluation = scope.launch(dispatcher) {
                try {
                    js.evaluate<Any?>(program, SNIPPET_FILE)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Thrown outside the promise chain (an unhandled rejection elsewhere, an engine error).
                    val q = e as? com.dokar.quickjs.QuickJsException
                    finished.complete(listOfNotNull(e.message ?: e.toString(), q?.stack).joinToString("\n"))
                }
            }
            val result = select<RunResult> {
                finished.onAwait { error ->
                    if (error == null) RunResult(RunOutcome.Ok, null, elapsed())
                    else RunResult(RunOutcome.Error, deviceError("javascript", normalizeStack(error)), elapsed())
                }
                stopped.onAwait { RunResult(RunOutcome.Stopped, null, elapsed()) }
                onTimeout(timeoutMs) { RunResult(RunOutcome.Timeout, null, elapsed()) }
            }
            // Like terminating the web's worker: pending timers and busy code are dropped.
            evaluation.cancel()
            return result
        } finally {
            withContext(NonCancellable) { js.close() }
        }
    }

    fun close() {
        transpiler?.close()
        transpiler = null
    }

    companion object {
        const val SNIPPET_FILE = "snippet.js"

        internal fun resource(name: String): String =
            requireNotNull(JsRunner::class.java.getResourceAsStream("/engine/$name")) { "missing engine resource $name" }
                .bufferedReader().use { it.readText() }

        /** QuickJS frames read "(snippet.js:3)" or "(snippet.js:3:5)"; give every one a column so the web's jsErrorLine() reads it. */
        fun normalizeStack(stack: String): String = Regex("""snippet\.js:(\d+)(?!:\d|\d)""").replace(stack) { "snippet.js:${it.groupValues[1]}:1" }
    }
}
