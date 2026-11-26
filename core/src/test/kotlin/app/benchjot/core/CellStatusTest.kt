package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** As the web's cell-status.test.ts. */
class CellStatusTest {
    private fun run(code: String, outcome: RunOutcome) = LastRun(outcome, "", "", null, null, 1, "browser", codeHash(code), "2026-11-19T10:00:00Z")

    @Test fun saysHowTheLastRunEndedOrThatTheCodeChanged() {
        assertEquals(CellState.Never, cellState("x", null))
        assertEquals(CellState.Ok, cellState("x", run("x", RunOutcome.Ok)))
        assertEquals(CellState.Failed, cellState("x", run("x", RunOutcome.Error)))
        assertEquals(CellState.Failed, cellState("x", run("x", RunOutcome.Timeout)))
        assertEquals(CellState.Stopped, cellState("x", run("x", RunOutcome.Stopped)))
        assertEquals(CellState.Stale, cellState("y", run("x", RunOutcome.Error)))
    }

    @Test fun findsTheFirstFailedCell() {
        assertEquals(3, firstFailed(listOf(CellState.Ok, CellState.Stale, CellState.Failed, CellState.Failed)))
        assertNull(firstFailed(listOf(CellState.Ok, CellState.Never)))
    }
}
