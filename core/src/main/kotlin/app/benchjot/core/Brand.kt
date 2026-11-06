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
    const val RADIUS = 14
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
    val hlKeyword: String, val hlString: String, val hlNumber: String, val hlComment: String, val hlType: String,
)

val DARK = Palette(
    bg = BrandColors.CHARCOAL, fg = BrandColors.ASH, muted = "#8a8885", faint = "#878582", line = "#2a2a2a", tint = "#1d1d1d",
    ok = BrandColors.ASH, fail = "#c4726a",
    hlKeyword = "#d9d7d4", hlString = "#a3aeb1", hlNumber = "#c2c0bc", hlComment = "#878582", hlType = "#b3b1ad",
)

val LIGHT = Palette(
    bg = BrandColors.ASH, fg = BrandColors.CHARCOAL, muted = "#5c5a57", faint = "#62605d", line = "#d0cecb", tint = "#dedcd9",
    ok = BrandColors.CHARCOAL, fail = "#924840",
    hlKeyword = "#161616", hlString = "#4b585c", hlNumber = "#3f3d3a", hlComment = "#62605d", hlType = "#34322f",
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
