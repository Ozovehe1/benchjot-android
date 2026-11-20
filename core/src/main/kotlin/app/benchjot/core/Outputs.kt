package app.benchjot.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Rich run outputs (figures, tables, interactive charts): the web's src/lib/run/outputs.ts.
// bj_capture.py (the same file on web, server and phone) writes a manifest; files live in the
// private run-outputs bucket under {user}/{cell}/{run}/ and the run record keeps that folder.

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
    /** image with a kept figure ([FIGURE_FILE]): the matplotlib version that kept it (it loads only in the same one). */
    val matplotlib: String? = null,
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
    /** What it shows, in a few sentences: written once by the vision model (the seer, the web's /api/see) and kept with this run. */
    val seen: String? = null,
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
    /** {user id}/{cell id}/{run id} */
    val dir: String,
)

/** What bj_capture.py writes as manifest.json (before upload). */
@Serializable
data class Manifest(val version: Int = 1, val items: List<OutputItem> = emptyList(), val notes: List<String> = emptyList())

private val FILE_NAME = Regex("""^\d{1,3}\.[a-z0-9]{2,5}$""")
private val DIR = Regex("""^[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}$""")
/** A matplotlib figure kept at run time (bj_capture.FIG_FILE): its SVG and PDF are made from it on Download. */
const val FIGURE_FILE = "mpl"
/** The formats made from a kept figure (bj_capture.VECTOR_FORMATS). */
val FIGURE_FORMATS = listOf("svg", "pdf")
const val MAX_OUTPUTS = 30

/** The checks the web's zod contract makes that matter on the phone: file names and kinds. Throws ValidationError. */
fun checkManifest(m: Manifest): Manifest {
    if (m.items.size > MAX_OUTPUTS) throw ValidationError("Too many outputs")
    for (item in m.items) {
        if (item.kind !in setOf("image", "table", "chart")) throw ValidationError("Unknown output kind ${item.kind}")
        if (item.isChart && item.lib !in setOf("plotly", "vega-lite", "bokeh")) throw ValidationError("Unknown chart library ${item.lib}")
        for ((fmt, name) in item.files) if (fmt.length > 5) throw ValidationError("Bad output file name") else checkedOutputFileName(name)
    }
    return m
}

fun outputDir(userId: String, cellId: String, runId: String) = "$userId/$cellId/$runId".also { require(DIR.matches(it)) { "Bad output folder" } }
/** An output file name as the contract allows it ("3.png"): never a path (no "/", "..", or anything else). Throws ValidationError. */
fun checkedOutputFileName(name: String): String = name.also { if (!FILE_NAME.matches(it)) throw ValidationError("Bad output file name") }
fun outputPath(dir: String, name: String) = "$dir/${checkedOutputFileName(name)}"
fun outputPaths(o: SavedOutputs): List<String> = o.items.flatMap { it.files.values }.map { outputPath(o.dir, it) }

/** The file to show: a raster first (cheapest to decode on a phone), else SVG. */
fun displayFile(item: OutputItem): String? = listOf("png", "jpg", "webp", "gif", "bmp", "svg").firstNotNullOfOrNull { item.files[it] }

val MIME = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "bmp" to "image/bmp",
    "svg" to "image/svg+xml", "pdf" to "application/pdf", "csv" to "text/csv", "json" to "application/json", "zip" to "application/zip",
    "html" to "text/html", "mp4" to "video/mp4", "webm" to "video/webm",
)

private val LABEL = mapOf("png" to "PNG", "jpg" to "JPG", "webp" to "WebP", "svg" to "SVG", "pdf" to "PDF", "gif" to "GIF", "bmp" to "BMP", "csv" to "CSV", "json" to "JSON")

/**
 * One way to download an output (the web's DownloadFormat). File: a stored file as is.
 * Convert: re-encoded on the phone from `from` (JPG / WebP over a solid background).
 * Render: drawn again at download time (charts from their spec, a table's preview as CSV).
 * Figure: made by matplotlib from the figure kept at run time (`file`), in the phone's own Python.
 */
data class DownloadFormat(val format: String, val label: String, val how: String, val file: String? = null, val from: String? = null)

/** A chart as one self-contained page that still moves and responds (Plotly animations and 3D, Vega-Lite, Bokeh). */
val HTML_FORMAT = DownloadFormat("html", "HTML (interactive)", "render")

