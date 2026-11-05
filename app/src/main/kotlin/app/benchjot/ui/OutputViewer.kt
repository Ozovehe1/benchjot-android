package app.benchjot.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.benchjot.core.DownloadFormat
import app.benchjot.core.MIME
import app.benchjot.core.OutputItem
import app.benchjot.core.chart.ChartTheme
import app.benchjot.core.chart.layout
import app.benchjot.core.chart.toSvg
import app.benchjot.core.displayFile
import app.benchjot.core.downloadFormats
import app.benchjot.core.outputDownloadName
import app.benchjot.core.outputLabel
import app.benchjot.core.previewCsv
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// One figure, table or chart on its own screen (the web's /n/{id}/out/{snippet}/{n}):
// pinch-zoom images, interactive native charts, every table row; a format picker to
// save it to Downloads or share it.

/** The bytes of one output in one format: a stored file, re-encoded from one, or drawn now. */
suspend fun exportBytes(item: OutputItem, files: OutputFiles, f: DownloadFormat, chart: ChartState?, density: Float): ByteArray = withContext(Dispatchers.Default) {
    when (f.how) {
        "file" -> files.bytes(f.file!!)
        "convert" -> {
            val src = files.bytes(f.from!!)
            val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: error("Couldn't decode the image")
            encode(onWhite(bmp), f.format)
        }
        else -> when {
            item.isTable -> previewCsv(item).toByteArray()
            chart != null -> {
                val w = 1200; val h = 800
                val d = density.coerceIn(1.5f, 2.5f)
                val white = ChartTheme(0xffffffff.toInt(), 0xff161616.toInt(), 0xff5c5a57.toInt(), 0xffd0cecb.toInt())
                if (f.format == "pdf") {
                    // Vector: the same scene ops drawn on a PDF page (800 x 533 pt), as the user sees it.
                    val pw = 800; val ph = 533
                    pdfPage(pw, ph) { c -> c.drawColor(android.graphics.Color.WHITE); drawOps(c, layout(chart.chart, pw.toFloat(), ph.toFloat(), white, 1f, chart.view, chart.hidden, camera = chart.camera).ops) }
                } else if (f.format == "svg") {
                    toSvg(layout(chart.chart, w.toFloat(), h.toFloat(), white, d, chart.view, chart.hidden, camera = chart.camera), background = white.bg).toByteArray()
                } else encode(renderChart(chart.chart, w, h, d, chart.hidden, chart.view, camera = chart.camera).first, f.format)
            }
            else -> {
                // A chart the phone can't draw: its stored static render, re-encoded.
                val png = item.files["png"] ?: error("This chart can't be drawn on the phone")
                if (f.format == "svg") files.bytes(item.files["svg"] ?: error("No SVG of this chart")) else {
                    val src = files.bytes(png)
                    val bmp = onWhite(BitmapFactory.decodeByteArray(src, 0, src.size))
                    if (f.format == "pdf") imagePdf(bmp) else encode(bmp, f.format)
                }
            }
        }
    }
}

/** A one-page PDF (android.graphics.pdf, no library): `draw` paints the page in points. */
fun pdfPage(width: Int, height: Int, draw: (Canvas) -> Unit): ByteArray {
    val doc = android.graphics.pdf.PdfDocument()
    try {
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, 1).create())
        draw(page.canvas)
        doc.finishPage(page)
        return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
    } finally { doc.close() }
}

/** A bitmap on one PDF page, 2 px per point. */
private fun imagePdf(bmp: Bitmap): ByteArray {
    val w = (bmp.width / 2).coerceAtLeast(1); val h = (bmp.height / 2).coerceAtLeast(1)
    return pdfPage(w, h) { c -> c.drawBitmap(bmp, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
}

private fun onWhite(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, 0f, 0f, null) }
    return out
}

@Suppress("DEPRECATION")
private fun encode(bmp: Bitmap, format: String): ByteArray {
    val out = ByteArrayOutputStream()
    val fmt = when (format) {
        "jpg" -> Bitmap.CompressFormat.JPEG
        "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.PNG
    }
    bmp.compress(fmt, if (format == "png") 100 else 92, out)
    return out.toByteArray()
}

/** Saves into Downloads/Benchjot (Android 10+); returns where, for the message. Older phones use the system "save as". */
fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Benchjot")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { it.write(bytes) }
    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}

/** Opens the share sheet for bytes saved under the app's cache (shared through its FileProvider). */
fun share(context: Context, name: String, mime: String, bytes: ByteArray) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val f = File(dir, name).apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** An image that pinches (1x–8x), drags when zoomed, and double-tap toggles 2.5x / fit. */
@Composable
private fun ZoomableImage(item: OutputItem, files: OutputFiles, modifier: Modifier) {
    val img = rememberOutputImage(item, files, 4096)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .testTag("zoomable-image")
            .semantics { contentDescription = "Pinch to zoom, drag to move, double-tap to reset" }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 8f)
                    val f = next / scale
                    // Keep the point under the fingers fixed, then follow the pan; no drifting off screen.
                    val o = (offset - centroid + Offset(size.width / 2f, size.height / 2f)) * f + centroid - Offset(size.width / 2f, size.height / 2f) + pan
                    val maxX = size.width * (next - 1) / 2; val maxY = size.height * (next - 1) / 2
                    scale = next
                    offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                    else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        img?.let {
            Image(it, item.title.ifEmpty { outputLabel(item) }, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
        }
    }
}

private val mono12 = TextStyle(fontFamily = Type.mono, fontSize = 12.sp)

