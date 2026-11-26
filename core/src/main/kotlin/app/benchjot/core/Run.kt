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

data class RunResult(
    val outcome: RunOutcome,
    val error: RunError?,
    val ms: Long,
    /** A background server run's id, so its stored result can be cleared once the cell saved it. */
    val serverRunId: String? = null,
)

/** Languages that run on the device, for free (the web runs the same set in the browser). */
val DEVICE_LANGUAGES = setOf("python")
fun runsOnDevice(language: String) = language in DEVICE_LANGUAGES

/** Languages every account runs on the server (the web's SERVER_LANGUAGES: MATLAB cells on GNU Octave). */
val SERVER_LANGUAGES = setOf("python", "c", "cpp", "fortran", "r", "matlab", "julia")
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

fun firstLine(stack: String): String = stack.split("\n").first()

/** The error a device run reports, as the web's runner shapes it (Python is the phone's language). */
fun deviceError(language: String, raw: String): RunError =
    if (language == "python") RunError(cleanPythonTraceback(raw), pythonErrorLine(raw)) else RunError(firstLine(raw), null)

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

// ------------------------------------------------------------------ server runs
// Every account runs on the server (the web's /api/run and /api/runs); a GPU when the code needs
// one, counted against the day's GPU runs by the server.
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
    /** A line about how the run was set up (where it ran, when chosen automatically), shown before its output. */
    val notice: String? = null,
)

@Serializable
data class ServerOutputs(val manifest: Manifest, val files: Map<String, String> = emptyMap())

/** Maps the /api/run answer to the same chunks and result a device run gives (the web's toRunResult). */
fun toRunResult(r: ServerRunResponse): Pair<List<Chunk>, RunResult> {
    val failed = r.outcome != RunOutcome.Ok
    val chunks = (listOf(Chunk(Stream.Stderr, r.notice.orEmpty()), Chunk(Stream.Stdout, r.stdout)) + if (failed) emptyList() else listOf(Chunk(Stream.Stderr, r.stderr)))
        .filter { it.text.isNotEmpty() }
    val errorText = if (r.outcome == RunOutcome.Timeout) r.stderr else r.stderr.ifEmpty { "Exited with code ${r.exitCode}" }
    return chunks to RunResult(r.outcome, if (failed) RunError(errorText, r.line) else null, r.ms)
}

// ------------------------------------------------------------------ background server runs
/** While a background run goes: its phase, the seconds the code has run, its latest output line. */
@Serializable
data class RunLive(val phase: String, val elapsedSec: Double = 0.0, val line: String = "")

/** A background run as GET /api/runs/{id} answers (the web's BackgroundRun). */
@Serializable
data class BackgroundRun(
    val id: String,
    /** running | done | stopped | failed */
    val status: String,
    val startedAt: String,
    val limitSec: Int = 0,
    val notice: String? = null,
    val result: ServerRunResponse? = null,
    val error: String? = null,
    val live: RunLive? = null,
) {
    val startedMs: Long get() = runCatching { java.time.Instant.parse(startedAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
}

/** m:ss or h:mm:ss. */
fun elapsedText(ms: Long): String {
    val s = maxOf(0L, ms / 1000)
    val h = s / 3600
    val mm = ((s % 3600) / 60).toString().padStart(if (h > 0) 2 else 1, '0')
    val ss = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}

/**
 * What the cell says while a background run is going (the web's runStatusText):
 * "Starting server · 0:25" while the machine is prepared; "Running · 0:42 · epoch 3: loss 0.412"
 * with the code's own time and latest line; "Saving results…" once the code is done.
 */
fun runStatusText(run: BackgroundRun, now: Long, liveAt: Long?, reconnecting: Boolean = false): String {
    if (reconnecting) return "Reconnecting… · ${elapsedText(now - run.startedMs)}"
    val live = run.live
    if (live == null || liveAt == null) return "Starting server · ${elapsedText(now - run.startedMs)}"
    if (live.phase == "saving") return "Saving results…"
    val ran = (live.elapsedSec * 1000).toLong() + (now - liveAt)
    val line = if (live.line.length > 70) live.line.take(69) + "…" else live.line
    return "Running · ${elapsedText(ran)}${if (line.isNotEmpty()) " · $line" else ""}"
}

/** The line a finished background run adds about its time: how long the server took to be ready, and the code. */
fun timingNote(totalMs: Long, codeMs: Long): String {
    val wait = maxOf(0L, totalMs - codeMs)
    fun secs(ms: Long) = if (ms < 60_000) "${maxOf(1L, Math.round(ms / 1000.0))} s" else elapsedText(ms)
    return if (wait >= 5_000) "[Benchjot] Server ready in ${secs(wait)}; your code ran for ${secs(codeMs)}.\n" else ""
}

/** How long to wait before asking again: often at first, then less. */
fun pollDelay(elapsedMs: Long): Long = when {
    elapsedMs < 60_000 -> 2_000
    elapsedMs < 10 * 60_000 -> 5_000
    else -> 10_000
}

/** A finished background run as the cell shows it (the web's backgroundResult). */
fun backgroundResult(run: BackgroundRun, now: Long = System.currentTimeMillis()): Pair<List<Chunk>, RunResult> {
    val ms = now - run.startedMs
    if (run.status == "stopped") return emptyList<Chunk>() to RunResult(RunOutcome.Stopped, null, ms, run.id)
    val result = run.result
    if (run.status == "failed" || result == null) {
        return emptyList<Chunk>() to RunResult(RunOutcome.Error, RunError(run.error ?: "The server run ended without sending its result.", null), ms, run.id)
    }
    val notice = run.notice.orEmpty() + timingNote(ms, result.ms)
    val (chunks, r) = toRunResult(result.copy(notice = notice.ifEmpty { null }))
    return chunks to r.copy(serverRunId = run.id)
}

/** Which server runs go to the background (they may outlast the screen, up to an hour): Python, as on the web. */
fun runsInBackground(language: String) = language == "python"

// ------------------------------------------------------------------ last run (cells.last_run)
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
    /** What the code logged with log() (experiment tracking, Logged.kt); null when it logged nothing. */
    val logged: Map<String, kotlinx.serialization.json.JsonPrimitive>? = null,
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
fun toLastRun(chunks: List<Chunk>, result: RunResult, where: String, code: String, now: java.time.Instant = java.time.Instant.now(), outputs: SavedOutputs? = null, logged: Map<String, kotlinx.serialization.json.JsonPrimitive>? = null): LastRun {
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
        logged = cleanLogged(logged),
    )
}

/** What the output view shows for a stored run. */
fun fromLastRun(run: LastRun): Pair<List<Chunk>, RunResult> {
    val chunks = listOf(Chunk(Stream.Stdout, run.stdout), Chunk(Stream.Stderr, run.stderr)).filter { it.text.isNotEmpty() }
    return chunks to RunResult(run.outcome, run.errorLine?.let { RunError(it, run.line) }, run.ms)
}

/** The output exactly as shown: stdout and stderr in order, then the error. */
fun outputText(chunks: List<Chunk>, error: String?): String = chunks.joinToString("") { it.text } + (error ?: "")
