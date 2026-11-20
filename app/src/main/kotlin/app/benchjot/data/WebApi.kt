package app.benchjot.data

import app.benchjot.BuildConfig
import app.benchjot.core.AgentReply
import app.benchjot.core.AgentRequest
import app.benchjot.core.BackgroundRun
import app.benchjot.core.FixAnswer
import app.benchjot.core.FixRequest
import app.benchjot.core.FriendlyException
import app.benchjot.core.SeeReply
import app.benchjot.core.SeeRequest
import app.benchjot.core.ServerRunResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The web app's API routes, called with the user's Supabase access token as a Bearer header:
// /api/run (a server run the request waits for), /api/runs (Python in the background, up to an hour,
// followed by GET /api/runs/{id}, stopped by DELETE), /api/fix and /api/assistant (on the user's own
// connected AI), and /api/agent (one step of the voice agent; the
// words themselves come from the phone's own speech recognition, voice/Speech.kt). The server checks every limit (the day's GPU runs); the app shows its answer.

/** A route's own words that come with something to offer: "own_key" (Connect AI) or "hf" (Connect Hugging Face). */
class OfferException(message: String, val offer: String) : FriendlyException(message)

/** The AI's own limit, said in the route's words: the agent waits out one that clears within a minute or two. */
class LimitNotice(message: String, override val retryAfter: Double?) : FriendlyException(message), app.benchjot.core.StatusFailure {
    override val status: Int get() = 429
    override val code: String get() = "notice"
}

@Serializable
private data class RunBody(val language: String, val code: String)

@Serializable
private data class BackgroundBody(val language: String, val code: String, val cellId: String? = null)

@Serializable
private data class ConnectBody(val provider: String, val nonce: String)

@Serializable
private data class ConnectLink(val url: String)

@Serializable
private data class FinishBody(val r: String)

@Serializable
data class FinishAnswer(val provider: String)

/** Hugging Face as Settings shows it: connected or not, as whom, and how (signed in or a pasted token). */
@Serializable
data class HfStatus(val hf: Boolean = false, val hfUser: String? = null, val hfBy: String? = null, val hfRemoved: Boolean = false)

/** Said once the server finds Benchjot's access was removed on Hugging Face itself (the web's dataset-keys.tsx). */
const val HF_REMOVED = "Benchjot’s access was removed on Hugging Face, so it’s disconnected here too. Connect again to use gated and private models."

/** The account's own counts (GET /api/usage): AI requests on its connection, GPU runs against the day's limit. */
@Serializable
data class Usage(
    @kotlinx.serialization.SerialName("ai_own_24h") val aiOwn24h: Int = 0,
    @kotlinx.serialization.SerialName("ai_own_30d") val aiOwn30d: Int = 0,
    @kotlinx.serialization.SerialName("server_runs_24h") val gpuRuns24h: Int = 0,
    @kotlinx.serialization.SerialName("ai_connected") val aiConnected: Boolean = false,
    @kotlinx.serialization.SerialName("gpu_runs_per_day") val gpuRunsPerDay: Int? = null,
)

@Serializable
private class Empty

