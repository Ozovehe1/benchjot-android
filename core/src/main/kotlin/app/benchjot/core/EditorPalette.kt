package app.benchjot.core


// Which theme token every part of the live note editor and its format bar uses (the user's rule,
// 2026-11-06: nothing washed out by the theme). One place for the choice, so the Compose UI and the
// contrast test (EditorPaletteTest) read the same answer. Built from the shared Palette (DARK/LIGHT).

data class EditorPalette(
    /** The format bar's background, and the note body's. */
    val bar: String,
    /** Toolbar glyphs: the full foreground, never muted. */
    val glyph: String,
    /** A disabled button (Undo with nothing to undo): dimmed, still >= 3:1. */
    val disabled: String,
    /** The filled chip behind an active button, and the glyph on it. */
    val chip: String,
    val chipGlyph: String,
    /** Body text and its live parts. */
    val text: String,
    val marker: String,
    val done: String,
    val quote: String,
    val quoteBar: String,
    val link: String,
    /** Inline code, fenced blocks and tables: text on a tinted ground. */
    val codeText: String,
    val codeBg: String,
    /** Bullets, numbers and checkboxes (as text, >= 4.5:1), the tick on a done box, and the divider rule (>= 3:1). */
    val listGlyph: String,
    val checkMark: String,
    val rule: String,
    /** The empty block's placeholder (>= 3:1). */
    val placeholder: String,
    /** [[ link suggestions: a row, and the strong (chosen) row. */
    val suggestion: String,
    val suggestionStrong: String,
)

fun editorPalette(p: Palette) = EditorPalette(
    bar = p.bg, glyph = p.fg, disabled = p.faint, chip = p.fg, chipGlyph = p.bg,
    text = p.fg, marker = p.muted, done = p.muted, quote = p.muted, quoteBar = p.muted, link = p.fg,
    codeText = p.fg, codeBg = p.tint, listGlyph = p.fg, checkMark = p.bg, rule = p.muted,
    placeholder = p.faint, suggestion = p.muted, suggestionStrong = p.fg,
)
// Same tokens as the web's LIVE_COLORS (src/lib/live-editor.ts) and format-bar.tsx.

/** A foreground drawn on a background, and the WCAG ratio it must reach. */
data class ContrastPair(val name: String, val fg: String, val bg: String, val min: Double)

const val AA_TEXT = 4.5
const val AA_NON_TEXT = 3.0

/** Every colour pair the editor draws. */
fun EditorPalette.pairs(): List<ContrastPair> = listOf(
    ContrastPair("bar glyph", glyph, bar, AA_TEXT),
    ContrastPair("disabled glyph", disabled, bar, AA_NON_TEXT),
    ContrastPair("active chip vs bar", chip, bar, AA_NON_TEXT),
    ContrastPair("glyph on active chip", chipGlyph, chip, AA_TEXT),
    ContrastPair("body text", text, bar, AA_TEXT),
    ContrastPair("marker on active line", marker, bar, AA_TEXT),
    ContrastPair("checked item", done, bar, AA_TEXT),
    ContrastPair("quote text", quote, bar, AA_TEXT),
    ContrastPair("quote bar", quoteBar, bar, AA_NON_TEXT),
    ContrastPair("link / wikilink", link, bar, AA_TEXT),
    ContrastPair("code text on tint", codeText, codeBg, AA_TEXT),
    ContrastPair("marker on tint", marker, codeBg, AA_TEXT),
    ContrastPair("bullets and numbers", listGlyph, bar, AA_TEXT),
    ContrastPair("checkbox outline", listGlyph, bar, AA_NON_TEXT),
    ContrastPair("tick on a done checkbox", checkMark, listGlyph, AA_TEXT),
    ContrastPair("divider rule", rule, bar, AA_NON_TEXT),
    ContrastPair("placeholder", placeholder, bar, AA_NON_TEXT),
    ContrastPair("link suggestion", suggestion, bar, AA_TEXT),
    ContrastPair("link suggestion (strong)", suggestionStrong, bar, AA_TEXT),
)

// Contrast: luminance() and contrastRatio() in Brand.kt (one WCAG implementation for both palettes).
