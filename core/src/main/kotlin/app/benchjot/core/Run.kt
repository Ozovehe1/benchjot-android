package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Run contracts and the pure parts of running code. Mirrors the web's
// src/lib/run/{types,errors,last-run,server-client}.ts and run-output.tsx's summary.

const val RUN_TIMEOUT_MS = 30_000L
const val MAX_OUTPUT = 200_000
const val MAX_SAVED_STREAM = 64 * 1024

enum class Stream { Stdout, Stderr }

data class Chunk(val stream: Stream, val text: String)

@Serializable
enum class RunOutcome {
    @SerialName("ok") Ok,
    @SerialName("error") Error,
    @SerialName("timeout") Timeout,
    @SerialName("stopped") Stopped,
}

data class RunError(val text: String, val line: Int?)

data class RunResult(val outcome: RunOutcome, val error: RunError?, val ms: Long)

/** Languages that run on the device, for free (the web runs the same set in the browser). */
val DEVICE_LANGUAGES = setOf("python", "javascript", "typescript")
fun runsOnDevice(language: String) = language in DEVICE_LANGUAGES

/** Languages Pro runs on the server (the web's SERVER_LANGUAGES). */
val SERVER_LANGUAGES = setOf("python", "c", "cpp", "go", "rust", "java")
fun runsOnServer(language: String) = language in SERVER_LANGUAGES

// ------------------------------------------------------------------ errors
/** Line of the user's code in a Python traceback (the last frame in "<exec>"), or null. */
fun pythonErrorLine(traceback: String): Int? =
    Regex("""File "<exec>", line (\d+)""").findAll(traceback).lastOrNull()?.groupValues?.get(1)?.toInt()

/** Keeps only the "Traceback" header plus the frames from the user's code onwards. */
fun cleanPythonTraceback(traceback: String): String {
    val text = traceback.trimEnd()
    val first = text.indexOf("File \"<exec>\"")
    if (first == -1) return text
    val start = text.lastIndexOf("\n", first)
    return "Traceback (most recent call last):\n${text.substring(start + 1)}"
}

/** Line in the user's JS from a stack of code run as "snippet.js" wrapped with `offset` lines on top. */
fun jsErrorLine(stack: String, offset: Int = 1): Int? {
    val m = Regex("""snippet\.js:(\d+):\d+""").find(stack) ?: return null
    val line = m.groupValues[1].toInt() - offset
    return if (line >= 1) line else null
}

fun firstLine(stack: String): String = stack.split("\n").first()

/** The error a device run reports, as the web's runner shapes it. */
fun deviceError(language: String, raw: String): RunError =
    if (language == "python") RunError(cleanPythonTraceback(raw), pythonErrorLine(raw))
    else RunError(firstLine(raw), jsErrorLine(raw))

// ------------------------------------------------------------------ status line
/** The quiet line under the output: time, error line, or why it stopped. */
fun runSummary(result: RunResult?, running: Boolean, status: String): String {
    if (running) return status.ifEmpty { "Running…" }
    if (result == null) return ""
    val time = String.format(java.util.Locale.ROOT, "%.2f s", result.ms / 1000.0)
    return when (result.outcome) {
        RunOutcome.Ok -> time
        RunOutcome.Error -> result.error?.line?.let { "Error on line $it · $time" } ?: "Error · $time"
        RunOutcome.Timeout -> "Stopped after ${RUN_TIMEOUT_MS / 1000} s (time limit)"
        RunOutcome.Stopped -> "Stopped · $time"
    }
}

// ------------------------------------------------------------------ server runs (Pro)
@Serializable
data class ServerRunResponse(
    val outcome: RunOutcome,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val ms: Long = 0,
    val line: Int? = null,
    /** Python: bj_capture's manifest and files (base64) when the run made figures, tables or charts. */
    val outputs: ServerOutputs? = null,
)

@Serializable
data class ServerOutputs(val manifest: Manifest, val files: Map<String, String> = emptyMap())

