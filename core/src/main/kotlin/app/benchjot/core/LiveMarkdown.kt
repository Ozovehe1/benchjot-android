package app.benchjot.core

// The note body's live preview (the user's, 2026-11-06): the body stays Markdown text and the
// editor shows it styled while typing, Obsidian-style. A line-for-line port of the web's
// src/lib/live-markdown.ts; tests/fixtures/live_markdown.json (copied to web/live_markdown.json)
// is the authority for both platforms. Everything here is pure; the Compose editor only paints it.
//
// Rules (as the web writes them):
// - Active lines are the lines touched by [activeFrom, activeTo]; activeFrom < 0 means none (no focus).
// - Markers on active lines are kind "marker" (shown dimmed), on other lines "hide". Inline tokens use
//   the whole-active-line variant: a token's markers show when its line is active.
// - Block spans cover the line content after the marker. List spans (bullet, ordered, check_open,
//   check_done) cover the list MARKER itself on inactive lines (the platform draws •, the number, or a
//   tappable checkbox there); on active lines that marker is "marker". A done item's content is also
//   "done" (struck, dimmed). Leading list indent on inactive lines is "indent" (2 spaces per level).
// - "fence" lines (``` … ```) and "table" lines are whole-line monospace, never parsed or hidden.
// - Spans are sorted by from, then longer first, then kind; empty spans are never emitted.

enum class LiveKind(val id: String) {
    Marker("marker"), Hide("hide"),
    H1("h1"), H2("h2"), H3("h3"), Quote("quote"),
    Bullet("bullet"), Ordered("ordered"), CheckOpen("check_open"), CheckDone("check_done"), Done("done"), Indent("indent"),
    Divider("divider"), Fence("fence"), Table("table"),
    Bold("bold"), Italic("italic"), Strike("strike"), Code("code"), Math("math"), Link("link"), Wikilink("wikilink");

    companion object {
        fun of(id: String): LiveKind = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown live kind $id")
    }
}

data class LiveSpan(val from: Int, val to: Int, val kind: LiveKind)

enum class ListKind(val id: String) { Bullet("bullet"), Ordered("ordered"), Check("check") }

/** A list marker at the start of a line: `marker` is the text after the indent ("- ", "1. ", "- [x] "). */
data class ListMarker(val list: ListKind, val indent: Int, val marker: String, val number: Int? = null, val done: Boolean = false)

sealed interface Block {
    data object Plain : Block
    data class Heading(val level: Int) : Block
    data object Quote : Block
    data class ListItem(val m: ListMarker) : Block
    data object Divider : Block
    data object Fence : Block
    data object Table : Block
}

/** One line of the body, classified. Offsets are absolute; `content` is where the text after the marker starts. */
data class LineInfo(val from: Int, val to: Int, val content: Int, val block: Block)

private val FENCE = Regex("""^ {0,3}```""")
private val HEADING = Regex("""^(#{1,3}) """)
private val QUOTE = Regex("""^> ?""")
private val CHECK = Regex("""^( *)([-*]) \[([ xX])\](?: |$)""")
private val ORDERED = Regex("""^( *)(\d{1,9})\. """)
private val BULLET = Regex("""^( *)([-*]) """)
private val DIVIDER = Regex("""^-{3,}\s*$""")
private val TABLE = Regex("""^\s*\|.*\|\s*$""")
private val MD_LINK = Regex("""^\[([^\]\n]+)\]\(([^)\s]*)\)""")
private val WORD = Regex("""[\p{L}\p{N}_]""")

private fun isWord(c: Char?) = c != null && WORD.matches(c.toString())

/** The list marker at the start of a line, if any (also used by the format commands). */
fun listMarker(line: String): ListMarker? {
    CHECK.find(line)?.let { m ->
        val ind = m.groupValues[1].length
        return ListMarker(ListKind.Check, ind, m.value.substring(ind), done = m.groupValues[3] != " ")
    }
    ORDERED.find(line)?.let { m ->
        val ind = m.groupValues[1].length
        return ListMarker(ListKind.Ordered, ind, m.value.substring(ind), number = m.groupValues[2].toInt())
    }
    if (DIVIDER.containsMatchIn(line)) return null
    BULLET.find(line)?.let { m ->
        val ind = m.groupValues[1].length
        return ListMarker(ListKind.Bullet, ind, m.value.substring(ind))
    }
    return null
}

