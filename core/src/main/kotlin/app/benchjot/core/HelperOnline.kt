package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// The note Helper's two places and the online one's pure parts: the web's src/lib/helper.ts, which
// POST /api/helper runs on Groq with Fix's limit. Here: the note as read on the tap that asks (every
// cell with its notes, its last output or error), cut to the size the route takes, the history sent
// back (the note's summary and last turns), the request, and reading the answer, including the
// language check (a Python cell never gets JavaScript back). The helper.json fixture holds all of it
// to the web's own answers. The helper proposes code and answers; it never writes the note's text,
// its cell notes or its outcome, and never runs code.

/** Where the helper runs. Only the user changes it; the helper never switches by itself. */
enum class HelperPlace(val id: String, val label: String) {
    Phone("phone", "On phone"),
    Online("online", "Online helper");

    companion object { fun of(id: String?): HelperPlace = entries.firstOrNull { it.id == id } ?: Phone }
}

/** The web's HELPER_LIMITS: what the user may type, the note and history as sent, each cell's error and output tail. */
object HelperLimits {
    const val MAX_REQUEST_CHARS = 2_000
    const val MAX_CONTEXT_CHARS = 16_000
    const val MAX_ERROR_CHARS = 4_000
    const val MAX_OUTPUT_CHARS = 2_000
}

/**
 * The web's HELPER_HISTORY: turns are kept whole for [DAYS] (helper_turns), then the database condenses
 * them into the note's summary (helper_summaries, at most [MAX_STORED_SUMMARY_CHARS]). Online sends the
 * summary's newest [MAX_SUMMARY_CHARS] and the last [TURNS] turns, each cut; the phone sends the summary
 * only, at most [PHONE_SUMMARY_TOKENS] tokens, and drops it before it cuts the note.
 */
object HelperHistoryLimits {
    const val DAYS = 30
    const val TURNS = 6
    const val MAX_SUMMARY_CHARS = 1_500
    const val MAX_STORED_SUMMARY_CHARS = 4_000
    const val MAX_TURN_REQUEST_CHARS = 400
    const val MAX_TURN_ANSWER_CHARS = 1_200
    const val PHONE_SUMMARY_TOKENS = 300
}

/**
 * One code cell as the helper reads it, in the note's order: its "Note before run", its code, its last
 * run's output when that run succeeded (the stdout and what it made, by label) or its last error when
 * it failed, and its "Note after run". '' / empty when there is none.
 */
@Serializable
data class HelperCell(
    val language: String,
    val code: String,
    val error: String = "",
    val noteBefore: String = "",
    val noteAfter: String = "",
    val stdout: String = "",
    val produced: List<String> = emptyList(),
)

/** What a run made, as the helper reads it: the output's label, and its title when it has one. */
fun producedLabel(item: OutputItem): String {
    val title = if (item.isTable) "" else item.title.trim()
    return if (title.isEmpty()) outputLabel(item) else "${outputLabel(item)} \"$title\""
}

/** The cells as saved: each with its cell notes and its last run's output or error (the same error Fix is given). */
fun helperCells(snippets: List<Snippet>): List<HelperCell> = snippets.map { s ->
    val run = s.lastRun
    val ok = run?.outcome == RunOutcome.Ok
    HelperCell(
        language = s.language,
        code = s.content,
        error = if (run?.outcome == RunOutcome.Error) run.errorLine ?: run.stderr else "",
        noteBefore = s.noteBefore,
        noteAfter = s.noteAfter,
        stdout = if (ok) run!!.stdout else "",
        produced = if (ok) run!!.outputs?.items.orEmpty().map(::producedLabel) else emptyList(),
    )
}

/** New code is in the last cell's language, unless that is plain text. */
fun helperLanguage(languages: List<String>): String =
    languages.lastOrNull()?.takeIf { it != "text" && isLanguageId(it) } ?: DEFAULT_LANGUAGE

/** A fenced block of the text's tail, labelled, saying when its front was cut. */
private fun tailBlock(label: String, text: String, max: Int): String {
    val (lines, cut) = AssistBudget.errorTail(text, max)
    return "${if (cut) "$label (only its last lines):" else "$label:"}\n```\n${lines.joinToString("\n")}\n```"
}