/**
 * The viewer for output `index` of a run. `files` reads the run's files; `base` names downloads
 * (the block's file name). Prev / Next step through the run's outputs.
 */
@Composable
fun OutputViewer(
    items: List<OutputItem>,
    index: Int,
    files: OutputFiles,
    base: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onStep: (Int) -> Unit,
    badge: (@Composable () -> Unit)? = null,
    backLabel: String = "Back to note",
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val item = items.getOrNull(index)
    var pick by remember(index) { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(index) { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pendingSave
        if (uri != null && p != null) { context.contentResolver.openOutputStream(uri)?.use { it.write(p.first) }; message = "Saved ${p.second}" }
        pendingSave = null
    }
    Screen {
        PageColumn(Modifier.fillMaxSize()) {
            TopBar(onHome, back = backLabel to onBack) {
                if (items.size > 1) {
                    if (index > 0) TextBtn("Prev", { onStep(index - 1) })
                    Text("${index + 1} / ${items.size}", style = mono12, color = t.faint)
                    if (index < items.size - 1) TextBtn("Next", { onStep(index + 1) })
                }
            }
            if (item == null) { Muted("This output isn’t here any more. Run the code again to make it."); return@PageColumn }
            val label = outputLabel(item) + if (!item.isTable && item.title.isNotEmpty()) " · ${item.title}" else ""
            badge?.let { Box(Modifier.padding(bottom = 8.dp)) { it() } }
            Text(label, Modifier.padding(bottom = 8.dp), style = mono12, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chart = if (item.isChart) rememberChart(item, files) else null
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    item.isImage -> ZoomableImage(item, files, Modifier.fillMaxSize())
                    item.isTable -> TablePreview(item, rows = item.rows.size, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                    chart is LoadedChart.Ready -> ChartCanvas(chart.state, interactive = true, modifier = Modifier.fillMaxSize())
                    chart is LoadedChart.Fallback -> Column(Modifier.fillMaxSize()) {
                        chart.image?.let { Image(it, item.title, Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit) }
                        Text(fallbackNote(chart.reason, chart.image != null), style = mono12, color = t.muted)
                    }
                    else -> {}
                }
            }
            val formats = downloadFormats(item).filter { f ->
                // A chart the phone can't draw downloads only what the web stored for it.
                !(item.isChart && chart !is LoadedChart.Ready && f.how == "render" && item.files[if (f.format == "svg") "svg" else "png"] == null)
            }
            val chosen = formats.getOrNull(pick.coerceAtMost(formats.size - 1))
            Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("Format")
                    Box {
                        TextBtn(chosen?.label ?: "—", { menu = true }, strong = true, modifier = Modifier.testTag("format"))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = t.bg) {
                            formats.forEachIndexed { i, f ->
                                DropdownMenuItem(text = { Text(f.label, style = Type.sm, color = if (i == pick) t.fg else t.muted) }, onClick = { pick = i; menu = false })
                            }
                        }
                    }
                }
                fun export(then: (ByteArray, String, String) -> Unit) {
                    val f = chosen ?: return
                    busy = true; message = null
                    scope.launch {
                        try {
                            val bytes = exportBytes(item, files, f, (chart as? LoadedChart.Ready)?.state, density)
                            then(bytes, outputDownloadName(base, index, f.format), MIME[f.format] ?: "application/octet-stream")
                        } catch (e: Exception) {
                            message = app.benchjot.data.Accounts.message(e, "download")
                        } finally { busy = false }
                    }
                }
                TextBtn("Share", { export { b, name, mime -> share(context, name, mime, b) } }, enabled = !busy && chosen != null)
                FilledBtn(if (busy) "Preparing…" else "Download", {
                    export { b, name, mime ->
                        val uri = saveToDownloads(context, name, mime, b)
                        if (uri != null) message = "Saved to Downloads/Benchjot/$name" else { pendingSave = b to name; saveAs.launch(name) }
                    }
                }, enabled = !busy && chosen != null, modifier = Modifier.testTag("download"))
            }
            message?.let { Text(it, Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()), style = mono12, color = t.muted, maxLines = 1) }
        }
    }
}

/** For tests and the inline list: which file an image output shows. */
fun shownFile(item: OutputItem) = displayFile(item)

/** The viewer's route: the note, the code block and the output's index (the web's /n/{id}/out/{snippet}/{n}). */
const val OUTPUT_ROUTE = "out/{note}/{snippet}/{n}"
fun outputRoute(noteId: String, snippetId: String, index: Int) = "out/$noteId/$snippetId/$index"

/** Loads the code block's saved run and shows output `start`; Prev / Next step within the screen. */
@Composable
fun OutputScreen(noteId: String, snippetId: String, start: Int, onBack: () -> Unit, onHome: () -> Unit) {
    val snippets = rememberLive(noteId) { app.benchjot.data.Backend.snippets(noteId) }
    val snippet = snippets.value?.find { it.id == snippetId }
    val saved = snippet?.lastRun?.outputs
    var index by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(start) }
    if (saved == null) {
        Screen {
            PageColumn {
                TopBar(onHome, back = "Back to note" to onBack)
                Muted(if (!snippets.loaded) "Loading…" else snippets.error ?: "This output isn’t here any more. Run the code again to make it.")
            }
        }
        return
    }
    val files = remember(saved) { OutputFiles.Saved(saved) }
    OutputViewer(saved.items, index, files, snippet?.filename.orEmpty().ifEmpty { "output" }, onBack, onHome, onStep = { index = it })
}
