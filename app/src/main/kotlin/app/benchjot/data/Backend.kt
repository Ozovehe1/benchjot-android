package app.benchjot.data

import app.benchjot.BuildConfig
import app.benchjot.core.Note
import app.benchjot.core.allRows
import app.benchjot.core.NoteListItem
import app.benchjot.core.LastRun
import app.benchjot.core.Limits
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.NoteLinkRow
import app.benchjot.core.Changes
import app.benchjot.core.withServerChanges
import app.benchjot.core.localLinks
import app.benchjot.core.OutcomeFilter
import app.benchjot.core.Project
import app.benchjot.core.Cell
import app.benchjot.core.SourcedLink
import app.benchjot.core.DEFAULT_LANGUAGE
import app.benchjot.core.CodeFile
import app.benchjot.core.searchTerms
import app.benchjot.core.AssistantReply
import app.benchjot.core.SavedHistory
import app.benchjot.core.SavedTurn
import app.benchjot.core.stored
import app.benchjot.core.NOTE_INDEX_COLUMNS
import app.benchjot.core.BackgroundRun
import app.benchjot.core.ServerRunResponse
import kotlinx.serialization.SerialName
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import app.benchjot.core.FriendlyException
import app.benchjot.core.LocalData
import app.benchjot.core.Op
import app.benchjot.core.cellsOf
import app.benchjot.core.index
import app.benchjot.core.recentlyDeleted
import app.benchjot.core.isTransient
import app.benchjot.core.search
import app.benchjot.core.withServerAccount
import app.benchjot.core.withServerNote
import app.benchjot.core.withServerCells
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

// Data access: the only module that talks to Supabase tables (the web's src/lib/data.ts).
// Every write validates its input first (core Limits, the web's zod contracts).
// Owner-only RLS on the server does the rest.
//
// Offline first (Offline.kt): notes, code cells and projects are read from the server when there is
// a connection and nothing is waiting to be sent (and kept on the phone as they come), and from the
// phone's copy otherwise. Every change to them is made on the phone first and queued; sendOp sends
// one queued change. Ids are made here, so a note made offline is the same note on the server.

/** A failed call; status/code/retryAfter feed the shared error table (Accounts.message), never shown raw. */
class DataError(message: String, override val status: Int? = null, override val code: String? = null, override val retryAfter: Double? = null) : Exception(message), app.benchjot.core.StatusFailure

@Serializable
private data class IdRow(val id: String)

@Serializable
private data class SummaryRow(val summary: String)

@Serializable
data class Profile(val username: String? = null)

/** A background server run as public.server_runs keeps it (owner only). */
@Serializable
private data class ServerRunRow(
    val id: String,
    val status: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("limit_sec") val limitSec: Int = 0,
    val notice: String? = null,
    val result: ServerRunResponse? = null,
    val error: String? = null,
)

