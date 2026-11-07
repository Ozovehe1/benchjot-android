package app.benchjot.data

import app.benchjot.BuildConfig
import app.benchjot.core.FixAnswer
import app.benchjot.core.FixRequest
import app.benchjot.core.FriendlyException
import app.benchjot.core.HelperReply
import app.benchjot.core.HelperRequest
import app.benchjot.core.ServerRunResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The web app's API routes (/api/run for Pro server runs, /api/fix for Fix, /api/helper for the
// Online helper; Fix and the helper share one limit, counted on the server), called
// with the user's Supabase access token as a Bearer header. The server checks the
// plan; the app only decides which buttons to show.

@Serializable
private data class RunBody(val language: String, val code: String)

object WebApi {
    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 310_000 // the run route may live 300 s
            connectTimeoutMillis = 20_000
        }
    }

    private suspend fun <B, T> post(path: String, body: B, inSer: KSerializer<B>, outSer: KSerializer<T>): T {
        val token = Backend.accessToken() ?: throw DataError("Sign in first", status = 401)
        val res = http.post(BuildConfig.WEB_URL + path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Backend.json.encodeToString(inSer, body))
        }
        val text = res.bodyAsText()
        if (!res.status.isSuccess()) {
            // {error: text for the user, code: kind or "notice" (the route's own text), retryAfter: seconds}
            val o = runCatching { Backend.json.parseToJsonElement(text).jsonObject }.getOrNull()
            val error = runCatching { o?.get("error")?.jsonPrimitive?.content }.getOrNull()
            val code = runCatching { o?.get("code")?.jsonPrimitive?.content }.getOrNull()
            if (code == "notice" && !error.isNullOrBlank()) throw FriendlyException(error)
            val retryAfter = runCatching { o?.get("retryAfter")?.jsonPrimitive?.content?.toDouble() }.getOrNull()
                ?: res.headers["retry-after"]?.toDoubleOrNull()
            throw DataError("$path answered ${res.status.value}", status = res.status.value, code = code, retryAfter = retryAfter)
        }
        return Backend.json.decodeFromString(outSer, text)
    }

    suspend fun runOnServer(language: String, code: String): ServerRunResponse =
        post("/api/run", RunBody(language, code), RunBody.serializer(), ServerRunResponse.serializer())

    suspend fun fix(request: FixRequest): FixAnswer =
        post("/api/fix", request, FixRequest.serializer(), FixAnswer.serializer())

    suspend fun helper(request: HelperRequest): HelperReply =
        post("/api/helper", request, HelperRequest.serializer(), HelperReply.serializer())
}
