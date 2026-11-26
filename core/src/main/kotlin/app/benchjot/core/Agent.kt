package app.benchjot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

// The agent (the web's lib/ai/agent.ts): the one assistant in a note. Everything sent in the Assistant
// (typed or spoken) comes here. It answers questions, and does work with tools, one step at a time:
// each step is one call to the web's /api/agent (the user's own connected AI), which answers with the
// tool calls to make. The app makes them on the note (add, change, run or delete its own cells; write
// or change the note's text), reads back what each gave, and sends the next step, until the agent is
// done, the user stops it, or [AgentLimits.STEPS] run out. It never fakes a result: code that would is
// refused ([fakeSmell]), and "done" is checked ([unfinishedCells]). Undo puts everything back. It never
// touches cell notes or the outcome. The parts the web also has are checked against its output (agent.json).

/** The web's AGENT_LIMITS. */
object AgentLimits {
    const val STEPS = 8
    const val CALLS_PER_STEP = 6
    const val MAX_CODE_CHARS = 30_000
    const val MAX_RESULT_CHARS = 3_000
    const val MAX_HISTORY_CODE_CHARS = 1_500
    const val MAX_MESSAGE_CHARS = 4_000
}

/** Dictation's limits (the web's VOICE_LIMITS): listening stops by itself at [MAX_SECONDS]. */
object VoiceLimits {
    const val MAX_SECONDS = 120
}

/**
 * One tool call, as the web sends and reads it: {"tool": "add_cell", "language", "code"},
 * {"tool": "edit_cell", "cell", "code"} or {"tool": "run_cell", "cell"} (absent fields left out).
 * Cells are numbered from 1 in the note's order.
 */
@Serializable
data class AgentCall(
    val tool: String,
    val language: String? = null,
    val code: String? = null,
    val cell: Int? = null,
    /** write_note: the note's line it goes under ('' for the end), and the text. */
    val after: String? = null,
    val text: String? = null,
    /** replace_note: the text found once in the note's text, and what replaces it ('' removes it). */
    val find: String? = null,
    val replace: String? = null,
    /** run_cell: "device" (the phone) or "server"; absent runs it on the phone, moving to the server by itself when it must. */
    val where: String? = null,
    /** find_notes: words to search the user's other notes for ('' for the linked, parent, children and newest). */
    val query: String? = null,
    /** read_note / ask_note: the note's reference from find_notes ("N2"), and ask_note's question. */
    val note: String? = null,
    val question: String? = null,
    /** save_data: the file's https address, and the name to keep it under ('' for the address's own). */
    val url: String? = null,
    val name: String? = null,
) {
    companion object {
        fun add(language: String, code: String) = AgentCall("add_cell", language = language, code = code)
        fun edit(cell: Int, code: String) = AgentCall("edit_cell", code = code, cell = cell)
        fun run(cell: Int) = AgentCall("run_cell", cell = cell)
        fun delete(cell: Int) = AgentCall("delete_cell", cell = cell)
        fun write(after: String, text: String) = AgentCall("write_note", after = after, text = text)
        fun replaceText(find: String, replace: String) = AgentCall("replace_note", find = find, replace = replace)
    }
}

/** A step already taken, as sent back: what the agent said, its calls, and what each gave. */
@Serializable
data class AgentStep(val message: String, val calls: List<AgentCall>, val results: List<String>, val plan: List<String>? = null)

/** One step's request to /api/agent. */
@Serializable
data class AgentRequest(
    val request: String,
    val language: String,
    val title: String,
    val note: String,
    val steps: List<AgentStep>,
    val history: AssistantHistory = AssistantHistory(),
    /** The live conversation (the web's LIVE): Claude's own turns, sent back unchanged, with what its last calls gave. */
    val live: Boolean? = null,
    val transcript: List<JsonObject>? = null,
    val results: List<LiveResult>? = null,
    val notes: List<String>? = null,
)

/** One call's result in the live conversation, by Claude's id for the call. */
@Serializable
data class LiveResult(val id: String, val text: String)

/** What a step's calls gave, waiting to go with the next live step (the web's LivePending). */
@Serializable
data class LivePending(val results: List<LiveResult> = emptyList(), val notes: List<String> = emptyList())

/**
 * The live conversation's bounds (the web's LIVE): a call's result as sent, the whole conversation as
 * sent (characters of JSON; past it the request goes on the older way, rebuilt from the steps), and
 * the most kept on the phone to pick a request up again.
 */
object Live {
    const val MAX_RESULT_CHARS = 8_000
    const val MAX_CHARS = 1_500_000
    const val MAX_KEPT_CHARS = 1_000_000
}

internal fun jsonSize(messages: List<JsonObject>) = JsonArray(messages).toString().length

/** The results of a step's calls as one text (the web's resultsText). */
internal fun resultsText(results: List<String>) = if (results.isEmpty()) "You made no calls." else "Results of your calls:\n" + results.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")

/** A step's results for the live conversation (the web's pendingOf): each call's by its id when every call has one; the rest as notes. */
fun pendingOf(ids: List<String>?, calls: Int, results: List<String>): LivePending {
    fun cut(t: String) = headCut(t, Live.MAX_RESULT_CHARS)
    if (calls > 0 && ids != null && ids.size == calls) return LivePending(ids.mapIndexed { i, id -> LiveResult(id, cut(results.getOrNull(i) ?: "No result.")) }, results.drop(calls).map(::cut))
    if (results.isEmpty()) return LivePending()
    return LivePending(notes = listOf(cut(if (calls > 0) resultsText(results) else results.joinToString("\n\n"))))
}

/** One step's answer from /api/agent: what the agent says, the calls to make, and whether it is done. */
@Serializable
data class AgentReply(
    val message: String = "",
    val calls: List<AgentCall> = emptyList(),
    val done: Boolean = true,
    /** The plan's steps (a checklist ticked off as it goes), when it sets or changes one. */
    val plan: List<String>? = null,
    /** How many plan steps are finished. */
    val planDone: Int? = null,
    /** The reply was cut off at the length limit before it was whole: its calls are lost, and it is told to write less. */
    val cut: Boolean = false,
    /** It called finish: it says the request is done (the loop still checks its work before ending). */
    val finished: Boolean = false,
    /** Calls that couldn't be made as written: told back to it with the step's results, not thrown. */
    val problems: List<String> = emptyList(),
    /** Claude's id for each call in [calls], so its result goes back to it. */
    val ids: List<String>? = null,
    /** Live conversation: the turns to add to it (what was asked this step and Claude's reply). */
    val turns: List<JsonObject>? = null,
)

/** Said to the agent when its reply was cut off at the length limit (the web's CUT_RESULT). */
const val CUT_RESULT =
    "Your last reply was cut off at the length limit before it was complete, so none of its calls were made. Keep each reply short: code under about 120 lines (split bigger work across cells or steps), and plain strings in the code."

/** A failure from the web that says what it was: its HTTP status, error code and wait (the app's DataError is one). */
interface StatusFailure {
    val status: Int?
    val code: String?
    val retryAfter: Double? get() = null
}

/** A step's request that failed for a passing reason (slow, overloaded, dropped): waited out and asked again by itself (the web's passingFailure). */
fun passingFailure(e: Throwable): Boolean = when {
    e is CancellationException -> false
    // The server's own words (the AI isn't set up, its key was refused): final, not a hiccup.
    e is StatusFailure && e.code == "notice" -> false
    e is StatusFailure -> e.code == "timeout" || e.code == "server" || (e.status ?: 0) >= 500 || e.status == 408
    else -> e is java.io.IOException // the connection dropped, or the request timed out
}

/**
 * How a request rides out hiccups by itself, so the user never has to step in (the web's AGENT_PATIENCE):
 * a slow, busy or dropped AI is waited out, up to [MAX_TRIES] tries, about four minutes (Stop ends it sooner); an AI limit that
 * clears within a minute or two is waited out [LIMIT_TRIES] times; and work that needs more than
 * [AgentLimits.STEPS] steps goes on (the oldest drop out of what's sent back) up to [MAX_STEPS].
 */
object AgentPatience {
    val WAITS_S = listOf(5, 15, 30, 60)
    const val MAX_TRIES = 6
    const val LIMIT_TRIES = 2
    const val MAX_LIMIT_WAIT_S = 120
    const val MAX_STEPS = 24
}

