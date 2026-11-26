package app.benchjot.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast at scale (the web's tests/scale.test.ts): on 3,000 notes and 15,000 cells the work that grows
 * with an account stays within budgets several times what a laptop takes, so work per note × per
 * cell can't come back unnoticed.
 */
class ScaleTest {
    private val n = 3_000
    private val words = "adam sgd warmup cosine dropout resnet cifar mnist batch norm loss accuracy epoch".split(" ")
    private fun text(k: Int, s: Int) = (0 until k).joinToString(" ") { words[(it * 7 + s) % words.size] }
    private val t0 = "2026-01-01T00:00:00Z"
    private val d: LocalData by lazy {
        val notes = (0 until n).map { i -> Note("e$i", "u", projectId = if (i % 3 == 0) null else "p", title = "Experiment $i", notes = "${text(120, i)}\n[[Experiment ${(i * 13) % n}]]", createdAt = t0, updatedAt = t0) }
        val cells = (0 until n).flatMap { i -> (0 until 5).map { c -> Cell("s${i}_$c", "u", "e$i", content = "import numpy as np\n# ${text(80, c)} $i\n", position = c, createdAt = t0, updatedAt = t0) } }
        LocalData().withServerAccount(notes, cells, listOf(Project("p", "Project")), t0)
    }
    private fun ms(f: () -> Unit): Double { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1e6 }
    private fun within(budget: Double, f: () -> Unit) = ms(f).let { assertTrue("took $it ms, budget $budget", it < budget) }

    @Test fun search() {
        within(3_000.0) { d.search(listOf("dropout"), null, false, null) }
        within(500.0) { d.search(listOf("norm", "2999"), null, false, null) }
    }

    @Test fun aChangeAndASyncByChanges() {
        within(200.0) { d.apply(Op.PatchNote("e5", JsonObject(mapOf("notes" to JsonPrimitive("x")))), t0) }
        within(300.0) { d.withServerChanges(Changes("2026-01-02T00:00:00Z", notes = listOf(d.notes["e7"]!!.copy(title = "y", updatedAt = "2026-01-02T00:00:00Z"))), listOf(Project("p", "Project"))) }
    }

    @Test fun theNotesFolderReMakesOnlyChangedNotes() {
        within(10_000.0) { noteFiles(d) }
        val changed = d.apply(Op.PatchNote("e5", JsonObject(mapOf("notes" to JsonPrimitive("x")))), t0)
        within(800.0) { noteFiles(changed) }
    }

    @Test fun linksBacklinksAndTheMap() {
        var links: List<SourcedLink> = emptyList()
        within(3_000.0) { links = d.localLinks() }
        val index = d.index()
        within(400.0) { backlinksTo("e13", links, index) }
        var g: MapGraph? = null
        within(1_000.0) { g = buildGraph(index, links) }
        within(400.0) { mapSlice(g!!) }
    }
}
