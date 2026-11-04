package app.benchjot.core

// Every code language the app understands. Generated from the web's
// src/lib/languages.ts (the source of truth); LanguagesTest checks the copy
// against core/src/test/resources/web/languages.json, which
// scripts/web-fixtures.sh regenerates from the web repo.

data class Language(
    val id: String,
    val label: String,
    /** File extensions; the first is used for downloads. */
    val ext: List<String>,
    val indent: String,
    /** Line-comment token, null when the language has none. */
    val comment: String?,
    val keywords: List<String>,
    /** Keys shown first on the symbol row. */
    val keys: List<String>,
)

val LANGUAGES: List<Language> = listOf(
    Language("python", "Python", listOf("py", "pyw", "pyx", "ipynb"), "    ", "#",
        keywords = listOf("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield", "print", "range", "len", "enumerate", "zip", "self", "__init__", "isinstance", "super", "dict", "list", "tuple", "set", "float", "int", "str"),
        keys = listOf(":", "#", "_", "self", "=", "\"", "'")),
    Language("javascript", "JavaScript", listOf("js", "mjs", "cjs", "jsx"), "  ", "//",
        keywords = listOf("async", "await", "break", "case", "catch", "class", "const", "continue", "default", "delete", "else", "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new", "null", "of", "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var", "while", "yield", "console", "Promise", "Array", "Object", "JSON", "Math"),
        keys = listOf("=>", "{", "}", ";", "`", "(", ")", ".")),
    Language("typescript", "TypeScript", listOf("ts", "tsx", "mts", "cts"), "  ", "//",
        keywords = listOf("abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const", "continue", "declare", "default", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import", "in", "interface", "keyof", "let", "never", "new", "null", "number", "private", "protected", "public", "readonly", "return", "string", "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "unknown", "void", "while", "Promise", "Record"),
        keys = listOf("=>", "{", "}", ";", ":", "`", "<", ">")),
    Language("go", "Go", listOf("go"), "\t", "//",
        keywords = listOf("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var", "nil", "true", "false", "err", "error", "string", "int", "float64", "bool", "byte", "make", "append", "len", "fmt"),
        keys = listOf(":=", "{", "}", "(", ")", "*", "&", ".")),
    Language("c", "C", listOf("c", "h"), "    ", "//",
        keywords = listOf("auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern", "float", "for", "goto", "if", "int", "long", "register", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "NULL", "printf", "malloc", "free", "include"),
        keys = listOf(";", "{", "}", "*", "&", "#", "->", "(", ")")),
    Language("cpp", "C++ / CUDA", listOf("cpp", "cc", "cxx", "hpp", "hh", "cu", "cuh"), "    ", "//",
        keywords = listOf("auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "explicit", "false", "float", "for", "if", "include", "int", "long", "namespace", "new", "nullptr", "private", "protected", "public", "return", "sizeof", "static", "std", "struct", "switch", "template", "this", "throw", "true", "try", "typename", "using", "vector", "virtual", "void", "while", "__global__", "__device__"),
        keys = listOf(";", "{", "}", "::", "<", ">", "*", "&", "#")),
    Language("rust", "Rust", listOf("rs"), "    ", "//",
        keywords = listOf("as", "async", "await", "break", "const", "continue", "crate", "else", "enum", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while", "Vec", "String", "Option", "Result", "Some", "None", "Ok", "Err", "println"),
        keys = listOf(";", "{", "}", "::", "&", "->", "!", "<", ">")),
    Language("java", "Java", listOf("java"), "    ", "//",
        keywords = listOf("abstract", "boolean", "break", "case", "catch", "char", "class", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private", "protected", "public", "return", "static", "String", "super", "switch", "this", "throw", "throws", "true", "false", "try", "var", "void", "while", "System", "List", "Map"),
        keys = listOf(";", "{", "}", "(", ")", ".", "<", ">", "\"")),
    Language("matlab", "MATLAB", listOf("m"), "    ", "%",
        keywords = listOf("break", "case", "catch", "continue", "else", "elseif", "end", "for", "function", "global", "if", "otherwise", "persistent", "return", "switch", "try", "while", "zeros", "ones", "size", "length", "plot", "disp", "fprintf"),
        keys = listOf("%", ";", "(", ")", "[", "]", ".*", "'")),
    Language("r", "R", listOf("r", "R"), "  ", "#",
        keywords = listOf("break", "else", "FALSE", "for", "function", "if", "in", "next", "NULL", "NA", "repeat", "return", "TRUE", "while", "library", "data.frame", "c", "list", "print", "paste", "mean", "sum"),
        keys = listOf("<-", "%>%", "#", "\$", "(", ")", "~", ",")),
    Language("julia", "Julia", listOf("jl"), "    ", "#",
        keywords = listOf("begin", "break", "catch", "const", "continue", "do", "else", "elseif", "end", "export", "false", "finally", "for", "function", "global", "if", "import", "let", "local", "macro", "module", "quote", "return", "struct", "true", "try", "using", "while", "println"),
        keys = listOf("end", "#", "::", ".", "(", ")", "[", "]")),
    Language("bash", "Shell", listOf("sh", "bash", "zsh"), "  ", "#",
        keywords = listOf("case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if", "in", "local", "return", "then", "until", "while", "echo", "cd", "grep", "sed", "awk", "python", "pip", "sudo"),
        keys = listOf("\$", "|", "-", "#", ">", "&&", "\"", "~")),
    Language("sql", "SQL", listOf("sql"), "  ", "--",
        keywords = listOf("select", "from", "where", "and", "or", "not", "insert", "into", "values", "update", "set", "delete", "create", "table", "alter", "drop", "join", "left", "inner", "on", "group", "by", "order", "having", "limit", "as", "distinct", "count", "null", "is", "in", "with"),
        keys = listOf(";", "*", "'", ",", "(", ")", "=", "--")),
    Language("json", "JSON", listOf("json"), "  ", null,
        keywords = listOf("true", "false", "null"),
        keys = listOf("\"", ":", ",", "{", "}", "[", "]")),
    Language("yaml", "YAML", listOf("yaml", "yml"), "  ", "#",
        keywords = listOf("true", "false", "null"),
        keys = listOf(":", "-", "#", "\"", "|", ">")),
    Language("markdown", "Markdown", listOf("md", "markdown"), "  ", null,
        keywords = listOf(),
        keys = listOf("#", "*", "-", "`", "[", "]", "(", ")")),
    Language("text", "Plain text", listOf("txt", "log", "csv", "tsv"), "    ", null,
        keywords = listOf(),
        keys = listOf()),
)

/** New code blocks start as Python; an empty one still switches to a language detected from what is typed. */
const val DEFAULT_LANGUAGE = "python"

/**
 * Whether a block's language is settled on open: it has code, or the user picked a language
 * other than the default (or plain text, the old default). Mirrors the web's languageChosen.
 */
fun languageChosen(language: String, content: String): Boolean =
    content.isNotBlank() || (language != DEFAULT_LANGUAGE && language != "text")

fun languageConfig(id: String): Language = LANGUAGES.firstOrNull { it.id == id } ?: LANGUAGES.last()
fun isLanguageId(id: String): Boolean = LANGUAGES.any { it.id == id }

/** Language from a filename's extension (case-sensitive first, so .R is R and .m is MATLAB); null when unknown. */
fun languageFromFilename(filename: String): String? {
    val ext = Regex("""\.([A-Za-z0-9+]+)$""").find(filename.trim())?.groupValues?.get(1) ?: return null
    return (LANGUAGES.firstOrNull { ext in it.ext } ?: LANGUAGES.firstOrNull { ext.lowercase() in it.ext })?.id
}

/** File name for downloading a block: its own name, or code-N.<ext of its language>. */
fun downloadName(filename: String, language: String, index: Int): String =
    filename.trim().ifEmpty { "code-${index + 1}.${languageConfig(language).ext[0]}" }

val COMMON_KEYS = listOf("{", "}", "(", ")", "[", "]", "<", ">", "=", ";", ":", "\"", "'", "/", "\\", "_", "|")

/** The symbol row: Tab, the language's keys, then the common ones (no repeats). */
fun symbolKeys(id: String): List<String> = (listOf("Tab") + languageConfig(id).keys + COMMON_KEYS).distinct()
