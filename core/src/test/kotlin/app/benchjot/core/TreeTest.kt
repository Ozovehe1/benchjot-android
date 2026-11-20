package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeTest {
    private val nodes = listOf(
        NoteIndexItem("root", "Root"), NoteIndexItem("a", "A", "root"), NoteIndexItem("b", "B", "a"), NoteIndexItem("c", "C", "root"),
    )

    @Test fun ancestorsRootFirst() {
        assertEquals(listOf("root", "a"), ancestors("b", nodes).map { it.id })
        assertEquals(emptyList<String>(), ancestors("root", nodes).map { it.id })
    }

    @Test fun ancestorsStopOnCycles() {
        val loop = listOf(NoteIndexItem("x", "X", "y"), NoteIndexItem("y", "Y", "x"))
        assertEquals(listOf("y"), ancestors("x", loop).map { it.id })
    }

    @Test fun childrenAndDescendants() {
        assertEquals(listOf("a", "c"), childrenOf("root", nodes).map { it.id })
        assertEquals(setOf("a", "b", "c"), descendantIds("root", nodes))
    }

    @Test fun cyclesAreRefused() {
        assertTrue(wouldCycle("root", "b", nodes))
        assertTrue(wouldCycle("a", "a", nodes))
        assertFalse(wouldCycle("b", "c", nodes))
        assertFalse(wouldCycle("b", null, nodes))
        assertEquals(listOf("c"), parentCandidates("a", nodes).filter { it.id != "root" }.map { it.id })
    }
}
