package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Rich run outputs (figures, tables, interactive charts): the web's src/lib/run/outputs.ts.
// bj_capture.py (the same file on web, server and phone) writes a manifest; files live in the
// private run-outputs bucket under {user}/{snippet}/{run}/ and the run record keeps that folder.

const val OUTPUTS_BUCKET = "run-outputs"

/** One output, flat: `kind` says which fields apply (image / table / chart), as in the manifest JSON. */
@Serializable
data class OutputItem(
    val kind: String,
    /** image: matplotlib | pil | file */
    val source: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val title: String = "",
    val name: String? = null,
    /** chart: plotly | vega-lite | bokeh */
    val lib: String? = null,
    val version: String? = null,
    /** format (png, svg, pdf, csv, json, ...) -> file name in the run's folder */
    val files: Map<String, String> = emptyMap(),
    @SerialName("index_columns") val indexColumns: Int = 0,
    val columns: List<String> = emptyList(),
    /** Cells are strings, numbers, booleans or null. */
    val rows: List<List<JsonElement>> = emptyList(),
    @SerialName("total_rows") val totalRows: Int = 0,
    @SerialName("total_columns") val totalColumns: Int = 0,
) {
    val isImage get() = kind == "image"
    val isTable get() = kind == "table"
    val isChart get() = kind == "chart"
}

@Serializable
data class SavedOutputs(
    val version: Int = 1,
    val items: List<OutputItem> = emptyList(),
    val notes: List<String> = emptyList(),
    /** {user id}/{snippet id}/{run id} */
    val dir: String,
)

/** What bj_capture.py writes as manifest.json (before upload). */
@Serializable
data class Manifest(val version: Int = 1, val items: List<OutputItem> = emptyList(), val notes: List<String> = emptyList())

private val FILE_NAME = Regex("""^\d{1,3}\.[a-z0-9]{2,5}$""")
private val DIR = Regex("""^[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}$""")
val IMAGE_FORMATS = listOf("png", "svg", "pdf", "jpg", "gif", "webp", "bmp")
const val MAX_OUTPUTS = 30

/** The checks the web's zod contract makes that matter on the phone: file names and kinds. Throws ValidationError. */
fun checkManifest(m: Manifest): Manifest {
    if (m.items.size > MAX_OUTPUTS) throw ValidationError("Too many outputs")
    for (item in m.items) {
        if (item.kind !in setOf("image", "table", "chart")) throw ValidationError("Unknown output kind ${item.kind}")
        if (item.isChart && item.lib !in setOf("plotly", "vega-lite", "bokeh")) throw ValidationError("Unknown chart library ${item.lib}")
        for ((fmt, name) in item.files) if (!FILE_NAME.matches(name) || fmt.length > 5) throw ValidationError("Bad output file name")
    }
    return m
}

fun outputDir(userId: String, snippetId: String, runId: String) = "$userId/$snippetId/$runId".also { require(DIR.matches(it)) { "Bad output folder" } }
fun outputPath(dir: String, name: String) = "$dir/$name"
fun outputPaths(o: SavedOutputs): List<String> = o.items.flatMap { it.files.values }.map { outputPath(o.dir, it) }

/** The file to show: a raster first (cheapest to decode on a phone), else SVG. */
fun displayFile(item: OutputItem): String? = listOf("png", "jpg", "webp", "gif", "bmp", "svg").firstNotNullOfOrNull { item.files[it] }

val MIME = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "bmp" to "image/bmp",
    "svg" to "image/svg+xml", "pdf" to "application/pdf", "csv" to "text/csv", "json" to "application/json", "zip" to "application/zip",
)

private val LABEL = mapOf("png" to "PNG", "jpg" to "JPG", "webp" to "WebP", "svg" to "SVG", "pdf" to "PDF", "gif" to "GIF", "bmp" to "BMP", "csv" to "CSV", "json" to "JSON")

/**
 * One way to download an output (the web's DownloadFormat). File: a stored file as is.
 * Convert: re-encoded on the phone from `from` (JPG / WebP over a solid background).
 * Render: drawn again at download time (charts from their spec, a table's preview as CSV).
 */
data class DownloadFormat(val format: String, val label: String, val how: String, val file: String? = null, val from: String? = null)

/** The formats the viewer offers, best first: the same list as the web (checked against its fixtures). */
fun downloadFormats(item: OutputItem): List<DownloadFormat> {
    val f = item.files
    val out = mutableListOf<DownloadFormat>()
    fun file(format: String): Boolean {
        val name = f[format] ?: return false
        out += DownloadFormat(format, LABEL[format] ?: format.uppercase(), "file", file = name)
        return true
    }
    when {
        item.isTable -> if (!file("csv")) out += DownloadFormat("csv", "CSV (preview)", "render")
        item.isChart -> {
            // PDF: the drawn chart on one page (vector here, via android.graphics.pdf).
            val formats = if (item.lib == "bokeh") listOf("png", "jpg", "webp", "pdf") else listOf("png", "jpg", "webp", "svg", "pdf")
            for (format in formats) out += DownloadFormat(format, LABEL.getValue(format), "render")
            file("json")
        }
        else -> {
            file("png")
            val raster = f["png"] ?: f["webp"] ?: f["bmp"] ?: f["gif"]
            if (raster != null) {
                for (format in listOf("jpg", "webp")) if (!file(format)) out += DownloadFormat(format, LABEL.getValue(format), "convert", from = raster)
            } else {
                file("jpg"); file("webp")
            }
            for (format in listOf("svg", "pdf", "gif", "bmp")) file(format)
        }
    }
    return out
}

/** "<block name>-<n>.<ext>" (n is 1-based), as the web names downloads. */
fun outputDownloadName(base: String, index: Int, format: String): String {
    val clean = base.replace(Regex("""\.[^.]*$"""), "").replace(Regex("""[^\w.-]+"""), "-").trim('-').ifEmpty { "output" }
    return "$clean-${index + 1}.$format"
}

/** "Figure", "Table · 150 × 3", "Plotly chart" (the web's outputLabel). */
fun outputLabel(item: OutputItem): String = when {
    item.isTable -> "Table · ${item.totalRows} × ${item.totalColumns}"
    item.isChart -> "${mapOf("plotly" to "Plotly", "vega-lite" to "Vega-Lite", "bokeh" to "Bokeh")[item.lib] ?: "Chart"} chart"
    item.source == "file" -> item.name ?: "Image"
    item.source == "pil" -> "Image"
    else -> "Figure"
}

/** A cell as text ("" for null), as the web's table shows it. */
fun cellText(v: JsonElement): String = when (v) {
    is kotlinx.serialization.json.JsonNull -> ""
    is kotlinx.serialization.json.JsonPrimitive -> v.content.let { s ->
        // JSON numbers print like JavaScript's String(n): 1.0 -> "1", 0.5 -> "0.5"
        if (!v.isString && s.endsWith(".0")) s.dropLast(2) else s
    }
    else -> v.toString()
}

/** CSV of a table preview (used only when the full CSV was too large to keep). */
fun previewCsv(t: OutputItem): String {
    fun esc(s: String) = if (s.any { it == '"' || it == ',' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
    return (listOf(t.columns.map(::esc)) + t.rows.map { r -> r.map { esc(cellText(it)) } }).joinToString("\n") { it.joinToString(",") } + "\n"
}
