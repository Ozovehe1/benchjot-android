package app.benchjot.llm

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentCallbacks2
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
import app.benchjot.core.AssistError
import app.benchjot.core.GenerateStatus
import app.benchjot.core.OnDeviceModel
import app.benchjot.core.Utf8Stream

/**
 * llama.cpp in its own process, ":llm", like Python in ":python". A native crash or the system
 * killing it for memory takes this process down, never the app: the note you're editing stays.
 * Analogy: the model is a kitchen with its own fire door.
 *
 * One worker thread runs one request at a time. The model stays loaded between requests and is
 * unloaded after [IDLE_UNLOAD_MS] without one, or at once when the system is short of memory.
 * Text goes back in batches about every [FLUSH_MS], so the UI redraws ~20 times a second
 * however fast tokens come.
 */
class LlmService : Service() {
    private val workerThread = HandlerThread("llm", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val worker = Handler(workerThread.looper)
    @Volatile private var handle = 0L
    private var loadedPath: String? = null
    private val unload = Runnable { freeModel() }

    private val incoming = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            LlmProtocol.GENERATE -> {
                val reply = msg.replyTo
                val id = msg.arg1
                val d = msg.data
                val prompt = d.getString(LlmProtocol.PROMPT).orEmpty()
                val path = d.getString(LlmProtocol.MODEL).orEmpty()
                val maxTokens = d.getInt(LlmProtocol.MAX_TOKENS)
                worker.removeCallbacks(unload)
                worker.post { generateOne(reply, id, path, prompt, maxTokens) }
            }
            LlmProtocol.STOP -> handle.takeIf { it != 0L }?.let(LlamaNative::stop) // the native side checks between tokens
        }
        true
    }

    override fun onBind(intent: Intent): IBinder = Messenger(incoming).binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Free the model at once if the system is pressed and nothing is running (posted after any running request).
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { worker.removeCallbacks(unload); worker.post(unload) }
    }

    override fun onDestroy() {
        worker.post { freeModel(); workerThread.quitSafely() }
        super.onDestroy()
    }

    private fun freeModel() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
        loadedPath = null
    }

    /** Enough memory for the weights (mapped, so reclaimable) plus the KV cache and work buffers? */
    private fun memoryOk(): Boolean {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return !info.lowMemory && info.availMem >= LOW_MEMORY_BYTES
    }

    private fun generateOne(reply: Messenger, id: Int, path: String, prompt: String, maxTokens: Int) {
        val out = Out(reply, id)
        try {
            if (handle == 0L || loadedPath != path) {
                freeModel()
                if (!memoryOk()) { out.fail(AssistError.LowMemory); return }
                out.send(LlmProtocol.LOADING)
                val cores = Runtime.getRuntime().availableProcessors()
                handle = LlamaNative.load(path, OnDeviceModel.CONTEXT_TOKENS, OnDeviceModel.threads(cores))
                if (handle == 0L) {
                    android.util.Log.e(TAG, "load failed: ${LlamaNative.lastError()}")
                    out.fail(if (memoryOk()) AssistError.ModelCorrupt else AssistError.LowMemory)
                    return
                }
                loadedPath = path
            }
            out.send(LlmProtocol.STARTED)
            val text = Utf8Stream()
            val code = LlamaNative.generate(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens) { bytes ->
                out.text(text.push(bytes))
                out.alive // the app went away: stop spending the battery
            }
            out.text(text.finish())
            when (val status = GenerateStatus.of(code)) {
                GenerateStatus.PromptTooLong -> out.fail(AssistError.TooLong)
                GenerateStatus.Failed, GenerateStatus.BadArgs -> {
                    android.util.Log.e(TAG, "generate failed ($status): ${LlamaNative.lastError()}")
                    out.fail(AssistError.EngineCrashed)
                }
                else -> out.done(status)
            }
        } catch (e: Throwable) { // UnsatisfiedLinkError (no library for this CPU), OutOfMemoryError, …
            android.util.Log.e(TAG, "request failed", e)
            out.fail(if (e is UnsatisfiedLinkError) AssistError.Unsupported else AssistError.EngineCrashed)
        } finally {
            out.close()
            worker.postDelayed(unload, IDLE_UNLOAD_MS)
        }
    }

    /** Batches text for the app and notices when the app has gone. */
    private class Out(private val reply: Messenger, private val id: Int) {
        private val flusher = HandlerThread("llm-out").apply { start() }
        private val timer = Handler(flusher.looper)
        private val pending = StringBuilder()
        private var scheduled = false
        @Volatile var alive = true
            private set

        fun send(what: Int, data: Bundle.() -> Unit = {}) {
            val m = Message.obtain(null, what, id, 0)
            m.data = Bundle().apply(data)
            try { reply.send(m) } catch (_: RemoteException) { alive = false }
        }

        @Synchronized private fun flush() {
            scheduled = false
            if (pending.isEmpty()) return
            val t = pending.toString()
            pending.setLength(0)
            send(LlmProtocol.TEXT) { putString(LlmProtocol.TEXT_KEY, t) }
        }

        @Synchronized fun text(t: String) {
            if (t.isEmpty()) return
            pending.append(t)
            if (!scheduled) { scheduled = true; timer.postDelayed({ flush() }, FLUSH_MS) }
        }

        fun done(status: GenerateStatus) { flush(); send(LlmProtocol.DONE) { putInt(LlmProtocol.STATUS, status.code) } }
        fun fail(e: AssistError) { flush(); send(LlmProtocol.FAIL) { putString(LlmProtocol.ERROR, e.id) } }
        fun close() { flush(); flusher.quitSafely() }
    }

    companion object {
        private const val TAG = "LlmService"
        const val IDLE_UNLOAD_MS = 120_000L
        const val FLUSH_MS = 50L
        /** Below this much free RAM the model isn't loaded: the phone would start killing apps. */
        const val LOW_MEMORY_BYTES = 300L * 1024 * 1024
    }
}

/** The messages between the app and LlmService (arg1 is the request id). */
internal object LlmProtocol {
    const val GENERATE = 1
    const val STOP = 2
    const val LOADING = 3
    const val STARTED = 4
    const val TEXT = 5
    const val DONE = 6
    const val FAIL = 7

    const val PROMPT = "prompt"
    const val MODEL = "model"
    const val MAX_TOKENS = "max_tokens"
    const val TEXT_KEY = "text"
    const val STATUS = "status"
    const val ERROR = "error"
}
