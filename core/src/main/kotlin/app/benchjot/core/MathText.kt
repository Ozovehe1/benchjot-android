package app.benchjot.core

// Math in note bodies, as the web renders it with remark-math + rehype-katex:
// $inline$ (any run of dollars, closed by a run of the same length, like a code
// span) and display math fenced by $$ lines. Nothing inside code is touched.
// The app's Markdown renderer has no math, so each piece becomes an image
// placeholder, ![math](bjmath:N), that the preview draws natively.

data class MathPiece(val tex: String, val display: Boolean, val source: String)

data class MathDoc(val markdown: String, val pieces: List<MathPiece>)

const val MATH_SCHEME = "bjmath:"

private val CODE_FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")
private val MATH_FENCE_OPEN = Regex("""^ {0,3}(\${'$'}{2,})([^${'$'}]*)$""")

fun extractMath(markdown: String): MathDoc {
    val pieces = mutableListOf<MathPiece>()
    val out = StringBuilder()
    val lines = markdown.split("\n")
    val paragraph = mutableListOf<String>()

    fun place(piece: MathPiece): String { pieces += piece; return "![math]($MATH_SCHEME${pieces.size - 1})" }
    fun emit(line: String) { if (out.isNotEmpty()) out.append('\n'); out.append(line) }
    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        emit(inlineMath(paragraph.joinToString("\n"), ::place))
        paragraph.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val code = CODE_FENCE.find(line)
        val math = if (code == null) MATH_FENCE_OPEN.find(line) else null
        when {
            code != null -> {
                flushParagraph()
                val fence = code.groupValues[1]
                val close = Regex("""^ {0,3}${Regex.escape(fence[0].toString())}{${fence.length},}\s*$""")
                emit(line)
                i++
                while (i < lines.size) {
                    emit(lines[i])
                    if (close.matches(lines[i])) break
                    i++
                }
            }
            math != null -> {
                flushParagraph()
                val n = math.groupValues[1].length
                val close = Regex("""^ {0,3}\${'$'}{$n,}\s*$""")
                val body = mutableListOf<String>()
                val raw = mutableListOf(line)
                i++
                while (i < lines.size && !close.matches(lines[i])) { body += lines[i]; raw += lines[i]; i++ }
                if (i < lines.size) raw += lines[i] // an unclosed block runs to the end, as in remark-math
                emit("")
                emit(place(MathPiece(body.joinToString("\n"), display = true, source = raw.joinToString("\n"))))
                emit("")
            }
            line.isBlank() -> { flushParagraph(); emit(line) }
            else -> paragraph += line
        }
        i++
    }
    flushParagraph()
    return MathDoc(out.toString(), pieces)
}

private fun isAsciiPunct(c: Char) = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/** Inline math in one paragraph: dollar runs close on a run of the same length; code spans and \$ escapes are left alone. */
private fun inlineMath(text: String, place: (MathPiece) -> String): String {
    val out = StringBuilder()
    var i = 0
    fun runAt(at: Int, c: Char): Int { var j = at; while (j < text.length && text[j] == c) j++; return j - at }
    fun closingRun(from: Int, c: Char, n: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == c) {
                val len = runAt(j, c)
                if (len == n) return j
                j += len
            } else j++
        }
        return -1
    }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && isAsciiPunct(text[i + 1]) -> { out.append(text, i, i + 2); i += 2 }
            c == '`' -> {
                val n = runAt(i, '`')
                val end = closingRun(i + n, '`', n)
                val stop = if (end == -1) i + n else end + n
                out.append(text, i, stop)
                i = stop
            }
            c == '$' -> {
                val n = runAt(i, '$')
                val end = closingRun(i + n, '$', n)
                if (end == -1) { out.append(text, i, i + n); i += n }
                else {
                    var tex = text.substring(i + n, end)
                    if (tex.length >= 2 && tex.startsWith(' ') && tex.endsWith(' ') && tex.isNotBlank()) tex = tex.substring(1, tex.length - 1)
                    out.append(place(MathPiece(tex, display = false, source = text.substring(i, end + n))))
                    i = end + n
                }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

/** The piece a placeholder link points at, or null for any other link. */
fun mathPieceAt(link: String, doc: MathDoc): MathPiece? =
    link.removePrefix(MATH_SCHEME).takeIf { link.startsWith(MATH_SCHEME) }?.toIntOrNull()?.let(doc.pieces::getOrNull)