/** Seconds to wait before asking again, or null when waiting won't help (a refusal, a daily limit). */
fun retryWait(e: Throwable, attempt: Int, limitTries: Int): Int? {
    // A hiccup is waited out, but not forever: after MAX_TRIES (about four minutes) the error shows.
    if (passingFailure(e)) return if (attempt + 1 < AgentPatience.MAX_TRIES) AgentPatience.WAITS_S[minOf(attempt, AgentPatience.WAITS_S.size - 1)] else null
    if (e is StatusFailure && e.status == 429 && limitTries < AgentPatience.LIMIT_TRIES) {
        // A per-minute limit clears by itself; a daily one (a long retryAfter) doesn't.
        val after = e.retryAfter?.toInt() ?: 60
        return if (after <= AgentPatience.MAX_LIMIT_WAIT_S) maxOf(after, 5) else null
    }
    return null
}

/** Working steps without a tick before the agent is reminded of its plan. */
const val PLAN_NUDGE_STEPS = 2

/** Told to the agent when its checklist hasn't moved for a while: the user watches it to see progress. */
fun planNudge(plan: AgentPlan): String =
    "Your plan shows ${plan.done} of ${plan.steps.size} steps done (next: \"${plan.steps.getOrNull(plan.done).orEmpty()}\"). The user watches this checklist: when a step is finished, call set_plan with the same steps and done = how many are finished, in the same reply as your other calls."

/** Told to the agent after its reply took too long to arrive: a shorter reply gets through. */
const val SLOW_RESULT = "Your last reply took too long to arrive and was lost, so none of its calls were made. Reply again, shorter: one small step, code under about 120 lines."

/** Told to the agent on its last step: it finishes with what it has instead of being cut off. */
const val LAST_STEP_RESULT = "This is your last step: call finish now with what you did, what the results show (with the numbers), and plainly what is left undone, if anything."

/** The clock the loop waits on (tests make it instant). */
object AgentClock {
    var sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
}

/** The agent's plan as a checklist: its steps, and how many are finished. */
data class AgentPlan(val steps: List<String>, val done: Int)

/** The characters the earlier steps add to the request; the note gets the rest of the reading budget. */
fun stepsSize(steps: List<AgentStep>): Int =
    steps.sumOf { s -> s.message.length + s.calls.sumOf { callChars(it) + 40 } + s.results.sumOf { it.length } }

private fun callChars(c: AgentCall): Int = when {
    c.code != null -> c.code.length
    c.text != null -> c.text.length + (c.after?.length ?: 0)
    c.find != null -> c.find.length + (c.replace?.length ?: 0)
    else -> 0
}

/** A call as it goes back: what it wrote cut short (the whole of it is in the note). */
private fun sentCall(c: AgentCall): AgentCall {
    val cut = { t: String -> headCut(t, AgentLimits.MAX_HISTORY_CODE_CHARS) }
    return when (c.tool) {
        "write_note" -> c.copy(after = headCut(c.after.orEmpty(), 400), text = cut(c.text.orEmpty()))
        "replace_note" -> c.copy(find = cut(c.find.orEmpty()), replace = cut(c.replace.orEmpty()))
        else -> c.code?.let { c.copy(code = cut(it)) } ?: c
    }
}

/** A step as it goes back: code cut short (the whole code is in the note), results cut to their budget. */
fun sentStep(message: String, calls: List<AgentCall>, results: List<String>, plan: List<String>? = null): AgentStep = AgentStep(
    headCut(message, AgentLimits.MAX_MESSAGE_CHARS),
    calls.take(AgentLimits.CALLS_PER_STEP).map(::sentCall),
    results.take(AgentLimits.CALLS_PER_STEP + 4).map { headCut(it, AgentLimits.MAX_RESULT_CHARS) },
    plan?.take(6)?.map { headCut(it, 200) },
)

/** The cells as the agent reads them: the Assistant's reading, each labelled with its number, and the ones it wrote itself said so (the web's agentCells). */
fun agentCells(cells: List<Cell>, mine: Set<String> = emptySet()): List<AssistantCell> =
    assistantCells(cells).mapIndexed { i, c -> c.copy(label = "[Cell ${i + 1}${if (cells[i].id in mine) " · you wrote this cell" else ""}]") }

/** What Undo needs, as kept with the turn (the web's keptChanges). */
fun keptChanges(undo: AgentUndo) = KeptChanges(
    added = undo.added.toList(),
    changed = undo.changed.map { KeptChanged(it.first, it.second) },
    note = undo.note.map { KeptNoteEdit(it.wrote, it.replaced) },
)

/** Undo from what was kept with the turn (the web's undoFromKept): text changes are taken back by what was written. */
fun undoFromKept(c: KeptChanges) = AgentUndo().apply {
    added += c.added
    changed += c.changed.map { it.id to it.code }
    note += c.note.map { NoteEdit("\u0000", "\u0000", it.wrote, it.replaced) }
}

/** A thing done, as kept with the turn, and back. */
fun AgentAction.kept() = KeptAction(text, tone, cellId, detail?.let { d ->
    when (d.kind) {
        "code" -> KeptDetail("code", language = d.language, code = d.text)
        "diff" -> KeptDetail("diff", before = d.before, after = d.text)
        "proposal" -> KeptDetail("proposal", text = d.text, noteId = d.noteId, title = d.title, after = d.after)
        else -> KeptDetail(d.kind, text = d.text)
    }
})
fun KeptAction.shown() = AgentAction(text, tone, cellId, detail?.let { d ->
    when (d.kind) {
        "code" -> ActionDetail("code", d.code.orEmpty(), language = d.language.orEmpty())
        "diff" -> ActionDetail("diff", d.after.orEmpty(), before = d.before.orEmpty())
        "proposal" -> ActionDetail("proposal", d.text.orEmpty(), noteId = d.noteId.orEmpty(), title = d.title.orEmpty(), after = d.after.orEmpty())
        else -> ActionDetail(d.kind, d.text.orEmpty())
    }
})

/** What a finished request keeps with its turn (the web's keptAnswer): the summary the history reads, its answer, how it went, and what Undo needs. */
fun keptReply(final: AgentState, undo: AgentUndo, secs: Long, language: String): AssistantReply {
    val said = final.steps.map { it.message.trim() }.filter { it.isNotEmpty() }
    val answer = when (final) { is AgentState.Done -> said.lastOrNull().orEmpty(); is AgentState.Failed -> final.message; else -> "Stopped." }
    val narration = (if (final is AgentState.Done) said.dropLast(1) else said).takeLast(12).map { headCut(it, 4_000) }
    val actions = final.steps.flatMap { it.actions }.takeLast(60).map { it.kept().let { k -> k.copy(text = headCut(k.text, 300)) } }.toMutableList()
    val plan = final.plan?.let { KeptPlan(it.steps, it.done) }
    val changes = keptChanges(undo)
    var log = KeptLog(secs.coerceIn(0, 86_400).toInt(), narration, actions, plan)
    // A turn is kept within 200 KB: the oldest steps' details go first when it would be more.
    var i = 0
    while (keptSize(log, changes) > 150_000 && i < actions.size) { actions[i] = actions[i].copy(detail = null); i++; log = log.copy(actions = actions.toList()) }
    return AssistantReply("prose", text = agentSummary(final.steps), language = language, answer = answer, log = log, changes = changes.takeIf { !it.isEmpty })
}

private fun keptSize(log: KeptLog, changes: KeptChanges): Int =
    kotlinx.serialization.json.Json.encodeToString(KeptLog.serializer(), log).length + kotlinx.serialization.json.Json.encodeToString(KeptChanges.serializer(), changes).length

/** Seconds as the web's `(ms / 1000).toFixed(1)` writes them (the double's exact value, ties up). */
private fun seconds(ms: Long): String = BigDecimal(ms / 1000.0).setScale(1, RoundingMode.HALF_UP).toPlainString()

