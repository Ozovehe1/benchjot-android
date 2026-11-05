package app.benchjot.run

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import app.benchjot.core.RUN_TIMEOUT_MS
import app.benchjot.core.RunError
import app.benchjot.core.RunEvents
import app.benchjot.core.RunHandle
import app.benchjot.core.RunOutcome
import app.benchjot.core.RunResult
import app.benchjot.core.Stream
import app.benchjot.core.deviceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app side of PythonService. Runs go to the ":python" process one after the
 * other (like the web's single Pyodide worker); Stop and the time limit kill that
 * process. Everything here runs on the main thread.
 */
object PythonRunner {
    private class Conn(val connection: ServiceConnection, val messenger: CompletableDeferred<Messenger?>) {
        var pid = 0
    }

    private var conn: Conn? = null
    private val listeners = HashMap<Int, Channel<Message>>()
    private var nextId = 1
    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == PyProtocol.PID) conn?.pid = msg.data.getInt(PyProtocol.PID_KEY)
        listeners[msg.arg1]?.trySend(Message.obtain(msg))
        true
    })

    private fun bind(context: Context): Conn = conn ?: run {
        val ready = CompletableDeferred<Messenger?>()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { ready.complete(service?.let(::Messenger)) }
            override fun onServiceDisconnected(name: ComponentName?) = died(this)
            override fun onBindingDied(name: ComponentName?) = died(this)
            override fun onNullBinding(name: ComponentName?) { ready.complete(null) }
        }
        Conn(c, ready).also {
            conn = it
            if (!context.applicationContext.bindService(Intent(context, PythonService::class.java), c, Context.BIND_AUTO_CREATE)) ready.complete(null)
        }
    }

    /** The Python process went away by itself (crash, out of memory): every run in it ends. */
    private fun died(c: ServiceConnection) {
        val current = conn ?: return
        if (current.connection !== c) return
        conn = null
        val gone = Message.obtain(null, GONE)
        listeners.values.forEach { it.trySend(gone) }
    }

    /** Kills the Python process (Stop / time limit). The next run starts a fresh one. */
    private fun kill(context: Context) {
        val c = conn ?: return
        conn = null
        runCatching { context.applicationContext.unbindService(c.connection) }
        if (c.pid != 0 && c.pid != Process.myPid()) Process.killProcess(c.pid)
        val gone = Message.obtain(null, GONE)
        listeners.values.forEach { it.trySend(gone) }
    }

    /** `outDir`: an empty folder bj_capture fills with the run's outputs and manifest.json. */
    fun run(context: Context, scope: CoroutineScope, code: String, events: RunEvents, timeoutMs: Long = RUN_TIMEOUT_MS, outDir: java.io.File? = null): RunHandle {
        val id = nextId++
        val inbox = Channel<Message>(Channel.UNLIMITED)
        listeners[id] = inbox
        val stop = Message.obtain(null, STOP)
        val done = scope.async(Dispatchers.Main) {
            var started = 0L
            fun elapsed() = if (started == 0L) 0L else System.currentTimeMillis() - started
            try {
                val c = bind(context)
                val connected = withTimeoutOrNull(30_000) { c.messenger.await() ?: NO_BINDING }
                if (connected == null) { kill(context); return@async RunResult(RunOutcome.Error, RunError("Python didn't start (no answer in 30 s).", null), 0) }
                val messenger = (connected as? Messenger)
                    ?: run { kill(context); return@async RunResult(RunOutcome.Error, RunError("Python didn't start (the service refused to bind).", null), 0) }
                PyProtocol.nativeStderr(context).delete() // an earlier run's; this run writes its own
                messenger.send(Message.obtain(null, PyProtocol.RUN, id, 0).apply {
                    data = Bundle().apply {
                        putString(PyProtocol.CODE, code)
                        putString(PyProtocol.OUT_DIR, (outDir ?: java.io.File(context.cacheDir, "py-out/$id")).path)
                    }
                    replyTo = replies
                })
                while (true) {
                    val msg = if (started == 0L) inbox.receive() else {
                        val left = started + timeoutMs - System.currentTimeMillis()
                        withTimeoutOrNull(left.coerceAtLeast(1)) { inbox.receive() }
                            ?: run { kill(context); return@async RunResult(RunOutcome.Timeout, null, elapsed()) }
                    }
                    when (msg.what) {
                        STOP -> { kill(context); return@async RunResult(RunOutcome.Stopped, null, elapsed()) }
                        GONE -> return@async RunResult(RunOutcome.Error, RunError(stoppedUnexpectedly(PyProtocol.crashReport(context)), null), elapsed())
                        PyProtocol.STATUS -> events.onStatus(msg.data.getString(PyProtocol.TEXT).orEmpty())
                        PyProtocol.START -> { started = System.currentTimeMillis(); events.onStatus(""); events.onStart() }
                        PyProtocol.OUT -> events.onOutput(
                            if (msg.data.getString(PyProtocol.STREAM) == "stderr") Stream.Stderr else Stream.Stdout,
                            msg.data.getString(PyProtocol.TEXT).orEmpty(),
                        )
                        PyProtocol.DONE -> return@async RunResult(RunOutcome.Ok, null, elapsed())
                        PyProtocol.ERROR -> return@async RunResult(RunOutcome.Error, deviceError("python", msg.data.getString(PyProtocol.TEXT).orEmpty()), elapsed())
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                RunResult(RunOutcome.Error, null, 0)
            } finally {
                listeners.remove(id)
            }
        }
        return RunHandle(done) { inbox.trySend(stop) }
    }

    /** The error of a run whose process died by itself, with what native code said before it went. */
    internal fun stoppedUnexpectedly(report: String) =
        if (report.isEmpty()) "Python stopped unexpectedly." else "Python stopped unexpectedly:\n$report"

    private val NO_BINDING = Any()
    private const val STOP = -1
    private const val GONE = -2
}
