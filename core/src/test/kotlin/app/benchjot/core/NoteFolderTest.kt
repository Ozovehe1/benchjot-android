package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every note as a file in Documents/Benchjot: folder by project, name by title, Markdown inside. */
class NoteFolderTest {
    private val t0 = "2026-11-19T10:00:00Z"
    private fun note(id: String, title: String, project: String? = null, at: String = t0) = Note(id, "u", project, null, title, "Does warmup help?", null, at, at)

    @Test fun eachNoteHasItsFileInItsProjectsFolder() {
        val d = LocalData(
            notes = mapOf(
                "a1b2c3d4" to note("a1b2c3d4", "Warmup sweep", "p"),
                "e5f6a7b8" to note("e5f6a7b8", "", null),
                "c9d0e1f2" to note("c9d0e1f2", "Warmup sweep", "p", "2026-11-19T11:00:00Z"),
                "f3a4b5c6" to note("f3a4b5c6", "a/b: test?", null),
            ),
            cells = mapOf("s" to Cell("s", "u", "a1b2c3d4", "train.py", "python", "print(1)", 0, createdAt = t0, updatedAt = t0)),
            projects = listOf(Project("p", "Vision / CNNs")),
        )
        val files = noteFiles(d)
        assertEquals(NoteFile("Benchjot/Vision - CNNs", "Warmup sweep.md", files["a1b2c3d4"]!!.text), files["a1b2c3d4"])
        assertEquals("Warmup sweep c9d0e1.md", files["c9d0e1f2"]!!.name) // the later one is told apart
        assertEquals("Benchjot/Notes", files["e5f6a7b8"]!!.dir)
        assertEquals("Untitled.md", files["e5f6a7b8"]!!.name)
        assertEquals("a-b- test-.md", files["f3a4b5c6"]!!.name)
        val text = files["a1b2c3d4"]!!.text
        assertTrue(text, text.startsWith("# Warmup sweep\n\nDoes warmup help?\n\n## train.py\n\n```python\nprint(1)\n```"))
    }

    @Test fun namesEveryFileSystemTakes() {
        assertEquals("Project", safeName("  ...  ", "Project"))
        assertEquals("x-y", safeName("x\\y", "n"))
        assertEquals(80, safeName("z".repeat(200), "n").length)
    }
}