/** Splits the body into classified lines, tracking ``` fences. */
fun classifyLines(text: String): List<LineInfo> {
    val out = ArrayList<LineInfo>()
    var fenced = false
    var from = 0
    for (line in text.split("\n")) {
        val to = from + line.length
        var block: Block = Block.Plain
        var content = from
        val fenceLine = FENCE.containsMatchIn(line)
        if (fenced || fenceLine) {
            if (fenceLine) fenced = !fenced
            block = Block.Fence
        } else if (TABLE.containsMatchIn(line)) block = Block.Table
        else if (DIVIDER.containsMatchIn(line)) block = Block.Divider
        else {
            val h = HEADING.find(line)
            val l = if (h != null) null else listMarker(line)
            val q = if (h != null || l != null) null else QUOTE.find(line)
            when {
                h != null -> { block = Block.Heading(h.groupValues[1].length); content = from + h.value.length }
                l != null -> { block = Block.ListItem(l); content = from + l.indent + l.marker.length }
                q != null -> { block = Block.Quote; content = from + q.value.length }
            }
        }
        out += LineInfo(from, to, content, block)
        from = to + 1
    }
    return out
}

/** The line containing `pos` (clamped to the text). */
fun lineAt(lines: List<LineInfo>, pos: Int): LineInfo = lines.firstOrNull { pos <= it.to } ?: lines.last()

// ---- inline tokens ----

private fun closeOf(text: String, m: String, from: Int, end: Int): Int {
    var i = text.indexOf(m, from)
    while (i != -1 && i + m.length <= end) {
        if (i > from && text[i - 1] != ' ') return i
        i = text.indexOf(m, i + 1)
    }
    return -1
}

private fun MutableList<LiveSpan>.span(a: Int, b: Int, kind: LiveKind) { if (b > a) add(LiveSpan(a, b, kind)) }

/** Inline spans for text[from, end) (one line's content). `mark` is Marker or Hide. */
private fun inline(text: String, from: Int, end: Int, mark: LiveKind, out: MutableList<LiveSpan>) {
    var i = from
    while (i < end) {
        val c = text[i]
        val rest = text.substring(i, end)
        // `code`: no parsing inside.
        if (c == '`') {
            val j = text.indexOf('`', i + 1)
            if (j != -1 && j < end && j > i + 1) {
                out.span(i, i + 1, mark); out.span(i + 1, j, LiveKind.Code); out.span(j, j + 1, mark)
                i = j + 1
                continue
            }
        }
        // $math$: not $$, opener followed and closer preceded by non-space.
        if (c == '$' && text.getOrNull(i + 1) != '$' && text.getOrNull(i + 1) != ' ' && i + 1 < end) {
            var j = text.indexOf('$', i + 1)
            while (j != -1 && j < end && text[j - 1] == '\\') j = text.indexOf('$', j + 1)
            if (j != -1 && j < end && j > i + 1 && text[j - 1] != ' ') {
                out.span(i, i + 1, mark); out.span(i + 1, j, LiveKind.Math); out.span(j, j + 1, mark)
                i = j + 1
                continue
            }
        }
        // [[Title|id]] note links: only the title shows.
        if (rest.startsWith("[[")) {
            val m = LINK_REGEX.find(rest)
            if (m != null && m.range.first == 0) {
                val title = m.groupValues[1]
                out.span(i, i + 2, mark); out.span(i + 2, i + 2 + title.length, LiveKind.Wikilink); out.span(i + 2 + title.length, i + m.value.length, mark)
                i += m.value.length
                continue
            }
        }
        // [label](url)
        if (c == '[') {
            val m = MD_LINK.find(rest)
            if (m != null) {
                val lb = i + 1 + m.groupValues[1].length
                out.span(i, i + 1, mark)
                inline(text, i + 1, lb, mark, out)
                out.span(i + 1, lb, LiveKind.Link)
                out.span(lb, i + m.value.length, mark)
                i += m.value.length
                continue
            }
        }
        // **bold**, ~~strike~~, _italic_ / *italic*
        val pair = when {
            rest.startsWith("**") -> "**"
            rest.startsWith("~~") -> "~~"
            c == '_' || c == '*' -> c.toString()
            else -> null
        }
        if (pair != null && text.getOrNull(i + pair.length) != null && text[i + pair.length] != ' ' && i + pair.length < end) {
            val intraword = pair == "_" && i > 0 && isWord(text[i - 1])
            val j = if (intraword) -1 else closeOf(text, pair, i + pair.length, end)
            val after = if (j == -1) null else text.getOrNull(j + pair.length)
            if (j != -1 && !(pair == "_" && isWord(after)) && !(pair == "*" && text.getOrNull(j + 1) == '*')) {
                val kind = when (pair) { "**" -> LiveKind.Bold; "~~" -> LiveKind.Strike; else -> LiveKind.Italic }
                out.span(i, i + pair.length, mark)
                out.span(i + pair.length, j, kind)
                inline(text, i + pair.length, j, mark, out)
                out.span(j, j + pair.length, mark)
                i = j + pair.length
                continue
            }
            i += pair.length
            continue
        }
        i++
    }
}

private val SPAN_ORDER = compareBy<LiveSpan> { it.from }.thenByDescending { it.to }.thenBy { it.kind.id }

/** Lines touched by [activeFrom, activeTo]. activeFrom < 0: none. */
private fun activeTest(activeFrom: Int, activeTo: Int): (LineInfo) -> Boolean {
    if (activeFrom < 0) return { false }
    val a = minOf(activeFrom, activeTo)
    val b = maxOf(activeFrom, activeTo)
    return { l -> l.from <= b && l.to >= a }
}

