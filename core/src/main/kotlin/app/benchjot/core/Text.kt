package app.benchjot.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Small text rules mirrored from the web: search terms (src/lib/search.ts),
// usernames (src/lib/username.ts) and list dates/titles (src/lib/format.ts).

private const val MAX_TERMS = 8
private const val MAX_TERM_LENGTH = 64

/** Whitespace-separated, lowercased, de-duplicated terms for the search_entries RPC. */
fun searchTerms(input: String): List<String> =
    input.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        .map { it.take(MAX_TERM_LENGTH) }.distinct().take(MAX_TERMS)

/** Same pattern as the database's private.valid_username. */
val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,20}$")
const val USERNAME_HINT = "3 to 20 letters, numbers or underscores."

fun normalizeUsername(raw: String): String = raw.trim().removePrefix("@").lowercase()
fun isValidUsername(name: String): Boolean = USERNAME_PATTERN.matches(name)

/** A starting suggestion from an email's local part, e.g. "Ada.Lovelace+x@..." -> "ada_lovelace". */
fun suggestUsername(email: String?): String {
    val local = (email ?: "").split("@")[0].split("+")[0]
    val s = local.lowercase().replace(Regex("[^a-z0-9_]+"), "_").replace(Regex("^_+|_+$"), "").take(20)
    return if (s.length >= 3) s else ""
}

/** True when the sign-in field holds an email rather than a username. */
fun looksLikeEmail(identifier: String): Boolean = identifier.contains("@") && !identifier.startsWith("@")

private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
fun isValidEmail(value: String): Boolean = EMAIL.matches(value)

fun displayTitle(title: String): String = title.trim().ifEmpty { "Untitled" }

private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val LONG = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/** Quiet list date: time today, "3 Nov" this year, "3 Nov 2025" otherwise. */
fun listDate(iso: String, now: ZonedDateTime = ZonedDateTime.now(), zone: ZoneId = now.zone): String {
    val d = runCatching { Instant.parse(iso) }.getOrElse { java.time.OffsetDateTime.parse(iso).toInstant() }.atZone(zone)
    return when {
        d.toLocalDate() == now.toLocalDate() -> TIME.format(d)
        d.year == now.year -> SHORT.format(d)
        else -> LONG.format(d)
    }
}
