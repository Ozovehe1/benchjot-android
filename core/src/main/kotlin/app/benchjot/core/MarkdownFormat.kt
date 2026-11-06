package app.benchjot.core

// The note body's Markdown format bar (the user's, 2026-11-05; Tier 1 tools 2026-11-06): a port of
// the web's src/lib/markdown-format.ts, checked by the markdown_format.json fixture (the web's
// hand-written cases). Wraps toggle inline markers around the selection (or the cursor); line
// commands rewrite the leading marker of each selected line; block commands put a divider or a
// table on their own lines.
//
// Cycles: heading none -> H1 -> H2 -> H3 -> none; list none -> bullet -> numbered -> checklist -> none.
// The state is read from the first non-empty selected line and the next state is applied to every
// non-empty selected line (an empty line counts when it is the only one).

/** Every format command, in format-bar order (Undo and Redo come first in the bar but are not formats). */
enum class MarkdownFormat(val id: String) {
    Heading("heading"), Bold("bold"), List("list"), Checklist("checklist"),
    Italic("italic"), Strike("strike"), Quote("quote"), Indent("indent"), Outdent("outdent"), Code("code"), Math("math"), Link("link"),
    Divider("divider"), Table("table");

    companion object {
        fun of(id: String): MarkdownFormat = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown format $id")
    }
}

/** Formats that live in the bar's "+" sheet rather than the row. */
val SHEET_FORMATS: kotlin.collections.List<MarkdownFormat> = listOf(MarkdownFormat.Divider, MarkdownFormat.Table)

/** Text and the selection after an edit (start == end: a cursor). */
data class FormatEdit(val text: String, val start: Int, val end: Int)

private val WRAP = mapOf(
    MarkdownFormat.Bold to "**", MarkdownFormat.Italic to "_", MarkdownFormat.Strike to "~~",
    MarkdownFormat.Code to "`", MarkdownFormat.Math to "$",
)
private const val LINK_URL = "url"
private const val INDENT = "  "
const val DIVIDER_BLOCK = "---"
const val TABLE_BLOCK = "| Column 1 | Column 2 |\n| --- | --- |\n|  |  |"
private val LINK = Regex("""^\[([^\]]*)\]\(([^)]*)\)$""")
private val LINK_TAIL = Regex("""^\]\([^)\n]*\)""")
private val HEADING_PREFIX = Regex("""^#{1,6} """)
private val QUOTE_PREFIX = Regex("""^> ?""")
private val CHECK_SHORTCUT = Regex("""^( *)\[\] $""")

private val WRAP_KIND = mapOf("**" to LiveKind.Bold, "_" to LiveKind.Italic, "~~" to LiveKind.Strike, "`" to LiveKind.Code, "$" to LiveKind.Math)

private data class WrapToken(val from: Int, val to: Int, val open: Int, val close: Int)

/** The token of `kind` whose content holds [start, end], with its marker lengths (from the live view). */
private fun enclosing(text: String, start: Int, end: Int, kind: LiveKind): WrapToken? {
    val spans = liveSpans(text, start, end)
    val t = spans.firstOrNull { it.kind == kind && it.from <= start && it.to >= end } ?: return null
    val open = spans.firstOrNull { it.kind == LiveKind.Marker && it.to == t.from }
    val close = spans.firstOrNull { it.kind == LiveKind.Marker && it.from == t.to }
    return if (open != null && close != null) WrapToken(t.from, t.to, open.to - open.from, close.to - close.from) else null
}

private fun wrap(e: FormatEdit, m: String): FormatEdit {
    val (text, start, end) = e
    val sel = text.substring(start, end)
    // The cursor or selection is inside a token of this format (e.g. **bo|ld**): take its markers off.
    val t = enclosing(text, start, end, WRAP_KIND.getValue(m))
    if (t != null && !(start == t.from && end == t.to && t.from == t.to)) {
        val out = text.substring(0, t.from - t.open) + text.substring(t.from, t.to) + text.substring(t.to + t.close)
        return FormatEdit(out, start - t.open, end - t.open)
    }
    // The selection includes the markers: take them off.
    if (sel.length >= 2 * m.length && sel.startsWith(m) && sel.endsWith(m)) {
        val inner = sel.substring(m.length, sel.length - m.length)
        return FormatEdit(text.substring(0, start) + inner + text.substring(end), start, start + inner.length)
    }
    // The markers sit right around the selection (or the cursor): take them off.
    if (start >= m.length && text.substring(start - m.length, start) == m && text.startsWith(m, end)) {
        return FormatEdit(text.substring(0, start - m.length) + sel + text.substring(end + m.length), start - m.length, end - m.length)
    }
    return FormatEdit(text.substring(0, start) + m + sel + m + text.substring(end), start + m.length, end + m.length)
}

