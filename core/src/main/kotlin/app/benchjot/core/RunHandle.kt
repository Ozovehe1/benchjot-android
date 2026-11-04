package app.benchjot.core

import kotlinx.coroutines.Deferred

/** A run in progress: wait on `done`, or `stop()` it. The same shape as the web's RunHandle. */
class RunHandle(val done: Deferred<RunResult>, val stop: () -> Unit)

/** What a running engine reports, in the web runner's events: status lines, the moment the code starts, output. */
interface RunEvents {
    fun onStatus(text: String) {}
    fun onStart() {}
    fun onOutput(stream: Stream, text: String)
}

/** Collects what a run printed, capped like the web (200k characters). Thread-safe: engines print from their own threads. */
class OutputBuffer(private val onChunk: (Chunk) -> Unit = {}) {
    private val list = mutableListOf<Chunk>()
    private var size = 0
    val chunks: List<Chunk> get() = synchronized(this) { list.toList() }
    val full: Boolean get() = synchronized(this) { size > MAX_OUTPUT }

    fun add(stream: Stream, text: String) {
        val chunk = synchronized(this) {
            if (size > MAX_OUTPUT) return
            size += text.length
            Chunk(stream, if (size > MAX_OUTPUT) "$text\n[output truncated]\n" else text).also { list += it }
        }
        onChunk(chunk)
    }

    fun text(): String = chunks.joinToString("") { it.text }
}

/** The SyntaxError result the web's runner gives when transpiling fails (no time spent). */
fun syntaxError(message: String, line: Int, column: Int): RunResult =
    RunResult(RunOutcome.Error, RunError("SyntaxError: $message (line $line:$column)", line), 0)
