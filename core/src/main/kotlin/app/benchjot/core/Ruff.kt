package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Ruff lint + autofix, as the web's src/lib/run/ruff.ts and ruff-fix.ts. The web
// runs Ruff as WASM; the app runs the same Ruff version's official binary and
// reads its JSON output. Pure here; the process lives in :engine.

/** The web's Ruff settings (ruff.ts SETTINGS): real problems only, no style noise. Checked against the web by RuffTest. */
object RuffSettings {
    const val LINE_LENGTH = 100
    const val INDENT_WIDTH = 4
    val SELECT = listOf("E4", "E7", "E9", "F", "B")

    /** `ruff check` arguments for one snippet on stdin, isolated from any config on the device. */
    fun checkArgs(): List<String> = listOf(
        "check", "--isolated", "--no-cache", "--quiet", "--exit-zero",
        "--line-length", "$LINE_LENGTH", "--config", "indent-width = $INDENT_WIDTH",
        "--select", SELECT.joinToString(","),
        "--unsafe-fixes", // the web's WASM check reports every fix, unsafe ones too
        "--output-format", "json", "--stdin-filename", "snippet.py", "-",
    )
}

@Serializable
data class RuffPosition(val row: Int, val column: Int)

@Serializable
data class RuffEdit(val content: String? = null, val location: RuffPosition, @SerialName("end_location") val endLocation: RuffPosition)

@Serializable
data class RuffFix(val edits: List<RuffEdit> = emptyList(), val message: String? = null, val applicability: String? = null)

@Serializable
data class RuffDiagnostic(
    val code: String? = null,
    val message: String,
    val location: RuffPosition,
    @SerialName("end_location") val endLocation: RuffPosition,
    val fix: RuffFix? = null,
) {
    val fixable: Boolean get() = fix?.edits?.isNotEmpty() == true
}

private val ruffJson = Json { ignoreUnknownKeys = true }

fun parseRuffOutput(json: String): List<RuffDiagnostic> = if (json.isBlank()) emptyList() else ruffJson.decodeFromString(json)

/** Count of issues Autofix can fix (the web shows Autofix only when this is > 0). */
fun fixableCount(diagnostics: List<RuffDiagnostic>): Int = diagnostics.count { it.fixable }

/**
 * Offset in `text` (UTF-16, a Kotlin String index) of a 1-based row/column. The
 * Ruff CLI counts columns in characters (code points), not UTF-16 units as the
 * web's WASM build is asked to, so an emoji counts once here.
 */
fun ruffOffset(text: String, pos: RuffPosition): Int {
    var offset = 0
    for (row in 1 until pos.row) {
        val nl = text.indexOf('\n', offset)
        if (nl == -1) return text.length
        offset = nl + 1
    }
    var cps = pos.column - 1
    while (cps > 0 && offset < text.length && text[offset] != '\n') {
        offset += Character.charCount(text.codePointAt(offset))
        cps--
    }
    return minOf(text.length, offset)
}

/** Applies non-overlapping edits (later ones first); an edit overlapping one already applied is skipped. As the web's applyEdits. */
fun applyRuffEdits(text: String, edits: List<RuffEdit>): String {
    data class Span(val from: Int, val to: Int, val insert: String)
    val spans = edits.map { Span(ruffOffset(text, it.location), ruffOffset(text, it.endLocation), it.content ?: "") }
        .sortedWith(compareByDescending<Span> { it.from }.thenByDescending { it.to })
    val out = StringBuilder(text)
    var limit = Int.MAX_VALUE
    for (s in spans) {
        if (s.to > limit) continue
        out.replace(s.from, s.to, s.insert)
        limit = s.from
    }
    return out.toString()
}

/** Applies every available fix, re-checking until nothing more is fixable (max 5 passes). As the web's ruffFixAll. */
suspend fun ruffFixAll(code: String, check: suspend (String) -> List<RuffDiagnostic>): String {
    var text = code
    repeat(5) {
        val edits = check(text).flatMap { it.fix?.edits.orEmpty() }
        if (edits.isEmpty()) return text
        val next = applyRuffEdits(text, edits)
        if (next == text) return text
        text = next
    }
    return text
}
