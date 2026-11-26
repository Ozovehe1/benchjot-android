package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FixTest {
    @Test fun diffKeepsOnlyChangesAndContext() {
        val before = (1..10).joinToString("\n") { "l$it" }
        val after = before.replace("l5", "L5")
        val hunks = diffHunks(lineDiff(before, after))
        assertEquals(listOf("l4", "l5", "L5", "l6"), hunks.map { it?.text })
        assertEquals(listOf(4, 5, 5, 6), hunks.map { it?.line })
    }

    @Test fun gapsBetweenHunks() {
        val before = (1..10).joinToString("\n") { "l$it" }
        val after = before.replace("l2", "x").replace("l9", "y")
        val hunks = diffHunks(lineDiff(before, after))
        assertEquals(1, hunks.count { it == null })
    }

    @Test fun noChangeNoHunks() {
        assertEquals(emptyList<DiffLine?>(), diffHunks(lineDiff("a\nb", "a\nb")))
    }
}
