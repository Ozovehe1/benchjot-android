package app.benchjot.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.benchjot.core.LocalData
import app.benchjot.core.Op
import app.benchjot.core.apply
import app.benchjot.core.isTransient
import app.benchjot.core.sent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Offline first, on the phone (core Offline.kt is the pure part). The account's notes, code cells
 * and projects live in a database on the phone (OfflineStore: a row per note and per cell, so a
 * change writes only what it touched, however big the account); every change is made there
 * first and queued, and the queue is sent to the server in order whenever there is a connection
 * (at once after a change, when the network comes back, and when the app starts). So opening,
 * editing, running and saving never wait on the network. The whole account is read again from the
 * server when the queue is empty, so the copy stays complete for the next time there is none.
 */
object Offline {
    private val lock = Mutex()
    private val sending = Mutex()
    private val syncing = Mutex()
    private var dir: File? = null
    private var store: OfflineStore? = null
    private var owner: String? = null
    private val _data = MutableStateFlow(LocalData())
    val data: StateFlow<LocalData> = _data.asStateFlow()

    /** Changes made on the phone and not yet on the server (the save status shows them). */
    private val _waiting = MutableStateFlow(0)
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    /** Whether the phone has a connection now (the system's own view, kept current). */
    @Volatile var online = true
        private set

    fun init(context: Context) {
        dir = File(context.filesDir, "offline").apply { mkdirs() }
        store = OfflineStore(context)
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { online = true; Backend.scope.launch { runCatching { syncAccount() } } }
                override fun onLost(network: Network) { online = false }
            })
        }
    }

    private fun now() = Instant.now().toString()

    /** Loads the signed-in account's copy (once per account). */
    private suspend fun ensure() {
        val uid = runCatching { Backend.userId() }.getOrNull() ?: return
        if (owner == uid) return
        lock.withLock {
            if (owner == uid) return
            val loaded = withContext(Dispatchers.IO) {
                store?.load(uid) ?: run {
                    // A copy kept before the database (one file for the whole account) moves over once.
                    val f = dir?.let { File(it, "$uid.json") }?.takeIf { it.isFile } ?: return@run null
                    val old = runCatching { Backend.json.decodeFromString(LocalData.serializer(), f.readText()) }.getOrNull() ?: return@run null
                    store?.save(uid, LocalData(), old)
                    f.delete()
                    old
                }
            }
            _data.value = loaded ?: LocalData()
            _waiting.value = _data.value.outbox.size
            owner = uid
        }
    }

    /** Keeps what changed between two versions of the copy (one transaction: all of it or none). */
    private suspend fun save(before: LocalData, after: LocalData) {
        val uid = owner ?: return
        withContext(Dispatchers.IO) { store?.save(uid, before, after) }
    }

    /** Sign-out: sends what waits, then forgets this account's copy (kept if something couldn't be sent), as on the web. */
    suspend fun forgetAccount() {
        ensure()
        val uid = owner ?: return
        if (!send()) return
        lock.withLock {
            withContext(Dispatchers.IO) { store?.drop(uid) }
            owner = null
            _data.value = LocalData()
            _waiting.value = 0
        }
    }

    /** The phone's copy as it is now. */
    suspend fun read(): LocalData { ensure(); return _data.value }

    /** Replaces the copy with [f] of it (what the server sent back, say). */
    suspend fun update(f: (LocalData) -> LocalData) {
        ensure()
        lock.withLock {
            val before = _data.value
            val next = f(before)
            if (next === before) return
            _data.value = next
            _waiting.value = next.outbox.size
            save(before, next)
        }
    }

    /** A change: made on the phone's copy now, queued, and sent as soon as there is a connection. */
    suspend fun change(op: Op, notify: Boolean = true) {
        update { it.apply(op, now()) }
        if (notify) Backend.changed()
        kick()
    }

    /** Sends the queue in the background. */
    fun kick() { Backend.scope.launch { runCatching { send() } } }

    /**
     * Sends the queue in order, each change once it is on the server. "Not now" (no connection, the
     * server busy) stops and keeps the rest for the next time; a change the server refuses for good
     * (a note deleted elsewhere) is dropped. True when nothing is left to send.
     */
    suspend fun send(): Boolean {
        ensure()
        if (!online) return _data.value.outbox.isEmpty()
        sending.withLock {
            while (true) {
                val op = _data.value.outbox.firstOrNull() ?: break
                try {
                    Backend.sendOp(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isTransient(Accounts.facts(e))) return false
                    android.util.Log.w("Benchjot", "a queued change was refused and dropped: ${op::class.simpleName}", e)
                }
                update { it.sent() }
            }
        }
        return true
    }

    @Volatile private var lastSync = 0L

    /**
     * Brings the copy up to date with the server once the queue is sent (only what changed, once
     * the copy is complete: Backend.syncCopy); what's shown is read again when anything changed.
     * Quiet on failure.
     */
    suspend fun syncAccount() {
        if (!online || syncing.isLocked) return
        syncing.withLock {
            lastSync = System.currentTimeMillis()
            if (!send()) return
            runCatching { PendingOutputs.uploadAll() }.onFailure { if (it is CancellationException) throw it }
            if (!send()) return
            runCatching { if (Backend.syncCopy()) Backend.changed() }.onFailure {
                if (it is CancellationException) throw it
                android.util.Log.w("Benchjot", "couldn't bring the copy up to date", it)
            }
        }
    }

    /** A sync unless one ran in the last [gapMs] (when the app comes back, on a timer, after a read). */
    fun syncSoon(gapMs: Long = 30_000) {
        if (System.currentTimeMillis() - lastSync >= gapMs) Backend.scope.launch { runCatching { syncAccount() } }
    }
}
