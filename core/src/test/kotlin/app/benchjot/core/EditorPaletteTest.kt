package app.benchjot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's rule (2026-11-06): nothing in the editor is washed out by the theme. WCAG 2 ratios, both themes. */
class EditorPaletteTest {
    @Test
    fun contrastFormulaMatchesKnownValues() {
        assertEquals(21.0, contrastRatio("#000000", "#ffffff"), 0.01)
        assertEquals(1.0, contrastRatio("#777777", "#777777"), 0.0001)
        assertEquals(4.48, contrastRatio("#777777", "#ffffff"), 0.01)
    }

    @Test
    fun everyPairMeetsItsMinimumInBothThemes() {
        for ((theme, p) in listOf("dark" to DARK, "light" to LIGHT)) {
            val pairs = editorPalette(p).pairs()
            assertEquals(19, pairs.size)
            for (c in pairs) {
                val r = contrastRatio(c.fg, c.bg)
                assertTrue("$theme ${c.name}: ${"%.2f".format(r)} < ${c.min} (${c.fg} on ${c.bg})", r >= c.min)
            }
        }
    }

    @Test
    fun disabledIsVisiblyDimmerThanEnabled() {
        for (p in listOf(DARK, LIGHT)) {
            val e = editorPalette(p)
            assertTrue(contrastRatio(e.disabled, e.bar) < contrastRatio(e.glyph, e.bar) - 3)
        }
    }

    @Test
    fun toolbarGlyphsUseTheFullForeground() {
        for (p in listOf(DARK, LIGHT)) assertEquals(p.fg, editorPalette(p).glyph)
    }
}
