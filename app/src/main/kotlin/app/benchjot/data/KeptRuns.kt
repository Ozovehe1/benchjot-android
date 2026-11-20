package app.benchjot.data

import android.content.Context
import app.benchjot.core.KeptRun
import app.benchjot.core.RESUME_HOURS
import kotlinx.serialization.json.Json

/**
 * A request the Assistant was in the middle of when the note was left (another screen, the app closed
 * or killed): kept on the phone after every step, so opening the note again picks it up from that step
 * (the web's agent-resume.ts). It never leaves the phone; a request older than RESUME_HOURS is let go.
 */
object KeptRuns {
    private const val PREFS = "agent-runs"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun keep(context: Context, noteId: String, run: KeptRun) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(noteId, json.encodeToString(KeptRun.serializer(), run)).apply() }
    }

    /** The request kept for this note, if it is recent and reads back whole. */
    fun kept(context: Context, noteId: String, now: Long = System.currentTimeMillis()): KeptRun? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val run = prefs.getString(noteId, null)?.let { runCatching { json.decodeFromString(KeptRun.serializer(), it) }.getOrNull() }
        if (run != null && now - run.savedAt < RESUME_HOURS * 3_600_000L) return run
        prefs.edit().remove(noteId).apply()
        return null
    }

    fun drop(context: Context, noteId: String) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(noteId).apply() }
    }
}
