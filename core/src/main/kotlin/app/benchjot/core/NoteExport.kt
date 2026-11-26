package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

// A whole note out of Benchjot (the web's src/lib/files/note-export.ts, line for line): a Jupyter
// notebook (.ipynb), one self-contained web page (.html), or Markdown with its files (.zip). Each
// holds the note's text, its outcome, every code cell with its notes, and the cell's saved run:
// printed output, the error, figures, tables and charts. Pure: the caller passes the note, each
// run's files (already downloaded) and, for the web page, a Markdown renderer. NoteExportTest checks
// all three against the web's own exports of a shared note (note_export.json).

class ExportCell(
    val filename: String,
    val language: String,
    val code: String,
    val noteBefore: String,
    val noteAfter: String,
    val run: LastRun?,
    /** The run's output files by name; missing ones are left out. */
    val files: Map<String, ByteArray>,
)

class ExportNote(val title: String, val body: String, val outcome: String?, val cells: List<ExportCell>, val exportedAt: String)

enum class ExportFormat(val ext: String, val label: String, val detail: String, val mime: String) {
    Ipynb("ipynb", "Jupyter notebook (.ipynb)", "Opens in Jupyter, Google Colab and VS Code, with your outputs.", "application/x-ipynb+json"),
    Html("html", "Web page (.html)", "One file that opens in any browser, offline. Print it to save a PDF.", "text/html"),
    Md("zip", "Markdown and files (.zip)", "note.md with its figures and tables, for Obsidian, GitHub or Notion.", "application/zip"),
}

/** A file name from the note's title. */
fun exportBaseName(title: String): String =
    java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKD)
        .replace(Regex("[^\\w\\s.-]"), "").trim().replace(Regex("\\s+"), "-").take(80).ifEmpty { "note" }

