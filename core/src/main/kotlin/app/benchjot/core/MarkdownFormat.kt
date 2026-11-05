package app.benchjot.core

// The note body's Markdown format bar: the web's src/lib/markdown-format.ts, checked by the
// markdown_format.json fixture (the web's own answers on the shared cases). One pure function for
// every button: wrap the selection (or insert the markers with the cursor between them),
// List / Heading as a line prefix, and toggle off when the format is already there.

enum class MarkdownFormat(val id: String) { Bold("bold"), Italic("italic"), Heading("heading"), List("list"), Code("code"), Math("math"), Link("link") }

/** Text and the selection after an edit (start == end: a cursor). */
data class FormatEdit(val text: String, val start: Int, val end: Int)

private val WRAP = mapOf(MarkdownFormat.Bold to "**", MarkdownFormat.Italic to "_", MarkdownFormat.Code to "`", MarkdownFormat.Math to "$")
private val PREFIX = mapOf(MarkdownFormat.Heading to "# ", MarkdownFormat.List to "- ")
private const val LINK_URL = "url"
private val LINK = Regex("""^\[([^\]]*)\]\(([^)]*)\)$""")
private val LINK_TAIL = Regex("""^\]\([^)\n]*\)""")

private fun wrap(text: String, start: Int, end: Int, m: String): FormatEdit {
    val sel = text.substring(start, end)
    if (sel.length >= 2 * m.length && sel.startsWith(m) && sel.endsWith(m)) {
        val inner = sel.substring(m.length, sel.length - m.length)
        return FormatEdit(text.substring(0, start) + inner + text.substring(end), start, start + inner.length)
    }
    if (start >= m.length && text.substring(start - m.length, start) == m && text.startsWith(m, end)) {
        return FormatEdit(text.substring(0, start - m.length) + sel + text.substring(end + m.length), start - m.length, end - m.length)
    }
    return FormatEdit(text.substring(0, start) + m + sel + m + text.substring(end), start + m.length, end + m.length)
}

private fun prefix(text: String, start: Int, end: Int, p: String): FormatEdit {
    val from = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    val nl = text.indexOf('\n', if (end > start && text[end - 1] == '\n') end - 1 else end)
    val to = if (nl == -1) text.length else nl
    val lines = text.substring(from, to).split("\n")
    val used = lines.filter { it.isNotBlank() }
    val off = used.isNotEmpty() && used.all { it.startsWith(p) }
    var delta = 0
    var first = 0
    val out = lines.mapIndexed { i, l ->
        val next = when {
            off -> if (l.startsWith(p)) l.substring(p.length) else l
            !l.startsWith(p) && (l.isNotBlank() || lines.size == 1) -> p + l
            else -> l
        }
        if (i == 0) first = next.length - l.length
        delta += next.length - l.length
        next
    }
    return FormatEdit(text.substring(0, from) + out.joinToString("\n") + text.substring(to), maxOf(from, start + first), maxOf(from, end + delta))
}

private fun link(text: String, start: Int, end: Int): FormatEdit {
    val sel = text.substring(start, end)
    LINK.find(sel)?.let { m ->
        val label = m.groupValues[1]
        return FormatEdit(text.substring(0, start) + label + text.substring(end), start, start + label.length)
    }
    if (start > 0 && text[start - 1] == '[') {
        LINK_TAIL.find(text.substring(end))?.let { a ->
            return FormatEdit(text.substring(0, start - 1) + sel + text.substring(end + a.value.length), start - 1, end - 1)
        }
    }
    if (sel.isEmpty()) return FormatEdit(text.substring(0, start) + "[]($LINK_URL)" + text.substring(end), start + 1, start + 1)
    val url = start + sel.length + 3
    return FormatEdit(text.substring(0, start) + "[$sel]($LINK_URL)" + text.substring(end), url, url + LINK_URL.length)
}

/** Applies one format button to `text` with the selection [start, end). */
fun applyFormat(text: String, start: Int, end: Int, format: MarkdownFormat): FormatEdit {
    val a = maxOf(0, minOf(start, end, text.length))
    val b = minOf(text.length, maxOf(start, end))
    if (format == MarkdownFormat.Link) return link(text, a, b)
    PREFIX[format]?.let { return prefix(text, a, b, it) }
    return wrap(text, a, b, WRAP.getValue(format))
}
