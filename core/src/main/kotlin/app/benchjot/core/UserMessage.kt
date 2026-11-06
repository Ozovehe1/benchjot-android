package app.benchjot.core

import kotlin.math.ceil

// Every error the app shows, in plain words: the web's src/lib/user-message.ts, line for line
// (same table, same rules; error_messages.json checks both). The app extracts ErrorFacts from
// its exceptions (data/Accounts.kt) and logs the raw error; users never see provider text.
// Not for the user's own code: run tracebacks and Ruff diagnostics are shown as they are.

enum class ErrorKind(val id: String) {
    Offline("offline"), Timeout("timeout"), RateLimited("rate_limited"), Session("session"), Permission("permission"),
    TooLarge("too_large"), Conflict("conflict"), NotFound("not_found"), Server("server"), Unknown("unknown");

    companion object { fun of(id: String?): ErrorKind? = entries.firstOrNull { it.id == id } }
}

val ERROR_TEXT: Map<ErrorKind, String> = mapOf(
    ErrorKind.Offline to "You seem to be offline. Check your connection and try again.",
    ErrorKind.Timeout to "This is taking too long. Try again in a moment.",
    ErrorKind.RateLimited to "Too many requests right now. Try again in {wait}.",
    ErrorKind.Session to "Your session has ended. Sign in again to continue.",
    ErrorKind.Permission to "You don’t have access to this.",
    ErrorKind.TooLarge to "That’s too large. Try something smaller.",
    ErrorKind.Conflict to "That’s already taken. Try a different one.",
    ErrorKind.NotFound to "This couldn’t be found. It may have been deleted.",
    ErrorKind.Server to "Something went wrong on our side. Try again in a moment.",
    ErrorKind.Unknown to "Something went wrong. Try again.",
)

const val RETRY_TEXT = "Try again."

/** What the user was doing, said first. Keys are the web's ErrorAction names. */
val ACTION_TEXT: Map<String, String> = mapOf(
    "save_note" to "Couldn’t save your note.",
    "save_output" to "Couldn’t save the output.",
    "save_figures" to "Couldn’t save the figures.",
    "load_figures" to "Couldn’t load the figures.",
    "load" to "Couldn’t load this.",
    "load_notes" to "Couldn’t load your notes.",
    "load_map" to "Couldn’t load the map.",
    "new_attempt" to "Couldn’t start a new attempt.",
    "outcome" to "Couldn’t save the outcome.",
    "parent" to "Couldn’t set the parent note.",
    "move" to "Couldn’t move the note.",
    "project" to "Couldn’t update the project.",
    "upload" to "Couldn’t add the file.",
    "download" to "Couldn’t prepare the file.",
    "sign_in" to "Couldn’t sign you in.",
    "sign_up" to "Couldn’t create your account.",
    "resend" to "Couldn’t resend the link.",
    "username" to "Couldn’t save your username.",
    "fix" to "Fix couldn’t finish.",
    "run_server" to "The server run couldn’t finish.",
    "runner_start" to "The runner couldn’t start.",
)

val ACTION_OVERRIDES: Map<String, Map<ErrorKind, String>> = mapOf(
    "fix" to mapOf(
        ErrorKind.RateLimited to "Fix has hit its limit for now. Try again in {wait}.",
        ErrorKind.TooLarge to "This code is too long for Fix. Try a smaller part.",
    ),
    "run_server" to mapOf(ErrorKind.RateLimited to "Server runs have hit their limit for now. Try again in {wait}."),
    "sign_in" to mapOf(ErrorKind.RateLimited to "Too many attempts. Try again in {wait}."),
    "username" to mapOf(ErrorKind.Conflict to "That username is taken. Try another."),
    "sign_up" to mapOf(ErrorKind.Conflict to "An account with this email already exists. Sign in instead."),
)

/** What both platforms know about a failure. [friendly] is text the app wrote itself, shown as is. */
data class ErrorFacts(
    val status: Int? = null,
    val code: String? = null,
    val message: String? = null,
    val retryAfter: Double? = null,
    val friendly: String? = null,
)