/** Note links ([[Title|id]], [[Title]]) read as their title outside the app. */
fun plainLinks(md: String): String = md.replace(Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?\]\]"""), "$1")

private val BENCHJOT_NOTE = Regex("^\\[Benchjot\\] ")
private val PROGRESS_BAR = Regex("^\\s*(?:[^|\\n]*:\\s*)?\\d{1,3}%\\|.*\\|")

/** What the code printed, for use outside the app: Benchjot's notes and progress bars left out. */
fun cleanStream(text: String): String =
    text.split("\n").filter { !BENCHJOT_NOTE.containsMatchIn(it) && !PROGRESS_BAR.containsMatchIn(it) }.joinToString("\n").replace(Regex("^\\n+"), "")

/** JavaScript's toFixed for the run summary's seconds. */
private fun fixed(x: Double, digits: Int): String = java.math.BigDecimal(x).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString()

/** Where and how long a run ran, in one line ("Ran on a T4 GPU · 10:11"), from its record. */
fun exportRunSummary(r: LastRun): String {
    val gpu = Regex("""\[Benchjot\] Ran on an? (\S+) GPU""").find(r.stderr)?.groupValues?.get(1)
    val where = if (r.where == "browser") "in the browser" else if (gpu != null) "on a $gpu GPU" else "on the server's CPU"
    val said = Regex("""your code ran for (\d+:\d+|[\d.]+ s)""").find(r.stderr)?.groupValues?.get(1)
    val secs = r.ms / 1000.0
    val took = said ?: if (secs >= 60) "${(secs / 60).toInt()}:${Math.round(secs % 60).toString().padStart(2, '0')}" else "${fixed(secs, if (secs < 10) 2 else 1)} s"
    return "Ran $where · $took"
}

private fun stdoutOf(r: LastRun) = cleanStream(r.stdout)
private fun stderrOf(r: LastRun) = cleanStream(r.stderr)
private fun failed(r: LastRun) = r.outcome == RunOutcome.Error

private class Still(val name: String, val mime: String)

/** A figure's best still: PNG, then the other raster formats, then SVG. */
private fun stillFile(item: OutputItem): Still? {
    if (item.isTable) return null
    val name = (if (item.isChart) item.files["png"] ?: item.files["svg"] else displayFile(item)) ?: return null
    return Still(name, MIME[name.substringAfterLast('.')] ?: "application/octet-stream")
}

private fun htmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun str(v: JsonElement?): String = if (v == null || v is JsonNull) "" else cellText(v)
private fun outcomeLabel(o: String?) = Outcome.of(o)?.label

private fun tableHtml(t: OutputItem): String {
    val head = "<tr>${t.columns.joinToString("") { "<th>${htmlEsc(it)}</th>" }}</tr>"
    val rows = t.rows.joinToString("") { r -> "<tr>${r.joinToString("") { "<td>${htmlEsc(str(it))}</td>" }}</tr>" }
    val more = if (t.totalRows > t.rows.size) "<p class=\"more\">First ${t.rows.size} of ${t.totalRows} rows.</p>" else ""
    return "<table><thead>$head</thead><tbody>$rows</tbody></table>$more"
}

private fun tableMarkdown(t: OutputItem): String {
    fun cell(v: String) = v.replace("|", "\\|").replace("\n", " ")
    val lines = mutableListOf("| ${t.columns.joinToString(" | ") { cell(it) }} |", "| ${t.columns.joinToString(" | ") { "---" }} |")
    t.rows.forEach { r -> lines += "| ${r.joinToString(" | ") { cell(str(it)) }} |" }
    if (t.totalRows > t.rows.size) { lines += ""; lines += "_First ${t.rows.size} of ${t.totalRows} rows._" }
    return lines.joinToString("\n")
}

private fun tableCsv(t: OutputItem): String =
    (listOf(t.columns) + t.rows.map { r -> r.map { str(it) } }).joinToString("\n") { r ->
        r.joinToString(",") { s -> if (Regex("[\",\\n]").containsMatchIn(s)) "\"" + s.replace("\"", "\"\"") + "\"" else s }
    } + "\n"

private fun base64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

// ------------------------------------------------------------------ Jupyter

private class Kernel(val name: String, val displayName: String, val language: String)

private val KERNELS = mapOf(
    "python" to Kernel("python3", "Python 3", "python"),
    "r" to Kernel("ir", "R", "R"),
    "julia" to Kernel("julia", "Julia", "julia"),
    "matlab" to Kernel("octave", "Octave", "octave"),
)

/** The notebook's language: the one most of its code cells are in among those with a kernel, else Python. */
fun notebookLanguage(cells: List<ExportCell>): String {
    val counts = LinkedHashMap<String, Int>()
    for (c in cells) if (KERNELS.containsKey(c.language) && c.code.isNotBlank()) counts[c.language] = (counts[c.language] ?: 0) + 1
    return counts.entries.sortedByDescending { it.value }.firstOrNull()?.key ?: "python"
}

/** nbformat's multi-line strings: a list of lines, each but the last ending in \n. */
private fun lines(s: String): JsonArray {
    val out = mutableListOf<JsonElement>()
    var start = 0
    for (i in s.indices) if (s[i] == '\n') { out += JsonPrimitive(s.substring(start, i + 1)); start = i + 1 }
    if (start < s.length) out += JsonPrimitive(s.substring(start))
    return JsonArray(out)
}

private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
private fun s(v: String) = JsonPrimitive(v)

private fun jupyterOutputs(c: ExportCell): List<JsonElement> {
    val r = c.run ?: return emptyList()
    val out = mutableListOf<JsonElement>()
    val stdout = stdoutOf(r)
    if (stdout.isNotEmpty()) out += obj("output_type" to s("stream"), "name" to s("stdout"), "text" to lines(stdout))
    val stderr = stderrOf(r)
    if (stderr.isNotBlank() && !failed(r)) out += obj("output_type" to s("stream"), "name" to s("stderr"), "text" to lines(stderr))
    for (item in r.outputs?.items.orEmpty()) {
        val data = LinkedHashMap<String, JsonElement>()
        if (item.isTable) {
            data["text/html"] = lines(tableHtml(item))
            data["text/plain"] = lines(tableCsv(item))
        } else {
            val json = item.files["json"]?.let { c.files[it] }
            if (item.isChart && json != null) {
                val spec = Json.parseToJsonElement(json.decodeToString())
                if (item.lib == "plotly") data["application/vnd.plotly.v1+json"] = spec
                if (item.lib == "vega-lite") data["application/vnd.vegalite.v${item.version.orEmpty().split(".")[0].ifEmpty { "5" }}+json"] = spec
            }
            val still = stillFile(item)
            val bytes = still?.let { c.files[it.name] }
            if (still != null && bytes != null) data[still.mime] = if (still.mime == "image/svg+xml") lines(bytes.decodeToString()) else s(base64(bytes))
            data["text/plain"] = JsonArray(listOf(s(if (item.title.isNotEmpty()) "${outputLabel(item)}: ${item.title}" else outputLabel(item))))
        }
        out += obj("output_type" to s("display_data"), "data" to JsonObject(data), "metadata" to JsonObject(emptyMap()))
    }
    if (failed(r)) {
        val last = (r.errorLine ?: stderr.trim().split("\n").lastOrNull() ?: "Error").trim()
        val m = Regex("""^([\w.]+):\s*(.*)$""").find(last)
        val tb = if (stderr.isNotBlank()) stderr.trimEnd().split("\n") else listOf(last)
        out += obj("output_type" to s("error"), "ename" to s(m?.groupValues?.get(1) ?: "Error"), "evalue" to s(m?.groupValues?.get(2) ?: last), "traceback" to JsonArray(tb.map(::s)))
    }
    return out
}

/** The note as a Jupyter notebook (nbformat 4.5), written as JSON.stringify(notebook, null, 1). */
fun toNotebook(note: ExportNote): String {
    val language = notebookLanguage(note.cells)
    var n = 0
    fun id() = "bj-${(++n).toString().padStart(3, '0')}"
    fun md(text: String) = obj("cell_type" to s("markdown"), "id" to s(id()), "metadata" to JsonObject(emptyMap()), "source" to lines(text))
    val head = listOf("# ${note.title.ifEmpty { "Untitled" }}", outcomeLabel(note.outcome)?.let { "**Outcome:** $it" }.orEmpty(), plainLinks(note.body).trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    val cells = mutableListOf<JsonElement>(md(head))
    for (c in note.cells) {
        if (c.noteBefore.isNotBlank()) cells += md("**Before run**\n\n${plainLinks(c.noteBefore).trim()}")
        if (c.language == language) {
            val meta = LinkedHashMap<String, JsonElement>().apply { put("filename", s(c.filename)); c.run?.let { put("run", s(exportRunSummary(it))) } }
            cells += obj("cell_type" to s("code"), "id" to s(id()), "metadata" to obj("benchjot" to JsonObject(meta)), "execution_count" to JsonNull, "source" to lines(c.code), "outputs" to JsonArray(jupyterOutputs(c)))
        } else {
            val label = languageConfig(c.language).label
            val output = c.run?.let { (if (failed(it)) it.errorLine ?: stderrOf(it) else stdoutOf(it)).trim() }.orEmpty()
            cells += md(listOf("**${c.filename.ifEmpty { label }}** ($label)", "```${c.language}\n${c.code.removeSuffix("\n")}\n```", if (output.isNotEmpty()) "Output:\n\n```text\n$output\n```" else "").filter { it.isNotEmpty() }.joinToString("\n\n"))
        }
        if (c.noteAfter.isNotBlank()) cells += md("**After run**\n\n${plainLinks(c.noteAfter).trim()}")
    }
    cells += md("_Exported from Benchjot on ${note.exportedAt.take(10)}._")
    val k = KERNELS.getValue(language)
    val notebook = obj(
        "nbformat" to JsonPrimitive(4),
        "nbformat_minor" to JsonPrimitive(5),
        "metadata" to obj(
            "kernelspec" to obj("name" to s(k.name), "display_name" to s(k.displayName), "language" to s(k.language)),
            "language_info" to obj("name" to s(k.language.lowercase())),
            "benchjot" to obj("title" to s(note.title), "outcome" to (note.outcome?.takeIf { it.isNotEmpty() }?.let(::s) ?: JsonNull), "exported_at" to s(note.exportedAt)),
        ),
        "cells" to JsonArray(cells),
    )
    return JsJson.stringify(notebook, 1) + "\n"
}

// ------------------------------------------------------------------ web page

private const val PAGE_CSS = """
:root { color-scheme: light; --fg: #1a1a19; --muted: #5f5e5a; --line: #e2e1dc; --tint: #f4f3ef; }
* { box-sizing: border-box; }
body { margin: 0; background: #fff; color: var(--fg); font: 16px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width: 820px; margin: 0 auto; padding: 40px 20px 64px; }
h1 { font-size: 30px; line-height: 1.2; margin: 0 0 8px; }
.meta { color: var(--muted); font-size: 14px; margin: 0 0 24px; }
.cell { border-top: 1px solid var(--line); padding: 20px 0; }
.label { font: 11px/1 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); margin: 0 0 6px; }
.file { font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--muted); margin: 14px 0 6px; }
pre { background: var(--tint); border-radius: 8px; padding: 12px 14px; overflow-x: auto; font: 13.5px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre; }
pre.out { background: #fff; border: 1px solid var(--line); }
pre.err { border-color: #e3b7b3; }
figure { margin: 14px 0; }
figure img { display: block; max-width: 100%; height: auto; border: 1px solid var(--line); border-radius: 6px; }
figcaption, .more { color: var(--muted); font-size: 13px; margin-top: 6px; }
table { border-collapse: collapse; font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; display: block; overflow-x: auto; }
th, td { border-bottom: 1px solid var(--line); padding: 4px 10px; text-align: left; white-space: nowrap; }
th { color: var(--muted); font-weight: 500; }
.body p:first-child { margin-top: 0; }
footer { color: var(--muted); font-size: 13px; border-top: 1px solid var(--line); padding-top: 16px; margin-top: 24px; }
@media print { main { padding: 0; } pre { white-space: pre-wrap; } .cell { break-inside: avoid-page; } }
"""

/** The note as one self-contained web page: figures inside it, math via KaTeX's stylesheet when online. */
fun toWebPage(note: ExportNote, renderMarkdown: (String) -> String): String {
    fun block(md: String) = "<div class=\"body\">${renderMarkdown(plainLinks(md))}</div>"
    val parts = note.cells.map { c ->
        val cell = mutableListOf<String>()
        if (c.noteBefore.isNotBlank()) cell += "<p class=\"label\">Before run</p>${block(c.noteBefore)}"
        cell += "<p class=\"file\">${htmlEsc(c.filename.ifEmpty { "code" })} · ${htmlEsc(languageConfig(c.language).label)}</p><pre><code>${htmlEsc(c.code)}</code></pre>"
        c.run?.let { r ->
            val text = listOf(stdoutOf(r), if (failed(r)) stderrOf(r).ifEmpty { r.errorLine.orEmpty() } else stderrOf(r)).filter { it.isNotEmpty() }.joinToString("")
            if (text.isNotBlank()) cell += "<pre class=\"out${if (failed(r)) " err" else ""}\">${htmlEsc(text.trimEnd())}</pre>"
            cell += "<p class=\"more\">${htmlEsc(exportRunSummary(r))}</p>"
            for (item in r.outputs?.items.orEmpty()) {
                if (item.isTable) { cell += "<figure>${tableHtml(item)}</figure>"; continue }
                val still = stillFile(item)
                val bytes = still?.let { c.files[it.name] }
                val caption = htmlEsc(if (item.title.isNotEmpty()) "${outputLabel(item)} · ${item.title}" else outputLabel(item))
                cell += if (still != null && bytes != null) "<figure><img src=\"data:${still.mime};base64,${base64(bytes)}\" alt=\"$caption\"><figcaption>$caption</figcaption></figure>"
                else "<p class=\"more\">$caption (open the note in Benchjot to see it)</p>"
            }
        }
        if (c.noteAfter.isNotBlank()) cell += "<p class=\"label\">After run</p>${block(c.noteAfter)}"
        "<section class=\"cell\">${cell.joinToString("\n")}</section>"
    }
    val title = htmlEsc(note.title.ifEmpty { "Untitled" })
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.19.1/dist/katex.min.css">
<style>$PAGE_CSS</style>
</head>
<body>
<main>
<h1>$title</h1>
${outcomeLabel(note.outcome)?.let { "<p class=\"meta\">Outcome: $it</p>" }.orEmpty()}
${if (note.body.isNotBlank()) block(note.body) else ""}
${parts.joinToString("\n")}
<footer>Exported from Benchjot on ${note.exportedAt.take(10)}.</footer>
</main>
</body>
</html>
"""
}

// ------------------------------------------------------------------ Markdown

/** The Markdown export's files, in order: note.md, then each cell's figures and tables (the zip's notes). */
fun markdownFiles(note: ExportNote): List<Pair<String, ByteArray>> {
    val files = mutableListOf<Pair<String, ByteArray>>()
    val md = mutableListOf("# ${note.title.ifEmpty { "Untitled" }}")
    outcomeLabel(note.outcome)?.let { md += "**Outcome:** $it" }
    if (note.body.isNotBlank()) md += plainLinks(note.body).trim()
    note.cells.forEachIndexed { i, c ->
        val n = i + 1
        md += "## ${c.filename.ifEmpty { "Cell $n" }}"
        if (c.noteBefore.isNotBlank()) md += "**Before run**\n\n${plainLinks(c.noteBefore).trim()}"
        val fence = if (LANGUAGES.any { it.id == c.language }) c.language else ""
        md += "```$fence\n${c.code.removeSuffix("\n")}\n```"
        c.run?.let { r ->
            val text = (if (failed(r)) listOf(stdoutOf(r), stderrOf(r).ifEmpty { r.errorLine.orEmpty() }) else listOf(stdoutOf(r))).joinToString("").trimEnd()
            if (text.isNotEmpty()) md += "Output:\n\n```text\n$text\n```"
            md += "_${exportRunSummary(r)}_"
            r.outputs?.items.orEmpty().forEachIndexed { k, item ->
                val stem = "files/cell$n-${k + 1}"
                if (item.isTable) {
                    val csv = item.files["csv"]?.let { c.files[it] }
                    files += "$stem.csv" to (csv ?: tableCsv(item).toByteArray())
                    md += "${tableMarkdown(item)}\n\n[${outputLabel(item)} as CSV]($stem.csv)"
                    return@forEachIndexed
                }
                val still = stillFile(item)
                val bytes = still?.let { c.files[it.name] }
                val caption = if (item.title.isNotEmpty()) "${outputLabel(item)}: ${item.title}" else outputLabel(item)
                if (still != null && bytes != null) {
                    val ext = still.name.substring(still.name.lastIndexOf('.'))
                    files += "$stem$ext" to bytes
                    md += "![$caption]($stem$ext)"
                }
                for ((kind, name) in item.files) {
                    if (name == still?.name || kind == "mpl" || kind == "strip") continue
                    val data = c.files[name] ?: continue
                    files += "$stem.$kind" to data
                }
            }
        }
        if (c.noteAfter.isNotBlank()) md += "**After run**\n\n${plainLinks(c.noteAfter).trim()}"
    }
    md += "---\n\n_Exported from Benchjot on ${note.exportedAt.take(10)}._"
    return listOf("note.md" to (md.joinToString("\n\n") + "\n").toByteArray()) + files
}

/** The Markdown export zipped (note.md first). */
fun toMarkdownZip(note: ExportNote): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { z -> for ((name, data) in markdownFiles(note)) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry() } }
    return out.toByteArray()
}

