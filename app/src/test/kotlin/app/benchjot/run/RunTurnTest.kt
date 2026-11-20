package app.benchjot.run

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two Python runs never hold the phone at the same time. */
class RunTurnTest {
    @Test fun theSecondWaitsSayingSoAndGoesWhenTheFirstEnds() = runTest {
        val first = Any()
        val other = Any()
        assertTrue(RunTurn.take(first, {}, { false }))
        var waited = 0
        val second = async { RunTurn.take(other, { waited++ }, { false }) }
        runCurrent()
        advanceTimeBy(1_000)
        assertFalse(second.isCompleted)
        assertEquals(1, waited) // said once, not on every check
        RunTurn.give(first)
        assertTrue(second.await())
        RunTurn.give(other)
    }

    @Test fun stoppingWhileWaitingGivesUpAndHoldsNothing() = runTest {
        val first = Any()
        assertTrue(RunTurn.take(first, {}, { false }))
        var stop = false
        val other = Any()
        val second = async { RunTurn.take(other, {}, { stop }) }
        advanceTimeBy(500)
        stop = true
        assertFalse(second.await())
        RunTurn.give(other) // not held: nothing happens
        RunTurn.give(first)
        assertTrue(RunTurn.take(other, {}, { false })) // free again
        RunTurn.give(other)
    }
}