/** A finished run, as the agent reads it back: how it ended and in how long, then its output, what it made and its error. */
fun runReport(n: Int, s: Cell): String {
    val run = s.lastRun ?: return "Cell $n ran, but its result wasn't saved, so there is nothing to read."
    val secs = if (run.ms >= 1000) "${seconds(run.ms)} s" else "${run.ms} ms"
    val head = when (run.outcome) {
        RunOutcome.Ok -> "Cell $n ran without error in $secs."
        RunOutcome.Error -> "Cell $n failed after $secs."
        RunOutcome.Timeout -> "Cell $n ran out of time after $secs."
        RunOutcome.Stopped -> "Cell $n was stopped after $secs."
    }
    val cell = assistantCells(listOf(s)).first()
    val room = AgentLimits.MAX_RESULT_CHARS - head.length - 40
    val parts = buildList {
        add(head)
        if (cell.stdout.isNotBlank()) add(tailBlock("Output", cell.stdout, room / 2))
        if (cell.produced.isNotEmpty()) add("Shown in the note: ${cell.produced.joinToString("; ")}.")
        if (cell.runNotes.isNotEmpty()) add("Notes from the run: ${cell.runNotes.joinToString(" ")}")
        cell.tables.firstOrNull()?.let(::add)
        if (cell.error.isNotBlank()) add(tailBlock("Error", cell.error, room / 2))
        if (run.outcome == RunOutcome.Ok && cell.stdout.isBlank() && cell.produced.isEmpty()) add("It printed nothing.")
    }
    return headCut(parts.joinToString("\n"), AgentLimits.MAX_RESULT_CHARS)
}

/** What a thing done looks like opened: "code" (language, text), "diff" (before, text), "output" or "text" (text). */
data class ActionDetail(
    val kind: String,
    val text: String,
    val before: String = "",
    val language: String = "",
    /** "proposal": the other note it is for, its title, and the line of it the text goes under ('' for the end). */
    val noteId: String = "",
    val title: String = "",
    val after: String = "",
)

/** One thing done in the note: a line, whether it went well ("ok", "fail" or "plain"), the cell it's about, and what it looks like opened. */
data class AgentAction(val text: String, val tone: String = "plain", val cellId: String? = null, val detail: ActionDetail? = null)

private fun clip(t: String) = headCut(t, 6_000)

/** What one step did, as the Assistant shows it. */
data class AgentDone(val message: String, val actions: List<AgentAction>)

sealed interface AgentState {
    val steps: List<AgentDone>
    val plan: AgentPlan?
    /** [step]: the step it is on, from 1. */
    data class Working(override val steps: List<AgentDone>, val doing: String, val step: Int = 1, override val plan: AgentPlan? = null) : AgentState
    data class Done(override val steps: List<AgentDone>, override val plan: AgentPlan? = null) : AgentState
    data class Stopped(override val steps: List<AgentDone>, override val plan: AgentPlan? = null) : AgentState
    data class Failed(override val steps: List<AgentDone>, val message: String, override val plan: AgentPlan? = null) : AgentState
}

/** What the agent read in a step's failure: the steps shown so far and its plan go with it. */
class AgentFailure(val steps: List<AgentDone>, override val cause: Throwable, val plan: AgentPlan? = null) : Exception(cause.message, cause)

/** A change to the note's text: before and after, and what was written or replaced (to take it out if the user edited around it). */
data class NoteEdit(val before: String, val after: String, val wrote: String, val replaced: String? = null)

/** What Undo puts back: the cells the agent added, the code of each cell it changed as it was before, and its changes to the note's text. */
class AgentUndo {
    val added = mutableListOf<String>()
    val changed = mutableListOf<Pair<String, String>>()
    val note = mutableListOf<NoteEdit>()
    val isEmpty get() = added.isEmpty() && changed.isEmpty() && note.isEmpty()
}

/** The note as the agent changes it; the app owns the cells. */
interface AgentNote {
    /** The note as saved now (pending edits saved first): its title, text and cells in order. */
    suspend fun read(): Triple<String, String, List<Cell>>
    /** The note's outcome ("success", "failed" or null), for the context graph. */
    suspend fun outcome(): String? = null
    /** Adds a code cell at the end; its id once the screen shows it. */
    suspend fun add(language: String, code: String): String
    /** Replaces a cell's code and saves it. */
    suspend fun setCode(id: String, code: String)
    /** Runs a cell on the phone (moving to the server by itself when it must), or on the server when [where] is "server"; returns once its run is saved. */
    suspend fun run(id: String, where: String? = null)
    /** Deletes a cell (cells the agent added: delete_cell, and Undo). */
    suspend fun remove(id: String)
    /** Replaces the note's text (write_note, replace_note, and Undo); saved like typing. */
    suspend fun setBody(text: String)
    /** Whether [see] does anything (a screen with the seer). */
    val sees: Boolean get() = false
    /**
     * The seer: has a vision model look at the pictures of the cell's last run it hasn't described yet
     * and keeps what it says with that run. Best effort: returns either way.
     */
    suspend fun see(id: String) {}
    /** The user's other notes (find_notes, read_note, ask_note); null where they can't be reached. */
    val others: OtherNotes? get() = null
    /** The user's Data library (list_data, save_data, find_datasets); null where it can't be reached. */
    val data: AgentData? get() = null
}

/** The user's Data library, as the agent uses it (the web's DataLibrary in lib/ai/agent.ts). */
interface AgentData {
    /** The library's files: name and size in bytes. */
    suspend fun list(): List<Pair<String, Long>>
    /** Downloads a public https file into the library (the web's /api/data/fetch); its name and size. Throws with words for the user. */
    suspend fun save(url: String, name: String?): Pair<String, Long>
    /** Datasets across public platforms for words; or, for a ref ("zenodo:123"), that dataset's files. */
    suspend fun find(query: String): DatasetFind
}

/** What find_datasets read: datasets (each with a ref, "source:id"), or one dataset's files with the address to save each. */
sealed interface DatasetFind {
    data class Datasets(val results: List<FoundRef>, val missed: List<String>) : DatasetFind
    data class Files(val ref: String, val files: List<FoundData>) : DatasetFind
}
data class FoundRef(val ref: String, val title: String, val source: String, val detail: String)
data class FoundData(val name: String, val url: String, val size: Long)

private fun sizeText(b: Long) = when {
    b >= 1_048_576 -> "%.1f MB".format(java.util.Locale.US, b / 1_048_576.0)
    b >= 1024 -> "${Math.round(b / 1024.0)} KB"
    else -> "$b B"
}

/** A web page or a README rather than data (save_data refuses it): by its name, or a GitHub page that isn't a raw file (the web's notData). */
fun notData(url: String, name: String = ""): Boolean {
    val file = name.ifEmpty { url.split('?', '#').first().substringAfterLast('/') }.lowercase()
    if (Regex("""\.(html?|md|markdown|rst|php|aspx?)$""").containsMatchIn(file) || Regex("""^readme(\.|$)""").containsMatchIn(file)) return true
    val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    return u.host == "github.com" && !Regex("""/(raw|releases/download)/""").containsMatchIn(u.path.orEmpty()) && !Regex("""(^|&)raw=(true|1)""").containsMatchIn(u.query.orEmpty())
}

