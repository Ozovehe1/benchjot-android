package app.benchjot.data

import android.content.Context
import app.benchjot.core.LastRun
import app.benchjot.core.isTransient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Figures, tables and charts from a run made offline: kept on the phone (not in the cache, which
 * Android may clear) until they can be uploaded, shown under their cell meanwhile, then uploaded and
 * attached to the run when there is a connection (Offline.syncAccount). One run per cell: a newer
 * run of the cell replaces them.
 */
object PendingOutputs {
    @Serializable
    private data class Pending(val cellId: String, val run: LastRun)

    private var root: File? = null

    fun init(context: Context) { root = File(context.filesDir, "pending-outputs").apply { mkdirs() } }

    private fun folder(cellId: String) = root?.let { File(it, cellId.filter { c -> c.isLetterOrDigit() || c == '-' }) }

    /** Keeps [local] (the run's files) for the cell, with the run it belongs to. */
    suspend fun keep(cellId: String, run: LastRun, local: LocalOutputs) = withContext(Dispatchers.IO) {
        val dir = folder(cellId) ?: return@withContext
        dir.deleteRecursively()
        local.dir.copyRecursively(File(dir, "files"), overwrite = true)
        File(dir, "run.json").writeText(Backend.json.encodeToString(Pending.serializer(), Pending(cellId, run)))
    }

    /** The cell's kept files when they belong to its saved run ([ranAt]); null otherwise. */
    fun of(cellId: String, ranAt: String?): LocalOutputs? {
        val dir = folder(cellId) ?: return null
        val p = read(dir) ?: return null
        return if (p.run.ranAt == ranAt) LocalOutputs.read(File(dir, "files")) else null
    }

    private fun read(dir: File): Pending? =
        runCatching { Backend.json.decodeFromString(Pending.serializer(), File(dir, "run.json").readText()) }.getOrNull()

    /** Uploads every kept run's files and attaches them to its run, unless the cell has run again since. */
    suspend fun uploadAll() {
        val dirs = withContext(Dispatchers.IO) { root?.listFiles()?.filter { it.isDirectory }.orEmpty() }
        for (dir in dirs) {
            val p = read(dir) ?: run { dir.deleteRecursively(); null } ?: continue
            val current = Offline.read().cells[p.cellId]?.lastRun
            if (current?.ranAt != p.run.ranAt) { dir.deleteRecursively(); continue }
            val local = LocalOutputs.read(File(dir, "files")) ?: run { dir.deleteRecursively(); null } ?: continue
            try {
                val uploaded = OutputStore.upload(p.cellId, local)
                Backend.saveLastRun(p.cellId, current.copy(outputs = uploaded))
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTransient(Accounts.facts(e))) return
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
            }
        }
    }
}
