package app.benchjot.run

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.benchjot.core.MAX_OUTPUT
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors

/**
 * Embedded CPython (Chaquopy) in its own process, ":python". The app talks to it
 * over a Messenger; stopping a run (Stop or the 30 s limit) kills this process,
 * which really stops any Python, busy loops and native code included. The next
 * run starts a fresh one, as the web throws its Pyodide worker away.
 */
class PythonService : Service() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "python") }
    private val incoming = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == PyProtocol.RUN) {
            val reply = msg.replyTo
            val id = msg.arg1
            val code = msg.data.getString(PyProtocol.CODE).orEmpty()
            val outDir = msg.data.getString(PyProtocol.OUT_DIR).orEmpty()
            worker.execute { runOne(reply, id, code, outDir) }
        } else if (msg.what == PyProtocol.RENDER) {
            val reply = msg.replyTo
            val id = msg.arg1
            val d = msg.data
            val src = d.getString(PyProtocol.SRC).orEmpty()
            val format = d.getString(PyProtocol.FORMAT).orEmpty()
            val dest = d.getString(PyProtocol.DEST).orEmpty()
            val made = d.getString(PyProtocol.MADE_WITH)
            worker.execute { renderOne(reply, id, src, format, dest, made) }
        }
        true
    }

    override fun onBind(intent: Intent): IBinder = Messenger(incoming).binder

    private fun runOne(reply: Messenger, id: Int, code: String, outDir: String) {
        val emit = Emitter(reply, id)
        emit.send(PyProtocol.PID) { putInt(PyProtocol.PID_KEY, Process.myPid()) }
        try {
            if (!Python.isStarted()) {
                emit.status("Loading Python…")
                Python.start(AndroidPlatform(applicationContext))
            }
            val work = java.io.File(filesDir, "python-home").path
            Python.getInstance().getModule("bj_runner").callAttr("run", code, emit, outDir, work, PyProtocol.nativeStderr(this).path)
        } catch (e: Throwable) {
            android.util.Log.e("PythonService", "run failed", e)
            // bj_runner reports the user's own errors itself; this is Python failing to start or the bridge.
            emit.error(app.benchjot.core.messageFor(app.benchjot.core.ErrorKind.Unknown, "runner_start"))
        } finally {
            emit.close()
        }
    }

    /** A kept figure's SVG / PDF (bj_runner.render) into `dest`, after any run before it (one Python thread). */
    private fun renderOne(reply: Messenger, id: Int, src: String, format: String, dest: String, madeWith: String?) {
        val emit = Emitter(reply, id)
        emit.send(PyProtocol.PID) { putInt(PyProtocol.PID_KEY, Process.myPid()) }
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val work = java.io.File(filesDir, "python-home").path
            Python.getInstance().getModule("bj_runner").callAttr("render", src, format, dest, madeWith, work)
            emit.send(PyProtocol.RENDERED)
        } catch (e: com.chaquo.python.PyException) {
            android.util.Log.w("PythonService", "render failed", e)
            emit.send(PyProtocol.ERROR) { putString(PyProtocol.TEXT, PyProtocol.renderErrorText(e.message.orEmpty())) }
        } catch (e: Throwable) {
            android.util.Log.e("PythonService", "render failed", e)
            emit.send(PyProtocol.ERROR) { putString(PyProtocol.TEXT, "") }
        } finally {
            emit.close()
        }
    }

    /** What bj_runner.py calls. Output is batched (a few ms, or 32 KB) so a print loop doesn't flood Binder. */
    class Emitter(private val reply: Messenger, private val id: Int) {
        private val flusher = HandlerThread("py-out").apply { start() }
        private val timer = Handler(flusher.looper)
        private val pending = mutableListOf<Pair<String, StringBuilder>>()
        private var pendingSize = 0
        private var sent = 0
        private var scheduled = false

        internal fun send(what: Int, data: Bundle.() -> Unit = {}) {
            val m = Message.obtain(null, what, id, 0)
            m.data = Bundle().apply(data)
            try { reply.send(m) } catch (_: RemoteException) { /* the app went away */ }
        }

        @Synchronized private fun flush() {
            scheduled = false
            for ((stream, text) in pending) send(PyProtocol.OUT) { putString(PyProtocol.STREAM, stream); putString(PyProtocol.TEXT, text.toString()) }
            pending.clear()
            pendingSize = 0
        }

        @Synchronized fun out(stream: String, text: String) {
            if (sent > MAX_OUTPUT + 1024) return // the app caps output at MAX_OUTPUT; don't send what it would drop
            sent += text.length
            val last = pending.lastOrNull()
            if (last != null && last.first == stream) last.second.append(text) else pending += stream to StringBuilder(text)
            pendingSize += text.length
            if (pendingSize >= 32 * 1024) flush()
            else if (!scheduled) { scheduled = true; timer.postDelayed({ flush() }, 16) }
        }

        fun status(text: String) = send(PyProtocol.STATUS) { putString(PyProtocol.TEXT, text) }
        fun start() { flush(); send(PyProtocol.START) }
        fun done() { flush(); send(PyProtocol.DONE) }
        fun error(text: String) { flush(); send(PyProtocol.ERROR) { putString(PyProtocol.TEXT, text) } }
        internal fun close() { flush(); flusher.quitSafely() }
    }
}

/** The messages between the app and PythonService (arg1 is the run id). */
internal object PyProtocol {
    const val RUN = 1
    const val PID = 2
    const val STATUS = 3
    const val START = 4
    const val OUT = 5
    const val DONE = 6
    const val ERROR = 7
    /** Make a kept figure's SVG / PDF (SRC, FORMAT, DEST, MADE_WITH); answered by RENDERED or ERROR (TEXT: the user's message, or empty). */
    const val RENDER = 8
    const val RENDERED = 9

    const val CODE = "code"
    const val TEXT = "text"
    const val STREAM = "stream"
    const val PID_KEY = "pid"
    const val SRC = "src"
    const val FORMAT = "format"
    const val DEST = "dest"
    const val MADE_WITH = "made_with"
    /** Where bj_capture writes the run's figures, tables and charts (the app reads them after the run). */
    const val OUT_DIR = "out_dir"

    /** The Python process's native stderr (fd 2) and faulthandler's tracebacks, one run's worth: what a
     *  native crash said, read by the app when the process dies (PythonRunner). */
    fun nativeStderr(context: android.content.Context) = java.io.File(context.cacheDir, "python-native-stderr.txt")

    /** A bj_capture.RenderError's message (meant for the user) from Chaquopy's "RenderError: ..."; empty for any other error. */
    fun renderErrorText(message: String): String {
        val last = message.trim().lineSequence().lastOrNull().orEmpty()
        return listOf("bj_capture.RenderError: ", "RenderError: ").firstOrNull { last.startsWith(it) }?.let { last.removePrefix(it) }.orEmpty()
    }

    /** The tail of [nativeStderr], for the error of a run whose process died; empty if it said nothing. */
    fun crashReport(context: android.content.Context, maxChars: Int = 4000): String =
        runCatching { nativeStderr(context).readText() }.getOrDefault("").trim().takeLast(maxChars)
}