private fun link(e: FormatEdit): FormatEdit {
    val (text, start, end) = e
    val sel = text.substring(start, end)
    LINK.find(sel)?.let { m ->
        val label = m.groupValues[1]
        return FormatEdit(text.substring(0, start) + label + text.substring(end), start, start + label.length)
    }
    // The cursor or selection is a link's label: [label](...) around it.
    if (start > 0 && text[start - 1] == '[') {
        LINK_TAIL.find(text.substring(end))?.let { a ->
            return FormatEdit(text.substring(0, start - 1) + sel + text.substring(end + a.value.length), start - 1, end - 1)
        }
    }
    if (sel.isEmpty()) return FormatEdit(text.substring(0, start) + "[]($LINK_URL)" + text.substring(end), start + 1, start + 1)
    val url = start + sel.length + 3
    return FormatEdit(text.substring(0, start) + "[$sel]($LINK_URL)" + text.substring(end), url, url + LINK_URL.length)
}

// ---- line commands ----

private data class Line(val from: Int, val text: String)

/** The selected lines. A selection ending right after a newline does not take the next line. */
private fun selectedLines(e: FormatEdit): kotlin.collections.List<Line> {
    val (text, start, end) = e
    val from = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    val nl = text.indexOf('\n', if (end > start && text[end - 1] == '\n') end - 1 else end)
    val to = if (nl == -1) text.length else nl
    var at = from
    return text.substring(from, to).split("\n").map { t -> Line(at, t).also { at += t.length + 1 } }
}

private data class Rewrite(val cut: Int, val put: String)

/**
 * Rewrites each line's leading `cut` characters as `put` (null: line untouched). A position inside
 * the cut part moves to the end of the new prefix; later positions shift with it.
 */
private fun rewrite(e: FormatEdit, lines: kotlin.collections.List<Line>, fn: (Line, Int) -> Rewrite?): FormatEdit {
    var text = e.text
    var start = e.start
    var end = e.end
    // Right to left so earlier offsets stay valid.
    for (i in lines.indices.reversed()) {
        val l = lines[i]
        val r = fn(l, i) ?: continue
        val a = l.from
        val b = l.from + r.cut
        text = text.substring(0, a) + r.put + text.substring(b)
        fun map(p: Int) = if (p < a) p else if (p < b) a + r.put.length else p - r.cut + r.put.length
        start = map(start)
        end = map(end)
    }
    return FormatEdit(text, start, end)
}

/** Lines a line command acts on: the non-empty ones, or the only one (ascending). */
private fun targets(lines: kotlin.collections.List<Line>): Set<Int> =
    lines.indices.filter { lines[it].text.isNotBlank() || lines.size == 1 }.toCollection(LinkedHashSet())


private fun heading(e: FormatEdit): FormatEdit {
    val lines = selectedLines(e)
    val on = targets(lines)
    val first = lines[on.firstOrNull() ?: 0]
    val level = HEADING_PREFIX.find(first.text)?.value?.length ?: 0 // "# " = 2
    val cur = if (level == 0) 0 else minOf(level - 1, 3)
    val next = (cur + 1) % 4
    val put = if (next == 0) "" else "#".repeat(next) + " "
    return rewrite(e, lines) { l, i -> if (i in on) Rewrite(HEADING_PREFIX.find(l.text)?.value?.length ?: 0, put) else null }
}

private fun marker(kind: ListKind?, n: Int, done: Boolean = false): String = when (kind) {
    ListKind.Bullet -> "- "
    ListKind.Ordered -> "$n. "
    ListKind.Check -> if (done) "- [x] " else "- [ ] "
    null -> ""
}

/** Sets every target line's list marker to `kind` (keeping indent; numbered lines count 1, 2, …). */
private fun setList(e: FormatEdit, lines: kotlin.collections.List<Line>, on: Set<Int>, kind: ListKind?): FormatEdit {
    val n = HashMap<Int, Int>()
    var count = 0
    lines.indices.forEach { if (it in on) n[it] = ++count }
    return rewrite(e, lines) { l, i ->
        if (i !in on) return@rewrite null
        val m = listMarker(l.text)
        val indent = m?.indent ?: 0
        val keepDone = kind == ListKind.Check && m?.list == ListKind.Check && m.done
        Rewrite(indent + (m?.marker?.length ?: 0), " ".repeat(indent) + marker(kind, n.getValue(i), keepDone))
    }
}

private fun nextList(k: ListKind?): ListKind? = when (k) {
    null -> ListKind.Bullet
    ListKind.Bullet -> ListKind.Ordered
    ListKind.Ordered -> ListKind.Check
    ListKind.Check -> null
}

