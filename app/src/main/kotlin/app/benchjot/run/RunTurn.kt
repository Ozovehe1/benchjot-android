package app.benchjot.run

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * Python runs on the phone take turns: one at a time in the ":python" process, so each has the
 * phone's memory to itself. A run that starts second waits, saying so, and can still be stopped
 * while it waits. Main thread only.
 */
object RunTurn {
    private val mutex = Mutex()
    private const val POLL_MS = 200L

    /** Takes the turn for [owner]; [onWait] once if it must wait. False when [stopped] says to give up first. */
    suspend fun take(owner: Any, onWait: () -> Unit, stopped: () -> Boolean): Boolean {
        if (!mutex.tryLock(owner)) {
            onWait()
            while (!mutex.tryLock(owner)) {
                if (stopped()) return false
                delay(POLL_MS)
            }
        }
        return true
    }

    fun give(owner: Any) {
        if (mutex.holdsLock(owner)) mutex.unlock(owner)
    }
}
