package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// The note Assistant's two places and the online one's pure parts: the web's src/lib/ai/assistant.ts, which
// POST /api/assistant runs on the user's own connected AI (OpenRouter). Here: the note as read on the tap that asks (every
// cell with its notes, its last output or error), cut to the size the route takes, the history sent
// back (the note's summary and last turns), the request, and reading the answer, including the
// language check (a Python cell never gets web script back). The assistant.json fixture holds all of it
// to the web's own answers. The assistant proposes code and answers; it never writes the note's text,
// its cell notes or its outcome, and never runs code.

/** The web's ASSISTANT_LIMITS: what the user may type, the note and history as sent, each cell's error and output tail. */
object AssistantLimits {
    const val MAX_REQUEST_CHARS = 2_000
    const val MAX_CONTEXT_CHARS = 100_000
    const val MAX_ERROR_CHARS = 4_000
    const val MAX_OUTPUT_CHARS = 2_000
}

/**
 * The web's ASSISTANT_HISTORY: turns are kept whole for [DAYS] (assistant_turns), then the database condenses
 * them into the note's summary (assistant_summaries, at most [MAX_STORED_SUMMARY_CHARS]). Online sends the
 * summary's newest [MAX_SUMMARY_CHARS] and the last [TURNS] turns, each cut; the phone sends the summary
 * only, at most [PHONE_SUMMARY_TOKENS] tokens, and drops it before it cuts the note.
 */
object AssistantHistoryLimits {
    const val DAYS = 30
    const val TURNS = 6
    const val MAX_SUMMARY_CHARS = 1_500
    const val MAX_STORED_SUMMARY_CHARS = 4_000
    const val MAX_TURN_REQUEST_CHARS = 400
    const val MAX_TURN_ANSWER_CHARS = 1_200
    const val PHONE_SUMMARY_TOKENS = 300
}

/**
 * One code cell as the assistant reads it, in the note's order: its "Note before run", its code, its last
 * run's output when that run succeeded (the stdout and what it made, by label) or its last error when
 * it failed, and its "Note after run". '' / empty when there is none.
 */
@Serializable
data class AssistantCell(
    val language: String,
    val code: String,
    val error: String = "",
    val noteBefore: String = "",
    val noteAfter: String = "",
    val stdout: String = "",
    val produced: List<String> = emptyList(),
    /** The tables its run made, each as a short CSV preview ([tablePreview]), so the assistant can read the numbers. */
    val tables: List<String> = emptyList(),
    /** When its last run gave no result to read: why (stopped, timed out, never run). */
    val noResult: String? = null,
    /** Said before the cell, when the reader refers to cells by number (the voice agent: "[Cell 3]"). */
    val label: String? = null,
    /** What the run itself said about its outputs (bj_capture's notes: "HTML output isn't shown"). */
    val runNotes: List<String> = emptyList(),
)

/** Said for a cell whose last run left nothing to read. */
private val NO_RESULT = mapOf("stopped" to "it was stopped before it finished", "timeout" to "it ran out of time before it finished", "none" to "it hasn't been run yet")

/** How much of each table the assistant reads: the first rows, at most this many tables per cell, each within its chars. */
object TablePreview {
    const val ROWS = 8
    const val TABLES = 3
    const val MAX_CHARS = 1_200
}

private fun csvCell(v: kotlinx.serialization.json.JsonElement): String {
    val t = cellText(v)
    return if (Regex("[\",\n]").containsMatchIn(t)) "\"" + t.replace("\"", "\"\"") + "\"" else t
}

/** A table a run made, as the assistant reads it: its label, then its header and first rows as CSV, within [TablePreview.MAX_CHARS]. */
fun tablePreview(item: OutputItem): String {
    val shown = item.rows.take(TablePreview.ROWS)
    val lines = listOf(item.columns.map { csvCell(kotlinx.serialization.json.JsonPrimitive(it)) }) + shown.map { r -> r.map(::csvCell) }
    val note = if (item.totalRows > shown.size) " (first ${shown.size} of ${item.totalRows} rows)" else ""
    var csv = lines.joinToString("\n") { it.joinToString(",") }
    if (csv.length > TablePreview.MAX_CHARS) csv = csv.take(TablePreview.MAX_CHARS - 1) + "…"
    return "${outputLabel(item)}$note:\n```csv\n$csv\n```"
}