object Backend {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** App-wide scope: saves run here so leaving a screen never cancels one half-way. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
        defaultSerializer = KotlinXSerializer(json)
        install(Auth)
        install(Postgrest)
        install(Storage)
    }

    val session: StateFlow<SessionStatus> get() = client.auth.sessionStatus

    /** Bumped after every write; lists reload on it (the web's SWR revalidation). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    fun changed() { _version.value++ }

    fun userId(): String = client.auth.currentUserOrNull()?.id ?: throw DataError("Sign in first", status = 401)
    fun email(): String = client.auth.currentUserOrNull()?.email.orEmpty()
    fun accessToken(): String? = client.auth.currentSessionOrNull()?.accessToken

    /** Renews the login now (it is renewed in the background too); false when it can't be (signed out for real). */
    suspend fun renewSession(): Boolean = runCatching { client.auth.refreshCurrentSession() }.isSuccess && accessToken() != null

    private fun requireRows(rows: List<IdRow>) { if (rows.isEmpty()) throw DataError("Not found", status = 404) }

    private fun newId() = java.util.UUID.randomUUID().toString()
    private fun now() = java.time.Instant.now().toString()

    /**
     * A read (web: read in lib/data): from the phone's copy once it is complete, online or not (a
     * sync follows if none ran lately), unless the copy doesn't have it (a null answer); else from the
     * server when there is a connection and the queued changes are sent (the copy takes what it
     * sends, [keep]); from the copy when the server can't be reached. So reads cost the server
     * nothing, however many people use it.
     */
    private suspend fun <T> read(server: suspend () -> T, keep: (LocalData, T) -> LocalData = { d, _ -> d }, local: (LocalData) -> T): T {
        val copy = Offline.read()
        if (copy.syncedAt != null) {
            val r = local(copy)
            if (r != null || !Offline.online) { Offline.syncSoon(60_000); return r }
        }
        if (!Offline.online || !Offline.send()) return local(Offline.read())
        return try {
            server().also { r -> Offline.update { keep(it, r) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isTransient(Accounts.facts(e))) local(Offline.read()) else throw e
        }
    }

    /** Said when something only the server can do is asked for offline. */
    private fun needsConnection(what: String): Nothing = throw FriendlyException("$what needs a connection. Your notes are saved on this phone and sync when you’re back online.")

    private suspend fun allProjects() = client.from("projects").select(Columns.list("id", "name")) { order("name", Order.ASCENDING) }.decodeList<Project>()

    /** What changed in the account since [since] (sync_changes); null when the server can't say (not updated yet). */
    private suspend fun changesSince(since: String): Changes? = try {
        client.postgrest.rpc("sync_changes", buildJsonObject { put("since", since) }).decodeAs<Changes>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Brings the phone's copy up to date (Offline.syncAccount; web: syncAccount). A copy that was
     * synced before asks only for what changed since; the first time, a copy away longer than the
     * server keeps deletions, or a server without sync_changes, reads the whole account (that last
     * case at most every [FULL_GAP_MS]). True when anything changed.
     */
    suspend fun syncCopy(): Boolean {
        val before = Offline.read()
        val since = before.syncedAt
        if (since != null) {
            val c = changesSince(since)
            if (c != null && c.complete) {
                val projects = allProjects()
                Offline.update { it.withServerChanges(c, projects) }
                val after = Offline.read()
                return after.notes !== before.notes || after.cells !== before.cells || after.projects != before.projects
            }
            if (c == null && System.currentTimeMillis() - lastFull < FULL_GAP_MS) return false
        }
        lastFull = System.currentTimeMillis()
        // The time the copy is "as of" is the server's (asked first, so nothing saved during the
        // download is missed next time), or a little before this phone's if it can't say.
        val at = changesSince(java.time.Instant.now().plusSeconds(86_400).toString())?.at ?: java.time.Instant.now().minusSeconds(300).toString()
        val notes = allRows { from, to -> client.from("notes").select { order("updated_at", Order.DESCENDING); order("id", Order.ASCENDING); range(from, to) }.decodeList<Note>() }
        val cells = allRows { from, to -> client.from("cells").select { order("note_id", Order.ASCENDING); order("position", Order.ASCENDING); order("id", Order.ASCENDING); range(from, to) }.decodeList<Cell>() }
        val projects = allProjects()
        Offline.update { it.withServerAccount(notes, cells, projects, at) }
        return true
    }
    @Volatile private var lastFull = 0L
    private const val FULL_GAP_MS = 10 * 60_000L

    /** Sends one queued change (Offline.send). A create the server already has counts as sent. */
    internal suspend fun sendOp(op: Op) {
        try {
            when (op) {
                is Op.CreateNote -> client.from("notes").insert(buildJsonObject {
                    put("id", op.note.id)
                    put("title", op.note.title)
                    put("notes", op.note.notes)
                    put("project_id", op.note.projectId)
                    put("parent_id", op.note.parentId)
                    put("outcome", op.note.outcome)
                })
                is Op.PatchNote -> requireRows(client.from("notes").update(op.fields) { select(Columns.list("id")); filter { eq("id", op.id) } }.decodeList<IdRow>())
                is Op.DeleteNote -> client.from("notes").delete { filter { eq("id", op.id) } }
                is Op.CreateCells -> client.from("cells").insert(op.rows.map { s ->
                    buildJsonObject {
                        put("id", s.id)
                        put("note_id", s.noteId)
                        put("position", s.position)
                        put("filename", s.filename)
                        put("language", s.language)
                        put("content", s.content)
                        put("note_before", s.noteBefore)
                        put("note_after", s.noteAfter)
                        put("last_run", s.lastRun?.let { json.encodeToJsonElement(LastRun.serializer(), it) } ?: JsonNull)
                    }
                })
                is Op.PatchCell -> requireRows(client.from("cells").update(op.fields) { select(Columns.list("id")); filter { eq("id", op.id) } }.decodeList<IdRow>())
                is Op.DeleteCell -> client.from("cells").delete { filter { eq("id", op.id) } }
                is Op.CreateProject -> client.from("projects").insert(buildJsonObject { put("id", op.project.id); put("name", op.project.name) })
                is Op.RenameProject -> client.from("projects").update(buildJsonObject { put("name", op.name) }) { filter { eq("id", op.id) } }
                is Op.DeleteProject -> client.from("projects").delete { filter { eq("id", op.id) } }
            }
        } catch (e: Exception) {
            val created = op is Op.CreateNote || op is Op.CreateCells || op is Op.CreateProject
            if (created && app.benchjot.core.errorKind(Accounts.facts(e)) == app.benchjot.core.ErrorKind.Conflict) return
            throw e
        }
    }

    // ------------------------------------------------------------------ profile
    suspend fun profile(): Profile? = read(
        { client.from("profiles").select(Columns.list("username")) { filter { eq("user_id", userId()) } }.decodeSingleOrNull<Profile>() },
        { d, p -> d.copy(username = p?.username ?: d.username) },
        { d -> d.username?.let { Profile(username = it) } },
    )

    suspend fun setUsername(name: String) {
        client.postgrest.rpc("set_username", buildJsonObject { put("name", name) })
    }

    // ------------------------------------------------------------------ projects
    suspend fun projects(): List<Project> = read(
        { client.from("projects").select(Columns.list("id", "name")) { order("name", Order.ASCENDING) }.decodeList<Project>() },
        { d, list -> d.copy(projects = list) },
        { d -> d.projects },
    )

    suspend fun createProject(name: String): Project {
        val p = Project(newId(), Limits.projectName(name))
        Offline.change(Op.CreateProject(p))
        return p
    }

    suspend fun renameProject(id: String, name: String) = Offline.change(Op.RenameProject(id, Limits.projectName(name)))

    /** Deletes the project; its notes stay, unfiled. */
    suspend fun deleteProject(id: String) = Offline.change(Op.DeleteProject(id))

    // ------------------------------------------------------------------ notes
    /** Which notes a list shows: one project's, only unfiled ones, or all; optionally one outcome. */
    data class Scope(val projectId: String? = null, val unfiled: Boolean = false, val outcome: OutcomeFilter = OutcomeFilter.All)

    suspend fun notes(query: String, scope: Scope): List<NoteListItem> = read(
        {
            allRows { from, to ->
                client.postgrest.rpc("search_notes", buildJsonObject {
                    putJsonArray("terms") { searchTerms(query).forEach { add(it) } }
                    put("p_project", scope.projectId)
                    put("p_unfiled", scope.unfiled)
                    put("p_outcome", scope.outcome.param)
                }) { range(from, to) }.decodeList<NoteListItem>()
            }
        },
        local = { d -> d.search(searchTerms(query), scope.projectId, scope.unfiled, scope.outcome.param) },
    )

    suspend fun note(id: String): Note? = read(
        { client.from("notes").select { filter { eq("id", id) } }.decodeSingleOrNull<Note>() },
        { d, e -> if (e != null) d.withServerNote(e) else d },
        { d -> d.notes[id] }, // not here: made elsewhere a moment ago, so the server is asked
    )

    /** Every note (NOTE_INDEX_COLUMNS), most recently edited first. */
    suspend fun noteIndex(): List<NoteIndexItem> = read({ serverIndex() }, local = { d -> d.index() })

    private suspend fun serverIndex(): List<NoteIndexItem> =
        allRows { from, to ->
            client.from("notes").select(Columns.list(*NOTE_INDEX_COLUMNS.toTypedArray())) { filter { exact("deleted_at", null) }; order("updated_at", Order.DESCENDING); order("id", Order.ASCENDING); range(from, to) }.decodeList<NoteIndexItem>()
        }

    /**
     * A new attempt of a note: new_attempt() (SQL, from the web's Research Map migration) copies its
     * title as "<title> (attempt N)", body, project, parent and code cells (not their runs or the
     * outcome) and sets derived_from, in one transaction. Returns the new note's id.
     */
    suspend fun newAttempt(fromId: String): String {
        if (!Offline.online || !Offline.send()) needsConnection("A new attempt")
        val id = client.postgrest.rpc("new_attempt", buildJsonObject { put("p_from", fromId) }).decodeAs<String>()
        Offline.syncAccount() // the copy takes the new note and its cells before it opens
        changed()
        return id
    }

    /** Every [[link]] in the user's notes: worked out on the phone from its copy (as note_links() finds them), else by note_links(). */
    suspend fun noteLinks(): List<SourcedLink> = read(
        { client.postgrest.rpc("note_links").decodeList<NoteLinkRow>().map { SourcedLink(it.source, it.label.trim(), it.target) } },
        local = { d -> d.localLinks() },
    )

    suspend fun createNote(title: String = "", projectId: String? = null, parentId: String? = null): Note {
        val at = now()
        val e = Note(newId(), userId(), projectId, parentId, Limits.title(title), "", null, at, at)
        Offline.change(Op.CreateNote(e))
        return e
    }

    /** A partial update: only the keys present are written (null clears). */
    class NotePatch {
        internal val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        fun title(v: String) { fields["title"] = JsonPrimitive(Limits.title(v)) }
        fun notes(v: String) { fields["notes"] = JsonPrimitive(Limits.notes(v)) }
        fun projectId(v: String?) { fields["project_id"] = v?.let(::JsonPrimitive) ?: JsonNull }
        fun parentId(v: String?) { fields["parent_id"] = v?.let(::JsonPrimitive) ?: JsonNull }
        fun outcome(v: String?) { fields["outcome"] = v?.let(::JsonPrimitive) ?: JsonNull }
        fun pinnedAt(v: String?) { fields["pinned_at"] = v?.let(::JsonPrimitive) ?: JsonNull }
        fun deletedAt(v: String?) { fields["deleted_at"] = v?.let(::JsonPrimitive) ?: JsonNull }
    }

    suspend fun updateNote(id: String, patch: NotePatch.() -> Unit) =
        Offline.change(Op.PatchNote(id, JsonObject(NotePatch().apply(patch).fields)))

    /** Pins or unpins a note (pinned notes come first). Not an edit: its date stays. */
    suspend fun setPinned(id: String, pinned: Boolean) = updateNote(id) { pinnedAt(if (pinned) now() else null) }

    /** Moves a note to Recently Deleted: out of the lists, kept 30 days, restorable until then. */
    suspend fun deleteNote(id: String) = updateNote(id) { deletedAt(now()) }

    /** Brings a note back from Recently Deleted. */
    suspend fun restoreNote(id: String) = updateNote(id) { deletedAt(null) }

    /** Deletes notes for good, now (from Recently Deleted): their cells and shares go too. */
    suspend fun deleteNotesForever(ids: List<String>) = ids.forEach { Offline.change(Op.DeleteNote(it)) }

    /** Recently Deleted, from the phone's copy. */
    suspend fun recentlyDeleted(): List<Note> = Offline.read().recentlyDeleted()

    // ------------------------------------------------------------------ cells
    suspend fun cells(noteId: String): List<Cell> = read(
        {
            client.from("cells").select {
                filter { eq("note_id", noteId) }
                order("position", Order.ASCENDING)
                order("created_at", Order.ASCENDING)
            }.decodeList<Cell>()
        },
        { d, rows -> d.withServerCells(noteId, rows) },
        { d -> d.cellsOf(noteId) },
    )

    /** Adds code blocks to a note in one insert, positioned after `position`. */
    /** Adds code cells after [position], in one insert; their ids, in order. */
    suspend fun createCells(noteId: String, position: Int, items: List<CodeFile>): List<String> {
        val uid = userId()
        val at = now()
        val rows = items.mapIndexed { i, f ->
            Cell(newId(), uid, noteId, Limits.filename(f.filename), f.language, Limits.code(f.content), position + i, createdAt = at, updatedAt = at)
        }
        Offline.change(Op.CreateCells(rows))
        return rows.map { it.id }
    }

    suspend fun createEmptyCell(noteId: String, position: Int) {
        createCells(noteId, position, listOf(CodeFile("", DEFAULT_LANGUAGE, "")))
    }

    suspend fun updateCellCode(id: String, language: String, content: String) =
        Offline.change(Op.PatchCell(id, buildJsonObject { put("language", language); put("content", Limits.code(content)) }))

    /** Writes a code cell's two notes. Only the user's own typing reaches here (the assistant never writes notes). */
    suspend fun updateCellNotes(id: String, before: String, after: String) =
        Offline.change(Op.PatchCell(id, buildJsonObject { put("note_before", Limits.cellNote(before)); put("note_after", Limits.cellNote(after)) }))

    /** Stores (or, with null, clears) a code block's latest run. A saved run isn't an edit: lists don't reload. */
    suspend fun saveLastRun(id: String, run: LastRun?) {
        val value = run?.let { json.encodeToJsonElement(LastRun.serializer(), it) } ?: JsonNull
        Offline.change(Op.PatchCell(id, buildJsonObject { put("last_run", value) }), notify = false)
    }

    suspend fun deleteCell(id: String) = Offline.change(Op.DeleteCell(id))

    // ------------------------------------------------------------------ connections
    /** Removes the Hugging Face connection (a removal needs no secret: the database clears it). */
    suspend fun disconnectHf() {
        client.postgrest.rpc("set_data_tokens", buildJsonObject { put("hf_token", "") })
    }

    // ------------------------------------------------------------------ background server runs
    /** A cell's latest background run that still needs the app: going, or done with a result not yet saved. */
    suspend fun pendingServerRun(cellId: String): BackgroundRun? {
        val row = client.from("server_runs").select(Columns.list("id", "status", "started_at", "limit_sec", "notice", "result", "error")) {
            filter { eq("cell_id", cellId) }
            order("started_at", Order.DESCENDING)
            limit(1)
        }.decodeList<ServerRunRow>().firstOrNull() ?: return null
        if (!(row.status == "running" || (row.status == "done" && row.result != null))) return null
        return BackgroundRun(row.id, row.status, row.startedAt, row.limitSec, row.notice, row.result, row.error)
    }

    /** Drops a background run's stored result once the cell saved it as its last run. */
    suspend fun clearServerRunResult(id: String) {
        client.from("server_runs").update(buildJsonObject { put("result", JsonNull) }) { filter { eq("id", id) } }
    }

    // ------------------------------------------------------------------ assistant history
    // The note's saved assistant turns (assistant_turns) and the summary the database condensed from older
    // ones (assistant_summaries). Owner only (RLS). The assistant writes only these, never the note.

    /** The note's summary and its saved turns, oldest first; rows the app can't read are skipped. */
    suspend fun assistantHistory(noteId: String): SavedHistory {
        val turns = allRows { from, to ->
            client.from("assistant_turns").select(Columns.list("id", "task", "request", "answer", "created_at")) {
                filter { eq("note_id", noteId) }
                order("created_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                range(from, to)
            }.decodeList<SavedTurn>()
        }
        val summary = client.from("assistant_summaries").select(Columns.list("summary")) { filter { eq("note_id", noteId) } }.decodeList<SummaryRow>().firstOrNull()?.summary.orEmpty()
        return SavedHistory(summary, turns.filter { it.readable })
    }

    /** Saves one answered turn with the note; returns the saved row. */
    suspend fun saveAssistantTurn(noteId: String, task: String, request: String, answer: AssistantReply): SavedTurn =
        client.from("assistant_turns").insert(buildJsonObject {
            put("note_id", noteId)
            put("task", task)
            put("request", request)
            put("answer", answer.stored())
        }) { select(Columns.list("id", "task", "request", "answer", "created_at")) }.decodeSingle<SavedTurn>()

    /**
     * Saves a turn again with a new answer (Undo marks it undone), keeping when it was asked (the web's
     * resaveAssistantTurn): turns are never edited in place, so the new row goes in first and the old one goes.
     */
    suspend fun resaveAssistantTurn(noteId: String, turn: SavedTurn, answer: AssistantReply): SavedTurn {
        val row = client.from("assistant_turns").insert(buildJsonObject {
            put("note_id", noteId)
            put("task", turn.task)
            put("request", turn.request)
            put("answer", answer.stored())
            put("created_at", turn.createdAt)
        }) { select(Columns.list("id", "task", "request", "answer", "created_at")) }.decodeSingle<SavedTurn>()
        runCatching { deleteAssistantTurn(turn.id) } // a leftover copy is shown once, never lost
        return row
    }

    /** Clear: deletes one saved turn. */
    suspend fun deleteAssistantTurn(id: String) {
        client.from("assistant_turns").delete { filter { eq("id", id) } }
    }

    /** Clear on the summary: deletes the note's condensed earlier turns. */
    suspend fun deleteAssistantSummary(noteId: String) {
        client.from("assistant_summaries").delete { filter { eq("note_id", noteId) } }
    }
}