/** The live view of `text`: which ranges to hide, dim or style. See the rules at the top. */
fun liveSpans(text: String, activeFrom: Int, activeTo: Int): List<LiveSpan> {
    val isActive = activeTest(activeFrom, activeTo)
    val out = ArrayList<LiveSpan>()
    for (l in classifyLines(text)) {
        val active = isActive(l)
        val mark = if (active) LiveKind.Marker else LiveKind.Hide
        when (val b = l.block) {
            Block.Fence -> { out.span(l.from, l.to, LiveKind.Fence); continue }
            Block.Table -> { out.span(l.from, l.to, LiveKind.Table); continue }
            Block.Divider -> { out.span(l.from, l.to, if (active) LiveKind.Marker else LiveKind.Divider); continue }
            is Block.Heading -> {
                out.span(l.from, l.content, mark)
                out.span(l.content, l.to, when (b.level) { 1 -> LiveKind.H1; 2 -> LiveKind.H2; else -> LiveKind.H3 })
            }
            Block.Quote -> { out.span(l.from, l.content, mark); out.span(l.content, l.to, LiveKind.Quote) }
            is Block.ListItem -> {
                val m = l.from + b.m.indent
                if (!active) out.span(l.from, m, LiveKind.Indent)
                val kind = when {
                    active -> LiveKind.Marker
                    b.m.list == ListKind.Check -> if (b.m.done) LiveKind.CheckDone else LiveKind.CheckOpen
                    b.m.list == ListKind.Ordered -> LiveKind.Ordered
                    else -> LiveKind.Bullet
                }
                out.span(m, l.content, kind)
                if (b.m.list == ListKind.Check && b.m.done) out.span(l.content, l.to, LiveKind.Done)
            }
            Block.Plain -> Unit
        }
        inline(text, l.content, l.to, mark, out)
    }
    return out.sortedWith(SPAN_ORDER)
}

/** Flips `[ ]` <-> `[x]` on the checklist line containing `lineStart`; any other line: unchanged. */
fun toggleCheck(text: String, lineStart: Int): String {
    val l = lineAt(classifyLines(text), maxOf(0, lineStart))
    val b = l.block as? Block.ListItem ?: return text
    if (b.m.list != ListKind.Check) return text
    val box = l.from + b.m.indent + b.m.marker.indexOf('[') + 1
    return text.substring(0, box) + (if (b.m.done) " " else "x") + text.substring(box + 1)
}

/**
 * Enter at `caret` on a list line: continues the list (same indent, next number, a new `- [ ] `),
 * or, on an empty item, ends the list (the marker goes, no new line). Null: let Enter insert a
 * plain newline (not a list line, caret inside the marker, or inside a ``` fence).
 */
fun continueList(text: String, caret: Int): FormatEdit? {
    if (caret < 0 || caret > text.length) return null
    val l = lineAt(classifyLines(text), caret)
    val b = l.block as? Block.ListItem ?: return null
    if (caret < l.content) return null
    if (text.substring(l.content, l.to).isBlank()) {
        return FormatEdit(text.substring(0, l.from) + text.substring(l.to), l.from, l.from)
    }
    val bullet = b.m.marker[0]
    val next = when (b.m.list) {
        ListKind.Ordered -> "${(b.m.number ?: 0) + 1}. "
        ListKind.Check -> "$bullet [ ] "
        ListKind.Bullet -> "$bullet "
    }
    val ins = "\n" + " ".repeat(b.m.indent) + next
    val at = caret + ins.length
    return FormatEdit(text.substring(0, caret) + ins + text.substring(caret), at, at)
}

/** What the format bar lights up for the selection [start, end]. heading 0 = none. */
data class FormatState(
    val heading: Int = 0, val list: ListKind? = null, val quote: Boolean = false,
    val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false,
    val code: Boolean = false, val math: Boolean = false, val link: Boolean = false,
)

/** The formats at the cursor: block state from the first selected line, inline from the spans around `start`. */
fun formatState(text: String, start: Int, end: Int): FormatState {
    val l = lineAt(classifyLines(text), maxOf(0, minOf(start, end)))
    val b = l.block
    val spans = liveSpans(text, start, end)
    val a = minOf(start, end)
    val z = maxOf(start, end)
    fun inside(k: LiveKind) = spans.any { it.kind == k && it.from <= a && it.to >= z }
    return FormatState(
        heading = (b as? Block.Heading)?.level ?: 0,
        list = (b as? Block.ListItem)?.m?.list,
        quote = b == Block.Quote,
        bold = inside(LiveKind.Bold), italic = inside(LiveKind.Italic), strike = inside(LiveKind.Strike),
        code = inside(LiveKind.Code), math = inside(LiveKind.Math), link = inside(LiveKind.Link) || inside(LiveKind.Wikilink),
    )
}
