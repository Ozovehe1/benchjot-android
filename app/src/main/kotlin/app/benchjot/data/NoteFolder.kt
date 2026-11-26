package app.benchjot.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.benchjot.core.NoteFile
import app.benchjot.core.noteFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Every note as a Markdown file of its own on the phone, kept current by itself (core NoteFolder.kt
 * says which folder, name and text): Documents/Benchjot/<project>/<title>.md on Android 10 and
 * later (no permission needed: the files are the app's own), and on older phones the app's own
 * folder on shared storage (Android/data/app.benchjot/files/Benchjot). A note's file follows it
 * when it is renamed, moved to another project or deleted. Written a moment after the notes change,
 * from the phone's copy, so it works offline. On by default; Settings turns it off.
 */
object NoteFolder {
    private const val PREFS = "note-folder"
    private const val KEY_ON = "on"

    /** What was written for a note: where, and a fingerprint of what it said. */
    @Serializable
    private data class Written(val uri: String, val dir: String, val name: String, val hash: Int)

    @Serializable
    private data class State(val notes: Map<String, Written> = emptyMap())

    private val lock = Mutex()
    private lateinit var app: Context
    private var state = State()
    private var stateFile: File? = null

    /** Where the files are, as the user finds them. */
    val location: String get() = if (Build.VERSION.SDK_INT >= 29) "Documents/Benchjot" else "Android/data/app.benchjot/files/Benchjot"

    fun isOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (on) Backend.scope.launch { runCatching { write(Offline.read()) } }
    }

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        Backend.scope.launch {
            Offline.data.debounce(1_500).collect { d ->
                if (isOn(app) && d.notes.isNotEmpty()) runCatching { write(d) }.onFailure {
                    if (it is CancellationException) throw it
                    android.util.Log.w("Benchjot", "note folder not updated", it)
                }
            }
        }
    }

    private suspend fun write(d: app.benchjot.core.LocalData) = lock.withLock {
        withContext(Dispatchers.IO) {
            load()
            val plan = noteFiles(d)
            val next = HashMap(state.notes)
            for ((id, f) in plan) {
                val hash = f.text.hashCode()
                val before = state.notes[id]
                if (before != null && before.hash == hash && before.dir == f.dir && before.name == f.name) continue
                writeOne(f, before)?.let { next[id] = Written(it, f.dir, f.name, hash) }
            }
            for ((id, w) in state.notes) if (id !in plan) { remove(w); next.remove(id) }
            state = State(next)
            save()
        }
    }

    private fun load() {
        if (stateFile != null) return
        stateFile = File(app.filesDir, "note-folder.json")
        state = runCatching { Backend.json.decodeFromString(State.serializer(), stateFile!!.readText()) }.getOrDefault(State())
    }

    private fun save() {
        val f = stateFile ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Backend.json.encodeToString(State.serializer(), state))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Writes one note's file (updating, moving or renaming the one written before); its address, or null. */
    private fun writeOne(f: NoteFile, before: Written?): String? {
        if (Build.VERSION.SDK_INT < 29) {
            val root = app.getExternalFilesDir(null) ?: return null
            before?.let { File(it.uri).takeIf { old -> old.path != File(root, "${f.dir}/${f.name}").path }?.delete() }
            val file = File(root, "${f.dir}/${f.name}").apply { parentFile?.mkdirs() }
            file.writeText(f.text)
            return file.path
        }
        val resolver = app.contentResolver
        val path = "${Environment.DIRECTORY_DOCUMENTS}/${f.dir}/"
        before?.let { w ->
            val uri = Uri.parse(w.uri)
            val ok = runCatching {
                if (w.dir != f.dir || w.name != f.name) {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    }, null, null)
                }
                resolver.openOutputStream(uri, "wt")?.use { it.write(f.text.toByteArray()) } ?: error("gone")
            }.isSuccess
            if (ok) return w.uri
        }
        // New, or the old file was deleted by hand: a new one.
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        resolver.openOutputStream(uri, "wt")?.use { it.write(f.text.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri.toString()
    }

    /** A deleted note's file goes too. */
    private fun remove(w: Written) {
        if (Build.VERSION.SDK_INT < 29) File(w.uri).delete()
        else runCatching { app.contentResolver.delete(Uri.parse(w.uri), null, null) }
    }
}
