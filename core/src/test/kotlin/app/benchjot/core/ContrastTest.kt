package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA for the theme: every token used as text is ≥ 4.5:1 on the page (bg) and on code blocks (tint), both themes. */
class ContrastTest {
    private fun text(p: Palette) = mapOf(
        "fg" to p.fg, "muted" to p.muted, "faint" to p.faint, "ok" to p.ok, "fail" to p.fail,
        "hl-keyword" to p.hlKeyword, "hl-string" to p.hlString, "hl-number" to p.hlNumber, "hl-comment" to p.hlComment, "hl-type" to p.hlType,
    )

    @Test fun everyTextTokenIsAaOnBgAndTint() {
        val failures = mutableListOf<String>()
        for ((theme, p) in listOf("dark" to DARK, "light" to LIGHT)) {
            for ((name, color) in text(p)) for ((surface, under) in listOf("bg" to p.bg, "tint" to p.tint)) {
                val r = contrastRatio(color, under)
                if (r < 4.5) failures += "$theme $name $color on $surface $under: %.2f".format(r)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun ratioMatchesWcag() {
        assertEquals(21.0, contrastRatio("#000000", "#ffffff"), 1e-9)
        assertEquals(1.0, contrastRatio("#161616", "#161616"), 1e-9)
        assertEquals(4.92, contrastRatio("#878582", "#161616"), 0.005) // dark faint, the value the web uses
        assertTrue(contrastRatio("#6f6d6a", "#161616") < 4.5) // the old dark faint this replaced
        assertTrue(contrastRatio("#b0625a", "#161616") < 4.5) // the old dark fail
    }
}