/** A cell as the note shows it to the model, every part that has something, in this order. */
private fun cellText(cell: HelperCell): String = buildList {
    if (cell.noteBefore.isNotBlank()) add("Note before run:\n${cell.noteBefore.trim()}")
    add("```${cell.language}\n${cell.code}\n```")
    if (cell.stdout.isNotBlank()) add(tailBlock("Output", cell.stdout, HelperLimits.MAX_OUTPUT_CHARS))
    if (cell.produced.isNotEmpty()) add("Made: ${cell.produced.joinToString("; ")}")
    if (cell.error.isNotBlank()) add(tailBlock("Last error", cell.error, HelperLimits.MAX_ERROR_CHARS))
    if (cell.noteAfter.isNotBlank()) add("Note after run:\n${cell.noteAfter.trim()}")
}.joinToString("\n")

/**
 * The note as the helper reads it on a tap: the body, then each code cell (its notes, code, output or
 * error), a blank line between. Over [max] chars, the beginning is kept and whole lines are cut from the
 * end, which is said with [AssistBudget.NOTE_CUT]; the text never exceeds [max]. The flag says it was cut.
 */
fun noteContext(body: String, cells: List<HelperCell>, max: Int = HelperLimits.MAX_CONTEXT_CHARS): Pair<String, Boolean> {
    val text = (listOf(body) + cells.map(::cellText)).joinToString("\n\n")
    if (text.length <= max) return text to false
    val room = max - AssistBudget.NOTE_CUT.length - 1
    val lines = text.split("\n")
    var used = 0
    var keep = 0
    while (keep < lines.size && used + lines[keep].length + 1 <= room) used += lines[keep++].length + 1
    return (lines.take(keep).joinToString("\n") + "\n" + AssistBudget.NOTE_CUT) to true
}

/**
 * An answer as the route gives it, the panel shows it and helper_turns keeps it:
 * {kind:"code", code, summary, language} or {kind:"prose", text}.
 */
@Serializable
data class HelperReply(val kind: String, val code: String = "", val summary: String = "", val language: String = "", val text: String = "")

/** A turn as the model is shown it again (Online), its request and answer cut to their budget. */
@Serializable
data class HistoryTurn(val task: String, val request: String, val answer: HelperReply)

/** What goes back to the model: the summary's newest lines and the last answered turns, oldest first. */
@Serializable
data class HelperHistory(val summary: String = "", val turns: List<HistoryTurn> = emptyList())

/** The text's first [max] chars; a longer text ends in "…" and still fits (the web's headCut). */
fun headCut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"

/** The summary's newest whole lines within [max] chars ('' when blank). */
fun summaryTail(summary: String, max: Int): String =
    if (summary.isBlank()) "" else AssistBudget.errorTail(summary.trim(), max).first.joinToString("\n")

/** The web's helperHistory: [turns] are the note's answered turns, oldest first. */
fun helperHistory(summary: String, turns: List<HistoryTurn>): HelperHistory = HelperHistory(
    summary = summaryTail(summary, HelperHistoryLimits.MAX_SUMMARY_CHARS),
    turns = turns.takeLast(HelperHistoryLimits.TURNS).map { t ->
        val cut = { s: String -> headCut(s, HelperHistoryLimits.MAX_TURN_ANSWER_CHARS) }
        val a = t.answer
        HistoryTurn(
            t.task,
            headCut(t.request.trim(), HelperHistoryLimits.MAX_TURN_REQUEST_CHARS),
            if (a.kind == "code") HelperReply("code", code = cut(a.code), summary = cut(a.summary), language = a.language) else HelperReply("prose", text = cut(a.text)),
        )
    },
)

/** The characters the history adds to the request; the note gets the rest of [HelperLimits.MAX_CONTEXT_CHARS]. */
fun historySize(h: HelperHistory): Int =
    h.summary.length + h.turns.sumOf { t -> t.request.length + if (t.answer.kind == "code") t.answer.code.length + t.answer.summary.length else t.answer.text.length }

/** What POST /api/helper takes. [task] is "write" or "ask"; [note] is already cut to the room [history] leaves. */
@Serializable
data class HelperRequest(val task: String, val request: String, val language: String, val title: String, val note: String, val history: HelperHistory = HelperHistory())

fun taskId(task: AssistTask): String {
    require(task == AssistTask.Write || task == AssistTask.Ask) { "the helper writes or asks; Fix has its own route" }
    return if (task == AssistTask.Write) "write" else "ask"
}

