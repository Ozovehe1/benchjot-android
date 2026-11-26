package app.benchjot.core

// A note's table of contents, built as Jupyter builds its own (the web's lib/notes/outline.ts): from
// the Markdown headings the person wrote (# Title, ## Section, ### Part), in page order: the note's
// text, then each cell's "Note before run" and "Note after run". Code cells aren't listed and nothing
// is guessed from code. Pure.

sealed interface ContentsPlace {
    data object Body : ContentsPlace
    data class InCell(val id: String, val side: String) : ContentsPlace
}

/**
 * One entry: a heading (its level 1–3, its text as read, where it is, which heading of that place it is),
 * or a code cell ([cellId] set: "Cell n" and its title, at the level under the heading above it).
 */
data class ContentsItem(val key: String, val level: Int, val text: String, val place: ContentsPlace, val index: Int, val cellId: String? = null, val n: Int = 0)

private val HEADING = Regex("""^(#{1,3}) +(.+?)\s*#*\s*$""")
private val FENCE = Regex("""^ {0,3}(```|~~~)""")

/** A heading's words without Markdown marks: [[Title|id]] → Title, [text](url) → text, `code`, **bold**, _italic_, ~~strike~~. */
fun plainHeading(text: String): String = text
    .replace(Regex("""\[\[([^\]|]+)(\|[^\]]*)?\]\]"""), "$1")
    .replace(Regex("""!?\[([^\]]*)\]\([^)]*\)"""), "$1")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("""(\*\*|__|~~)(.+?)\1"""), "$2")
    .replace(Regex("""(^|\W)[*_](\S(?:.*?\S)?)[*_](?=\W|$)"""), "$1$2")
    .replace(Regex("""\s+"""), " ")
    .trim()

/** The headings in one Markdown text, in order (level, text). */
fun headingsOf(markdown: String): List<Pair<Int, String>> {
    val out = mutableListOf<Pair<Int, String>>()
    var fenced = false
    for (line in markdown.split("\n")) {
        if (FENCE.containsMatchIn(line)) { fenced = !fenced; continue }
        if (fenced) continue
        val m = HEADING.find(line) ?: continue
        val text = plainHeading(m.groupValues[2])
        if (text.isNotEmpty()) out += m.groupValues[1].length to text
    }
    return out
}

/** The note's table of contents: the text's headings, then for each cell its before-note's headings, the cell, its after-note's headings. */
fun contents(body: String, cells: List<Triple<String, String?, String?>>, titles: List<String> = emptyList()): List<ContentsItem> {
    val out = mutableListOf<ContentsItem>()
    var under = 0
    fun add(md: String?, place: ContentsPlace, key: String) =
        headingsOf(md.orEmpty()).forEachIndexed { i, (level, text) -> out += ContentsItem("$key:$i", level, text, place, i); under = level }
    add(body, ContentsPlace.Body, "body")
    cells.forEachIndexed { k, (id, before, after) ->
        add(before, ContentsPlace.InCell(id, "before"), "$id:before")
        out += ContentsItem("$id:cell", under + 1, titles.getOrNull(k).orEmpty().trim(), ContentsPlace.InCell(id, "cell"), 0, cellId = id, n = k + 1)
        add(after, ContentsPlace.InCell(id, "after"), "$id:after")
    }
    return out
}
