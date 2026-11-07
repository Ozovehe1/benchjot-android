package app.benchjot.core

import kotlinx.serialization.Serializable

// The note Helper's two places and the online one's pure parts: the web's src/lib/helper.ts (web
// 17fd268), which POST /api/helper runs on Groq with Fix's limit. Here: the note as read on the tap
// that asks, cut to the size the route takes, the request, and reading the answer, including the
// language check (a Python cell never gets JavaScript back). The helper proposes code and answers;
// it never writes the note's text, its cell notes or its outcome, and never runs code.

/** Where the helper runs. Only the user changes it; the helper never switches by itself. */
enum class HelperPlace(val id: String, val label: String) {
    Phone("phone", "On phone"),
    Online("online", "Online helper");

    companion object { fun of(id: String?): HelperPlace = entries.firstOrNull { it.id == id } ?: Phone }
}

/** The web's HELPER_LIMITS: what the user may type, the note as sent, and each cell's error tail. */
object HelperLimits {
    const val MAX_REQUEST_CHARS = 2_000
    const val MAX_CONTEXT_CHARS = 16_000
    const val MAX_ERROR_CHARS = 4_000
}

/** One code cell as the helper reads it: its language, code, and its last run's error ('' when none). */
data class HelperCell(val language: String, val code: String, val error: String = "")

/** The cells as saved, each with its last run's error (the same error Fix is given). */
fun helperCells(snippets: List<Snippet>): List<HelperCell> = snippets.map { s ->
    val run = s.lastRun
    HelperCell(s.language, s.content, if (run?.outcome == RunOutcome.Error) run.errorLine ?: run.stderr else "")
}

/** New code is in the last cell's language, unless that is plain text. */
fun helperLanguage(languages: List<String>): String =
    languages.lastOrNull()?.takeIf { it != "text" && isLanguageId(it) } ?: DEFAULT_LANGUAGE

/** A cell as the note shows it to the model: fenced code, then its last error's tail. */
private fun cellText(cell: HelperCell): String {
    val code = "```${cell.language}\n${cell.code}\n```"
    if (cell.error.isBlank()) return code
    val (lines, cut) = AssistBudget.errorTail(cell.error, HelperLimits.MAX_ERROR_CHARS)
    return "$code\n${if (cut) "Last error (only its last lines):" else "Last error:"}\n```\n${lines.joinToString("\n")}\n```"
}

/**
 * The note as the helper reads it on a tap: the body, then each code cell with its last error,
 * a blank line between. Over [max] chars, the beginning is kept and whole lines are cut from the
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

/** What POST /api/helper takes. [task] is "write" or "ask"; [note] is already cut to fit. */
@Serializable
data class HelperRequest(val task: String, val request: String, val language: String, val title: String, val note: String)

/** The request for [task] (Write or Ask), with the note read now and cut as the route allows. */
fun helperRequest(task: AssistTask, request: String, language: String, title: String, body: String, cells: List<HelperCell>): HelperRequest {
    require(task == AssistTask.Write || task == AssistTask.Ask) { "the online helper writes or asks; Fix has its own route" }
    return HelperRequest(
        task = if (task == AssistTask.Write) "write" else "ask",
        request = request.trim().take(HelperLimits.MAX_REQUEST_CHARS),
        language = language,
        title = title.take(200),
        note = noteContext(body, cells).first,
    )
}

/** What the route answers: {kind:"code", code, summary, language} or {kind:"prose", text}. */
@Serializable
data class HelperReply(val kind: String, val code: String = "", val summary: String = "", val language: String = "", val text: String = "")

sealed interface HelperAnswer {
    data class Code(val code: String, val summary: String) : HelperAnswer
    data class Prose(val text: String) : HelperAnswer
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
 * Reads the route's answer for [task] in [language]'s cell. Empty code or a missing answer, and code
 * in another language than the cell's, are refused in plain words (the route checks too; this keeps
 * a Python cell from ever getting JavaScript, whatever answered).
 */
fun readHelperReply(task: AssistTask, language: String, reply: HelperReply): HelperAnswer {
    if (task == AssistTask.Ask) {
        val text = reply.text.trim()
        if (reply.kind != "prose" || text.isEmpty()) throw FriendlyException(HELPER_NO_ANSWER)
        return HelperAnswer.Prose(text)
    }
    if (reply.kind != "code" || reply.code.isBlank()) throw FriendlyException(AssistError.NoCode.text)
    otherLanguage(language, reply.code)?.let {
        throw FriendlyException("The helper wrote ${languageConfig(it).label}, not ${languageConfig(language).label}, so it was left out. Try again.")
    }
    return HelperAnswer.Code(reply.code.trimEnd('\n'), reply.summary.trim())
}