object WebApi {
    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 310_000 // the run route may live 300 s
            connectTimeoutMillis = 20_000
        }
    }

    private suspend fun <B, T> post(path: String, body: B, inSer: KSerializer<B>, outSer: KSerializer<T>): T =
        call(HttpMethod.Post, path, Backend.json.encodeToString(inSer, body), outSer)

    private suspend fun <T> call(method: HttpMethod, path: String, body: String?, outSer: KSerializer<T>): T {
        val res = send(method, path, body, Backend.accessToken() ?: if (Backend.renewSession()) Backend.accessToken()!! else throw DataError("Sign in first", status = 401))
        // A login that ran out a moment ago (the app was in the background) is renewed and the call made again, once.
        if (res.status.value == 401 && Backend.renewSession()) return answer(path, send(method, path, body, Backend.accessToken()!!), outSer)
        return answer(path, res, outSer)
    }

    private suspend fun send(method: HttpMethod, path: String, body: String?, token: String): HttpResponse =
        http.request(BuildConfig.WEB_URL + path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.CacheControl, "no-store")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    /** A route's answer: its JSON, or the error it stands for (thrown). */
    private suspend fun <T> answer(path: String, res: HttpResponse, outSer: KSerializer<T>): T {
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) {
            // {error: text for the user, code: kind or "notice" (the route's own text), retryAfter: seconds}
            val o = runCatching { Backend.json.parseToJsonElement(text).jsonObject }.getOrNull()
            val error = runCatching { o?.get("error")?.jsonPrimitive?.content }.getOrNull()
            val code = runCatching { o?.get("code")?.jsonPrimitive?.content }.getOrNull()
            val offer = runCatching { o?.get("offer")?.jsonPrimitive?.content }.getOrNull()
            val retryAfter = runCatching { o?.get("retryAfter")?.jsonPrimitive?.content?.toDouble() }.getOrNull()
                ?: res.headers["retry-after"]?.toDoubleOrNull()
            if (code == "notice" && !error.isNullOrBlank()) throw when {
                offer != null -> OfferException(error, offer)
                res.status.value == 429 -> LimitNotice(error, retryAfter)
                else -> FriendlyException(error)
            }
            throw DataError("$path answered ${res.status.value}", status = res.status.value, code = code, retryAfter = retryAfter)
        }
        return Backend.json.decodeFromString(outSer, text)
    }

    suspend fun runOnServer(language: String, code: String): ServerRunResponse =
        post("/api/run", RunBody(language, code), RunBody.serializer(), ServerRunResponse.serializer())

    /** Starts a background server run of [cellId]'s code; it goes on if the app closes. */
    suspend fun startRun(language: String, code: String, cellId: String?): BackgroundRun =
        post("/api/runs", BackgroundBody(language, code, cellId), BackgroundBody.serializer(), BackgroundRun.serializer())

    /** A background run as it is now; null when it's no longer there (404). */
    suspend fun backgroundRun(id: String): BackgroundRun? = try {
        call(HttpMethod.Get, "/api/runs/$id", null, BackgroundRun.serializer())
    } catch (e: DataError) {
        if (e.status == 404) null else throw e
    }

    /** A link that starts connecting [provider] ("ai" or "hf") in the browser, bound to this user and [nonce]. */
    suspend fun connectLink(provider: String, nonce: String): String =
        post("/api/connect/app", ConnectBody(provider, nonce), ConnectBody.serializer(), ConnectLink.serializer()).url

    /** Saves what the browser tab returned with (sealed to this user); answers which provider it was. */
    suspend fun connectFinish(r: String): FinishAnswer = post("/api/connect/finish", FinishBody(r), FinishBody.serializer(), FinishAnswer.serializer())

    /** Disconnects the user's AI (their OpenRouter key leaves Benchjot). */
    suspend fun disconnectAi() { call(HttpMethod.Delete, "/api/ai-key", null, Empty.serializer()) }

    suspend fun hfStatus(): HfStatus = call(HttpMethod.Get, "/api/data-keys", null, HfStatus.serializer())

    suspend fun usage(): Usage = call(HttpMethod.Get, "/api/usage", null, Usage.serializer())

    /** Stops a background run; answers the run as it ended. */
    suspend fun stopRun(id: String): BackgroundRun = call(HttpMethod.Delete, "/api/runs/$id", null, BackgroundRun.serializer())

    suspend fun fix(request: FixRequest): FixAnswer =
        post("/api/fix", request, FixRequest.serializer(), FixAnswer.serializer())

    @Serializable private data class RefreshBody(val tokens: List<String>)
    @Serializable private data class InviteBody(val noteId: String, val username: String)
    @Serializable data class Invited(val username: String = "")

    /** Invites someone to a shared note through the web, which also alerts their browsers. */
    suspend fun shareInvite(noteId: String, username: String): Invited =
        post("/api/share/invite", InviteBody(noteId, username), InviteBody.serializer(), Invited.serializer())

    /** The owner changed a shared link: the web drops its cached shared page now (answers 204, no body). */
    suspend fun shareRefresh(tokens: List<String>) {
        val token = Backend.accessToken() ?: return
        send(HttpMethod.Post, "/api/share/refresh", Backend.json.encodeToString(RefreshBody.serializer(), RefreshBody(tokens.take(4))), token)
    }

    /** Tool calls go as the web's schema reads them: fields a call doesn't have are left out. */
    private val agentJson = Json(Backend.json) { explicitNulls = false }

    /** One step of the voice agent: the calls to make next. */
    suspend fun agentStep(request: AgentRequest): AgentReply =
        call(HttpMethod.Post, "/api/agent", agentJson.encodeToString(AgentRequest.serializer(), request), AgentReply.serializer())

    /**
     * The user's notes closest in meaning (the note-search Edge Function, as the web calls it), closest
     * first: to the words, or with none to [note] itself. Empty when it can't answer within 8 s
     * (offline, or a new account still being embedded): find_notes then uses the word search alone.
     */
    suspend fun closeInMeaning(query: String, note: String): List<String> = runCatching {
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            val token = Backend.accessToken() ?: return@withTimeoutOrNull emptyList()
            val body = kotlinx.serialization.json.buildJsonObject {
                if (query.isNotBlank()) put("query", kotlinx.serialization.json.JsonPrimitive(query.trim()))
                put("note", kotlinx.serialization.json.JsonPrimitive(note))
            }
            val res = http.request(BuildConfig.SUPABASE_URL + "/functions/v1/note-search") {
                method = HttpMethod.Post
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            if (!res.status.isSuccess()) return@withTimeoutOrNull emptyList()
            Backend.json.parseToJsonElement(res.bodyAsText()).jsonObject["matches"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        } ?: emptyList()
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }

    /** The seer (the web's /api/see): what a run's pictures show. */
    suspend fun see(request: SeeRequest): SeeReply =
        call(HttpMethod.Post, "/api/see", agentJson.encodeToString(SeeRequest.serializer(), request), SeeReply.serializer())
}
