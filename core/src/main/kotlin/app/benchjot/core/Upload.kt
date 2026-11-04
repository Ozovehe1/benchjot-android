package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// An uploaded file as a code block: size and binary guard, UTF-8 decode, language
// from the extension, then the content, then plain text. Mirrors src/lib/upload.ts.

const val MAX_UPLOAD_BYTES = 1024 * 1024

data class CodeFile(val filename: String, val language: String, val content: String)

sealed interface UploadResult {
    data class Ok(val file: CodeFile) : UploadResult
    data class Failed(val error: String) : UploadResult
}

/** NUL byte, or over 10% control characters, in the first 8 KB. */
fun looksBinary(bytes: ByteArray): Boolean {
    val head = bytes.copyOfRange(0, minOf(bytes.size, 8192))
    var control = 0
    for (byte in head) {
        val b = byte.toInt() and 0xff
        if (b == 0) return true
        if (b < 32 && b != 9 && b != 10 && b != 13 && b != 12) control++
    }
    return head.isNotEmpty() && control.toDouble() / head.size > 0.1
}

/** Code cells of a Jupyter notebook joined with "# %%" separators; null if it isn't one. */
fun notebookCode(text: String): String? = runCatching {
    val cells = (Json.parseToJsonElement(text) as JsonObject)["cells"] as? JsonArray ?: return null
    cells.mapNotNull { it as? JsonObject }
        .filter { it["cell_type"]?.jsonPrimitive?.content == "code" }
        .map { cell ->
            when (val src = cell["source"]) {
                is JsonArray -> src.joinToString("") { it.jsonPrimitive.content }
                null -> ""
                else -> src.jsonPrimitive.content
            }.trimEnd()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n# %%\n")
}.getOrNull()

private fun cleanName(filename: String) = filename.trim().take(200).ifEmpty { "untitled" }

fun tooLarge(filename: String) = UploadResult.Failed("${cleanName(filename)} is over 1 MB.")

fun prepareUpload(filename: String, bytes: ByteArray): UploadResult {
    val name = cleanName(filename)
    if (bytes.size > MAX_UPLOAD_BYTES) return tooLarge(name)
    if (looksBinary(bytes)) return UploadResult.Failed("$name isn’t a text file.")
    var content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").replace(Regex("\r\n?"), "\n")
    if (Regex("""\.ipynb$""", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
        content = notebookCode(content) ?: return UploadResult.Failed("$name isn’t a valid notebook.")
    }
    val language = languageFromFilename(name) ?: detectLanguage(content) ?: "text"
    return UploadResult.Ok(CodeFile(name, language, content))
}
