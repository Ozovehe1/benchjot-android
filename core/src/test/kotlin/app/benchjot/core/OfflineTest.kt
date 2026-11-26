package app.benchjot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline first: changes made on the phone's copy, queued, kept against the server's older copy, searched locally. */
class OfflineTest {
    private val t0 = "2026-11-19T10:00:00Z"
    private fun note(id: String, title: String = "", project: String? = null, outcome: String? = null, at: String = t0, notes: String = "") =
        Note(id, "u", project, null, title, notes, outcome, at, at)
    private fun cell(id: String, note: String, pos: Int, code: String = "") = Cell(id, "u", note, content = code, position = pos, createdAt = t0, updatedAt = t0)
    private fun fields(vararg kv: Pair<String, String?>) = JsonObject(kv.associate { (k, v) -> k to (v?.let(::JsonPrimitive) ?: JsonNull) })

    @Test fun aNoteIsWrittenOnThePhoneFirstAndQueued() {
        var d = LocalData().apply(Op.CreateNote(note("n1")), t0)
        d = d.apply(Op.PatchNote("n1", fields("title" to "Warmup")), "2026-11-19T10:01:00Z")
        d = d.apply(Op.PatchNote("n1", fields("notes" to "Does it help?")), "2026-11-19T10:02:00Z")
        assertEquals("Warmup", d.notes["n1"]!!.title)
        assertEquals("Does it help?", d.notes["n1"]!!.notes)
        assertEquals("2026-11-19T10:02:00Z", d.notes["n1"]!!.updatedAt)
        // Two patches in a row join: one send for the typing.
        assertEquals(2, d.outbox.size)
        assertEquals(fields("title" to "Warmup", "notes" to "Does it help?"), (d.outbox[1] as Op.PatchNote).fields)
        assertEquals(1, d.sent().outbox.size)
    }

    @Test fun cellsAreAddedChangedRunAndDeletedOnThePhone() {
        var d = LocalData().apply(Op.CreateNote(note("n1")), t0)
        d = d.apply(Op.CreateCells(listOf(cell("c2", "n1", 1), cell("c1", "n1", 0))), t0)
        d = d.apply(Op.PatchCell("c1", fields("content" to "print(1)", "language" to "python")), t0)
        val run = LastRun(RunOutcome.Ok, "1\n", "", null, null, 40, "browser", "h", t0)
        d = d.apply(Op.PatchCell("c1", JsonObject(mapOf("last_run" to Json.encodeToJsonElement(LastRun.serializer(), run)))), t0)
        assertEquals(listOf("c1", "c2"), d.cellsOf("n1").map { it.id })
        assertEquals("print(1)", d.cells["c1"]!!.content)
        assertEquals(run, d.cells["c1"]!!.lastRun)
        d = d.apply(Op.PatchCell("c1", JsonObject(mapOf("last_run" to JsonNull))), t0)
        assertNull(d.cells["c1"]!!.lastRun)
        d = d.apply(Op.DeleteNote("n1"), t0)
        assertTrue(d.cells.isEmpty() && d.notes.isEmpty())
    }

    @Test fun theServersOlderCopyNeverOverwritesAnEditNotYetSent() {
        var d = LocalData().withServerNote(note("n1", "Old"))
        d = d.apply(Op.PatchNote("n1", fields("title" to "Mine")), t0)
        d = d.withServerNote(note("n1", "Old"))
        assertEquals("Mine", d.notes["n1"]!!.title)
        d = d.sent().withServerNote(note("n1", "Mine, as sent"))
        assertEquals("Mine, as sent", d.notes["n1"]!!.title)

        d = d.withServerCells("n1", listOf(cell("c1", "n1", 0, "old")))
        d = d.apply(Op.PatchCell("c1", fields("content" to "new")), t0)
        d = d.withServerCells("n1", listOf(cell("c1", "n1", 0, "old")))
        assertEquals("new", d.cells["c1"]!!.content)
    }

