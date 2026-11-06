package app.benchjot.llm

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
import app.benchjot.core.AssistError
import app.benchjot.core.AssistInput
import app.benchjot.core.CodeWindow
import app.benchjot.core.GenerateStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** How a request to the on-device model ended. [text] is everything it said, also on failure. */
sealed interface LocalResult {
    val text: String
    /** [window]: a Fix that had to be cut to fit answered only these lines of the code. */
    data class Done(override val text: String, val cutShort: Boolean, val window: CodeWindow? = null) : LocalResult
    data class Stopped(override val text: String) : LocalResult
    data class Failed(override val text: String, val error: AssistError) : LocalResult
}

/** A request in flight: its result, and Stop. */
class LocalRequest(val result: Deferred<LocalResult>, val stop: () -> Unit)

/**
 * The app side of [LlmService]. Main thread only. One request at a time: a new one while
 * another runs stops the old one first (the helper sheet and Fix never ask at once anyway).
 * The time limit is per task ([app.benchjot.core.AssistTask.timeoutMs]); past it the request is stopped, and if
 * the process doesn't answer within [KILL_GRACE_MS] it is killed.
 */
object LocalModel {
    private const val KILL_GRACE_MS = 5_000L
    private const val BIND_TIMEOUT_MS = 15_000L
    private const val STOP_ASKED = -1
    private const val GONE = -2

    private class Conn(val context: Context, val connection: ServiceConnection, val messenger: CompletableDeferred<Messenger?>)

    private var conn: Conn? = null
    private var current: LocalRequest? = null
    private val listeners = HashMap<Int, Channel<Message>>()
    private var nextId = 1
    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> listeners[msg.arg1]?.trySend(Message.obtain(msg)); true })

    private fun bind(context: Context): Conn = conn ?: run {
        val ready = CompletableDeferred<Messenger?>()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { ready.complete(service?.let(::Messenger)) }
            override fun onServiceDisconnected(name: ComponentName?) = died(this)
            override fun onBindingDied(name: ComponentName?) = died(this)
            override fun onNullBinding(name: ComponentName?) { ready.complete(null) }
        }
        Conn(context.applicationContext, c, ready).also {
            conn = it
            if (!context.applicationContext.bindService(Intent(context, LlmService::class.java), c, Context.BIND_AUTO_CREATE)) ready.complete(null)
        }
    }

    private fun died(c: ServiceConnection) {
        val now = conn ?: return
        if (now.connection !== c) return
        conn = null
        runCatching { now.context.unbindService(c) } // left bound, Android would restart it for us
        listeners.values.forEach { it.trySend(Message.obtain(null, GONE)) }
    }

    private fun kill() {
        val c = conn ?: return
        conn = null
        runCatching { c.context.unbindService(c.connection) }
        pidOfService(c.context)?.let { if (it != Process.myPid()) Process.killProcess(it) }
        listeners.values.forEach { it.trySend(Message.obtain(null, GONE)) }
    }

    private fun pidOfService(context: Context): Int? =
        context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == "${context.packageName}:llm" }?.pid

    /**
     * Runs [input]. The model's process builds the prompt and fits it, in real tokens, into the
     * context with room to answer (or fails with [AssistError.TooLong]). [onText] gets the answer
     * as it grows, in ~50 ms batches; [onPhase] says "Loading the model…" while it loads.
     */
    fun generate(
        context: Context,
        scope: CoroutineScope,
        input: AssistInput,
        onPhase: (String) -> Unit = {},
        onText: (String) -> Unit = {},
        /** Tests only: a model file to use instead of the downloaded one. */
        testModel: java.io.File? = null,
    ): LocalRequest {
        current?.stop?.invoke()
        val task = input.task
        val state = testModel?.let { ModelState.Ready(it) } ?: ModelStore.state.value
        val id = nextId++
        val inbox = Channel<Message>(Channel.UNLIMITED)
        listeners[id] = inbox
        val text = StringBuilder()
        val result = scope.async(Dispatchers.Main) {
            try {
                if (state !is ModelState.Ready) return@async LocalResult.Failed("", if (state is ModelState.Downloading || state is ModelState.Waiting || state is ModelState.Verifying) AssistError.Downloading else AssistError.ModelMissing)
                if (!state.file.isFile) { ModelStore.refresh(); return@async LocalResult.Failed("", AssistError.ModelMissing) }
                val c = bind(context)
                val messenger = withTimeoutOrNull(BIND_TIMEOUT_MS) { c.messenger.await() }
                    ?: run { kill(); return@async LocalResult.Failed("", AssistError.EngineCrashed) }
                val sent = runCatching {
                    messenger.send(Message.obtain(null, LlmProtocol.GENERATE, id, 0).apply {
                        data = Bundle().apply {
                            putString(LlmProtocol.MODEL, state.file.path)
                            LlmProtocol.putInput(this, input)
                        }
                        replyTo = replies
                    })
                }.isSuccess
                if (!sent) { kill(); return@async LocalResult.Failed("", AssistError.EngineCrashed) }
                val deadline = System.currentTimeMillis() + task.timeoutMs
                var stopping = false
                var timedOut = false
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    val msg = if (stopping) withTimeoutOrNull(KILL_GRACE_MS) { inbox.receive() }
                        else withTimeoutOrNull(left.coerceAtLeast(1)) { inbox.receive() }
                    if (msg == null) {
                        if (stopping) { kill(); return@async if (timedOut) LocalResult.Failed(text.toString(), AssistError.Timeout) else LocalResult.Stopped(text.toString()) }
                        timedOut = true; stopping = true; messenger.trySend(LlmProtocol.STOP); continue
                    }
                    when (msg.what) {
                        STOP_ASKED -> if (!stopping) { stopping = true; messenger.trySend(LlmProtocol.STOP) }
                        GONE -> return@async if (stopping && !timedOut) LocalResult.Stopped(text.toString()) else LocalResult.Failed(text.toString(), if (timedOut) AssistError.Timeout else AssistError.EngineCrashed)
                        LlmProtocol.LOADING -> onPhase("Loading the model…")
                        LlmProtocol.STARTED -> onPhase("")
                        LlmProtocol.TEXT -> msg.data.getString(LlmProtocol.TEXT_KEY)?.let { text.append(it); onText(text.toString()) }
                        LlmProtocol.FAIL -> return@async LocalResult.Failed(text.toString(), AssistError.of(msg.data.getString(LlmProtocol.ERROR)) ?: AssistError.EngineCrashed)
                        LlmProtocol.DONE -> return@async when (GenerateStatus.of(msg.data.getInt(LlmProtocol.STATUS))) {
                            GenerateStatus.Stopped -> if (timedOut) LocalResult.Failed(text.toString(), AssistError.Timeout) else LocalResult.Stopped(text.toString())
                            GenerateStatus.Limit -> LocalResult.Done(text.toString(), cutShort = true, window = LlmProtocol.readWindow(msg.data))
                            else -> LocalResult.Done(text.toString(), cutShort = false, window = LlmProtocol.readWindow(msg.data))
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                LocalResult.Failed(text.toString(), AssistError.EngineCrashed)
            } finally {
                listeners.remove(id)
            }
        }
        val request = LocalRequest(result) { inbox.trySend(Message.obtain(null, STOP_ASKED)) }
        current = request
        result.invokeOnCompletion { if (current === request) current = null }
        return request
    }

    private fun Messenger.trySend(what: Int) = runCatching { send(Message.obtain(null, what)) }
}