/** list_data, save_data and find_datasets (the web's dataCall): what goes back to the agent, and the line the user reads. */
private suspend fun dataCall(call: AgentCall, data: AgentData?): Pair<String, AgentAction> {
    if (data == null) return "The Data library can't be reached here." to AgentAction("Data library not reachable", "fail")
    when (call.tool) {
        "list_data" -> {
            val files = data.list()
            val lines = files.map { (n, sz) -> "- $n (${sizeText(sz)})" }
            return (if (files.isEmpty()) "The Data library is empty." else "The Data library (a cell that names a file in quotes, like pd.read_csv(\"${files[0].first}\"), finds it in its working folder):\n${lines.joinToString("\n")}") to
                AgentAction("Looked in Data · ${files.size} file${if (files.size == 1) "" else "s"}", "plain", detail = if (lines.isEmpty()) null else ActionDetail("text", lines.joinToString("\n")))
        }
        "find_datasets" -> {
            val q = call.query.orEmpty().trim()
            return try {
                when (val found = data.find(q)) {
                    is DatasetFind.Datasets -> {
                        val missed = if (found.missed.isNotEmpty()) " (${found.missed.joinToString(", ")} didn't answer this time)" else ""
                        if (found.results.isEmpty()) "No datasets match \"$q\" by title on Hugging Face, Zenodo, OpenML, Harvard Dataverse or figshare$missed. Try fewer or other words, or web_search." to AgentAction("No datasets for \"$q\"", "plain")
                        else {
                            val lines = found.results.take(15).map { r -> "- ${r.ref} — ${r.title} (${r.source}${if (r.detail.isNotEmpty()) "; ${r.detail}" else ""})" }
                            "Datasets for \"$q\"$missed (call find_datasets again with a ref, such as ${found.results[0].ref}, to see its files):\n${lines.joinToString("\n")}" to
                                AgentAction("Found ${found.results.size} dataset${if (found.results.size == 1) "" else "s"}", "plain", detail = ActionDetail("text", lines.joinToString("\n")))
                        }
                    }
                    is DatasetFind.Files -> {
                        if (found.files.isEmpty()) "${found.ref} has no data files (tables, arrays or data archives). Pick another dataset, or load it in a server cell." to AgentAction("No data files in ${found.ref}", "plain")
                        else {
                            val lines = found.files.take(16).map { f -> "- ${f.name} — ${if (f.size > 0) sizeText(f.size) else "size not given"}${if (f.size > DATA_FILE_MAX) " (over 50 MB — load it in a server cell instead)" else " — ${f.url}"}" }
                            "Files in ${found.ref} (pass a file's address to save_data to add it to the library):\n${lines.joinToString("\n")}" to
                                AgentAction("${found.files.size} file${if (found.files.size == 1) "" else "s"} in ${found.ref}", "plain", detail = ActionDetail("text", lines.joinToString("\n")))
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Couldn't search for datasets: ${e.message ?: "the search failed."}" to AgentAction("Dataset search failed", "fail")
            }
        }
        else -> { // save_data
            if (notData(call.url.orEmpty(), call.name.orEmpty())) return "That's a page, not a data file: the library is for datasets. Read pages and READMEs with web_fetch, and save the data files they point to." to AgentAction("Not a data file", "plain")
            return try {
                val (n, sz) = data.save(call.url.orEmpty().trim(), call.name?.trim()?.ifEmpty { null })
                "Saved $n (${sizeText(sz)}) in the Data library. Open it by that name in any cell, e.g. \"$n\"." to AgentAction("Saved $n to Data · ${sizeText(sz)}", "plain")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = e.message ?: "It couldn't be saved."
                "Couldn't save it: $why If the file needs a sign-in or a script to reach, download it in a server cell instead (see the web rules)." to AgentAction("Couldn't save the file to Data", "fail", detail = ActionDetail("text", why))
            }
        }
    }
}

/** The user's other notes, read only (the web's OtherNotes). */
interface OtherNotes {
    /** Notes matching the words; with none, the linked ones, the parent and children, then the most recent. Never this note. */
    suspend fun find(query: String, from: SearchFrom? = null): List<OtherNote>
    /** Another note as saved (title, text, cells, outcome), or null when it's gone. */
    suspend fun open(id: String): SavedNote?
}

/** A note as another note's agent reads it. */
data class SavedNote(val title: String, val body: String, val cells: List<Cell>, val outcome: String?, val parentId: String? = null)

/** A note find_notes searches around: its id, parent and text (for its links). */
data class SearchFrom(val id: String, val parentId: String?, val body: String)

/** A change proposed to another note: Markdown added to its text under one of its lines, applied only on the user's tap. */
data class NoteProposal(val noteId: String, val title: String, val after: String, val text: String)

// ------------------------------------------------------------------ other notes (the web's agent.ts)

/** Where a request is between steps (the web's AgentCheckpoint): enough to go on from there when the note was left mid-request. */
data class AgentCheckpoint(val step: Int, val sent: List<AgentStep>, val shown: List<AgentDone>, val checks: Int, val nudges: Int, val plan: AgentPlan?, val live: KeptLive? = null)

/** The live conversation as kept to go on with: Claude's turns, the request's language, and what the last calls gave. */
@Serializable
data class KeptLive(val transcript: List<JsonObject>, val language: String, val pending: LivePending)

/** A checkpoint as kept on the phone (JSON). */
@Serializable
data class KeptCheckpoint(val step: Int, val sent: List<AgentStep>, val shown: List<KeptDone>, val checks: Int, val nudges: Int, val plan: KeptPlan? = null, val live: KeptLive? = null)

@Serializable
data class KeptDone(val message: String, val actions: List<KeptAction>)

fun AgentCheckpoint.kept() = KeptCheckpoint(step, sent, shown.map { KeptDone(it.message, it.actions.map { a -> a.kept() }) }, checks, nudges, plan?.let { KeptPlan(it.steps, it.done) }, live)
fun KeptCheckpoint.shown() = AgentCheckpoint(step, sent, shown.map { AgentDone(it.message, it.actions.map { a -> a.shown() }) }, checks, nudges, plan?.let { AgentPlan(it.steps, it.done) }, live)

/** A request kept on the phone to be picked up again (the web's KeptRun). */
@Serializable
data class KeptRun(val request: String, val voice: Boolean, val language: String, val undo: KeptChanges, val history: AssistantHistory, val mine: List<String>, val checkpoint: KeptCheckpoint, val savedAt: Long)

/** A request left longer than this isn't picked up again. */
const val RESUME_HOURS = 6

/** The AI requests asking other notes may use in one request, shared by every note asked (however deep). */
class AskBudget(var left: Int)

/**
 * How the loop reaches other notes in one request (the web's Reach): their references, the AI it asks
 * with, the budget asking shares, how deep in a chain of asks this note is, and the notes in that chain.
 */
class Reach(
    val others: OtherNotes,
    val ask: suspend (AgentRequest) -> AgentReply,
    val budget: AskBudget = AskBudget(NotesReach.BUDGET),
    val depth: Int = 0,
    val path: List<String> = emptyList(),
    val self: SearchFrom? = null,
) {
    val refs = LinkedHashMap<String, OtherNote>()
    var from = ""
}

/** FIND: notes per find_notes. ASK_STEPS: steps one asked note may take. BUDGET: AI requests all asking may use. DEPTH: how far a question may be passed on. */
object NotesReach {
    const val FIND = 8
    const val ASK_STEPS = 4
    const val BUDGET = 12
    const val DEPTH = 2
}

const val READ_ONLY_NOTE = "This note is read-only here: you are answering a question from another note. Answer from what it holds, and call finish with your answer."

/** The request a note's own agent gets when another note asks it something. */
fun askNoteRequest(question: String, from: String): String =
    "The assistant of another note${if (from.isNotBlank()) " (\"${headCut(from.trim(), 120)}\")" else ""} asks about this note: $question\nAnswer from this note only: what it found, with its numbers, and how sure the note is (its outcome, whether its cells ran with their current code). Don't change the note or run its cells; call finish with your answer."

private val OUTCOME_WORDS = mapOf("success" to "Successful", "failed" to "Failed")

/** Another note as its own agent works on it when asked: everything readable, nothing writable. */
private class ReadOnlyNote(private val saved: SavedNote, private val further: OtherNotes?) : AgentNote {
    override val others get() = further
    override suspend fun read() = Triple(saved.title, saved.body, saved.cells)
    override suspend fun outcome() = saved.outcome
    override suspend fun add(language: String, code: String): String = throw IllegalStateException(READ_ONLY_NOTE)
    override suspend fun setCode(id: String, code: String) = throw IllegalStateException(READ_ONLY_NOTE)
    override suspend fun run(id: String, where: String?) = throw IllegalStateException(READ_ONLY_NOTE)
    override suspend fun remove(id: String) = throw IllegalStateException(READ_ONLY_NOTE)
    override suspend fun setBody(text: String) = throw IllegalStateException(READ_ONLY_NOTE)
}

/** find_notes, read_note and ask_note. */
private suspend fun notesCall(call: AgentCall, reach: Reach?): Pair<String, AgentAction> {
    if (reach == null) return "Other notes can't be reached from here; answer from this note." to AgentAction("Other notes aren't available here", "fail")
    if (call.tool == "find_notes") {
        val query = call.query.orEmpty().trim()
        val found = reach.others.find(query, reach.self).filter { it.id !in reach.path }.take(NotesReach.FIND)
        val lines = found.map { n ->
            val ref = reach.refs.entries.firstOrNull { it.value.id == n.id }?.key ?: "N${reach.refs.size + 1}"
            reach.refs[ref] = n
            val bits = mutableListOf("$ref · \"${headCut(n.title.trim().ifEmpty { "Untitled" }, 120)}\"", OUTCOME_WORDS[n.outcome] ?: "not marked", "edited ${n.updatedAt.take(10)}")
            n.why?.let { bits += it }
            bits.joinToString(" · ")
        }
        val about = if (query.isNotEmpty()) " about \"${headCut(query, 60)}\"" else ""
        if (lines.isEmpty()) return "No other notes$about." to AgentAction("Looked for notes$about · none")
        return "Your other notes$about (use the N references with read_note, ask_note or propose_change):\n${lines.joinToString("\n")}" to AgentAction("Looked for notes$about · ${lines.size} found", detail = ActionDetail("text", lines.joinToString("\n")))
    }
    val ref = call.note.orEmpty()
    val target = reach.refs[ref] ?: return "There is no note $ref: call find_notes first and use a reference it gives." to AgentAction("No note $ref", "fail")
    val name = "\"${headCut(target.title.trim().ifEmpty { "Untitled" }, 80)}\""
    val saved = reach.others.open(target.id) ?: return "Note $ref $name is gone." to AgentAction("Note $name is gone", "fail")
    if (call.tool == "read_note") {
        val graph = noteGraph(saved.title, saved.body, saved.outcome, saved.cells).ifEmpty { "It is empty." }
        return headCut("Note $ref $name:\n$graph", AgentLimits.MAX_RESULT_CHARS) to AgentAction("Read note $name", detail = ActionDetail("text", clip(graph)))
    }
    if (call.tool == "propose_change") {
        if (reach.depth > 0) return "Only the note the user is working in can propose changes; say what should change in your answer instead." to AgentAction("Didn't propose a change to $name")
        val after = call.after.orEmpty().trim()
        if (after.isNotEmpty() && saved.body.split("\n").none { it.trim() == after })
            return "Note $ref has no line \"${headCut(after, 80)}\": read_note it and copy a line exactly, or leave after empty for the end." to AgentAction("Couldn't place a change in $name", "fail")
        val p = NoteProposal(target.id, target.title.trim().ifEmpty { "Untitled" }, after, call.text.orEmpty().trim())
        return "Proposed adding it to note $ref $name. Nothing changes there until the user taps Apply; tell them in your answer what you proposed and why." to
            AgentAction("Proposed a change to $name", detail = ActionDetail("proposal", p.text, noteId = p.noteId, title = p.title, after = p.after))
    }
    if (target.id in reach.path) return "Note $ref $name is already part of this question; answer with what you have." to AgentAction("Didn't ask note $name again")
    if (reach.budget.left < 2) return "The AI requests for asking other notes are used up for this request: answer with what you have." to AgentAction("Didn't ask note $name")
    // That note's own agent: the same loop and AI, on that note, read only. It may ask one note further
    // (NotesReach.DEPTH), never one already in this chain; every ask draws on the one budget.
    val counted: suspend (AgentRequest) -> AgentReply = { body ->
        if (reach.budget.left <= 0) throw IllegalStateException("The AI requests for asking other notes are used up.")
        reach.budget.left--
        reach.ask(body)
    }
    val deeper = reach.depth + 1 < NotesReach.DEPTH
    val final = try {
        runAgent(
            askNoteRequest(call.question.orEmpty(), reach.from), ReadOnlyNote(saved, if (deeper) reach.others else null), counted, {}, AgentUndo(),
            maxSteps = minOf(NotesReach.ASK_STEPS, reach.budget.left),
            parent = Reach(reach.others, counted, reach.budget, reach.depth + 1, reach.path + target.id, SearchFrom(target.id, saved.parentId, saved.body)),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AgentState.Failed(emptyList(), e.message ?: "It couldn't answer.")
    }
    val said = when (final) {
        is AgentState.Done -> final.steps.lastOrNull()?.message.orEmpty()
        is AgentState.Failed -> final.message
        else -> ""
    }
    val answer = said.trim().ifEmpty { "It couldn't answer." }
    return headCut("Note $ref $name answers: $answer", AgentLimits.MAX_RESULT_CHARS) to AgentAction("Asked note $name", if (final is AgentState.Done) "plain" else "fail", detail = ActionDetail("text", clip(answer)))
}

private fun doingText(call: AgentCall): String = when (call.tool) {
    "find_notes" -> "Looking through your notes…"
    "read_note" -> "Reading another note…"
    "ask_note" -> "Asking another note…"
    "propose_change" -> "Proposing a change…"
    "list_data" -> "Looking in your Data library…"
    "save_data" -> "Saving the file to Data…"
    "find_datasets" -> "Searching for datasets…"
    "add_cell" -> "Writing a ${languageConfig(call.language ?: DEFAULT_LANGUAGE).label} cell…"
    "edit_cell" -> "Changing cell ${call.cell}…"
    "delete_cell" -> "Deleting cell ${call.cell}…"
    "write_note", "replace_note" -> "Writing in the note…"
    else -> "Running cell ${call.cell}…"
}

/** An error in one line: its last line, where Python puts the exception, not the traceback's first (the web's shortLine). */
private fun shortLine(s: String): String {
    val line = s.split("\n").lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length > 80) line.take(79) + "…" else line
}

private fun secs(ms: Long) = if (ms >= 1000) "${seconds(ms)} s" else "$ms ms"

private val PLACEHOLDER = Regex("\\b(placeholder|not computed|dummy (data|values|results)|fake (data|results))\\b", RegexOption.IGNORE_CASE)
private val SWALLOW_PASS = Regex("except\\b[^\\n]*:\\s*(\\n\\s*)?pass\\b")
private val SWALLOW_NONE = Regex("except\\s*(Exception|BaseException)?\\s*(as \\w+)?\\s*:\\s*(\\n\\s*)?(#[^\\n]*\\n\\s*)?(print\\([^)]*\\)\\s*\\n\\s*)?\\w+\\s*=\\s*None\\b")
private val OTHER_CELLS = Regex("\\b(globals|locals)\\(\\)\\s*\\.get\\(|\\bin\\s+(globals|locals)\\(\\)|\\bNameError\\b")

/**
 * Code that would pass a failure off as a result (the web's fakeSmell): placeholders, an error caught
 * and carried on from, or a lookup of another cell's variables. Such code isn't put in the note.
 */
fun fakeSmell(code: String): String? = when {
    PLACEHOLDER.containsMatchIn(code) -> "it draws or prints placeholders instead of real results"
    SWALLOW_PASS.containsMatchIn(code) || SWALLOW_NONE.containsMatchIn(code) -> "it catches an error and carries on as if nothing failed"
    OTHER_CELLS.containsMatchIn(code) -> "it checks for variables from other cells, which never exist here (each cell runs alone)"
    else -> null
}

/** Said to the agent when it tries to finish with a cell it wrote that hasn't run cleanly (the web's unfinishedCells). */
/** How many times a request is sent back: replies with no call and no finish, and finishes with work open (the web's AGENT_CHECKS). */
object AgentChecks {
    const val NUDGES = 3
    const val CHECKS = 3
}

/** Said to the agent when it replied without a tool call and without finish (the web's NO_CALL_RESULT). */
const val NO_CALL_RESULT =
    "You replied without a tool call and without calling finish. If you said you would do something (run, change or add a cell, write in the note), make that call now. If the request is truly finished and every cell you wrote or changed has run cleanly, call finish with your answer."

fun unfinishedCells(cells: List<Cell>, undo: AgentUndo): String? {
    val mine = (undo.added + undo.changed.map { it.first }).toSet()
    val bad = cells.mapIndexedNotNull { i, c ->
        if (c.id !in mine) return@mapIndexedNotNull null
        val r = c.lastRun ?: return@mapIndexedNotNull "cell ${i + 1} hasn't been run"
        // Its last run was of other code: the cell was changed after it (the reported failure).
        if (r.codeHash.isNotEmpty() && r.codeHash != codeHash(c.content)) return@mapIndexedNotNull "cell ${i + 1} was changed after its last run"
        when (r.outcome) {
            RunOutcome.Ok -> null
            RunOutcome.Error -> "cell ${i + 1}'s last run failed"
            RunOutcome.Timeout -> "cell ${i + 1}'s last run ran out of time"
            RunOutcome.Stopped -> "cell ${i + 1}'s last run was stopped"
        }
    }
    return if (bad.isEmpty()) null else "Not finished: ${bad.joinToString("; ")}. Run and fix ${if (bad.size == 1) "it" else "them"}, or if it can't work here, say so plainly and why."
}

/** Makes one call on the note: what goes back to the agent, and the line the user reads. A failure is reported, not thrown. */
suspend fun makeCall(call: AgentCall, note: AgentNote, undo: AgentUndo, reach: Reach? = null): Pair<String, AgentAction> = try {
    val smell = if (call.tool == "add_cell" || call.tool == "edit_cell") fakeSmell(call.code.orEmpty()) else null
    when {
        smell != null -> "Not put in the note: $smell. Write code that does the real work, or say plainly what can't be done." to AgentAction("Rejected code that would fake a result", "fail")
        call.tool == "add_cell" -> {
            val lang = call.language?.takeIf(::isLanguageId) ?: DEFAULT_LANGUAGE
            val id = note.add(lang, call.code.orEmpty())
            undo.added += id
            val n = note.read().third.indexOfFirst { it.id == id } + 1
            val label = languageConfig(lang).label
            "Added cell $n ($label)." to AgentAction("Added cell $n · $label", "plain", id, ActionDetail("code", clip(call.code.orEmpty()), language = lang))
        }
        call.tool == "write_note" || call.tool == "replace_note" -> noteCall(call, note, undo)
        call.tool == "find_notes" || call.tool == "read_note" || call.tool == "ask_note" || call.tool == "propose_change" -> notesCall(call, reach)
        call.tool == "list_data" || call.tool == "save_data" || call.tool == "find_datasets" -> dataCall(call, note.data)
        else -> {
            val cells = note.read().third
            val n = call.cell ?: 0
            val cell = cells.getOrNull(n - 1)
            when {
                cell == null -> "There is no cell $n; the note has ${cells.size} code cell${if (cells.size == 1) "" else "s"}." to AgentAction("No cell $n", "fail")
                call.tool == "edit_cell" -> {
                    if (cell.id !in undo.added && undo.changed.none { it.first == cell.id }) undo.changed += cell.id to cell.content
                    note.setCode(cell.id, call.code.orEmpty())
                    "Changed cell $n." to AgentAction("Changed cell $n", "plain", cell.id, ActionDetail("diff", clip(call.code.orEmpty()), before = clip(cell.content)))
                }
                call.tool == "delete_cell" -> if (cell.id !in undo.added) {
                    "Cell $n isn't one you added for this request, so it stays; the user can delete it (Remove on the cell)." to AgentAction("Left cell $n in place", "plain", cell.id)
                } else {
                    note.remove(cell.id)
                    undo.added.remove(cell.id)
                    "Deleted cell $n. The cells after it are numbered one lower now." to AgentAction("Deleted cell $n")
                }
                else -> {
                    var where = runPlace(cell.language, cell.content, call.where)
                    note.run(cell.id, where)
                    var after = note.read().third.firstOrNull { it.id == cell.id }
                    // A library the phone doesn't have: run again on the server by itself, without the model.
                    val failed = after?.lastRun?.takeIf { it.outcome == RunOutcome.Error }
                    if (where != "server" && after?.language == "python" && failed != null && Regex("ModuleNotFoundError|No module named").containsMatchIn(failed.errorLine ?: failed.stderr)) {
                        where = "server"
                        note.run(cell.id, where)
                        after = note.read().third.firstOrNull { it.id == cell.id }
                    }
                    // The seer looks at what it showed, so the report says what the figure shows, not only that there is one.
                    if (note.sees && after?.lastRun?.outcome == RunOutcome.Ok && toSee(after.lastRun?.outputs).isNotEmpty()) {
                        note.see(cell.id)
                        after = note.read().third.firstOrNull { it.id == cell.id } ?: after
                    }
                    val run = after?.lastRun
                    val result = after?.let { runReport(n, it) } ?: "Cell $n is gone from the note."
                    val detail = ActionDetail("output", clip(result.split("\n").drop(1).joinToString("\n").trim().ifEmpty { result }))
                    when {
                        run == null -> result to AgentAction("Ran cell $n · no result", "fail", cell.id, detail)
                        run.outcome == RunOutcome.Ok -> result to AgentAction("Ran cell $n${if (run.where == "server" || where == "server") " on the server" else ""} · ${secs(run.ms)}", "ok", cell.id, detail)
                        else -> {
                            val why = when (run.outcome) {
                                RunOutcome.Error -> "failed" + (run.errorLine?.let { ": ${shortLine(it)}" } ?: "")
                                RunOutcome.Timeout -> "ran out of time"
                                else -> "stopped"
                            }
                            result to AgentAction("Ran cell $n · $why", "fail", cell.id, detail)
                        }
                    }
                }
            }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    "That call failed: ${e.message?.takeIf { it.isNotBlank() } ?: "it didn't work"}" to AgentAction("${doingText(call).removeSuffix("…")} failed", "fail")
}

/** write_note and replace_note: the note's text changed, with what Undo needs to take it back. */
private suspend fun noteCall(call: AgentCall, note: AgentNote, undo: AgentUndo): Pair<String, AgentAction> {
    val before = note.read().second
    if (call.tool == "write_note") {
        val text = call.text.orEmpty()
        val (body, under) = placeNoteText(before, NoteWrite(text, call.after.orEmpty()))
        note.setBody(body)
        undo.note += NoteEdit(before, body, text.trim())
        val where = under?.let { "under “${headCut(it.replace(Regex("[*_#>`]"), "").trim(), 60)}”" } ?: "at the end"
        return "Wrote it into the note's text $where." to AgentAction("Wrote in the note $where", detail = ActionDetail("text", clip(text.trim())))
    }
    val find = call.find.orEmpty()
    val replace = call.replace.orEmpty()
    val count = if (find.isEmpty()) 0 else before.split(find).size - 1
    if (count != 1) return (if (count == 0) "That text isn't in the note's text; copy it exactly as the note has it." else "That text is in the note's text $count times; give a longer piece that appears once.") to AgentAction("Couldn’t find that text in the note", "fail")
    val after = before.replaceFirst(find, replace)
    note.setBody(after)
    undo.note += NoteEdit(before, after, replace, find)
    return (if (replace.isNotEmpty()) "Changed that part of the note's text." else "Removed that part of the note's text.") to
        AgentAction(if (replace.isNotEmpty()) "Changed the note’s text" else "Removed text from the note", detail = ActionDetail("diff", clip(replace), before = clip(find)))
}

/**
 * Carries the request out (the web's runAgent): asks for a step, makes its calls in order, sends back
 * what each gave, until the agent is done. Stop is the coroutine's cancellation: [onState] hears
 * [AgentState.Stopped] and the cancellation goes on. A slow, busy or dropped AI is waited out by itself
 * ([retryWait]); only what waiting can't fix throws [AgentFailure].
 */
/** The most cells the seer looks at before the first step (each is one AI request). */
const val SEE_AT_START = 2

/** Before the first step: the newest current runs whose pictures the seer hasn't described yet (the web's seeFirst). */
private suspend fun seeFirst(note: AgentNote, onLook: () -> Unit) {
    if (!note.sees) return
    val unseen = note.read().third
        .filter { c -> c.lastRun?.let { it.outcome == RunOutcome.Ok && it.codeHash == codeHash(c.content) && toSee(it.outputs).isNotEmpty() } == true }
        .sortedByDescending { it.lastRun!!.ranAt }
        .take(SEE_AT_START)
    if (unseen.isNotEmpty()) onLook()
    for (c in unseen) {
        currentCoroutineContext().ensureActive()
        note.see(c.id)
    }
}

suspend fun runAgent(request: String, note: AgentNote, ask: suspend (AgentRequest) -> AgentReply, onState: (AgentState) -> Unit, undo: AgentUndo, history: AssistantHistory = AssistantHistory(), mine: Set<String> = emptySet(), maxSteps: Int = AgentPatience.MAX_STEPS, parent: Reach? = null, resume: AgentCheckpoint? = null, onCheckpoint: ((AgentCheckpoint) -> Unit)? = null): AgentState {
    // Other notes, reached by reference (N1, N2, …) for this request; a note asked by another shares its budget and chain.
    val reach = note.others?.let { o -> parent?.let { Reach(o, it.ask, it.budget, it.depth, it.path, it.self) } ?: Reach(o, ask) }
    // A request picked up again (the note was left mid-request): it goes on from the step it had reached.
    val shown = resume?.shown.orEmpty().toMutableList()
    val sent = resume?.sent.orEmpty().toMutableList()
    var checks = resume?.checks ?: 0
    var nudges = resume?.nudges ?: 0
    val loops = LoopGuard()
    var plan: AgentPlan? = resume?.plan
    /** Working steps since the plan was last set or ticked (planNudge). */
    var untickedSteps = 0
    // The live conversation: Claude's own turns, kept and sent back unchanged; null once it's too big
    // (or a request was picked up without it), and the request goes on rebuilt from `sent`.
    var live: MutableList<JsonObject>? = if (resume != null) resume.live?.transcript?.toMutableList() else mutableListOf()
    var liveLanguage: String? = resume?.live?.language
    var pend = resume?.live?.pending ?: LivePending()
    fun keep(step: AgentStep, ids: List<String>? = null) {
        sent += step
        pend = pendingOf(ids, step.calls.size, step.results)
    }
    fun withPlan(s: AgentState): AgentState = when (s) {
        is AgentState.Working -> s.copy(plan = plan)
        is AgentState.Done -> s.copy(plan = plan)
        is AgentState.Stopped -> s.copy(plan = plan)
        is AgentState.Failed -> s.copy(plan = plan)
    }
    fun report(s: AgentState) = withPlan(s).also(onState)
    try {
        if (resume == null) seeFirst(note) { report(AgentState.Working(emptyList(), "Looking at the figures…", 1)) }
        for (step in (resume?.step ?: 0) until maxSteps) {
            currentCoroutineContext().ensureActive()
            // Where it is, kept before each step, so a request left mid-way can go on from here.
            val keptLive = live?.let { l -> liveLanguage?.takeIf { jsonSize(l) <= Live.MAX_KEPT_CHARS }?.let { KeptLive(l.toList(), it, pend) } }
            onCheckpoint?.invoke(AgentCheckpoint(step, sent.toList(), shown.toList(), checks, nudges, plan, keptLive))
            report(AgentState.Working(shown.toList(), if (resume != null && step == resume.step) "Picking up where it left off…" else if (sent.isEmpty()) "Reading the note…" else "Thinking…", sent.size + 1))
            val (title, body, cells) = note.read()
            reach?.from = title
            val language = assistantLanguage(cells.map { it.language })
            // As many steps as the work needs: the last ones go back; the note shows all it did.
            val recent = sent.takeLast(AgentLimits.STEPS - 1)
            val last = step == maxSteps - 1
            // Its last step: it's told to finish, so it ends with an answer rather than a "go on".
            val steps = if (last) recent.takeLast(AgentLimits.STEPS - 2) + sentStep("", emptyList(), listOf(LAST_STEP_RESULT)) else recent
            // The note's context graph goes first (built from the note, no AI request), then the note itself.
            val graph = noteGraph(title, body, note.outcome(), cells)
            val room = AssistantLimits.MAX_CONTEXT_CHARS - stepsSize(recent) - historySize(history) - SLOW_RESULT.length - 100 - graph.length
            val text = listOf(graph, noteContext(body, agentCells(cells, mine + undo.added), room).first).filter { it.isNotEmpty() }.joinToString("\n\n")
            var asking = AgentRequest(request, language, title, text, steps, history)
            live?.let { l ->
                // Live: the system prompt stays the same for the whole request; the note and the history go in once, with the first step.
                val lang = liveLanguage ?: language.also { liveLanguage = it }
                val notes = (pend.notes + if (last) listOf(LAST_STEP_RESULT) else emptyList()).takeLast(12)
                asking = AgentRequest(request, lang, title, if (l.isEmpty()) text else "", emptyList(), if (l.isEmpty()) history else AssistantHistory(), live = true, transcript = l.toList(), results = pend.results, notes = notes)
            }
            var reply: AgentReply? = null
            var attempt = 0
            var limitTries = 0
            while (reply == null) {
                try {
                    reply = ask(asking)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val wait = retryWait(e, attempt, limitTries) ?: throw e
                    attempt++
                    if (e is StatusFailure && e.status == 429) limitTries++
                    // A reply that took too long is asked for again, shorter.
                    if (e is StatusFailure && e.code == "timeout") {
                        if (asking.live == true) asking = asking.copy(notes = (asking.notes.orEmpty().filter { it != SLOW_RESULT } + SLOW_RESULT).takeLast(12))
                        else if (asking.steps.lastOrNull()?.results?.firstOrNull() != SLOW_RESULT) asking = asking.copy(steps = steps.takeLast(AgentLimits.STEPS - 1) + sentStep("", emptyList(), listOf(SLOW_RESULT)))
                    }
                    // A hiccup is the agent's business, not the user's: the line stays "Thinking…" while it waits.
                    report(AgentState.Working(shown.toList(), "Thinking…", sent.size + 1))
                    AgentClock.sleep(wait * 1000L)
                    currentCoroutineContext().ensureActive()
                }
            }
            val r = reply
            live?.let { l ->
                // Claude's turn (and what was asked) join the conversation; past its size the request goes on the older way.
                val turns = r.turns
                if (turns.isNullOrEmpty()) live = null else l += turns
                if (live != null && jsonSize(l) > Live.MAX_CHARS) live = null
                pend = LivePending()
            }
            val newPlan = r.plan?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(6)
            plan = when {
                newPlan != null && newPlan.size >= 2 -> AgentPlan(newPlan, (r.planDone ?: 0).coerceIn(0, newPlan.size))
                plan != null && r.planDone != null -> plan.copy(done = r.planDone.coerceIn(0, plan.steps.size))
                else -> plan
            }
            untickedSteps = if (r.plan != null || r.planDone != null) 0 else untickedSteps + (if (r.calls.isNotEmpty()) 1 else 0)
            if (last) {
                shown += AgentDone(r.message.ifBlank { "It ran out of steps here; what it did is listed above." }, emptyList())
                return report(AgentState.Done(shown.toList()))
            }
            if (r.cut) {
                // Cut off before its calls were whole: nothing was made; it's told so and writes less. Its half
                // message isn't shown: the user reads the whole one that follows.
                keep(sentStep(r.message, emptyList(), listOf(CUT_RESULT)))
                continue
            }
            if (r.calls.isEmpty()) {
                // It stopped without calling finish. A plain answer to a question (nothing changed, nothing
                // wrong) is taken as it is; anything else, above all an announced action with no call, is
                // sent back to be done (the web's runAgent).
                val acted = !undo.isEmpty || sent.any { it.calls.isNotEmpty() }
                if (!r.finished && (acted || r.problems.isNotEmpty() || r.message.isBlank()) && nudges < AgentChecks.NUDGES) {
                    nudges++
                    keep(sentStep(r.message, emptyList(), r.problems + NO_CALL_RESULT))
                    continue
                }
                // Done is checked, not taken on its word: a cell it wrote or changed that hasn't run cleanly
                // with its current code sends it back, each time it tries to finish (up to AgentChecks.CHECKS).
                val open = unfinishedCells(note.read().third, undo)
                if (open != null && checks < AgentChecks.CHECKS) {
                    checks++
                    // Its "done" isn't shown yet: the work isn't, and the user reads the answer it gives after checking.
                    report(AgentState.Working(shown.toList(), "Checking its work…", sent.size + 1))
                    keep(sentStep(r.message, emptyList(), listOf(open)))
                    continue
                }
                // Still not finished after every check: said plainly with its answer, never passed off as done.
                val said = if (open == null) r.message else listOf(r.message, "**Not finished:** " + open.removePrefix("Not finished: ").replace(Regex(" Run and fix.*$"), "")).filter { it.isNotBlank() }.joinToString("\n\n")
                shown += AgentDone(said, emptyList())
                plan = plan?.let { it.copy(done = it.steps.size) } // finished: every step of its plan is done
                return report(AgentState.Done(shown.toList()))
            }
            shown += AgentDone(r.message, emptyList())
            val loopWhy = loops.before(r.calls)
            val results = mutableListOf<String>()
            r.calls.forEach { call ->
                currentCoroutineContext().ensureActive() // Stop lands between calls, as on the web
                report(AgentState.Working(shown.toList(), doingText(call), sent.size + 1))
                val (result, action) = makeCall(call, note, undo, reach)
                results += result
                shown[shown.lastIndex] = shown.last().let { it.copy(actions = it.actions + action) }
            }
            // Calls it made that couldn't be made as written go back with the results, so it fixes them.
            results += r.problems
            // The checklist the user watches only moves when it's ticked: after a couple of working steps
            // without a tick, it's reminded where the plan stands (the web's planNudge).
            plan?.let { p -> if (p.done < p.steps.size && untickedSteps >= PLAN_NUDGE_STEPS) { results += planNudge(p); untickedSteps = 0 } }
            // Going in circles: told once; the second time it stops and asks the user, with what it tried.
            val why = loopWhy ?: loops.after(r.calls, results)
            if (why != null) {
                loops.hits++
                if (loops.hits >= 2) {
                    shown += AgentDone(stuckMessage(why, shown), emptyList())
                    return report(AgentState.Done(shown.toList()))
                }
                results += loopResult(why)
            }
            keep(sentStep(r.message, r.calls, results, newPlan?.takeIf { it.size >= 2 }), r.ids)
        }
        return report(AgentState.Done(shown.toList()))
    } catch (e: CancellationException) {
        report(AgentState.Stopped(shown.toList()))
        throw e
    } catch (e: Exception) {
        throw AgentFailure(shown.toList(), e, plan)
    }
}

/**
 * Notices the agent going in circles with no progress (the web's loopGuard): a cell set back to code it
 * already had in this request, the same calls giving the same results again, or a cell changed and run
 * again twice with its result coming out exactly the same. A sweep that changes the numbers is progress.
 */
class LoopGuard {
    var hits = 0
    private val codes = mutableMapOf<Int, MutableSet<String>>()
    private val lastResult = mutableMapOf<Int, String>()
    private val sameAgain = mutableMapOf<Int, Int>()
    private val steps = mutableSetOf<String>()
    private fun plain(r: String) = r.replace(Regex(" in [\\d.]+ m?s\\."), ".").replace(Regex(" after [\\d.]+ m?s\\."), ".")

    fun before(calls: List<AgentCall>): String? {
        for (c in calls) {
            if (c.tool != "edit_cell" || c.cell == null) continue
            val seen = codes.getOrPut(c.cell) { mutableSetOf() }
            if (!seen.add(codeHash(c.code.orEmpty()))) return "cell ${c.cell} was set back to code it already had earlier in this request"
        }
        return null
    }

    fun after(calls: List<AgentCall>, results: List<String>): String? {
        val key = calls.joinToString("|") { "${it.tool}:${it.cell}:${it.code?.let(::codeHash)}:${it.text}:${it.find}:${it.replace}:${it.after}" } + "#" + results.joinToString("|")
        if (!steps.add(key)) return "the same calls gave the same results again"
        val edited = calls.filter { it.tool == "edit_cell" }.mapNotNull { it.cell }.toSet()
        var why: String? = null
        calls.forEachIndexed { i, c ->
            val cell = c.cell
            if (c.tool != "run_cell" || cell == null || i >= results.size) return@forEachIndexed
            val now = plain(results[i])
            if (cell in edited && lastResult[cell] == now) {
                val n = (sameAgain[cell] ?: 0) + 1
                sameAgain[cell] = n
                if (n >= 2 && why == null) why = "cell $cell was changed and run again twice and its result came out exactly the same"
            }
            lastResult[cell] = now
        }
        return why
    }
}

/** Told to the agent the first time it goes in circles (the web's loopResult). */
fun loopResult(why: String) =
    "You may be going in circles: $why. Whatever is listed as \"shown in the note\" is already displayed. Change your approach, or if the request is done, call finish; if you are stuck, call finish and say plainly what is blocking you."

/** What the user reads when it went in circles twice (the web's stuckMessage). */
fun stuckMessage(why: String, steps: List<AgentDone>): String {
    val tried = steps.flatMap { s -> s.actions.map { it.text } }.distinct().takeLast(6)
    return listOf("I stopped because I was going in circles: $why.", if (tried.isNotEmpty()) "What I tried: ${tried.joinToString("; ")}." else "", "What would you like me to do next?")
        .filter { it.isNotEmpty() }.joinToString("\n\n")
}

/** Puts the note back as it was before the agent: its cells deleted, the cells it changed given their old code, its text changes taken back (the web's undoAgent). */
suspend fun undoAgent(note: AgentNote, undo: AgentUndo) {
    // Each change is dropped from [undo] as soon as it is put back, so an Undo that fails part way
    // (no connection) and is tapped again carries on where it stopped instead of failing for good.
    while (undo.added.isNotEmpty()) {
        note.remove(undo.added.last())
        undo.added.removeAt(undo.added.lastIndex)
    }
    val ids = note.read().third.map { it.id }.toSet()
    while (undo.changed.isNotEmpty()) {
        val (id, code) = undo.changed.first()
        if (id in ids) note.setCode(id, code) // a cell the user has deleted since: nothing to put back
        undo.changed.removeAt(0)
    }
    while (undo.note.isNotEmpty()) {
        val e = undo.note.last()
        val now = note.read().second
        when {
            now == e.after -> note.setBody(e.before)
            e.replaced == null -> note.setBody(removeNoteText(now, e.wrote))
            e.wrote.isNotEmpty() && now.split(e.wrote).size == 2 -> note.setBody(now.replaceFirst(e.wrote, e.replaced))
            e.wrote.isEmpty() && e.replaced.isNotEmpty() && !now.contains(e.replaced) && e.before != "\u0000" -> {
                // Text it deleted: put back where it was, after the text that came before it then.
                val at = e.before.indexOf(e.replaced)
                val lead = if (at > 0) e.before.substring(maxOf(0, at - 80), at) else ""
                val k = if (lead.isNotEmpty()) now.indexOf(lead) else -1
                if (k >= 0) note.setBody(now.substring(0, k + lead.length) + e.replaced + now.substring(k + lead.length))
            }
        }
        undo.note.removeAt(undo.note.lastIndex)
    }
}

/** What the Assistant's history keeps of a finished request: what was done and what the agent said last. */
fun agentSummary(steps: List<AgentDone>): String {
    // Each line once: a cell run five times is "Ran cell 2" once, with its last time.
    val byText = LinkedHashMap<String, String>()
    steps.flatMap { it.actions }.forEach { a ->
        val key = a.text.replace(Regex(" · [\\d.]+ m?s$"), "")
        byText.remove(key)
        byText[key] = a.text
    }
    val actions = byText.values.toList()
    val last = splitSummary(steps.lastOrNull { splitSummary(it.message).second.isNotEmpty() }?.message.orEmpty()).second
    return listOfNotNull(actions.takeIf { it.isNotEmpty() }?.let { "$DONE_PREFIX${it.joinToString("; ")}." }, last.takeIf { it.isNotEmpty() })
        .joinToString("\n\n").ifEmpty { "Nothing was changed." }
}

private const val DONE_PREFIX = "Done in the note: "

/** A kept answer split into what was done in the note (its "Done in the note: …" lines, all of them) and the rest (the web's splitSummary). */
fun splitSummary(text: String): Pair<String, String> {
    val did = mutableListOf<String>()
    val rest = text.split(Regex("\n{2,}")).filter { p ->
        val t = p.trim()
        if (!t.startsWith(DONE_PREFIX.trim())) true else {
            did += t.removePrefix(DONE_PREFIX.trim()).trim().removeSuffix(".")
            false
        }
    }.joinToString("\n\n").trim()
    return did.joinToString("; ") to rest
}