/** Maps the /api/run answer to the same chunks and result a device run gives. */
fun toRunResult(r: ServerRunResponse): Pair<List<Chunk>, RunResult> {
    val failed = r.outcome != RunOutcome.Ok
    val chunks = (listOf(Chunk(Stream.Stdout, r.stdout)) + if (failed) emptyList() else listOf(Chunk(Stream.Stderr, r.stderr)))
        .filter { it.text.isNotEmpty() }
    val errorText = if (r.outcome == RunOutcome.Timeout) r.stderr else r.stderr.ifEmpty { "Exited with code ${r.exitCode}" }
    return chunks to RunResult(r.outcome, if (failed) RunError(errorText, r.line) else null, r.ms)
}

// ------------------------------------------------------------------ last run (snippets.last_run)
@Serializable
data class LastRun(
    val outcome: RunOutcome,
    val stdout: String,
    val stderr: String,
    @SerialName("error_line") val errorLine: String?,
    val line: Int?,
    val ms: Long,
    /** "browser" (on device; same column value the web uses for local runs) or "server". */
    val where: String,
    @SerialName("code_hash") val codeHash: String,
    @SerialName("ran_at") val ranAt: String,
    /** Figures, tables and charts (Outputs.kt); null on runs without any and on older runs. */
    val outputs: SavedOutputs? = null,
)

/** Keeps the first `max` characters and says how much was cut. */
fun truncate(text: String, max: Int = MAX_SAVED_STREAM): String {
    if (text.length <= max) return text
    fun kb(n: Int) = "${Math.round(n / 1024.0)} KB"
    return "${text.substring(0, max)}\n[output truncated: saved the first ${kb(max)} of ${kb(text.length)}]\n"
}

/** cyrb53 of the code as hex: the exact function the web uses, so hashes match across apps. */
fun codeHash(code: String): String {
    var h1 = 0xdeadbeef.toInt()
    var h2 = 0x41c6ce57
    for (ch in code) {
        val c = ch.code
        h1 = (h1 xor c) * 2654435761L.toInt()
        h2 = (h2 xor c) * 1597334677
    }
    h1 = ((h1 xor (h1 ushr 16)) * 2246822507L.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909L.toInt())
    h2 = ((h2 xor (h2 ushr 16)) * 2246822507L.toInt()) xor ((h1 xor (h1 ushr 13)) * 3266489909L.toInt())
    val value = 4294967296L * (2097151L and h2.toLong()) + (h1.toLong() and 0xffffffffL)
    return value.toString(16)
}

/** Builds the stored record from a finished run. `where` is "browser" for on-device runs. */
fun toLastRun(chunks: List<Chunk>, result: RunResult, where: String, code: String, now: java.time.Instant = java.time.Instant.now(), outputs: SavedOutputs? = null): LastRun {
    fun join(s: Stream) = chunks.filter { it.stream == s }.joinToString("") { it.text }
    return LastRun(
        outcome = result.outcome,
        stdout = truncate(join(Stream.Stdout)),
        stderr = truncate(join(Stream.Stderr)),
        errorLine = result.error?.let { truncate(it.text) },
        line = result.error?.line,
        ms = maxOf(0, result.ms),
        where = where,
        codeHash = codeHash(code),
        ranAt = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)),
        outputs = outputs?.takeIf { it.items.isNotEmpty() },
    )
}

/** What the output view shows for a stored run. */
fun fromLastRun(run: LastRun): Pair<List<Chunk>, RunResult> {
    val chunks = listOf(Chunk(Stream.Stdout, run.stdout), Chunk(Stream.Stderr, run.stderr)).filter { it.text.isNotEmpty() }
    return chunks to RunResult(run.outcome, run.errorLine?.let { RunError(it, run.line) }, run.ms)
}

/** The output exactly as shown: stdout and stderr in order, then the error. */
fun outputText(chunks: List<Chunk>, error: String?): String = chunks.joinToString("") { it.text } + (error ?: "")
