package app.benchjot.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutosaveTest {
    @Test fun debouncesAndSavesTheLatestValue() = runTest {
        val saves = mutableListOf<String>()
        val registry = SaveRegistry()
        val s = Autosaver(backgroundScope, "a", registry, delayMs = 900) { saves += it }
        s.update("ab")
        s.update("abc")
        assertEquals(SaveState.Pending, registry.summary.value.state)
        advanceTimeBy(899); runCurrent()
        assertEquals(emptyList<String>(), saves)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("abc"), saves)
        assertEquals(SaveState.Saved, registry.summary.value.state)
    }

    @Test fun backToTheSavedValueSavesNothing() = runTest {
        val saves = mutableListOf<String>()
        val s = Autosaver(backgroundScope, "a", null) { saves += it }
        s.update("b"); s.update("a")
        advanceTimeBy(2000); runCurrent()
        assertEquals(emptyList<String>(), saves)
    }

    @Test fun flushSavesNowAndErrorsAreReported() = runTest {
        val registry = SaveRegistry()
        var fail = true
        val saves = mutableListOf<Int>()
        val s = Autosaver(backgroundScope, 0, registry) { if (fail) error("offline") else saves += it }
        s.update(1)
        registry.flushAll()
        assertEquals(SaveSummary(SaveState.Error, "offline"), registry.summary.value)
        fail = false
        registry.flushAll()
        assertEquals(listOf(1), saves)
        assertEquals(SaveState.Saved, registry.summary.value.state)
    }
}
