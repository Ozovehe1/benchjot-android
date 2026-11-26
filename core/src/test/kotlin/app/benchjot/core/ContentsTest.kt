package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the web's tests/outline.test.ts. */
class ContentsTest {
    @Test fun headingsLikeMarkdown() {
        assertEquals(listOf(1 to "Question", 2 to "Setup", 3 to "Seeds"), headingsOf("# Question\ntext\n## Setup\n### Seeds ###\n#### not a heading here\n#nospace"))
        assertEquals(listOf(2 to "Result"), headingsOf("```python\n# ---- ribbon geometry (nm) ----\nx = 1\n```\n## Result"))
        assertEquals("Grain size in nm vs Warmup sweep and paper", plainHeading("**Grain** size in `nm` vs [[Warmup sweep|e1]] and [paper](https://x.org)"))
    }

    @Test fun headingsAndEveryCell() {
        val items = contents("# Nanocrystalline ribbon\n## Question", listOf(Triple("c1", "## Geometry", ""), Triple("c2", null, "### Grain sizes")), listOf("", "grains.py"))
        assertEquals(
            listOf("h1 Nanocrystalline ribbon", "h2 Question", "h2 Geometry", "cell 1  at 3", "cell 2 grains.py at 3", "h3 Grain sizes"),
            items.map { if (it.cellId != null) "cell ${it.n} ${it.text} at ${it.level}" else "h${it.level} ${it.text}" },
        )
        val plain = contents("First simulate the ribbon.", listOf(Triple("c1", "", ""), Triple("c2", "", "")))
        assertEquals(listOf(1, 2), plain.map { it.n })
    }
}
