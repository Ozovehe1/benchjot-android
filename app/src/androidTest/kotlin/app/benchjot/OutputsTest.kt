package app.benchjot

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.benchjot.core.DownloadFormat
import app.benchjot.core.Manifest
import app.benchjot.core.OutputBuffer
import app.benchjot.core.OutputItem
import app.benchjot.core.RunOutcome
import app.benchjot.core.checkManifest
import app.benchjot.core.chart.ChartResult
import app.benchjot.core.chart.ChartTheme
import app.benchjot.core.chart.fullRange
import app.benchjot.core.chart.layout
import app.benchjot.core.chart.mapChart
import app.benchjot.core.downloadFormats
import app.benchjot.data.LocalOutputs
import app.benchjot.run.DeviceRunner
import app.benchjot.ui.BenchjotTheme
import app.benchjot.ui.ChartCanvas
import app.benchjot.ui.ChartState
import app.benchjot.ui.OutputFiles
import app.benchjot.ui.exportBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Rich outputs on a real Android (CI emulator: x86_64, and armeabi-v7a under ARM translation):
// the bundled plotting libraries import, bj_capture writes every kind of output on the phone,
// a native Plotly chart answers a tap with the point's values, and every download format the
// viewer offers produces a file of that type.
@RunWith(AndroidJUnit4::class)
class OutputsTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }

    private fun runPython(code: String, outDir: File? = null, timeoutMs: Long = 240_000): Pair<app.benchjot.core.RunResult, String> = runBlocking {
        val out = OutputBuffer()
        val r = withContext(Dispatchers.Main) {
            DeviceRunner.run(context, this, "python", code, onStatus = {}, onOutput = out::add, timeoutMs = timeoutMs, outDir = outDir).done.await()
        }
        r to out.text()
    }

    @Test fun plottingLibrariesImport() {
        val (r, out) = runPython(
            "import matplotlib, pandas, PIL, plotly, bokeh, seaborn, altair\n" +
                "print(' '.join(m.__name__ for m in (matplotlib, pandas, PIL, plotly, bokeh, seaborn, altair)))",
        )
        assertEquals(r.error?.text, RunOutcome.Ok, r.outcome)
        assertEquals("matplotlib pandas PIL plotly bokeh seaborn altair\n", out)
    }

    @Test fun captureWritesEveryKindOfOutput() {
        val dir = File(context.cacheDir, "test-out-${System.nanoTime()}")
        val code = """
            import matplotlib.pyplot as plt
            import pandas as pd
            import plotly.express as px
            import altair as alt
            import seaborn as sns
            from bokeh.plotting import figure, show
            from PIL import Image
            df = pd.DataFrame({"epoch": [1, 2, 3, 4], "loss": [0.9, 0.6, 0.5, 0.45]})
            plt.plot(df.epoch, df.loss); plt.title("Loss"); plt.show()
            display(df)
            px.line(df, x="epoch", y="loss").show()
            alt.Chart(df).mark_line().encode(x="epoch", y="loss").show()
            p = figure(title="Bokeh loss"); p.line(df.epoch, df.loss); show(p)
            sns.lineplot(data=df, x="epoch", y="loss"); plt.show()
            display(Image.new("RGB", (8, 4), (40, 120, 200)))
            print("done")
        """.trimIndent()
        val (r, out) = runPython(code, dir)
        assertEquals(r.error?.text, RunOutcome.Ok, r.outcome)
        assertEquals("done\n", out)
        val m = checkManifest(json.decodeFromString(Manifest.serializer(), File(dir, "manifest.json").readText()))
        val kinds = m.items.map { it.kind + ":" + (it.lib ?: it.source ?: "") }
        assertEquals(listOf("image:matplotlib", "table:", "chart:plotly", "chart:vega-lite", "chart:bokeh", "image:matplotlib", "image:pil"), kinds)
        // Matplotlib figures come as PNG + SVG + PDF; every file the manifest names exists.
        assertEquals(setOf("png", "svg", "pdf"), m.items[0].files.keys)
        for (item in m.items) for (name in item.files.values) assertTrue("missing $name", File(dir, name).length() > 0)
        dir.deleteRecursively()
    }

    private val plotly = """{"data":[{"type":"scatter","mode":"lines+markers","name":"loss","x":[1,2,3,4],"y":[0.9,0.6,0.5,0.45]}],"layout":{"title":{"text":"Loss"}}}"""

    @Test fun tapOnAPlotlyPointShowsItsValue() {
        val chart = (mapChart("plotly", plotly) as ChartResult.Ok).chart
        val state = ChartState(chart)
        compose.setContent { BenchjotTheme { ChartCanvas(state, interactive = true, modifier = Modifier.fillMaxWidth().height(320.dp)) } }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("chart").fetchSemanticsNode()
        val w = node.size.width.toFloat(); val h = node.size.height.toFloat()
        val density = context.resources.displayMetrics.density
        val scene = layout(chart, w, h, ChartTheme(0, 0, 0, 0), density)
        val v = fullRange(chart, emptySet())
        val x = scene.plot.l + ((2.0 - v.x0) / (v.x1 - v.x0)).toFloat() * scene.plot.w
        val y = scene.plot.b - ((0.6 - v.y0) / (v.y1 - v.y0)).toFloat() * scene.plot.h
        compose.onNodeWithTag("chart").performTouchInput { click(Offset(x, y)) }
        // A tap is only a tap once the double-tap window has passed (detectTapGestures waits for a
        // second tap on the test clock), so move that clock past it instead of relying on timing.
        compose.mainClock.advanceTimeBy(2L * android.view.ViewConfiguration.getDoubleTapTimeout())
        compose.waitForIdle()
        val said = compose.onNodeWithTag("chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue("tooltip '$said' after a tap at ($x, $y) in ${scene.plot}", said.contains("0.6"))
    }

    @Test fun everyChartDownloadFormatIsThatFileType() = runBlocking {
        val dir = File(context.cacheDir, "test-dl-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "0.json").writeText(plotly)
        val item = OutputItem(kind = "chart", lib = "plotly", version = "6", title = "Loss", files = mapOf("json" to "0.json"))
        val files = OutputFiles.Live(LocalOutputs(Manifest(items = listOf(item)), dir))
        val chart = ChartState((mapChart("plotly", plotly) as ChartResult.Ok).chart)
        val formats: List<DownloadFormat> = downloadFormats(item)
        assertEquals(listOf("png", "jpg", "webp", "svg", "pdf", "json"), formats.map { it.format })
        val sig = mapOf("png" to "\u0089PNG", "jpg" to "\u00ff\u00d8\u00ff", "webp" to "RIFF", "svg" to "<svg", "pdf" to "%PDF", "json" to "{")
        for (f in formats) {
            val bytes = exportBytes(item, files, f, chart, 2f)
            val head = String(bytes.copyOfRange(0, minOf(bytes.size, 64)), Charsets.ISO_8859_1)
            assertTrue("${f.format}: ${head.take(16)}", head.trimStart().startsWith(sig.getValue(f.format)) || (f.format == "svg" && head.contains("<svg")))
        }
        dir.deleteRecursively()
        Unit
    }
}

