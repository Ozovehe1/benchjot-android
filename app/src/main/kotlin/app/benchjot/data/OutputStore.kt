package app.benchjot.data

import app.benchjot.core.MIME
import app.benchjot.core.Manifest
import app.benchjot.core.OUTPUTS_BUCKET
import app.benchjot.core.SavedOutputs
import app.benchjot.core.checkManifest
import app.benchjot.core.outputDir
import app.benchjot.core.outputPath
import app.benchjot.core.outputPaths
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// Run outputs in Supabase Storage (the web's src/lib/run/output-store.ts): upload a finished
// run's files into {user}/{snippet}/{run}/, read them back (cached on the phone), delete a
// replaced run's. The only module that talks to the run-outputs bucket.

/** A run's outputs before upload: the manifest bj_capture wrote and the folder holding its files. */
class LocalOutputs(val manifest: Manifest, val dir: File) {
    fun file(name: String) = File(dir, name)

    companion object {
        /** Reads `dir`/manifest.json if the run made any outputs. */
        fun read(dir: File): LocalOutputs? {
            val f = File(dir, "manifest.json")
            if (!f.isFile) return null
            val m = checkManifest(Backend.json.decodeFromString(Manifest.serializer(), f.readText()))
            return if (m.items.isEmpty()) null else LocalOutputs(m, dir)
        }

        /** A server run's outputs (manifest + base64 files) written into `dir`. */
        fun fromServer(o: app.benchjot.core.ServerOutputs, dir: File): LocalOutputs? {
            val m = checkManifest(o.manifest)
            if (m.items.isEmpty()) return null
            dir.mkdirs()
            for (name in m.items.flatMap { it.files.values }) {
                val b64 = o.files[name] ?: throw IllegalStateException("Output file $name is missing")
                File(dir, name).writeBytes(java.util.Base64.getDecoder().decode(b64))
            }
            return LocalOutputs(m, dir)
        }
    }
    }
}

object OutputStore {
    private val bucket get() = Backend.client.storage.from(OUTPUTS_BUCKET)
    private var cacheRoot: File? = null

    fun init(cacheDir: File) { cacheRoot = File(cacheDir, "run-outputs") }

    suspend fun upload(snippetId: String, local: LocalOutputs): SavedOutputs = withContext(Dispatchers.IO) {
        val dir = outputDir(Backend.userId(), snippetId, UUID.randomUUID().toString())
        coroutineScope {
            local.manifest.items.flatMap { it.files.values }.map { name ->
                async {
                    val data = local.file(name).readBytes()
                    bucket.upload(outputPath(dir, name), data) {
                        upsert = false
                        contentType = ContentType.parse(MIME[name.substringAfterLast('.')] ?: "application/octet-stream")
                    }
                    // Keep a copy in the cache, so showing the saved run needs no download.
                    cached(dir, name).apply { parentFile?.mkdirs() }.writeBytes(data)
                }
            }.awaitAll()
        }
        SavedOutputs(local.manifest.version, local.manifest.items, local.manifest.notes, dir)
    }

    private fun cached(dir: String, name: String) = File(requireNotNull(cacheRoot) { "OutputStore.init first" }, "$dir/$name")

    /** One stored file's bytes, from the phone's cache or Storage. */
    suspend fun bytes(o: SavedOutputs, name: String): ByteArray = withContext(Dispatchers.IO) {
        val f = cached(o.dir, name)
        if (f.isFile) return@withContext f.readBytes()
        val data = bucket.downloadAuthenticated(outputPath(o.dir, name))
        f.parentFile?.mkdirs(); f.writeBytes(data)
        data
    }

    /** Best effort: a replaced or cleared run's files (stored and cached). */
    suspend fun delete(o: SavedOutputs?) {
        if (o == null) return
        withContext(Dispatchers.IO) { cacheRoot?.let { File(it, o.dir).deleteRecursively() } }
        runCatching { bucket.delete(outputPaths(o)) }
    }
}
