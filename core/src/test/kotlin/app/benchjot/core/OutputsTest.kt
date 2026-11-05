package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone reads the same manifests the web writes, and offers the same downloads (web fixtures). */
class OutputsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun manifest(case: String) = checkManifest(json.decodeFromString(Manifest.serializer(), Fixtures.text("outputs/$case/manifest.json")))

    @Test
    fun downloadFormatsAndLabelsMatchTheWeb() {
        val web = Fixtures.json("output_formats.json").jsonObject
        assertTrue(web.size >= 30)
        for ((case, items) in web) {
            val mine = manifest(case).items
            for ((i, w) in items.jsonArray.withIndex()) {
                val o = w.jsonObject
                assertEquals("$case label", o.getValue("label").jsonPrimitive.content, outputLabel(mine[i]))
                val expected = o.getValue("formats").jsonArray.map { f ->
                    val fo = f.jsonObject
                    listOf(fo.getValue("format"), fo.getValue("label"), fo.getValue("how"), fo["file"], fo["from"]).map { it?.jsonPrimitive?.content }
                }
                assertEquals("$case formats", expected, downloadFormats(mine[i]).map { listOf(it.format, it.label, it.how, it.file, it.from) })
            }
        }
    }

    @Test
    fun downloadNamesMatchTheWeb() {
        for (c in Fixtures.json("output_names.json").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("name").jsonPrimitive.content, outputDownloadName(o.getValue("base").jsonPrimitive.content, o.getValue("index").jsonPrimitive.content.toInt(), "png"))
        }
    }

    @Test
    fun tablePreviewCellsAndCsv() {
        val t = manifest("table").items.single()
        assertEquals(listOf("", "epoch", "loss", "val"), t.columns)
        assertEquals(listOf("0", "1", "0.9", "1"), t.rows[0].map(::cellText))
        assertTrue(previewCsv(t).startsWith(",epoch,loss,val\n0,1,0.9,1\n"))
    }

    @Test
    fun runRecordCarriesOutputsOnlyWhenThereAreSome() {
        val dir = "11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/33333333-3333-3333-3333-333333333333"
        val m = manifest("matplotlib")
        val r = RunResult(RunOutcome.Ok, null, 5)
        val saved = SavedOutputs(m.version, m.items, m.notes, dir)
        assertEquals(saved, toLastRun(emptyList(), r, "browser", "x", outputs = saved).outputs)
        assertEquals(null, toLastRun(emptyList(), r, "browser", "x", outputs = saved.copy(items = emptyList())).outputs)
        assertEquals(listOf("$dir/0.png", "$dir/0.svg", "$dir/0.pdf").sorted(), outputPaths(saved).sorted())
        assertEquals("png", displayFile(m.items.single())?.substringAfter('.'))
    }

    @Test(expected = ValidationError::class)
    fun rejectsPathsOutsideTheRunFolder() {
        checkManifest(Manifest(items = listOf(OutputItem(kind = "image", files = mapOf("png" to "../x.png")))))
    }
}
