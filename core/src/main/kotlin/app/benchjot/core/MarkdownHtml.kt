package app.benchjot.core

// Markdown to HTML for the exported web page's text (the note body and cell notes). The web renders
// it with its own Markdown engine; the phone's export uses this: headings, paragraphs, lists, quotes,
// fenced code, `code`, **bold**, *italic*, and links that open (http, https, mailto). Everything else
// is shown as written, escaped: no raw HTML from a note reaches the page. Math stays as its TeX.

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val CODE_SPAN = Regex("`([^`]+)`")
private val LINK = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")
private val BOLD = Regex("""\*\*([^*]+)\*\*""")
private val ITALIC = Regex("""(?<![\w*])[*_]([^*_]+)[*_](?![\w*])""")

/** One line's inline Markdown as HTML (escaped first, so nothing in the note becomes markup by accident). */
fun inlineHtml(text: String): String {
    val codes = mutableListOf<String>()
    var s = CODE_SPAN.replace(text) { codes += it.groupValues[1]; "\u0000${codes.size - 1}\u0000" }
    s = esc(s)
    s = LINK.replace(s) { m ->
        val url = m.groupValues[2].replace("&amp;", "&")
        if (isOpenableLink(url)) "<a href=\"${esc(url)}\">${m.groupValues[1]}</a>" else m.value
    }
    s = BOLD.replace(s, "<strong>$1</strong>")
    s = ITALIC.replace(s, "<em>$1</em>")
    return Regex("\u0000(\\d+)\u0000").replace(s) { "<code>${esc(codes[it.groupValues[1].toInt()])}</code>" }
}

/** A note's Markdown as HTML blocks. */
fun markdownToHtml(md: String): String {
    val out = StringBuilder()
    val lines = md.replace("\r\n", "\n").split("\n")
    var i = 0
    fun flushPara(buf: MutableList<String>) {
        if (buf.isNotEmpty()) out.append("<p>").append(buf.joinToString("<br>\n") { inlineHtml(it) }).append("</p>\n")
        buf.clear()
    }
    val para = mutableListOf<String>()
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flushPara(para)
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                out.append("<pre><code>").append(esc(code.joinToString("\n"))).append("</code></pre>\n")
            }
            t.isEmpty() -> flushPara(para)
            Regex("^#{1,6}\\s").containsMatchIn(t) -> {
                flushPara(para)
                val level = t.takeWhile { it == '#' }.length
                out.append("<h$level>").append(inlineHtml(t.drop(level).trim())).append("</h$level>\n")
            }
            Regex("^[-*+]\\s").containsMatchIn(t) || Regex("^\\d+[.)]\\s").containsMatchIn(t) -> {
                flushPara(para)
                val ordered = t.first().isDigit()
                out.append(if (ordered) "<ol>\n" else "<ul>\n")
                while (i < lines.size) {
                    val l = lines[i].trim()
                    val m = (if (ordered) Regex("^\\d+[.)]\\s+(.*)") else Regex("^[-*+]\\s+(.*)")).find(l) ?: break
                    val item = m.groupValues[1].replace(Regex("^\\[( |x)]\\s"), "")
                    out.append("<li>").append(inlineHtml(item)).append("</li>\n")
                    i++
                }
                out.append(if (ordered) "</ol>\n" else "</ul>\n")
                continue
            }
            t.startsWith(">") -> {
                flushPara(para)
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                out.append("<blockquote>").append(quote.joinToString("<br>\n") { inlineHtml(it) }).append("</blockquote>\n")
                continue
            }
            else -> para += line
        }
        i++
    }
    flushPara(para)
    return out.toString().trimEnd()
}
