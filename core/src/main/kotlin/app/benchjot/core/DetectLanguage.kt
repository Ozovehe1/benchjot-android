package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Best-effort guess of a code block's language from its text, used only while
// a block is still "Plain text" and the user hasn't picked one. Mirrors the
// web's src/lib/detect-language.ts (same rules, same order).

private val M = setOf(RegexOption.MULTILINE)
private fun has(re: String, opts: Set<RegexOption> = emptySet()): (String) -> Boolean {
    val r = Regex(re, opts)
    return { r.containsMatchIn(it) }
}

private fun isJson(code: String): Boolean {
    val t = code.trim()
    if (!Regex("^[\\[{]").containsMatchIn(t)) return false
    return runCatching { Json.parseToJsonElement(t) as JsonElement }.isSuccess
}

private val RULES: List<Pair<String, (String) -> Boolean>> = listOf(
    "json" to ::isJson,
    "bash" to has("""^#!.*\b(ba|z)?sh\b"""),
    "python" to has("""^#!.*python"""),
    "go" to has("""^\s*package\s+\w+\s*$|^\s*func\s+(\(\w+\s+\*?\w+\)\s*)?\w+\(.*\)\s*.*\{|\w+\s*:=\s*""", M),
    "cpp" to { c -> Regex("""#include\s*[<"]""").containsMatchIn(c) && Regex("""\bstd::|\bcout\b|\btemplate\s*<|\bclass\s+\w+|__global__|\bnamespace\b""").containsMatchIn(c) },
    "c" to has("""#include\s*[<"]|\bint\s+main\s*\(|\bprintf\s*\("""),
    "cpp" to has("""\bstd::|__global__|\btemplate\s*<"""),
    "python" to has("""^\s*(def|class)\s+\w+.*:\s*$|^\s*(from\s+[\w.]+\s+)?import\s+[\w.]+(\s+as\s+\w+)?\s*$|^\s*(if|elif|for|while|with|try)\b.*:\s*$|\bself\.\w+|\bprint\(""", M),
    "typescript" to has("""\binterface\s+\w+\s*\{|\btype\s+\w+\s*=|:\s*(string|number|boolean|void|unknown|any)\b[^:]*[;,)=]|\bas\s+const\b"""),
    "javascript" to has("""\b(const|let|var)\s+\w+\s*=|\bfunction\s*\w*\s*\(|=>|\bconsole\.log\(|\brequire\(|^\s*import\s.+\sfrom\s+['"]|^\s*export\s""", M),
    "sql" to has("""^\s*(select\s+.+\s+from|insert\s+into|update\s+\w+\s+set|delete\s+from|create\s+(table|index|view)|with\s+\w+\s+as\s*\()""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
    "r" to has("""\w+\s*<-\s*|\blibrary\(\w+\)|%>%"""),
    "julia" to { c -> Regex("""^\s*(function|using|module)\s+\w+""", M).containsMatchIn(c) && Regex("""^\s*end\s*$""", M).containsMatchIn(c) },
    "matlab" to { c -> Regex("""^\s*%""", M).containsMatchIn(c) || (Regex("""^\s*function\s+.*=\s*\w+\(""", M).containsMatchIn(c) && Regex("""^\s*end\s*$""", M).containsMatchIn(c)) },
    "bash" to has("""^\s*(echo|export|cd|sudo|apt(-get)?|pip3?|conda|npm|git|chmod|mkdir)\s+\S""", M),
    "yaml" to { c -> Regex("""^\s*-?\s*[\w.-]+:(\s+\S.*)?$""", M).findAll(c).count() >= 2 && !Regex("[{};]").containsMatchIn(c) },
    "markdown" to has("""^#{1,6}\s+\S|^\s*[-*]\s+\[[ x]\]|^```""", M),
)

/** The likely language of `code`, or null when there isn't enough signal. */
fun detectLanguage(code: String): String? {
    if (code.trim().length < 8) return null
    return RULES.firstOrNull { (_, test) -> test(code) }?.first
}
