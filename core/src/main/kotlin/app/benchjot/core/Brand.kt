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
    bg = BrandColors.CHARCOAL, fg = BrandColors.ASH, muted = "#8a8885", faint = "#6f6d6a", line = "#2a2a2a", tint = "#1d1d1d",
    ok = BrandColors.ASH, fail = "#b0625a",
    hlKeyword = "#d9d7d4", hlString = "#a3aeb1", hlNumber = "#c2c0bc", hlComment = "#6f6d6a", hlType = "#b3b1ad",
)

val LIGHT = Palette(
    bg = BrandColors.ASH, fg = BrandColors.CHARCOAL, muted = "#5c5a57", faint = "#7a7874", line = "#d0cecb", tint = "#dedcd9",
    ok = "#8a8885", fail = "#9a4d45",
    hlKeyword = "#161616", hlString = "#4b585c", hlNumber = "#3f3d3a", hlComment = "#7a7874", hlType = "#34322f",
)