/** An animation from a run (a simulation): kept as GIF and/or video, with no still PNG. */
fun isAnimation(item: OutputItem): Boolean = !item.isTable && !item.isChart && item.files["png"] == null && (item.files["gif"] ?: item.files["mp4"] ?: item.files["webm"]) != null

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
            for (format in formats) {
                out += DownloadFormat(format, LABEL.getValue(format), "render")
                // Right after PNG: the chart as it is (interactive, animated), when its spec was kept.
                if (format == "png" && f["json"] != null) out += HTML_FORMAT
            }
            file("json")
        }
        isAnimation(item) -> {
            // A simulation's animation: as it is first (video, GIF); stills say they are one frame.
            f["mp4"]?.let { out += DownloadFormat("mp4", "MP4 (video)", "file", file = it) }
            f["webm"]?.let { out += DownloadFormat("webm", "WebM (video)", "file", file = it) }
            f["gif"]?.let { gif ->
                out += DownloadFormat("gif", "GIF (animation)", "file", file = gif)
                for (format in listOf("jpg", "webp")) out += DownloadFormat(format, "${LABEL.getValue(format)} (first frame)", "convert", from = gif)
            }
        }
        else -> {
            file("png")
            val raster = f["png"] ?: f["webp"] ?: f["bmp"] ?: f["gif"]
            if (raster != null) {
                for (format in listOf("jpg", "webp")) if (!file(format)) out += DownloadFormat(format, LABEL.getValue(format), "convert", from = raster)
            } else {
                file("jpg"); file("webp")
            }
            val kept = f[FIGURE_FILE]
            for (format in FIGURE_FORMATS) if (!file(format) && kept != null) out += DownloadFormat(format, LABEL.getValue(format), "figure", file = kept)
            for (format in listOf("gif", "bmp")) file(format)
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
    // An animation bj_capture made (animation-N.gif): said for what is shown, the video when there is one (the web's outputLabel).
    CAPTURED_ANIMATION.matches(item.name ?: "") -> if (item.files["mp4"] != null || item.files["webm"] != null) "Animation · video" else "Animation"
    item.source == "file" -> item.name ?: "Image"
    item.source == "pil" -> "Image"
    else -> "Figure"
}
private val CAPTURED_ANIMATION = Regex("^animation-\\d+\\.gif$", RegexOption.IGNORE_CASE)

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

// ------------------------------------------------------------------ the seer (the web's outputs.ts toSee / withSeen)

/** The longest description the seer keeps for one output. */
const val SEEN_MAX = 600
/** The most outputs the seer looks at in one request. */
const val SEE_MAX_ITEMS = 3

/** What the seer is shown of an output: its file, and what kind of thing it is (figure, image, animation, chart). */
@Serializable
data class SeeItem(val index: Int, val file: String, val kind: String, val title: String)

/**
 * The outputs of a run the seer hasn't described yet: a figure or image as its PNG (else JPEG or
 * WebP), an animation as its GIF, a chart as its static PNG; never tables, SVG-only or video-only.
 */
fun toSee(o: SavedOutputs?): List<SeeItem> {
    val out = mutableListOf<SeeItem>()
    o?.items.orEmpty().forEachIndexed { index, item ->
        if (out.size >= SEE_MAX_ITEMS || item.isTable || !item.seen.isNullOrEmpty()) return@forEachIndexed
        val f = item.files
        if (item.isChart) {
            f["png"]?.let { out += SeeItem(index, it, "chart", item.title) }
            return@forEachIndexed
        }
        val still = f["png"] ?: f["jpg"] ?: f["webp"]
        if (still != null) out += SeeItem(index, still, if (item.source == "matplotlib") "figure" else "image", item.title)
        else (f["strip"] ?: f["gif"])?.let { out += SeeItem(index, it, "animation", item.title) }
    }
    return out
}

/** One description from the seer, by the item's index in the run's outputs. */
@Serializable
data class Seen(val index: Int, val text: String)

/** POST /api/see's answer. */
@Serializable
data class SeeReply(val seen: List<Seen> = emptyList())

/** POST /api/see's body: the run's folder, the files to look at, and what the cell is about. */
@Serializable
data class SeeRequest(val dir: String, val items: List<SeeItem>, val title: String, val code: String, val printed: String)

/** The run's outputs with the seer's descriptions written into the items they are about. */
fun withSeen(o: SavedOutputs, seen: List<Seen>): SavedOutputs = o.copy(
    items = o.items.mapIndexed { i, item ->
        val s = seen.firstOrNull { it.index == i }?.text?.trim().orEmpty()
        if (s.isNotEmpty() && !item.isTable) item.copy(seen = s.take(SEEN_MAX)) else item
    },
)