/**
 * What a run showed, as the assistant reads it, in plain words that say it is on screen (the web's
 * shownLabel). A bare file name ("animation-1.gif") read to the model as a file saved but not shown.
 */
fun shownLabel(item: OutputItem): String {
    val title = if (item.isTable) "" else item.title.trim()
    fun titled(s: String) = if (title.isEmpty()) s else "$s \"$title\""
    if (item.isTable) return "a table, ${item.totalRows} rows × ${item.totalColumns} columns"
    if (item.kind == "chart") return titled("an interactive ${mapOf("plotly" to "Plotly", "vega-lite" to "Vega-Lite", "bokeh" to "Bokeh")[item.lib] ?: item.lib} chart")
    val f = item.files
    val name = item.name ?: ""
    if (Regex("^animation-\\d+\\.gif$", RegexOption.IGNORE_CASE).matches(name) || (f["gif"] != null && (f["mp4"] != null || f["webm"] != null))) return titled("an animation (it plays by itself in the note)")
    if (f["mp4"] != null || f["webm"] != null) return titled("a video${if (name.isNotEmpty()) " ($name)" else ""} (it plays in the note)")
    if (item.source == "matplotlib") return titled("a figure")
    if (item.source == "pil") return titled("an image")
    return titled("an image${if (name.isNotEmpty()) " ($name, a file the code wrote)" else ""}")
}
/**
 * What a run showed and, once the seer has looked at it, what it shows (the web's producedLabel).
 * A table also names its first columns.
 */
fun producedLabel(item: OutputItem, max: Int = 400): String {
    val label = shownLabel(item)
    if (item.isTable) {
        val cols = item.columns.drop(item.indexColumns).take(6).filter { it.isNotBlank() }
        val more = if (item.columns.size - item.indexColumns > cols.size) ", …" else ""
        return if (cols.isEmpty()) label else "$label (columns: ${cols.joinToString(", ")}$more)"
    }
    val seen = item.seen?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    return if (seen.isEmpty()) label else "$label (it shows: ${headCut(seen, max)})"
}

/** The cells as saved: each with its cell notes and its last run's output or error (the same error Fix is given). */
fun assistantCells(cells: List<Cell>): List<AssistantCell> = cells.map { s ->
    val run = s.lastRun
    val ok = run?.outcome == RunOutcome.Ok
    AssistantCell(
        language = s.language,
        code = s.content,
        error = if (run?.outcome == RunOutcome.Error) run.errorLine ?: run.stderr else "",
        noteBefore = s.noteBefore,
        noteAfter = s.noteAfter,
        stdout = if (ok) run.stdout else "",
        produced = if (ok) run.outputs?.items.orEmpty().map { producedLabel(it) } else emptyList(),
        tables = if (ok) run.outputs?.items.orEmpty().filter { it.isTable }.map(::tablePreview).take(TablePreview.TABLES) else emptyList(),
        noResult = when {
            run == null -> NO_RESULT["none"]
            run.outcome == RunOutcome.Stopped -> NO_RESULT["stopped"]
            run.outcome == RunOutcome.Timeout -> NO_RESULT["timeout"]
            else -> null
        },
        runNotes = run?.outputs?.notes.orEmpty().take(5),
    )
}

/** New code is in the last cell's language, unless that is plain text. */
fun assistantLanguage(languages: List<String>): String =
    languages.lastOrNull()?.takeIf { it != "text" && isLanguageId(it) } ?: DEFAULT_LANGUAGE

/** Said where the note was cut to fit what the model reads. */
const val NOTE_CUT = "[The rest of the note was cut to fit.]"

/**
 * The text's last lines, at most [max] chars, cut at a line start. A last line longer than that
 * alone keeps its end. The flag says whether anything was cut.
 */
internal fun textTail(text: String, max: Int): Pair<List<String>, Boolean> {
    val lines = text.trimEnd().split("\n")
    val kept = ArrayDeque<String>()
    var chars = 0
    for (line in lines.asReversed()) {
        if (kept.isNotEmpty() && chars + line.length + 1 > max) break
        kept.addFirst(if (kept.isEmpty()) line.takeLast(max) else line)
        chars += kept.first().length + 1
    }
    return kept.toList() to (kept.size < lines.size || kept.first().length < lines[lines.size - kept.size].length)
}

