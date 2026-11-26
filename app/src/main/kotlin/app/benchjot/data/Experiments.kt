package app.benchjot.data

import app.benchjot.core.Logged
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One tracked run (the web's lib/data/experiments.ts; table experiment_runs, owner-only). */
@Serializable
data class ExperimentRun(
    val id: String,
    @SerialName("cell_id") val cellId: String,
    @SerialName("ran_at") val ranAt: String,
    val outcome: String,
    val ms: Long,
    val where: String,
    @SerialName("code_hash") val codeHash: String,
    val logged: Logged,
)

/**
 * Experiment tracking: each run whose code called log() is kept with what it logged, so a note shows
 * its runs side by side. Recorded after the run is saved, best effort: a run that couldn't be recorded
 * still shows in its cell.
 */
object Experiments {
    private val db get() = Backend.client
    private val byNote = mutableMapOf<String, MutableStateFlow<List<ExperimentRun>?>>()

    /** The note's runs, newest first; null until loaded. */
    fun runs(noteId: String): StateFlow<List<ExperimentRun>?> = byNote.getOrPut(noteId) { MutableStateFlow(null) }

    suspend fun refresh(noteId: String) {
        val rows = db.from("experiment_runs").select(Columns.list("id", "cell_id", "ran_at", "outcome", "ms", "where", "code_hash", "logged")) {
            filter { eq("note_id", noteId) }
            order("ran_at", Order.DESCENDING)
            limit(500)
        }.decodeList<ExperimentRun>()
        (byNote.getOrPut(noteId) { MutableStateFlow(null) }).value = rows
    }

    /** Keeps a finished run that logged something. Never throws: tracking must not break a run. */
    suspend fun record(noteId: String, cellId: String, run: app.benchjot.core.LastRun) {
        val logged = run.logged ?: return
        runCatching {
            db.from("experiment_runs").insert(buildJsonObject {
                put("note_id", noteId); put("cell_id", cellId); put("ran_at", run.ranAt); put("outcome", when (run.outcome) { app.benchjot.core.RunOutcome.Ok -> "ok"; app.benchjot.core.RunOutcome.Error -> "error"; app.benchjot.core.RunOutcome.Timeout -> "timeout"; app.benchjot.core.RunOutcome.Stopped -> "stopped" })
                put("ms", run.ms); put("where", if (run.where == "server") "server" else "phone"); put("code_hash", run.codeHash)
                put("logged", kotlinx.serialization.json.JsonObject(logged))
            })
            refresh(noteId)
        }.onFailure { android.util.Log.w("Experiments", "run not recorded", it) }
    }

    suspend fun delete(noteId: String, id: String) {
        db.from("experiment_runs").delete { filter { eq("id", id) } }
        refresh(noteId)
    }
}
