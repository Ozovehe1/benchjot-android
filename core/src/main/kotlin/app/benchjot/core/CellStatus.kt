package app.benchjot.core

// A code cell's state at a glance (each cell's edge, and the failed-cell link above a note's cells), from
// what is saved: how its last run ended, and whether its code changed since (the web's
// lib/notes/cell-status.ts).

enum class CellState { Ok, Failed, Stopped, Stale, Never }

/** Ok / Failed (an error or a timeout) / Stopped, or Stale when the code changed since that run, Never before a first run. */
fun cellState(content: String, lastRun: LastRun?): CellState = when {
    lastRun == null -> CellState.Never
    codeHash(content) != lastRun.codeHash -> CellState.Stale
    lastRun.outcome == RunOutcome.Ok -> CellState.Ok
    lastRun.outcome == RunOutcome.Stopped -> CellState.Stopped
    else -> CellState.Failed
}

/** The first failed cell, by position (1-based), for "Go to cell N"; null when none failed. */
fun firstFailed(states: List<CellState>): Int? = states.indexOf(CellState.Failed).takeIf { it >= 0 }?.plus(1)