/** A fenced block of the text's tail, labelled, saying when its front was cut. */
internal fun tailBlock(label: String, text: String, max: Int): String {
    val (lines, cut) = textTail(text, max)
    return "${if (cut) "$label (only its last lines):" else "$label:"}\n```\n${lines.joinToString("\n")}\n```"
}

/**
 * A cell as the note shows it to the model, every part that has something, in this order (tables
 * after what it made). [code] replaces the cell's code (cut to fit, when the note is long).
 */
private fun cellText(cell: AssistantCell, code: String = cell.code): String = buildList {
    cell.label?.let { add(it) }
    if (cell.noteBefore.isNotBlank()) add("Note before run:\n${cell.noteBefore.trim()}")
    add("```${cell.language}\n$code\n```")
    if (cell.stdout.isNotBlank()) add(tailBlock("Output", cell.stdout, AssistantLimits.MAX_OUTPUT_CHARS))
    if (cell.produced.isNotEmpty()) add("Shown in the note: ${cell.produced.joinToString("; ")}.")
    addAll(cell.tables)
    if (cell.error.isNotBlank()) add(tailBlock("Last error", cell.error, AssistantLimits.MAX_ERROR_CHARS))
    cell.noResult?.let { add("No output to read: $it.") }
    if (cell.runNotes.isNotEmpty()) add("Notes from the run: ${cell.runNotes.joinToString(" ")}")
    if (cell.noteAfter.isNotBlank()) add("Note after run:\n${cell.noteAfter.trim()}")
}.joinToString("\n")

/** Keeps the text's first whole lines that fit in [room] chars, then NOTE_CUT; '' when not even that fits. */
private fun headLines(text: String, room: Int): String {
    if (text.length <= room) return text
    val space = room - NOTE_CUT.length - 1
    if (space < 0) return ""
    val lines = text.split("\n")
    var used = 0
    var keep = 0
    while (keep < lines.size && used + lines[keep].length + 1 <= space) used += lines[keep++].length + 1
    return if (keep > 0) lines.take(keep).joinToString("\n") + "\n" + NOTE_CUT else NOTE_CUT
}

/** The line said in place of the older cells that didn't fit. */
fun cellsLeftOut(n: Int) = "[$n earlier code ${if (n == 1) "cell was" else "cells were"} left out to fit.]"

/** The note's text never takes more than this share of the room when the note has to be cut. */
private const val BODY_SHARE = 0.25

/**
 * The note as the assistant reads it on a tap (the web's noteContext): the body, then each code cell, a
 * blank line between. Over [max] chars, what a run showed is kept before code: the body gets at most a
 * quarter of the room (its first lines, then NOTE_CUT); then every cell's notes, output, figures, tables
 * and error go in, from the newest back, with its code left out, as many as fit (older ones beyond that
 * are named in one line, [cellsLeftOut]); then the room left is given to their code from the newest
 * back, whole while it fits, and the first lines of the one that doesn't. Never over [max].
 */
fun noteContext(body: String, cells: List<AssistantCell>, max: Int = AssistantLimits.MAX_CONTEXT_CHARS): Pair<String, Boolean> {
    val texts = cells.map { cellText(it) }
    val whole = (listOf(body) + texts).joinToString("\n\n")
    if (whole.length <= max) return whole to false

    val bodyReserve = minOf(body.length, (max * BODY_SHARE).toInt())
    val omitLine = cellsLeftOut(maxOf(texts.size, 1)).length + 2
    var room = max - bodyReserve - 2 - omitLine
    val brief = cells.map { cellText(it, NOTE_CUT) }
    var first = texts.size
    while (first > 0 && brief[first - 1].length + 2 <= room) room -= brief[--first].length + 2
    val kept = brief.subList(first, brief.size).toMutableList()
    for (i in texts.indices.reversed()) {
        if (i < first) break
        val extra = texts[i].length - brief[i].length
        if (extra <= room) {
            kept[i - first] = texts[i]
            room -= extra
            continue
        }
        val code = headLines(cells[i].code, room + NOTE_CUT.length)
        if (code.isNotEmpty() && code != NOTE_CUT) {
            val t = cellText(cells[i], code)
            room -= t.length - brief[i].length
            kept[i - first] = t
        }
        break
    }
    val left = texts.size - kept.size
    val tail = (if (left > 0) listOf(cellsLeftOut(left)) else emptyList()) + kept
    val tailText = tail.joinToString("\n\n")
    val bodyRoom = max - if (tailText.isNotEmpty()) tailText.length + 2 else 0
    val head = headLines(body, bodyRoom)
    return listOf(head, tailText).filter { it.isNotEmpty() }.joinToString("\n\n") to true
}

