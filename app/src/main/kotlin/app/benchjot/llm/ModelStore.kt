package app.benchjot.llm

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import app.benchjot.core.AssistError
import app.benchjot.core.OnDeviceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Where the on-device model is, as the download screen and the helper see it. */
sealed interface ModelState {
    data object Missing : ModelState
    /** Queued by the system: no network, or waiting for Wi-Fi when the user chose that. */
    data class Waiting(val forWifi: Boolean, val done: Long) : ModelState
    data class Downloading(val done: Long, val total: Long, val bytesPerSecond: Double) : ModelState
    data object Verifying : ModelState
    data class Ready(val file: File) : ModelState
    data class Failed(val error: AssistError) : ModelState
}

/**
 * Downloads, checks and keeps the model file. The download is Android's own DownloadManager:
 * it survives the app being closed, resumes after a dropped connection, shows its progress in
 * the notification shade, and honours "Wi-Fi only". The app never blocks on it.
 *
 * Integrity: the file is accepted only if its SHA-256 equals [OnDeviceModel.SHA256]; otherwise
 * it is deleted and never loaded. (HTTPS protects the trip; the hash protects against anything
 * else, a wrong mirror or a damaged file included.) Accepted files are remembered by size and
 * modification time, so the hash isn't recomputed on every load.
 *
 * Main-thread API; the hashing runs on IO.
 */
object ModelStore {
    private const val PREFS = "on_device_model"
    private const val KEY_DOWNLOAD = "download_id"
    private const val KEY_VERIFIED = "verified" // "<sha>:<size>:<mtime>" of the accepted file
    private const val KEY_ASKED = "asked"
    /** Room to spare beyond the file itself, so the download never fills the phone. */
    private const val SPARE_BYTES = 25_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var poller: Job? = null
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        refresh()
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // App-specific external storage: DownloadManager can write there, other apps can't read it (Android 10+).
    private fun dir() = File(app.getExternalFilesDir(null) ?: app.filesDir, "models").apply { mkdirs() }
    fun file(): File = File(dir(), OnDeviceModel.FILE_NAME)
    private fun dm() = app.getSystemService(DownloadManager::class.java)

    /** Whether the first-launch question was answered (either way). */
    var asked: Boolean
        get() = prefs().getBoolean(KEY_ASKED, false)
        set(v) { prefs().edit().putBoolean(KEY_ASKED, v).apply() }

    fun onUnmeteredNetwork(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun verifiedMark(f: File) = "${OnDeviceModel.SHA256}:${f.length()}:${f.lastModified()}"

    /** Re-reads where things stand: a finished file, a download in progress, or nothing. */
    fun refresh() {
        val f = file()
        if (f.isFile && prefs().getString(KEY_VERIFIED, null) == verifiedMark(f)) { _state.value = ModelState.Ready(f); return }
        val id = prefs().getLong(KEY_DOWNLOAD, -1)
        if (id >= 0) watch(id) else _state.value = if (_state.value is ModelState.Failed) _state.value else ModelState.Missing
    }

    /**
     * Starts the download. [allowMobileData] false waits for Wi-Fi (or any unmetered network).
     * Fails fast, with the reason, when there isn't room for it.
     */
    fun download(allowMobileData: Boolean) {
        if (_state.value is ModelState.Ready || _state.value is ModelState.Downloading || _state.value is ModelState.Verifying) return
        prefs().getLong(KEY_DOWNLOAD, -1).takeIf { it >= 0 }?.let { dm().remove(it) } // a stale or waiting one: start over with the new choice
        val target = file()
        target.delete()
        if (StatFs(dir().path).availableBytes < OnDeviceModel.SIZE_BYTES + SPARE_BYTES) { _state.value = ModelState.Failed(AssistError.StorageFull); return }
        val request = DownloadManager.Request(Uri.parse(OnDeviceModel.URL))
            .setTitle("Benchjot on-device model")
            .setDescription("${OnDeviceModel.NAME}, once only")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(allowMobileData)
            .setAllowedOverRoaming(false)
            .setDestinationUri(Uri.fromFile(target))
        val id = try { dm().enqueue(request) } catch (e: Exception) { // no shared storage mounted, download service disabled
            android.util.Log.e("ModelStore", "enqueue failed", e)
            _state.value = ModelState.Failed(AssistError.Unsupported)
            return
        }
        prefs().edit().putLong(KEY_DOWNLOAD, id).remove(KEY_VERIFIED).apply()
        asked = true
        watch(id)
    }

    /** Cancels a download, or deletes the model (Settings). The app falls back to the server. */
    fun delete() {
        poller?.cancel()
        prefs().getLong(KEY_DOWNLOAD, -1).takeIf { it >= 0 }?.let { dm().remove(it) }
        prefs().edit().remove(KEY_DOWNLOAD).remove(KEY_VERIFIED).apply()
        file().delete()
        File(dir(), "${OnDeviceModel.FILE_NAME}.part").delete()
        _state.value = ModelState.Missing
    }

    /** The engine couldn't read the accepted file: delete it, so the next tap offers a fresh download. */
    fun discard() {
        delete()
        _state.value = ModelState.Failed(AssistError.ModelCorrupt)
    }

    private fun watch(id: Long) {
        poller?.cancel()
        poller = scope.launch {
            var lastBytes = -1L
            var lastAt = 0L
            var rate = 0.0
            while (isActive) {
                val row = query(id)
                if (row == null) { // removed outside the app (e.g. from the notification)
                    prefs().edit().remove(KEY_DOWNLOAD).apply()
                    _state.value = ModelState.Missing
                    return@launch
                }
                when (row.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { verify(); return@launch }
                    DownloadManager.STATUS_FAILED -> {
                        dm().remove(id)
                        prefs().edit().remove(KEY_DOWNLOAD).apply()
                        _state.value = ModelState.Failed(
                            if (row.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) AssistError.StorageFull else AssistError.ModelMissing,
                        )
                        return@launch
                    }
                    DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING ->
                        _state.value = ModelState.Waiting(forWifi = row.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI, done = row.done)
                    else -> {
                        val now = System.nanoTime()
                        if (lastBytes >= 0 && now > lastAt) {
                            val instant = (row.done - lastBytes) / ((now - lastAt) / 1e9)
                            rate = if (rate == 0.0) instant else rate * 0.8 + instant * 0.2 // smoothed, so "time left" doesn't jump
                        }
                        lastBytes = row.done
                        lastAt = now
                        _state.value = ModelState.Downloading(row.done, row.total.takeIf { it > 0 } ?: OnDeviceModel.SIZE_BYTES, rate)
                    }
                }
                delay(500)
            }
        }
    }

    private class Row(val status: Int, val reason: Int, val done: Long, val total: Long)

    private fun query(id: Long): Row? = dm().query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (!c.moveToFirst()) return@use null
        Row(
            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
        )
    }

    private suspend fun verify() {
        _state.value = ModelState.Verifying
        val f = file()
        val ok = withContext(Dispatchers.IO) { f.length() == OnDeviceModel.SIZE_BYTES && sha256(f) == OnDeviceModel.SHA256 }
        prefs().edit().remove(KEY_DOWNLOAD).apply()
        if (ok) {
            prefs().edit().putString(KEY_VERIFIED, verifiedMark(f)).apply()
            _state.value = ModelState.Ready(f)
        } else {
            f.delete()
            _state.value = ModelState.Failed(AssistError.ModelCorrupt)
        }
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
