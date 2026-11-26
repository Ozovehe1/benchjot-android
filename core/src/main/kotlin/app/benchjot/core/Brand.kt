package app.benchjot.core

// Brand and palette, mirrored from the web: src/lib/brand.ts (mark + the two brand
// colours) and src/app/globals.css (theme tokens). BrandTest checks them against
// fixtures generated from those files (scripts/web-fixtures.sh).

const val APP_NAME = "Benchjot"
const val APP_DESCRIPTION = "A research jotter for your code."

object BrandColors {
    const val CHARCOAL = "#161616"
    const val ASH = "#e8e6e3"
}

/** 64-unit grid. Every shape is stroked (square caps, miter joins); the cursor is a filled rect. */
object Mark {
    const val VIEW_BOX = 64
    const val STROKE = 4
    val PATHS = listOf(
        "M13 15H51V55H13Z", // pad
        "M22 9V19M32 9V19M42 9V19", // binding rings
        "M21 30L27 36L21 42", // prompt chevron
    )
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
    val CURSOR = Rect(31, 39, 10, 4)
}

/** One theme's tokens as #rrggbb, names as in globals.css (--bg, --fg, ...). */
data class Palette(
    val bg: String, val fg: String, val muted: String, val faint: String, val line: String, val tint: String,
    val ok: String, val fail: String,
    /** Running (amber): colour only means something — ok worked, fail failed, run is going. */
    val run: String,
    val hlKeyword: String, val hlString: String, val hlNumber: String, val hlComment: String, val hlType: String,
    /** A raised surface (cards, sheets): the page's colour lifted a step. */
    val raise: String,
    /** The sidebar / app chrome: a step below the work, so the note leads. */
    val shell: String,
)

/** The web's depth tokens that are CSS shadows and translucent light, drawn natively on Android (no colour of their own here). */
val CSS_ONLY_TOKENS = setOf("edge", "shadow-card", "shadow-float")

val DARK = Palette(
    bg = BrandColors.CHARCOAL, fg = BrandColors.ASH, muted = "#a8a6a2", faint = "#878582", line = "#262523", tint = "#1e1d1b",
    ok = "#6fb07f", fail = "#c4726a", run = "#d4a44c",
    hlKeyword = "#d9d7d4", hlString = "#a3aeb1", hlNumber = "#c2c0bc", hlComment = "#878582", hlType = "#b3b1ad",
    raise = "#1d1c1a", shell = "#121211",
)

val LIGHT = Palette(
    bg = BrandColors.ASH, fg = BrandColors.CHARCOAL, muted = "#4a4845", faint = "#62605d", line = "#d6d4d0", tint = "#dedcd9",
    ok = "#276a3c", fail = "#924840", run = "#8a5a12",
    hlKeyword = "#161616", hlString = "#4b585c", hlNumber = "#3f3d3a", hlComment = "#62605d", hlType = "#34322f",
    raise = "#efedea", shell = "#e0deda",
)

// ------------------------------------------------------------------ contrast (WCAG 2.x)
private fun channel(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else StrictMath.pow((s + 0.055) / 1.055, 2.4) }

/** Relative luminance of "#rrggbb". */
fun luminance(hex: String): Double {
    val v = hex.removePrefix("#").also { require(it.length == 6) { "expected #rrggbb, got $hex" } }.toLong(16).toInt()
    return 0.2126 * channel(v shr 16 and 0xff) + 0.7152 * channel(v shr 8 and 0xff) + 0.0722 * channel(v and 0xff)
}

/** WCAG contrast ratio between two "#rrggbb" colours (1–21). */
fun contrastRatio(a: String, b: String): Double {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