/**
 * An answer as the route gives it, the panel shows it and assistant_turns keeps it:
 * {kind:"code", code, summary, language} or {kind:"prose", text}.
 */
@Serializable
data class AssistantReply(
    val kind: String,
    val code: String = "",
    val summary: String = "",
    val language: String = "",
    val text: String = "",
    /** Its code went into the note as a new cell (the user asked for that). */
    val insert: Boolean = false,
    /** Text it wrote into the note's text (the user asked for that). */
    val write: NoteWrite? = null,
    /** In the history sent back only: what it wrote, cut to its budget. */
    val wrote: String = "",
    /** A request the agent carried out: its answer, how it went, what Undo needs, and whether it was undone (the web's agentLog / agentChanges). */
    val answer: String? = null,
    val log: KeptLog? = null,
    val changes: KeptChanges? = null,
    val undone: Boolean? = null,
)

/** One thing done, opened, as kept with the turn: {kind:"code", language, code}, {kind:"diff", before, after}, or {kind:"output"|"text", text}. */
@Serializable
data class KeptDetail(
    val kind: String,
    val language: String? = null,
    val code: String? = null,
    val before: String? = null,
    val after: String? = null,
    val text: String? = null,
    /** "proposal": the other note it is for, and its title (the web's actionDetail). */
    val noteId: String? = null,
    val title: String? = null,
    val applied: Boolean? = null,
)

@Serializable
data class KeptAction(val text: String, val tone: String = "plain", val cellId: String? = null, val detail: KeptDetail? = null)

@Serializable
data class KeptPlan(val steps: List<String>, val done: Int)

/** How a request went, kept with its turn so the thread reads the same after the app is closed. */
@Serializable
data class KeptLog(val secs: Int, val narration: List<String> = emptyList(), val actions: List<KeptAction> = emptyList(), val plan: KeptPlan? = null)

@Serializable
data class KeptChanged(val id: String, val code: String)

@Serializable
data class KeptNoteEdit(val wrote: String, val replaced: String? = null)

/** What Undo needs, kept with the turn: the cells added, the code replaced, the text written or replaced (never the whole text). */
@Serializable
data class KeptChanges(val added: List<String> = emptyList(), val changed: List<KeptChanged> = emptyList(), val note: List<KeptNoteEdit> = emptyList()) {
    val isEmpty get() = added.isEmpty() && changed.isEmpty() && note.isEmpty()
}

/** Kept records are written without nulls (the web's schema has absent fields, never null ones). */
private val keptJson = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

/** Text the assistant wrote into the note: the Markdown, and the note's line it goes under ('' for the end). */
@Serializable
data class NoteWrite(val text: String, val after: String = "")

/** A turn as the model is shown it again (Online), its request and answer cut to their budget. */
@Serializable
data class HistoryTurn(val task: String, val request: String, val answer: AssistantReply)

/** What goes back to the model: the summary's newest lines and the last answered turns, oldest first. */
@Serializable
data class AssistantHistory(val summary: String = "", val turns: List<HistoryTurn> = emptyList())

/** The text's first [max] chars; a longer text ends in "…" and still fits (the web's headCut). */
fun headCut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"

/** The summary's newest whole lines within [max] chars ('' when blank). */
fun summaryTail(summary: String, max: Int): String =
    if (summary.isBlank()) "" else textTail(summary.trim(), max).first.joinToString("\n")

/** The web's assistantHistory: [turns] are the note's answered turns, oldest first. */
fun assistantHistory(summary: String, turns: List<HistoryTurn>): AssistantHistory = AssistantHistory(
    summary = summaryTail(summary, AssistantHistoryLimits.MAX_SUMMARY_CHARS),
    turns = turns.takeLast(AssistantHistoryLimits.TURNS).map { t ->
        val cut = { s: String -> headCut(s, AssistantHistoryLimits.MAX_TURN_ANSWER_CHARS) }
        val a = t.answer
        HistoryTurn(
            t.task,
            headCut(t.request.trim(), AssistantHistoryLimits.MAX_TURN_REQUEST_CHARS),
            (if (a.kind == "code") AssistantReply("code", code = cut(a.code), summary = cut(a.summary), language = a.language) else AssistantReply("prose", text = cut(a.text)))
                .copy(wrote = a.write?.let { cut(it.text) } ?: ""),
        )
    },
)

