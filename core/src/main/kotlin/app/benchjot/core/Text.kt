package app.benchjot.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// Small text rules mirrored from the web: search terms (src/lib/search.ts),
// usernames (src/lib/username.ts) and list dates/titles (src/lib/format.ts).

private const val MAX_TERMS = 8
private const val MAX_TERM_LENGTH = 64

/** Whitespace-separated, lowercased, de-duplicated terms for the search_notes RPC. */
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

/** A note's opening words as plain text for the list (the web's plainExcerpt in format.ts): links, code, maths and marks dropped. */
fun plainExcerpt(md: String, max: Int = 180): String {
    val text = md
        .replace(Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?]]"""), "$1")
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("""\$\$[\s\S]*?\$\$"""), " ")
        .replace(Regex("""(?m)^\s{0,3}(#{1,6}|>|[-*+]|\d+\.)\s+"""), "")
        .replace(Regex("[*_~`]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (text.length > max) text.take(max - 1).trimEnd() + "…" else text
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") // java.time order (Monday = 1)

/**
 * The list's date for a note (the web's listDateLocal, checked by the list_date.json fixture):
 * today "14:05", yesterday "Yesterday", up to 6 days back "Mon", earlier this year "3 Nov",
 * older "3 Nov 2025". A date after `now` (clock skew) shows as its date. Local wall-clock times.
 */
fun listDateLocal(then: LocalDateTime, now: LocalDateTime): String {
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
    return when {
        days == 0L -> "%02d:%02d".format(then.hour, then.minute)
        days == 1L -> "Yesterday"
        days in 2..6 -> WEEKDAYS[then.dayOfWeek.value - 1]
        then.year == now.year -> "${then.dayOfMonth} ${MONTHS[then.monthValue - 1]}"
        else -> "${then.dayOfMonth} ${MONTHS[then.monthValue - 1]} ${then.year}"
    }
}

private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/**
 * The heading a note sits under in the list, by when it was last edited (the web's listGroupLocal,
 * checked by the list_group.json fixture; Apple Notes' groups): "Today", "Yesterday",
 * "Previous 7 days", "Previous 30 days", then the month this year ("October"), then the year.
 */
fun listGroupLocal(then: LocalDateTime, now: LocalDateTime): String {
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        days <= 7L -> "Previous 7 days"
        days <= 30L -> "Previous 30 days"
        then.year == now.year -> MONTH_NAMES[then.monthValue - 1]
        else -> then.year.toString()
    }
}

/** listGroupLocal in the phone's time zone. */
fun listGroup(iso: String, now: ZonedDateTime = ZonedDateTime.now(), zone: ZoneId = now.zone): String {
    val d = runCatching { Instant.parse(iso) }.getOrElse { java.time.OffsetDateTime.parse(iso).toInstant() }.atZone(zone)
    return listGroupLocal(d.toLocalDateTime(), now.withZoneSameInstant(zone).toLocalDateTime())
}

/**
 * The list in sections (the web's listSections): Pinned first, then by when each note was last
 * edited. The rows come pinned first, then newest first, so each section is one run of them.
 */
fun listSections(rows: List<NoteListItem>, now: ZonedDateTime = ZonedDateTime.now()): List<Pair<String, List<NoteListItem>>> {
    val out = ArrayList<Pair<String, MutableList<NoteListItem>>>()
    for (r in rows) {
        val heading = if (r.pinnedAt != null) "Pinned" else listGroup(r.updatedAt, now)
        if (out.lastOrNull()?.first == heading) out.last().second += r else out += heading to mutableListOf(r)
    }
    return out
}

/** How long a note stays in Recently Deleted (the web's RECENTLY_DELETED_DAYS). */
const val RECENTLY_DELETED_DAYS = 30

/** What Recently Deleted says of a note deleted at [deletedIso] (the web's daysLeftText): "12 days left", "1 day left", "Removed today". */
fun daysLeftText(deletedIso: String, now: Instant = Instant.now()): String {
    val at = runCatching { Instant.parse(deletedIso) }.getOrElse { java.time.OffsetDateTime.parse(deletedIso).toInstant() }
    val left = RECENTLY_DELETED_DAYS - Math.floorDiv(now.toEpochMilli() - at.toEpochMilli(), 86_400_000L).toInt()
    return if (left <= 0) "Removed today" else "$left ${if (left == 1) "day" else "days"} left"
}

/** listDateLocal in the phone's time zone. */
fun listDate(iso: String, now: ZonedDateTime = ZonedDateTime.now(), zone: ZoneId = now.zone): String {
    val d = runCatching { Instant.parse(iso) }.getOrElse { java.time.OffsetDateTime.parse(iso).toInstant() }.atZone(zone)
    return listDateLocal(d.toLocalDateTime(), now.withZoneSameInstant(zone).toLocalDateTime())
}

/** PostgREST answers at most this many rows per request (Supabase's max rows); the web's src/lib/pages.ts. */
const val PAGE_ROWS = 1000

/** Every row of a query, a page at a time (inclusive [from, to]), so long lists aren't cut at the row cap. */
suspend fun <T> allRows(page: suspend (from: Long, to: Long) -> List<T>): List<T> {
    val rows = ArrayList<T>()
    var from = 0L
    while (true) {
        val got = page(from, from + PAGE_ROWS - 1)
        rows += got
        if (got.size < PAGE_ROWS) return rows
        from += PAGE_ROWS
    }
}