private fun list(e: FormatEdit): FormatEdit {
    val lines = selectedLines(e)
    val on = targets(lines)
    val first = lines[on.firstOrNull() ?: 0]
    return setList(e, lines, on, nextList(listMarker(first.text)?.list))
}

private fun checklist(e: FormatEdit): FormatEdit {
    val lines = selectedLines(e)
    val on = targets(lines)
    val all = on.all { listMarker(lines[it].text)?.list == ListKind.Check }
    return setList(e, lines, on, if (all) null else ListKind.Check)
}

private fun quote(e: FormatEdit): FormatEdit {
    val lines = selectedLines(e)
    val on = targets(lines)
    val off = on.all { QUOTE_PREFIX.containsMatchIn(lines[it].text) }
    return rewrite(e, lines) { l, i ->
        when {
            i !in on -> null
            off -> Rewrite(QUOTE_PREFIX.find(l.text)!!.value.length, "")
            QUOTE_PREFIX.containsMatchIn(l.text) -> null
            else -> Rewrite(0, "> ")
        }
    }
}

/** Indent / outdent: list lines only, 2 spaces a level, clamped at 0. */
private fun shift(e: FormatEdit, by: Int): FormatEdit {
    val lines = selectedLines(e)
    return rewrite(e, lines) { l, _ ->
        val m = listMarker(l.text) ?: return@rewrite null
        if (by == 1) return@rewrite Rewrite(0, INDENT)
        val cut = minOf(m.indent, INDENT.length)
        if (cut == 0) null else Rewrite(cut, "")
    }
}

// ---- block inserts ----

/**
 * Puts `block` on its own lines at the cursor's line (the selection end's), with a blank line before
 * it (so `---` never turns the line above into a heading) and the cursor on an empty line after it.
 * `select` picks a range inside the block to select instead (relative to the block's start).
 */
private fun insertBlock(e: FormatEdit, block: String, select: IntRange? = null): FormatEdit {
    val (text, _, end) = e
    val lines = classifyLines(text)
    val idx = lines.indexOfFirst { end <= it.to }
    val l = lines[if (idx == -1) lines.size - 1 else idx]
    val blank = text.substring(l.from, l.to).isBlank()
    val prev = if (idx > 0) lines[idx - 1] else null
    val prevBlank = prev == null || text.substring(prev.from, prev.to).isBlank()
    val at: Int
    val out: String
    if (blank && prevBlank) {
        at = l.from
        out = text.substring(0, l.from) + block + "\n" + text.substring(l.to)
    } else {
        val sep = if (blank) "\n" else "\n\n"
        at = l.to + sep.length
        out = text.substring(0, l.to) + sep + block + "\n" + text.substring(l.to)
    }
    if (select != null) return FormatEdit(out, at + select.first, at + select.last)
    val caret = at + block.length + 1
    return FormatEdit(out, caret, caret)
}

/** [2, 10): "Column 1" in TABLE_BLOCK (first..last as start..end offsets). */
private val TABLE_FIRST_CELL = 2..(2 + "Column 1".length)

/** Applies one format command to `text` with the selection [start, end). */
fun applyFormat(text: String, start: Int, end: Int, format: MarkdownFormat): FormatEdit {
    val a = maxOf(0, minOf(start, end, text.length))
    val b = minOf(text.length, maxOf(start, end))
    val e = FormatEdit(text, a, b)
    return when (format) {
        MarkdownFormat.Link -> link(e)
        MarkdownFormat.Heading -> heading(e)
        MarkdownFormat.List -> list(e)
        MarkdownFormat.Checklist -> checklist(e)
        MarkdownFormat.Quote -> quote(e)
        MarkdownFormat.Indent -> shift(e, 1)
        MarkdownFormat.Outdent -> shift(e, -1)
        MarkdownFormat.Divider -> insertBlock(e, DIVIDER_BLOCK)
        MarkdownFormat.Table -> insertBlock(e, TABLE_BLOCK, TABLE_FIRST_CELL)
        else -> wrap(e, WRAP.getValue(format))
    }
}

/** Typing shortcut: `[] ` at a line start becomes `- [ ] `. Returns the edit, or null when it doesn't apply. */
fun expandCheckShortcut(text: String, caret: Int): FormatEdit? {
    if (caret < 0 || caret > text.length) return null
    val from = if (caret == 0) 0 else text.lastIndexOf('\n', caret - 1) + 1
    val m = CHECK_SHORTCUT.find(text.substring(from, caret)) ?: return null
    if (classifyLines(text).firstOrNull { it.from == from }?.block == Block.Fence) return null
    val put = m.groupValues[1] + "- [ ] "
    return FormatEdit(text.substring(0, from) + put + text.substring(caret), from + put.length, from + put.length)
}
