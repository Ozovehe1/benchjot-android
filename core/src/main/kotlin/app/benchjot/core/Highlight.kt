package app.benchjot.core

// A small, honest syntax highlighter for the code editor: comments, strings,
// numbers, keywords (from LANGUAGES) and capitalised types. Greyscale tones in
// the UI, like the web's CodeMirror theme. Not a parser: good enough to read by.

enum class TokenKind { Keyword, Str, Number, Comment, Type }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

private val NUMBER = Regex("""\b(0[xX][0-9a-fA-F_]+|\d[\d_]*(\.\d+)?([eE][+-]?\d+)?)\b""")
private val WORD = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")

/** Tokens in order, non-overlapping. Unknown languages get strings and numbers only. */
fun highlight(code: String, language: String): List<Token> {
    if (language == "text" || language == "markdown") return emptyList()
    val cfg = languageConfig(language)
    val keywords = cfg.keywords.toSet()
    val comment = cfg.comment
    val blockComments = cfg.comment == "//" || language == "css"
    val tripleQuotes = language == "python"
    val out = mutableListOf<Token>()
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        // Line comment
        if (comment != null && code.startsWith(comment, i)) {
            val end = code.indexOf('\n', i).let { if (it < 0) n else it }
            out += Token(i, end, TokenKind.Comment); i = end; continue
        }
        if (blockComments && code.startsWith("/*", i)) {
            val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
            out += Token(i, end, TokenKind.Comment); i = end; continue
        }
        // Strings
        if (tripleQuotes && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i))) {
            val q = code.substring(i, i + 3)
            val end = code.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }
            out += Token(i, end, TokenKind.Str); i = end; continue
        }
        if (c == '"' || c == '\'' || (c == '`' && cfg.comment == "//")) {
            var j = i + 1
            while (j < n && code[j] != c && (code[j] != '\n' || c == '`')) j += if (code[j] == '\\') 2 else 1
            val end = minOf(n, j + 1)
            out += Token(i, end, TokenKind.Str); i = end; continue
        }
        if (c.isDigit()) {
            val m = NUMBER.find(code, i)
            if (m != null && m.range.first == i && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_')) {
                out += Token(i, m.range.last + 1, TokenKind.Number); i = m.range.last + 1; continue
            }
        }
        if (c.isLetter() || c == '_' || c == '$') {
            val m = WORD.find(code, i)!!
            val word = m.value
            val end = m.range.last + 1
            when {
                word in keywords -> out += Token(i, end, TokenKind.Keyword)
                word[0].isUpperCase() && word.length > 1 -> out += Token(i, end, TokenKind.Type)
            }
            i = end; continue
        }
        i++
    }
    return out
}
