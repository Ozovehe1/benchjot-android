package app.benchjot.data

import android.content.Context
import app.benchjot.core.DATA_BUCKET
import app.benchjot.core.datasetsIn
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's Data library (the web's Data page): files any cell opens by name. Before a phone run,
 * the files its code names are downloaded into Python's working folder (PythonService's
 * python-home), unless the same size is already there. Best effort: a file that can't be fetched is
 * said in the run's status and the run goes on (its open() then says the file is missing).
 */
/** One file in the library. */
data class Dataset(val name: String, val size: Long, val updatedAt: String)

object DataLibrary {
    private val bucket get() = Backend.client.storage.from(DATA_BUCKET)
    private val _files = kotlinx.coroutines.flow.MutableStateFlow<List<Dataset>?>(null)
    /** The library, newest first; null until loaded. */
    val files: kotlinx.coroutines.flow.StateFlow<List<Dataset>?> = _files

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val me = Backend.userId()
        _files.value = bucket.list(me) { limit = 1000 }
            .filter { it.name.isNotBlank() && !it.name.startsWith(".") }
            .map { Dataset(it.name, it.metadata?.get("size")?.toString()?.toLongOrNull() ?: 0L, (it.updatedAt ?: it.createdAt)?.toString().orEmpty()) }
            .sortedByDescending { it.updatedAt }
    }

    /** Adds (or replaces) a file under its own name; throws with words for the user. */
    suspend fun add(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val clean = app.benchjot.core.cleanDataName(name)
        if (!app.benchjot.core.validDataName(clean)) throw app.benchjot.core.FriendlyException("Give the file a plain name with an extension, like iris.csv.")
        if (bytes.size > app.benchjot.core.DATA_FILE_MAX) throw app.benchjot.core.FriendlyException("That file is over 50 MB, the most one dataset can be.")
        try {
            bucket.upload("${Backend.userId()}/$clean", bytes) { upsert = true }
        } catch (e: Exception) {
            if (Regex("row-level security|policy", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())) throw app.benchjot.core.FriendlyException("Your data library is full (250 MB). Remove a dataset to make room.")
            throw e
        }
        refresh()
    }

    suspend fun remove(name: String) = withContext(Dispatchers.IO) {
        bucket.delete(listOf("${Backend.userId()}/$name"))
        refresh()
    }

    /** Python's working folder on the phone (PythonService). */
    fun workDir(context: Context) = File(context.filesDir, "python-home")

    suspend fun place(context: Context, code: String, status: (String) -> Unit) = withContext(Dispatchers.IO) {
        val me = runCatching { Backend.userId() }.getOrNull() ?: return@withContext
        val items = runCatching { bucket.list(me) }.getOrNull() ?: return@withContext
        val sizes = items.associate { it.name to ((it.metadata?.get("size")?.toString()?.toLongOrNull()) ?: -1L) }
        val names = datasetsIn(code, sizes.keys.filter { it.isNotBlank() && !it.startsWith(".") })
        if (names.isEmpty()) return@withContext
        val dir = workDir(context).apply { mkdirs() }
        for (name in names) {
            val dest = File(dir, File(name).name)
            if (dest.isFile && dest.length() == sizes[name]) continue
            status("Getting $name…")
            runCatching { dest.writeBytes(bucket.downloadAuthenticated("$me/$name")) }
                .onFailure { status("Couldn't get $name from your Data library.") }
        }
    }
}
