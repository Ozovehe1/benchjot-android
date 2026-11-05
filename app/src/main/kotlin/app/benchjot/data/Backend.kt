package app.benchjot.data

import app.benchjot.BuildConfig
import app.benchjot.core.Entry
import app.benchjot.core.allRows
import app.benchjot.core.EntryListItem
import app.benchjot.core.LastRun
import app.benchjot.core.Limits
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.NoteLinkRow
import app.benchjot.core.OutcomeFilter
import app.benchjot.core.Project
import app.benchjot.core.Snippet
import app.benchjot.core.SourcedLink
import app.benchjot.core.DEFAULT_LANGUAGE
import app.benchjot.core.CodeFile
import app.benchjot.core.searchTerms
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

/** A failed call; status/code/retryAfter feed the shared error table (Accounts.message), never shown raw. */
class DataError(message: String, val status: Int? = null, val code: String? = null, val retryAfter: Double? = null) : Exception(message)

@Serializable
private data class IdRow(val id: String)

@Serializable
data class Profile(val plan: String? = null, val username: String? = null)

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

    private fun requireRows(rows: List<IdRow>) { if (rows.isEmpty()) throw DataError("Not found", status = 404) }

    // ------------------------------------------------------------------ profile
    suspend fun profile(): Profile? =
        client.from("profiles").select(Columns.list("plan", "username")) { filter { eq("user_id", userId()) } }.decodeSingleOrNull<Profile>()

    suspend fun setUsername(name: String) {
        client.postgrest.rpc("set_username", buildJsonObject { put("name", name) })
    }

    // ------------------------------------------------------------------ projects
    suspend fun projects(): List<Project> =
        client.from("projects").select(Columns.list("id", "name")) { order("name", Order.ASCENDING) }.decodeList<Project>()

    suspend fun createProject(name: String): Project {
        val p = client.from("projects").insert(buildJsonObject { put("name", Limits.projectName(name)) }) { select(Columns.list("id", "name")) }.decodeSingle<Project>()
        changed()
        return p
    }

    suspend fun renameProject(id: String, name: String) {
        requireRows(client.from("projects").update(buildJsonObject { put("name", Limits.projectName(name)) }) { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
        changed()
    }

    /** Deletes the project; its notes stay, unfiled. */
    suspend fun deleteProject(id: String) {
        requireRows(client.from("projects").delete { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
        changed()
    }

    // ------------------------------------------------------------------ entries
    /** Which notes a list shows: one project's, only unfiled ones, or all; optionally one outcome. */
    data class Scope(val projectId: String? = null, val unfiled: Boolean = false, val outcome: OutcomeFilter = OutcomeFilter.All)

    suspend fun entries(query: String, scope: Scope): List<EntryListItem> = allRows { from, to ->
        client.postgrest.rpc("search_entries", buildJsonObject {
            putJsonArray("terms") { searchTerms(query).forEach { add(it) } }
            put("p_project", scope.projectId)
            put("p_unfiled", scope.unfiled)
            put("p_outcome", scope.outcome.param)
        }) { range(from, to) }.decodeList<EntryListItem>()
    }

    suspend fun entry(id: String): Entry? =
        client.from("entries").select { filter { eq("id", id) } }.decodeSingleOrNull<Entry>()

    /** Every note (id, title, parent), most recently edited first. */
    suspend fun noteIndex(): List<NoteIndexItem> =
        allRows { from, to ->
            client.from("entries").select(Columns.list("id", "title", "parent_id")) { order("updated_at", Order.DESCENDING); order("id", Order.ASCENDING); range(from, to) }.decodeList<NoteIndexItem>()
        }

    /** Every [[link]] in the user's notes, derived from the bodies by note_links(). */
    suspend fun noteLinks(): List<SourcedLink> =
        client.postgrest.rpc("note_links").decodeList<NoteLinkRow>().map { SourcedLink(it.source, it.label.trim(), it.target) }

    suspend fun createEntry(title: String = "", projectId: String? = null, parentId: String? = null): Entry {
        val e = client.from("entries").insert(buildJsonObject {
            put("title", Limits.title(title))
            put("project_id", projectId)
            put("parent_id", parentId)
        }) { select() }.decodeSingle<Entry>()
        changed()
        return e
    }

    /** A partial update: only the keys present are written (null clears). */
    class EntryPatch {
        internal val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        fun title(v: String) { fields["title"] = JsonPrimitive(Limits.title(v)) }
        fun notes(v: String) { fields["notes"] = JsonPrimitive(Limits.notes(v)) }
        fun projectId(v: String?) { fields["project_id"] = v?.let(::JsonPrimitive) ?: JsonNull }
        fun parentId(v: String?) { fields["parent_id"] = v?.let(::JsonPrimitive) ?: JsonNull }
        fun outcome(v: String?) { fields["outcome"] = v?.let(::JsonPrimitive) ?: JsonNull }
    }

    suspend fun updateEntry(id: String, patch: EntryPatch.() -> Unit) {
        val body = JsonObject(EntryPatch().apply(patch).fields)
        requireRows(client.from("entries").update(body) { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
        changed()
    }

    suspend fun deleteEntry(id: String) {
        requireRows(client.from("entries").delete { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
        changed()
    }

    // ------------------------------------------------------------------ snippets
    suspend fun snippets(entryId: String): List<Snippet> =
        client.from("snippets").select {
            filter { eq("entry_id", entryId) }
            order("position", Order.ASCENDING)
            order("created_at", Order.ASCENDING)
        }.decodeList<Snippet>()

    /** Adds code blocks to a note in one insert, positioned after `position`. */
    suspend fun createSnippets(entryId: String, position: Int, items: List<CodeFile>) {
        val rows = items.mapIndexed { i, f ->
            buildJsonObject {
                put("entry_id", entryId)
                put("position", position + i)
                put("filename", Limits.filename(f.filename))
                put("language", f.language)
                put("content", Limits.code(f.content))
            }
        }
        client.from("snippets").insert(rows) { select(Columns.list("id")) }
        changed()
    }

    suspend fun createEmptySnippet(entryId: String, position: Int) =
        createSnippets(entryId, position, listOf(CodeFile("", DEFAULT_LANGUAGE, "")))

    suspend fun updateSnippetCode(id: String, language: String, content: String) {
        requireRows(client.from("snippets").update(buildJsonObject {
            put("language", language)
            put("content", Limits.code(content))
        }) { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
        changed()
    }

    /** Stores (or, with null, clears) a code block's latest run. A saved run isn't an edit: lists don't reload. */
    suspend fun saveLastRun(id: String, run: LastRun?) {
        val value = run?.let { json.encodeToJsonElement(LastRun.serializer(), it) } ?: JsonNull
        requireRows(client.from("snippets").update(buildJsonObject { put("last_run", value) }) { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
    }

    suspend fun deleteSnippet(id: String) {
        requireRows(client.from("snippets").delete { select(Columns.list("id")); filter { eq("id", id) } }.decodeList<IdRow>())
        changed()
    }
}
