package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime

class TextTest {
    @Test fun searchTerms() {
        assertEquals(listOf("torch.cos", "lr"), searchTerms("  Torch.cos   LR lr "))
        assertEquals(8, searchTerms((1..20).joinToString(" ") { "t$it" }).size)
        assertEquals(64, searchTerms("x".repeat(100))[0].length)
        assertEquals(emptyList<String>(), searchTerms("   "))
    }

    @Test fun usernames() {
        assertEquals("ada_l", normalizeUsername("  @Ada_L "))
        assertTrue(isValidUsername("ada_99"))
        for (bad in listOf("ab", "a".repeat(21), "Ada", "ad-a", "ad a")) assertFalse(bad, isValidUsername(bad))
        assertEquals("ada_lovelace", suggestUsername("Ada.Lovelace+x@example.com"))
        assertEquals("", suggestUsername("ab@x.com"))
        assertEquals("", suggestUsername(null))
        assertTrue(looksLikeEmail("a@b.co"))
        assertFalse(looksLikeEmail("@ada"))
        assertFalse(looksLikeEmail("ada"))
    }

    @Test fun titlesAndDates() {
        assertEquals("Untitled", displayTitle("  "))
        assertEquals("LR sweep", displayTitle("LR sweep"))
        val zone = ZoneId.of("Africa/Lagos")
        val now = ZonedDateTime.of(2026, 11, 3, 18, 0, 0, 0, zone)
        assertEquals("09:05", listDate(ZonedDateTime.of(2026, 11, 3, 9, 5, 0, 0, zone).toInstant().toString(), now))
        assertEquals("Yesterday", listDate(ZonedDateTime.of(2026, 11, 2, 23, 59, 0, 0, zone).toInstant().toString(), now))
        assertEquals("Sun", listDate(ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, zone).toInstant().toString(), now))
        assertEquals("2 Jan", listDate(ZonedDateTime.of(2026, 1, 2, 12, 0, 0, 0, zone).toInstant().toString(), now))
        assertEquals("2 Jan 2025", listDate(ZonedDateTime.of(2025, 1, 2, 12, 0, 0, 0, zone).toInstant().toString(), now))
        // Postgres timestamptz as Supabase returns it
        assertEquals("2 Jan 2025", listDate("2025-01-02T11:00:00.123456+00:00", now))
    }

    @Test fun listDatesMatchTheWeb() {
        // [name, then, now] stamps as local [y, m, d, h, min], with the web's own answer.
        for (c in Fixtures.json("list_date.json").jsonArray.map { it.jsonObject }) {
            fun stamp(k: String) = c.getValue(k).jsonArray.map { it.jsonPrimitive.int }.let { LocalDateTime.of(it[0], it[1], it[2], it[3], it[4]) }
            assertEquals(c.getValue("name").jsonPrimitive.content, c.getValue("date").jsonPrimitive.content, listDateLocal(stamp("then"), stamp("now")))
        }
    }

    @Test fun pagesPastTheRowCap() = kotlinx.coroutines.runBlocking {
        val table = (0 until 2 * PAGE_ROWS + 37).toList()
        val asked = mutableListOf<Pair<Long, Long>>()
        assertEquals(table, allRows { f, t -> asked += f to t; table.subList(f.toInt(), minOf(t.toInt() + 1, table.size)) })
        assertEquals(listOf(0L to 999L, 1000L to 1999L, 2000L to 2999L), asked)
    }

    @Test fun outcomes() {
        assertEquals(Outcome.Success, Outcome.of("success"))
        assertEquals(null, Outcome.of(null))
        assertEquals(listOf("—", "Successful", "Failed"), OUTCOME_CHOICES.map { it.label })
        assertEquals(listOf("All", "Successful", "Failed", "Unmarked"), OutcomeFilter.entries.map { it.label })
        assertEquals(null, OutcomeFilter.All.param)
        assertEquals("unmarked", OutcomeFilter.Unmarked.param)
    }
}
