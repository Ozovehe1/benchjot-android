package app.benchjot.data

import android.content.Context
import app.benchjot.core.SavedHistory
import java.io.File

// The phone's copy of each note's assistant history (summary and saved turns) as last read from
// Supabase, so reopening the Assistant shows them at once, and offline. Supabase stays the record:
// every open reads it again and replaces this copy. One small JSON file per note, in app storage.
object AssistantCache {
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Only a note id names a file: no other text reaches the path. */
    private fun file(context: Context, noteId: String): File? =
        if (UUID.matches(noteId)) File(context.filesDir, "assistant-history/$noteId.json") else null

    fun read(context: Context, noteId: String): SavedHistory? = runCatching {
        file(context, noteId)?.takeIf { it.isFile }?.readText()?.let { Backend.json.decodeFromString(SavedHistory.serializer(), it) }
    }.getOrNull()

    fun write(context: Context, noteId: String, history: SavedHistory) {
        val f = file(context, noteId) ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(Backend.json.encodeToString(SavedHistory.serializer(), history))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { android.util.Log.w("AssistantCache", "couldn't write the Assistant history copy", it) }
    }
}
