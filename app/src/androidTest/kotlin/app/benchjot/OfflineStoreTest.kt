package app.benchjot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.benchjot.core.Note
import app.benchjot.core.LocalData
import app.benchjot.core.Op
import app.benchjot.core.Project
import app.benchjot.core.Cell
import app.benchjot.core.apply
import app.benchjot.data.OfflineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's copy in SQLite (data/OfflineStore.kt): a row per note and per cell, changes written as changes. */
@RunWith(AndroidJUnit4::class)
class OfflineStoreTest {
    private val t0 = "2026-11-19T10:00:00Z"
    private fun note(id: String, title: String = "") = Note(id, "u", title = title, createdAt = t0, updatedAt = t0)
    private fun cell(id: String, note: String) = Cell(id, "u", note, content = "print(1)", position = 0, createdAt = t0, updatedAt = t0)
    private fun store() = OfflineStore(InstrumentationRegistry.getInstrumentation().targetContext).also { it.drop("test-a"); it.drop("test-b") }

    @Test fun aCopyIsKeptAndReadBack() {
        val s = store()
        val d = LocalData(notes = mapOf("n1" to note("n1", "Warmup")), cells = mapOf("c1" to cell("c1", "n1")), projects = listOf(Project("p", "P")), syncedAt = t0)
        s.save("test-a", LocalData(), d)
        val back = s.load("test-a")!!
        assertEquals(d.notes, back.notes)
        assertEquals(d.cells, back.cells)
        assertEquals(d.projects, back.projects)
        assertEquals(t0, back.syncedAt)
        assertNull(s.load("test-b"))
    }

    @Test fun changesAreWrittenAndDeletionsRemoved() {
        val s = store()
        var d = LocalData(notes = mapOf("n1" to note("n1"), "n2" to note("n2")), cells = mapOf("c1" to cell("c1", "n1")), syncedAt = t0)
        s.save("test-a", LocalData(), d)
        val before = d
        d = d.apply(Op.DeleteNote("n1"), t0).apply(Op.CreateNote(note("n3", "New")), t0)
        s.save("test-a", before, d)
        val back = s.load("test-a")!!
        assertEquals(setOf("n2", "n3"), back.notes.keys)
        assertEquals(emptySet<String>(), back.cells.keys) // the note's cell went with it
        assertEquals(2, back.outbox.size)
    }

    @Test fun anAccountIsForgotten() {
        val s = store()
        s.save("test-a", LocalData(), LocalData(notes = mapOf("n1" to note("n1")), syncedAt = t0))
        s.save("test-b", LocalData(), LocalData(notes = mapOf("n2" to note("n2")), syncedAt = t0))
        s.drop("test-a")
        assertNull(s.load("test-a"))
        assertEquals(setOf("n2"), s.load("test-b")!!.notes.keys)
    }
}
