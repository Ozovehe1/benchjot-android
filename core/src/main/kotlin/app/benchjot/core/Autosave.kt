package app.benchjot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Autosave, as the web's src/lib/autosave.tsx: each editor has a debounced saver;
// a registry aggregates their status for the header ("Saved" / "Not saved") and
// flushes them all before leaving a note or deleting it.

enum class SaveState { Idle, Pending, Saving, Saved, Error }

data class SaveSummary(val state: SaveState, val error: String?)

class SaveRegistry {
    private val states = mutableMapOf<Any, Pair<SaveState, String?>>()
    private val savers = mutableSetOf<Autosaver<*>>()
    private val _summary = MutableStateFlow(SaveSummary(SaveState.Idle, null))
    val summary: StateFlow<SaveSummary> = _summary.asStateFlow()

    @Synchronized internal fun report(who: Any, state: SaveState, error: String?) {
        states[who] = state to error
        val top = PRIORITY.firstOrNull { p -> states.values.any { it.first == p } } ?: SaveState.Idle
        _summary.value = SaveSummary(top, states.values.firstOrNull { it.first == SaveState.Error }?.second)
    }

    @Synchronized internal fun add(saver: Autosaver<*>) { savers += saver }
    @Synchronized fun remove(saver: Autosaver<*>) { savers -= saver; states.remove(saver) }

    suspend fun flushAll() = synchronized(this) { savers.toList() }.forEach { it.flush() }

    private companion object {
        val PRIORITY = listOf(SaveState.Error, SaveState.Saving, SaveState.Pending, SaveState.Saved, SaveState.Idle)
    }
}

/**
 * Debounced autosave of a value. `save` gets the latest value; it runs at most once
 * at a time and again if the value changed while saving. `baseline` is what is
 * already persisted. Saves run in `scope` (an app-wide scope, so leaving a screen
 * doesn't cancel one half-way).
 */
class Autosaver<T>(
    private val scope: CoroutineScope,
    baseline: T,
    private val registry: SaveRegistry? = null,
    private val delayMs: Long = 900,
    /** The text shown for a failed save (the app passes its shared error table). */
    private val describe: (Exception) -> String = { it.message ?: "Could not save" },
    private val save: suspend (T) -> Unit,
) {
    private val lock = Mutex()
    @Volatile private var saved: T = baseline
    @Volatile private var latest: T = baseline
    private var timer: Job? = null

    init { registry?.add(this) }

    private fun report(state: SaveState, error: String? = null) = registry?.report(this, state, error)

    fun update(value: T) {
        latest = value
        timer?.cancel()
        if (value == saved) return
        report(SaveState.Pending)
        // The timer only starts the save; a later edit cancels the timer, never a save in flight.
        timer = scope.launch {
            delay(delayMs)
            scope.launch { flush() }
        }
    }

    /** Saves now whatever is pending. */
    suspend fun flush() {
        timer?.cancel()
        var again: Boolean
        do {
            again = lock.withLock {
                val v = latest
                if (v == saved) return@withLock false
                report(SaveState.Saving)
                try {
                    save(v)
                    saved = v
                    report(if (latest == v) SaveState.Saved else SaveState.Pending)
                    latest != v
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // After an error, wait for the next edit or flush.
                    report(SaveState.Error, describe(e))
                    false
                }
            }
        } while (again)
    }
}
