package app.benchjot.run

import app.benchjot.core.BackgroundRun
import app.benchjot.core.Chunk
import app.benchjot.core.RunError
import app.benchjot.core.RunHandle
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.ServerOutputs
import app.benchjot.core.backgroundResult
import app.benchjot.core.pollDelay
import app.benchjot.core.runStatusText
import app.benchjot.core.runsInBackground
import app.benchjot.core.toRunResult
import app.benchjot.data.Accounts
import app.benchjot.data.WebApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Server runs, as the web's server-client: Python goes to the background (POST /api/runs; it may
 * outlast the screen, up to an hour) and is followed by asking GET /api/runs/{id} until it ends,
 * with "Starting server · 0:25" / "Running · 0:42 · <latest line>" meanwhile; other languages wait
 * for their result (/api/run). Stop asks the server to stop the run. Every callback is on the
 * caller's scope (the main thread for a screen).
 */
object ServerRunner {
    /** What a finished server run gives the cell: its output in order, and its figures, tables and charts. */
    class Events(val onStatus: (String) -> Unit, val onChunk: (Chunk) -> Unit, val onOutputs: (ServerOutputs) -> Unit)

    fun run(scope: CoroutineScope, language: String, code: String, cellId: String?, events: Events): RunHandle =
        if (runsInBackground(language)) start(scope, language, code, cellId, events) else wait(scope, language, code, events)

    /** /api/run: the request waits for the result. */
    private fun wait(scope: CoroutineScope, language: String, code: String, events: Events): RunHandle {
        val started = System.currentTimeMillis()
        val stopped = CompletableDeferred<RunResult>()
        events.onStatus("Running on server…")
        val call = scope.async {
            try {
                val response = WebApi.runOnServer(language, code)
                val (chunks, r) = toRunResult(response)
                chunks.forEach(events.onChunk)
                response.outputs?.let(events.onOutputs)
                r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RunResult(RunOutcome.Error, RunError(Accounts.message(e, "run_server"), null), System.currentTimeMillis() - started)
            }
        }
        val done = scope.async {
            kotlinx.coroutines.selects.select<RunResult> {
                call.onAwait { it }
                stopped.onAwait { call.cancel(); it }
            }
        }
        return RunHandle(done) { stopped.complete(RunResult(RunOutcome.Stopped, null, System.currentTimeMillis() - started)) }
    }

    /** /api/runs: starts the run in the background, then follows it. */
    private fun start(scope: CoroutineScope, language: String, code: String, cellId: String?, events: Events): RunHandle {
        val started = System.currentTimeMillis()
        var inner: RunHandle? = null
        var stopRequested = false
        events.onStatus("Preparing server…")
        val done = scope.async {
            val run = try {
                WebApi.startRun(language, code, cellId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@async RunResult(RunOutcome.Error, RunError(Accounts.message(e, "run_server"), null), System.currentTimeMillis() - started)
            }
            val h = follow(scope, run, events)
            inner = h
            if (stopRequested) h.stop()
            h.done.await()
        }
        return RunHandle(done) { stopRequested = true; inner?.stop?.invoke() }
    }

    /**
     * Follows a background run until it ends: asks for it ([pollDelay] apart, sooner after a dropped
     * connection), keeps the last known progress through a check that couldn't read it, and ticks the
     * status every second. Stop asks the server to stop it. [first]: the run as last known.
     */
    fun follow(scope: CoroutineScope, first: BackgroundRun, events: Events): RunHandle {
        val stop = CompletableDeferred<Unit>()
        val done = scope.async {
            var run = first
            var failures = 0
            var liveAt: Long? = if (first.live != null) System.currentTimeMillis() else null
            val ticker = scope.launch {
                while (isActive) {
                    events.onStatus(runStatusText(run, System.currentTimeMillis(), liveAt, failures > 0))
                    delay(1_000)
                }
            }
            try {
                while (run.status == "running" && !stop.isCompleted) {
                    val elapsed = System.currentTimeMillis() - run.startedMs
                    val wait = minOf(pollDelay(elapsed), 1_000L + failures * 2_000L)
                    kotlinx.coroutines.withTimeoutOrNull(wait) { stop.await() }
                    if (stop.isCompleted) break
                    try {
                        val next = WebApi.backgroundRun(run.id)
                            ?: return@async RunResult(RunOutcome.Error, RunError("This server run is no longer available.", null), elapsed)
                        run = if (next.status == "running" && next.live == null && run.live != null) next.copy(live = run.live) else next
                        if (next.live != null) liveAt = System.currentTimeMillis()
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failures = minOf(failures + 1, 5)
                    }
                }
            } finally {
                ticker.cancel()
            }
            if (stop.isCompleted) run = runCatching { WebApi.stopRun(run.id) }.getOrElse { run.copy(status = "stopped") }
            val (chunks, r) = backgroundResult(run)
            chunks.forEach(events.onChunk)
            run.result?.outputs?.let(events.onOutputs)
            r
        }
        return RunHandle(done) { stop.complete(Unit) }
    }
}
