package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
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
        assertEquals("2 Jan", listDate(ZonedDateTime.of(2026, 1, 2, 12, 0, 0, 0, zone).toInstant().toString(), now))
        assertEquals("2 Jan 2025", listDate(ZonedDateTime.of(2025, 1, 2, 12, 0, 0, 0, zone).toInstant().toString(), now))
        // Postgres timestamptz as Supabase returns it
        assertEquals("2 Jan 2025", listDate("2025-01-02T11:00:00.123456+00:00", now))
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
