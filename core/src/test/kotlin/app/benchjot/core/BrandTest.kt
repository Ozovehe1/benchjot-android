package app.benchjot.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BrandTest {
    private val web = Fixtures.json("brand.json").jsonObject

    @Test fun brandAndMark() {
        val brand = web["brand"]!!.jsonObject
        assertEquals(brand["charcoal"]!!.jsonPrimitive.content, BrandColors.CHARCOAL)
        assertEquals(brand["ash"]!!.jsonPrimitive.content, BrandColors.ASH)
        val mark = web["mark"]!!.jsonObject
        assertEquals(mark["viewBox"]!!.jsonPrimitive.int, Mark.VIEW_BOX)
        assertEquals(mark["stroke"]!!.jsonPrimitive.int, Mark.STROKE)
        assertEquals(mark["paths"]!!.jsonArray.map { it.jsonPrimitive.content }, Mark.PATHS)
        val c = mark["cursor"]!!.jsonObject
        assertEquals(Mark.Rect(c["x"]!!.jsonPrimitive.int, c["y"]!!.jsonPrimitive.int, c["width"]!!.jsonPrimitive.int, c["height"]!!.jsonPrimitive.int), Mark.CURSOR)
    }

    @Test fun palettesMatchGlobalsCss() {
        for ((name, palette) in listOf("dark" to DARK, "light" to LIGHT)) {
            val t = web[name]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            val mine = mapOf(
                "bg" to palette.bg, "fg" to palette.fg, "muted" to palette.muted, "faint" to palette.faint, "line" to palette.line,
                "tint" to palette.tint, "ok" to palette.ok, "fail" to palette.fail, "hl-keyword" to palette.hlKeyword,
                "hl-string" to palette.hlString, "hl-number" to palette.hlNumber, "hl-comment" to palette.hlComment, "hl-type" to palette.hlType,
            )
            assertEquals(name, t, mine)
        }
    }
}