/** The characters the history adds to the request; the note gets the rest of [AssistantLimits.MAX_CONTEXT_CHARS]. */
fun historySize(h: AssistantHistory): Int =
    h.summary.length + h.turns.sumOf { t -> t.request.length + (if (t.answer.kind == "code") t.answer.code.length + t.answer.summary.length else t.answer.text.length) + t.answer.wrote.length }

/** A line as compared when finding where text goes: no Markdown marks, spaces or case (the web's plainLine). */
private fun plainLine(line: String) = line.replace(Regex("[*_#>`]"), "").replace(Regex("\\s+"), " ").trim().lowercase()

/**
 * The note's text with [write] put in (the web's placeNoteText): under the first line that reads as
 * its `after` (the same, or starting with it, Markdown marks and case aside), else at the end; a
 * blank line around it. The second value is that line as written, or null when it went at the end.
 */
fun placeNoteText(body: String, write: NoteWrite): Pair<String, String?> {
    val text = write.text.trim()
    val want = plainLine(write.after)
    val lines = body.split("\n")
    val at = if (want.isNotEmpty()) lines.indexOfFirst { plainLine(it) == want } else -1
    val i = if (at >= 0) at else if (want.length >= 3) lines.indexOfFirst { plainLine(it).startsWith(want) } else -1
    if (i < 0) return (if (body.isNotBlank()) "${body.trimEnd()}\n\n$text\n" else "$text\n") to null
    val before = lines.take(i + 1).joinToString("\n")
    val rest = lines.drop(i + 1).joinToString("\n").replaceFirst(Regex("^\\s*\\n"), "")
    return "$before\n\n$text\n${if (rest.isNotEmpty()) "\n$rest" else ""}" to lines[i].trim()
}

/** The note's text with what the assistant wrote taken out again (Undo); unchanged when it was edited away. */
fun removeNoteText(body: String, text: String): String {
    val block = text.trim()
    val i = body.indexOf(block)
    if (i < 0) return body
    val head = body.substring(0, i).let { if (it.endsWith("\n\n")) it.dropLast(1) else it }
    val tail = body.substring(i + block.length).removePrefix("\n")
    return (head + tail).replace(Regex("\\n{3,}"), "\n\n")
}

// ------------------------------------------------------------------ the saved turns
// Each answered turn is saved with the note (assistant_turns, owner only) and shown again when the
// Assistant reopens, on the phone and the web; the phone keeps the last read in a local cache.

/** A row of assistant_turns. [answer] keeps only its kind's fields ([stored]). */
@Serializable
data class SavedTurn(
    val id: String,
    val task: String,
    val request: String,
    val answer: AssistantReply,
    @SerialName("created_at") val createdAt: String = "",
) {
    /** A row the app can show: a known task and an answer of one of the two kinds. */
    val readable: Boolean get() = (task == "write" || task == "ask") && request.isNotEmpty() &&
        ((answer.kind == "code" && answer.code.isNotEmpty()) || (answer.kind == "prose" && answer.text.isNotEmpty()))

}

/** The note's assistant history as last read: the condensed summary ('' when none) and the saved turns, oldest first. */
@Serializable
data class SavedHistory(val summary: String = "", val turns: List<SavedTurn> = emptyList())

/** The answer as assistant_turns stores it: {kind:"code", code, summary, language} or {kind:"prose", text}. */
fun AssistantReply.stored(): JsonObject = buildJsonObject {
    put("kind", JsonPrimitive(kind))
    if (kind == "code") {
        put("code", JsonPrimitive(code))
        put("summary", JsonPrimitive(summary))
        put("language", JsonPrimitive(language))
        if (insert) put("insert", JsonPrimitive(true))
    } else {
        put("text", JsonPrimitive(text))
    }
    write?.let { w -> put("write", buildJsonObject { put("text", JsonPrimitive(w.text)); put("after", JsonPrimitive(w.after)) }) }
    answer?.let { put("answer", JsonPrimitive(it)) }
    log?.let { put("log", keptJson.encodeToJsonElement(KeptLog.serializer(), it)) }
    changes?.takeIf { !it.isEmpty }?.let { put("changes", keptJson.encodeToJsonElement(KeptChanges.serializer(), it)) }
    if (undone == true) put("undone", JsonPrimitive(true))
}