/** JSON as JavaScript's JSON.stringify(value, null, indent) writes it (key order kept, numbers as JS prints them). */
object JsJson {
    fun stringify(v: JsonElement, indent: Int): String = StringBuilder().also { write(it, v, indent, 0) }.toString()

    private fun write(b: StringBuilder, v: JsonElement, indent: Int, depth: Int) {
        val pad = " ".repeat(indent * (depth + 1))
        val end = " ".repeat(indent * depth)
        when (v) {
            is JsonNull -> b.append("null")
            is JsonPrimitive -> if (v.isString) string(b, v.content) else b.append(number(v.content))
            is JsonArray -> if (v.isEmpty()) b.append("[]") else {
                b.append("[\n")
                v.forEachIndexed { i, e -> b.append(pad); write(b, e, indent, depth + 1); if (i < v.size - 1) b.append(","); b.append("\n") }
                b.append(end).append("]")
            }
            is JsonObject -> if (v.isEmpty()) b.append("{}") else {
                b.append("{\n")
                v.entries.forEachIndexed { i, (k, e) -> b.append(pad); string(b, k); b.append(": "); write(b, e, indent, depth + 1); if (i < v.size - 1) b.append(","); b.append("\n") }
                b.append(end).append("}")
            }
        }
    }

    /** true / false stay; a number prints as JavaScript's String(n) for the integers and decimals a spec holds. */
    private fun number(raw: String): String {
        if (raw == "true" || raw == "false") return raw
        val d = raw.toDoubleOrNull() ?: return raw
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e21) return java.math.BigDecimal(d).toPlainString()
        return java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()
    }

    private fun string(b: StringBuilder, s: String) {
        b.append('"')
        for (ch in s) when (ch) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            '\b' -> b.append("\\b")
            '\u000c' -> b.append("\\f")
            else -> if (ch < ' ') b.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else b.append(ch)
        }
        b.append('"')
    }
}