    @Test fun theWholeAccountIsTakenFromTheServerExceptWhatWaits() {
        var d = LocalData().apply(Op.CreateNote(note("mine", "Offline note")), t0)
        d = d.withServerAccount(listOf(note("a", "A"), note("gone", "G")), listOf(cell("ca", "a", 0)), listOf(Project("p", "Proj")), t0)
        assertEquals(setOf("mine", "a", "gone"), d.notes.keys)
        d = d.apply(Op.DeleteNote("gone"), t0)
        d = d.withServerAccount(listOf(note("a", "A"), note("gone", "G")), emptyList(), emptyList(), t0)
        assertEquals(setOf("mine", "a"), d.notes.keys) // deleted here, not back from the server's copy
        assertEquals(t0, d.syncedAt)
    }

    @Test fun searchAndIndexWorkWithoutTheServer() {
        var d = LocalData()
        d = d.withServerAccount(
            listOf(note("a", "Warmup sweep", "p", "success", "2026-11-19T09:00:00Z"), note("b", "Dropout", null, null, "2026-11-19T11:00:00Z", notes = "learning rate 0.1")),
            listOf(cell("cb", "b", 0, "import torch")), listOf(Project("p", "Proj")), t0,
        )
        assertEquals(listOf("b", "a"), d.search(emptyList(), null, false, null).map { it.id })
        assertEquals(listOf("b"), d.search(listOf("TORCH"), null, false, null).map { it.id }) // in a cell's code
        assertEquals(listOf("b"), d.search(listOf("learning", "rate"), null, false, null).map { it.id })
        assertEquals(listOf("a"), d.search(emptyList(), "p", false, null).map { it.id })
        assertEquals(listOf("b"), d.search(emptyList(), null, true, null).map { it.id })
        assertEquals(listOf("b"), d.search(emptyList(), null, false, "unmarked").map { it.id })
        assertEquals(listOf("b", "a"), d.index().map { it.id })
    }

    @Test fun aDeletedNoteWaitsInRecentlyDeletedAndAPinComesFirst() {
        val t1 = "2026-11-20T10:00:00Z"
        var d = LocalData().withServerAccount(listOf(note("a", "A", notes = "[[B]]"), note("b", "B")), emptyList(), emptyList(), t0)
        // Deleted: out of search, the index and links; kept for Recently Deleted; its edit time stays.
        d = d.apply(Op.PatchNote("a", fields("deleted_at" to t1)), t1)
        assertEquals(listOf("b"), d.search(emptyList(), null, false, null).map { it.id })
        assertEquals(listOf("b"), d.index().map { it.id })
        assertEquals(emptyList<SourcedLink>(), d.localLinks())
        assertEquals(listOf("a"), d.recentlyDeleted().map { it.id })
        assertEquals(t0, d.notes.getValue("a").updatedAt)
        // Restored, then pinned: first in the list, still not edited.
        d = d.apply(Op.PatchNote("a", fields("deleted_at" to null)), t1).apply(Op.PatchNote("b", fields("pinned_at" to t1)), t1)
        assertEquals(listOf("b", "a"), d.search(emptyList(), null, false, null).map { it.id })
        assertEquals(t0, d.notes.getValue("b").updatedAt)
        assertEquals(emptyList<Note>(), d.recentlyDeleted())
    }

    @Test fun projectsChangeOnThePhone() {
        var d = LocalData().apply(Op.CreateNote(note("n", project = "p")), t0)
        d = d.apply(Op.CreateProject(Project("p", "Zeta")), t0).apply(Op.CreateProject(Project("q", "alpha")), t0)
        assertEquals(listOf("alpha", "Zeta"), d.projects.map { it.name })
        d = d.apply(Op.DeleteProject("p"), t0)
        assertNull(d.notes["n"]!!.projectId) // the notes stay, unfiled
    }