/** The request for [task] (Write or Ask), with the note read now and cut to the room the history leaves. */
fun helperRequest(task: AssistTask, request: String, language: String, title: String, body: String, cells: List<HelperCell>, history: HelperHistory = HelperHistory()): HelperRequest =
    HelperRequest(
        task = taskId(task),
        request = request.trim().take(HelperLimits.MAX_REQUEST_CHARS),
        language = language,
        title = title.take(200),
        note = noteContext(body, cells, HelperLimits.MAX_CONTEXT_CHARS - historySize(history)).first,
        history = history,
    )

sealed interface HelperAnswer {
    data class Code(val code: String, val summary: String) : HelperAnswer
    data class Prose(val text: String) : HelperAnswer
}

/** The answer as helper_turns keeps it, code in [language]'s cell. */
fun HelperAnswer.reply(language: String): HelperReply = when (this) {
    is HelperAnswer.Code -> HelperReply("code", code = code, summary = summary, language = language)
    is HelperAnswer.Prose -> HelperReply("prose", text = text)
}

const val HELPER_NO_ANSWER = "The model didn’t answer. Try again."

// Detection is only trusted to tell these apart; JS and TS count as one.
private val CONFUSABLE = mapOf("python" to "python", "javascript" to "js", "typescript" to "js")

/** The other language [code] is clearly in, when it isn't [expected]'s; null when it fits (or can't be told). */
fun otherLanguage(expected: String, code: String): String? {
    val want = CONFUSABLE[expected] ?: return null
    val got = detectLanguage(code) ?: return null
    val gotGroup = CONFUSABLE[got] ?: return null
    return if (gotGroup == want) null else got
}

/**
 * Code for [language]'s cell: empty code, or code in another language than the cell's (a Python cell
 * never gets JavaScript, whatever answered: the route, or the phone), is refused in plain words.
 */
fun checkedCode(language: String, code: String, summary: String): HelperAnswer.Code {
    if (code.isBlank()) throw FriendlyException(AssistError.NoCode.text)
    otherLanguage(language, code)?.let {
        throw FriendlyException("The helper wrote ${languageConfig(it).label}, not ${languageConfig(language).label}, so it was left out. Try again.")
    }
    return HelperAnswer.Code(code.trimEnd('\n'), summary.trim())
}

/**
 * Reads the route's answer for [task] in [language]'s cell. Write answers with code; Ask answers in
 * words, or with proposed code when asked for a change. The route checks too; [checkedCode] keeps a
 * Python cell from ever getting JavaScript, whatever answered.
 */
fun readHelperReply(task: AssistTask, language: String, reply: HelperReply): HelperAnswer {
    if (task == AssistTask.Ask && reply.kind == "prose") {
        val text = reply.text.trim()
        if (text.isEmpty()) throw FriendlyException(HELPER_NO_ANSWER)
        return HelperAnswer.Prose(text)
    }
    if (reply.kind != "code") throw FriendlyException(if (task == AssistTask.Ask) HELPER_NO_ANSWER else AssistError.NoCode.text)
    return checkedCode(language, reply.code, reply.summary)
}

// ------------------------------------------------------------------ the saved turns
// Each answered turn is saved with the note (helper_turns, owner only) and shown again when the
// Helper reopens, on the phone and the web; the phone keeps the last read in a local cache.

/** A row of helper_turns. [answer] keeps only its kind's fields ([stored]). */
@Serializable
data class SavedTurn(
    val id: String,
    val task: String,
    val request: String,
    val answer: HelperReply,
    @SerialName("created_at") val createdAt: String = "",
) {
    /** A row the app can show: a known task and an answer of one of the two kinds. */
    val readable: Boolean get() = (task == "write" || task == "ask") && request.isNotEmpty() &&
        ((answer.kind == "code" && answer.code.isNotEmpty()) || (answer.kind == "prose" && answer.text.isNotEmpty()))

    val historyTurn: HistoryTurn get() = HistoryTurn(task, request, answer)
}

/** The note's helper history as last read: the condensed summary ('' when none) and the saved turns, oldest first. */
@Serializable
data class SavedHistory(val summary: String = "", val turns: List<SavedTurn> = emptyList())

/** The answer as helper_turns stores it: {kind:"code", code, summary, language} or {kind:"prose", text}. */
fun HelperReply.stored(): JsonObject = buildJsonObject {
    put("kind", JsonPrimitive(kind))
    if (kind == "code") {
        put("code", JsonPrimitive(code))
        put("summary", JsonPrimitive(summary))
        put("language", JsonPrimitive(language))
    } else {
        put("text", JsonPrimitive(text))
    }
}
