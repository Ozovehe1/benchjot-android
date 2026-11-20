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
        assertEquals("success", note.note.outcome)
        assertTrue(note.cells.size >= 2) // two experiments, each with its saved run
        for (s in note.cells) {
            assertEquals("python", s.language)
            assertEquals(codeHash(s.content), s.lastRun!!.codeHash)
            assertTrue(s.lastRun!!.outputs!!.items.isNotEmpty())
        }
    }

    @Test
    fun everyOutputFileIsBundled() {
        // Each cell's files are in the folder named by its cell's id.
        for (s in note.cells) for (n in s.lastRun!!.outputs!!.items.flatMap { it.files.values }) assertTrue("${s.id}/$n", File(dir, "${s.id}/$n").isFile)
    }
}
