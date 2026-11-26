package app.benchjot.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// Sharing a note (the web's lib/data/shares.ts), through the same database functions: a note's link
// and options (note_shares, owner-only), the people it's shared with (added by username, in Benchjot:
// no email), and the reader's "Shared with me". Every change then clears the shared page's cache on
// the web (/api/share/refresh), so a lock or a removal holds for everyone at once. Needs a connection.

@Serializable
data class ShareRow(
    @SerialName("note_id") val noteId: String,
    val token: String,
    @SerialName("show_code") val showCode: Boolean,
    @SerialName("allow_copy") val allowCopy: Boolean,
    val indexable: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** "people": only the people added; "link": anyone with the link. */
    val access: String,
    val views: Int,
)

@Serializable
data class SharePerson(@SerialName("user_id") val userId: String, val username: String? = null, @SerialName("added_at") val addedAt: String, val accepted: Boolean = true)

@Serializable
data class SharedWithMe(
    @SerialName("note_id") val noteId: String,
    val title: String,
    val outcome: String? = null,
    val token: String,
    val author: String? = null,
    @SerialName("added_at") val addedAt: String,
    /** An invitation not answered yet (Accept / Decline). */
    val pending: Boolean = false,
)


object Shares {
    private val db get() = Backend.client

    fun link(token: String) = "${app.benchjot.BuildConfig.WEB_URL}/s/$token"

    suspend fun get(noteId: String): ShareRow? =
        db.from("note_shares").select { filter { eq("note_id", noteId) } }.decodeSingleOrNull<ShareRow>()

    /** Starts sharing; only the people added can open it unless [access] is "link". */
    suspend fun start(noteId: String, access: String = "people"): ShareRow {
        val me = db.auth.currentUserOrNull()?.id ?: throw DataError("Sign in first", status = 401)
        return db.from("note_shares").insert(buildJsonObject { put("note_id", noteId); put("user_id", me); put("access", access) }) { select() }.decodeSingle<ShareRow>()
    }

    suspend fun update(noteId: String, patch: JsonObject): ShareRow {
        val row = db.from("note_shares").update(patch) { select(); filter { eq("note_id", noteId) } }.decodeSingle<ShareRow>()
        refresh(row.token)
        return row
    }

    /** A day picked in the calendar (the picker's UTC midnight) as the moment sharing ends: the end of that day, on this phone. */
    fun endOfDay(pickedUtcMillis: Long): String {
        val day = Instant.ofEpochMilli(pickedUtcMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return day.atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
    }

    /** Answers an invitation: accept keeps it in Shared with me; decline removes you from the note. */
    suspend fun respond(noteId: String, accept: Boolean) {
        db.postgrest.rpc("share_respond", buildJsonObject { put("note", noteId); put("accept", accept) })
    }

    /** A new link; the old one stops working at once. The people added keep access. */
    suspend fun reset(noteId: String, oldToken: String): ShareRow? {
        db.postgrest.rpc("share_reset", buildJsonObject { put("note", noteId) })
        val row = get(noteId)
        refresh(oldToken, row?.token)
        return row
    }

    /** Stops sharing (locks the note): the link and everyone's access end at once. */
    suspend fun stop(noteId: String, token: String) {
        db.from("note_shares").delete { filter { eq("note_id", noteId) } }
        refresh(token)
    }

    suspend fun people(noteId: String): List<SharePerson> =
        db.postgrest.rpc("share_people", buildJsonObject { put("note", noteId) }).decodeAs<List<SharePerson>>()

    /** Through the web, which also notifies their browsers. */
    suspend fun invite(noteId: String, username: String): WebApi.Invited = WebApi.shareInvite(noteId, username.trim().removePrefix("@"))

    suspend fun remove(noteId: String, personId: String, token: String) {
        db.postgrest.rpc("share_remove", buildJsonObject { put("note", noteId); put("person", personId) })
        refresh(token)
    }

    suspend fun sharedWithMe(): List<SharedWithMe> = db.postgrest.rpc("shared_with_me").decodeAs<List<SharedWithMe>>()

    private suspend fun refresh(vararg tokens: String?) {
        val list = tokens.filterNotNull()
        if (list.isNotEmpty()) runCatching { WebApi.shareRefresh(list) }
    }

    /** The database's own words for what a person can fix ("No one has that username"), else null. */
    fun friendly(e: Throwable): String? {
        val m = e.message.orEmpty()
        return listOf("No one has that username", "That’s you: you already have this note", "This note isn't shared").firstOrNull { m.contains(it) }
    }

}