private fun Regex.hit(s: String?) = s != null && containsMatchIn(s)
private val OFFLINE = Regex("(?i)failed to fetch|networkerror|load failed|fetch failed|network request failed|unable to resolve host|failed to connect|connection (refused|reset)|no address associated|enotfound|econnrefused|econnreset|software caused connection abort")
private val RATE_CODE = Regex("(?i)rate.?limit|quota")
private val RATE_MSG = Regex("(?i)rate limit|too many requests|too many attempts|quota|tokens per (minute|day)|requests per (minute|day)")
private val TIMEOUT_MSG = Regex("(?i)timed? ?out|timeout")
private val SESSION_CODE = Regex("^(PGRST30[123]|session_expired|session_not_found|refresh_token_not_found|refresh_token_already_used|bad_jwt|no_authorization)$")
private val SESSION_MSG = Regex("(?i)jwt|refresh token|session (has )?expired|session missing|not authenticated|sign in first")
private val PERMISSION_MSG = Regex("(?i)row-level security|permission denied|forbidden")
private val LARGE_MSG = Regex("(?i)too large|exceeded the maximum allowed size")
private val CONFLICT_CODE = Regex("^(user_already_exists|email_exists|identity_already_exists)$")
private val CONFLICT_MSG = Regex("(?i)duplicate key|already exists|already registered|unique constraint")
private val NOT_FOUND_MSG = Regex("(?i)not found|no rows")
private val SERVER_MSG = Regex("(?i)internal server error|bad gateway|service unavailable")

/** The kind of a failure. Order matters: the first rule that fits wins (as on the web). */
fun errorKind(f: ErrorFacts): ErrorKind {
    val s = f.status
    val c = f.code
    val m = f.message
    ErrorKind.of(c)?.let { return it }
    if (c == "network" || OFFLINE.hit(m)) return ErrorKind.Offline
    if (s == 429 || RATE_CODE.hit(c) || c == "PT429" || RATE_MSG.hit(m)) return ErrorKind.RateLimited
    if (c == "timeout" || s == 408 || s == 504 || TIMEOUT_MSG.hit(m)) return ErrorKind.Timeout
    if (s == 401 || SESSION_CODE.hit(c) || SESSION_MSG.hit(m)) return ErrorKind.Session
    if (s == 403 || c == "42501" || PERMISSION_MSG.hit(m)) return ErrorKind.Permission
    if (s == 413 || LARGE_MSG.hit(m)) return ErrorKind.TooLarge
    if (s == 409 || c == "23505" || CONFLICT_CODE.hit(c) || CONFLICT_MSG.hit(m)) return ErrorKind.Conflict
    if (s == 404 || c == "PGRST116" || NOT_FOUND_MSG.hit(m)) return ErrorKind.NotFound
    if ((s != null && s >= 500) || SERVER_MSG.hit(m)) return ErrorKind.Server
    return ErrorKind.Unknown
}

private val RETRY_IN = Regex("(?i)try again in ((?:\\d+(?:\\.\\d+)?[hms])+)")
private val RETRY_PART = Regex("(?i)(\\d+(?:\\.\\d+)?)([hms])")

/** Seconds from "try again in 7m12.5s" / "1h2m" / "30s" (Groq's wording), or null. */
fun retryAfterFromText(m: String?): Double? {
    val r = m?.let { RETRY_IN.find(it) } ?: return null
    return RETRY_PART.findAll(r.groupValues[1]).sumOf { p ->
        p.groupValues[1].toDouble() * when (p.groupValues[2].lowercase()) { "h" -> 3600.0; "m" -> 60.0; else -> 1.0 }
    }
}

/** "a minute", "5 minutes", "2 hours"; "a few minutes" when the wait is unknown. */
fun waitText(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0) return "a few minutes"
    if (seconds <= 60) return "a minute"
    if (seconds < 3600) return "${ceil(seconds / 60).toInt()} minutes"
    val h = ceil(seconds / 3600).toInt()
    return if (h == 1) "an hour" else "$h hours"
}

fun messageFor(kind: ErrorKind, action: String? = null, retryAfter: Double? = null): String {
    val override = action?.let { ACTION_OVERRIDES[it]?.get(kind) }
    val prefix = action?.let { ACTION_TEXT[it] }
    val text = override ?: if (prefix != null) "$prefix ${if (kind == ErrorKind.Unknown) RETRY_TEXT else ERROR_TEXT.getValue(kind)}" else ERROR_TEXT.getValue(kind)
    return text.replace("{wait}", waitText(retryAfter))
}

/** The text for these facts: the app's own text as is, else the kind's message. */
fun userText(f: ErrorFacts, action: String? = null): String {
    f.friendly?.let { return it }
    return messageFor(errorKind(f), action, f.retryAfter ?: retryAfterFromText(f.message))
}

/** An error whose message the app wrote for the user (validation, limits): shown as is. */
open class FriendlyException(message: String) : Exception(message)
