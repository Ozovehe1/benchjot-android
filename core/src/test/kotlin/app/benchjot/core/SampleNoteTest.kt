package app.benchjot.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleNoteTest {
    // The app's own asset (copied from the web by scripts/web-fixtures.mjs; CI fails on drift).
    private val dir = File("../app/src/main/assets/$SAMPLE_ASSETS")
    private val note = parseSampleNote(File(dir, "note.json").readText())

    @Test
    fun theWebLandingsNote() {
        assertEquals("success", note.entry.outcome)
        val s = note.snippets.single()
        assertEquals("python", s.language)
        assertEquals(codeHash(s.content), s.lastRun!!.codeHash)
        val outputs = s.lastRun!!.outputs!!
        assertEquals(listOf("table", "image", "chart"), outputs.items.map { it.kind })
        assertEquals("plotly", outputs.items[2].lib)
    }

    @Test
    fun everyOutputFileIsBundled() {
        val names = note.snippets.single().lastRun!!.outputs!!.items.flatMap { it.files.values }
        for (n in names) assertTrue(n, File(dir, n).isFile)
    }
}
