package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same choices as the web's figuresOf. */
class FiguresTest {
    private fun run(at: String, vararg items: OutputItem) = LastRun(RunOutcome.Ok, "", "", null, null, 1, "browser", "h", at, SavedOutputs(items = items.toList(), dir = "u/c/r"))

    @Test fun visualsNewestFirstNoTables() {
        val figs = figuresOf(
            listOf(
                Triple("c1", "n1", run("2026-11-25T10:00:00Z", OutputItem("image", source = "matplotlib", title = "Loss", files = mapOf("png" to "0.png")), OutputItem("table", files = mapOf("json" to "1.json")))),
                Triple("c2", "n2", run("2026-11-26T10:00:00Z", OutputItem("image", source = "matplotlib", title = "Grains", files = mapOf("gif" to "0.gif")), OutputItem("chart", lib = "plotly", files = mapOf("json" to "1.json")))),
                Triple("c3", "n3", null),
            ),
        )
        assertEquals(listOf("c2:0 animation Grains", "c1:0 figure Loss"), figs.map { "${it.key} ${it.kind} ${it.title}" }) // a chart without a picture is skipped
    }
}
