package app.benchjot.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.OutputItem
import app.benchjot.core.SavedOutputs
import app.benchjot.core.cellText
import app.benchjot.core.chart.ChartResult
import app.benchjot.core.chart.mapChart
import app.benchjot.core.displayFile
import app.benchjot.core.outputLabel
import app.benchjot.data.LocalOutputs
import app.benchjot.data.OutputStore
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

// Figures, tables and charts under a code block (the web's run-outputs.tsx), from the live run's
// files on the phone or the stored run in Storage. Each opens on its own screen (OutputViewer.kt).

/** Where an output's files come from: the run that just finished, or the saved run. */
sealed interface OutputFiles {
    suspend fun bytes(name: String): ByteArray
    data class Live(val local: LocalOutputs) : OutputFiles {
        override suspend fun bytes(name: String) = withContext(Dispatchers.IO) { local.file(name).readBytes() }
    }
    data class Saved(val saved: SavedOutputs) : OutputFiles {
        override suspend fun bytes(name: String) = OutputStore.bytes(saved, name)
    }
}

/** Decodes PNG / JPG / WebP / GIF / BMP, or draws an SVG, scaled down to at most `maxPx` on its longer side. */
suspend fun decodeImage(bytes: ByteArray, format: String, maxPx: Int): Bitmap? = withContext(Dispatchers.Default) {
    if (format == "svg") {
        val svg = runCatching { SVG.getFromString(bytes.decodeToString()) }.getOrNull() ?: return@withContext null
        val w0 = svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: 800f
        val h0 = svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: 600f
        val scale = minOf(maxPx / maxOf(w0, h0), 3f)
        val bmp = Bitmap.createBitmap((w0 * scale).toInt().coerceAtLeast(1), (h0 * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        svg.setDocumentWidth(bmp.width.toFloat()); svg.setDocumentHeight(bmp.height.toFloat())
        svg.renderToCanvas(Canvas(bmp))
        return@withContext bmp
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun ext(name: String) = name.substringAfterLast('.')

/** An image output as a bitmap (or null while loading / if it can't be decoded). */
@Composable
fun rememberOutputImage(item: OutputItem, files: OutputFiles, maxPx: Int): ImageBitmap? {
    var image by remember(item, files) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item, files, maxPx) {
        val name = displayFile(item) ?: return@LaunchedEffect
        image = runCatching { decodeImage(files.bytes(name), ext(name), maxPx)?.asImageBitmap() }.getOrNull()
    }
    return image
}

/** A chart output mapped into the native model (or why it can't be). */
sealed interface LoadedChart {
    data object Loading : LoadedChart
    data class Ready(val state: ChartState) : LoadedChart
    data class Fallback(val reason: String, val image: ImageBitmap?) : LoadedChart
}

@Composable
fun rememberChart(item: OutputItem, files: OutputFiles): LoadedChart {
    var loaded by remember(item, files) { mutableStateOf<LoadedChart>(LoadedChart.Loading) }
    LaunchedEffect(item, files) {
        loaded = try {
            val spec = files.bytes(item.files.getValue("json")).decodeToString()
            when (val r = withContext(Dispatchers.Default) { mapChart(item.lib.orEmpty(), spec) }) {
                is ChartResult.Ok -> LoadedChart.Ready(ChartState(r.chart))
                is ChartResult.Unsupported -> {
                    // The static render the web stored at run time, when there is one.
                    val still = item.files["png"]?.let { n -> runCatching { decodeImage(files.bytes(n), "png", 2048)?.asImageBitmap() }.getOrNull() }
                    LoadedChart.Fallback(r.reason, still)
                }
            }
        } catch (e: Exception) {
            LoadedChart.Fallback("this chart (${e.message ?: "couldn't load it"})", null)
        }
    }
    return loaded
}

/** Why a chart shows as a still picture (or not at all) on the phone. */
fun fallbackNote(reason: String, hasImage: Boolean) =
    if (hasImage) "Shown as a picture: $reason aren't drawn natively on Android yet." else "Can't draw $reason on Android yet; open it on the web."

private val mono12 = TextStyle(fontFamily = Type.mono, fontSize = 12.sp)

/** A table preview: index columns muted, numbers right-aligned, scrolls sideways. */
@Composable
fun TablePreview(item: OutputItem, rows: Int = 8, modifier: Modifier = Modifier) {
    val shown = item.rows.take(rows)
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            item.columns.indices.forEach { c ->
                Column {
                    Text(item.columns[c], Modifier.padding(horizontal = 8.dp, vertical = 6.dp).widthIn(min = 24.dp), style = mono12, color = t.muted, maxLines = 1)
                    Hairline()
                    shown.forEach { r ->
                        val v = r.getOrNull(c)
                        val number = v is JsonPrimitive && !v.isString && v.content != "null" && v.content.toDoubleOrNull() != null
                        Text(
                            v?.let(::cellText).orEmpty(), Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(min = 24.dp), style = mono12.copy(textAlign = if (number) TextAlign.End else TextAlign.Start),
                            color = if (c < item.indexColumns) t.muted else t.fg, maxLines = 1,
                        )
                        Hairline()
                    }
                }
            }
        }
        if (item.totalRows > shown.size) {
            val cols = if (item.totalColumns + item.indexColumns > item.columns.size) " · ${item.columns.size - item.indexColumns} of ${item.totalColumns} columns" else ""
            Text("${shown.size} of ${item.totalRows} rows$cols", Modifier.padding(vertical = 4.dp), style = mono12, color = t.faint)
        }
    }
}

@Composable
private fun Preview(item: OutputItem, files: OutputFiles) {
    when {
        item.isTable -> TablePreview(item)
        item.isChart -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val h = inlineChartHeight(maxWidth.value).dp
            when (val c = rememberChart(item, files)) {
                LoadedChart.Loading -> Box(Modifier.fillMaxWidth().height(h))
                is LoadedChart.Ready -> ChartCanvas(c.state, interactive = false, modifier = Modifier.fillMaxWidth().height(h))
                is LoadedChart.Fallback -> Column {
                    c.image?.let { Image(it, item.title, Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit) }
                    Text(fallbackNote(c.reason, c.image != null), Modifier.padding(vertical = 4.dp), style = mono12, color = t.muted)
                }
            }
        }
        else -> BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val img = rememberOutputImage(item, files, 1600)
            // While it loads, hold the height it will take (the run records its size), so the page
            // doesn't grow under the reader, nor under a scroll position restored on coming back.
            if (img == null) Box(Modifier.fillMaxWidth().height(imagePlaceholderHeight(maxWidth.value, item.width, item.height).dp))
            else Image(img, item.title.ifEmpty { outputLabel(item) }, Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit)
        }
    }
}

/** The inline image's height in dp before it has loaded: its own aspect at the column's width, capped as the image is (480). */
fun imagePlaceholderHeight(widthDp: Float, w: Int, h: Int): Float =
    if (w > 0 && h > 0) minOf(widthDp * h / w, 480f) else 160f

/** The outputs under a code block, in the order the code made them; `onOpen` null until they are saved. */
@Composable
fun RunOutputs(items: List<OutputItem>, notes: List<String>, files: OutputFiles, onOpen: ((Int) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Figures and tables" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEachIndexed { i, item ->
            val label = outputLabel(item) + if (!item.isTable && item.title.isNotEmpty()) " · ${item.title}" else ""
            val open = onOpen?.let { o -> Modifier.plainClick { o(i) } } ?: Modifier
            Column(Modifier.fillMaxWidth().then(open).testTag("output-$i")) {
                Preview(item, files)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = mono12, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onOpen != null) Text("Open", style = Type.xs, color = t.muted)
                }
            }
        }
        notes.forEach { Text(it, style = mono12, color = t.muted) }
    }
}