    @Test fun theQueueSurvivesBeingWrittenAndRead() {
        val d = LocalData().apply(Op.CreateNote(note("n1")), t0).apply(Op.PatchCell("c", fields("content" to "x")), t0)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val back = json.decodeFromString(LocalData.serializer(), json.encodeToString(LocalData.serializer(), d))
        assertEquals(d, back)
    }

    @Test fun onlyNotNowFailuresWait() {
        assertTrue(isTransient(ErrorFacts(code = "network")))
        assertTrue(isTransient(ErrorFacts(status = 503)))
        assertEquals(false, isTransient(ErrorFacts(status = 403)))
        assertEquals(false, isTransient(ErrorFacts(status = 409)))
    }

    private val later = "2026-11-19T11:00:00Z"
    private fun base() = LocalData().withServerAccount(listOf(note("a", "A"), note("b", "B")), listOf(cell("ca", "a", 0, "x"), cell("cb", "b", 0, "y")), listOf(Project("p", "P")), t0)

    @Test fun syncByChangesKeepsUnchangedRowsAsTheyAre() {
        val d = base()
        val n = d.withServerChanges(Changes(later, notes = listOf(note("a", "A2", at = later), note("b", "B"))), listOf(Project("p", "P")))
        assertEquals("A2", n.notes["a"]!!.title)
        assertTrue(n.notes["b"] === d.notes["b"]) // same edit time: kept as it was
        assertTrue(n.cells === d.cells) // no cell changed
        assertEquals(later, n.syncedAt)
    }

    @Test fun syncByChangesRemovesDeletionsAndKeepsWhatWaits() {
        var d = base()
        d = d.apply(Op.PatchNote("a", JsonObject(mapOf("title" to JsonPrimitive("Mine")))), later)
        val n = d.withServerChanges(Changes(later, notes = listOf(note("a", "Theirs", at = later), note("c")), cells = listOf(cell("cc", "c", 0)), deleted = listOf(Changes.Deleted("note", "b"), Changes.Deleted("note", "a"))), emptyList())
        assertEquals(setOf("a", "c"), n.notes.keys)
        assertEquals("Mine", n.notes["a"]!!.title)
        assertEquals(setOf("ca", "cc"), n.cells.keys)
    }

    @Test fun searchAndLinksStayQuickOnABigAccount() {
        val notes = (0 until 5_000).map { note("n$it", "Note $it", notes = "see [[Note ${(it * 7) % 5_000}]]") }
        val cells = (0 until 5_000).flatMap { i -> (0 until 5).map { c -> cell("c${i}_$c", "n$i", c, "import numpy # $i") } }
        val d = LocalData().withServerAccount(notes, cells, emptyList(), t0)
        val t = System.nanoTime()
        assertEquals(1, d.search(listOf("# 4321"), null, false, null).size)
        assertEquals(5_000, d.localLinks().size)
        assertTrue("took ${(System.nanoTime() - t) / 1e6} ms", System.nanoTime() - t < 3_000_000_000L)
    }

    @Test fun searchHitSaysWhereANoteMatched() {
        val d = LocalData().withServerAccount(
            listOf(note("n", "Warmup", notes = "We try AdamW here.\nThen SGD.")),
            listOf(cell("c1", "n", 0, "import numpy as np\nmodel = torch.nn.Linear(2, 2)").copy(filename = "train.py"), cell("c2", "n", 1, "print(1)").copy(noteBefore = "uses torch too")),
            emptyList(), t0,
        )
        assertEquals(SearchHit("code", "c1", 1, "train.py", 2, "model = torch.nn.Linear(2, 2)"), d.searchHit("n", listOf("TORCH")))
        assertEquals("note", d.searchHit("n", listOf("sgd"))?.where)
        assertEquals("c2", d.searchHit("n", listOf("too"))?.cellId)
        assertNull(d.searchHit("n", listOf("warmup")))
    }
}
