package app.benchjot.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's icons, the same family as the web's (src/components/ui/icons.tsx): drawn on a 20-unit
 * grid with a 1.6 stroke and round ends, in the text colour. Decorative beside words; give a
 * description when one stands alone.
 */
object Icons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 20.dp, 20.dp, 20f, 20f).apply {
            for (d in paths) addPath(
                addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Plus = icon("plus", "M10 4.5v11M4.5 10h11")
    val Database = icon("database", "M4.25 5a5.75 2.25 0 1 0 11.5 0a5.75 2.25 0 1 0 -11.5 0", "M4.25 5v10c0 1.24 2.57 2.25 5.75 2.25s5.75-1.01 5.75-2.25V5M4.25 10c0 1.24 2.57 2.25 5.75 2.25s5.75-1.01 5.75-2.25")
    val Upload = icon("upload", "M10 13V4M6.5 7.5 10 4l3.5 3.5", "M4 13.5v1A1.5 1.5 0 0 0 5.5 16h9a1.5 1.5 0 0 0 1.5-1.5v-1")
    val Search = icon("search", circle(8.75f, 8.75f, 5f), "m12.5 12.5 3.5 3.5")
    val Folder = icon("folder", "M3.5 6.25A1.75 1.75 0 0 1 5.25 4.5h2.6l1.6 1.75h5.3a1.75 1.75 0 0 1 1.75 1.75v6.25A1.75 1.75 0 0 1 14.75 16H5.25A1.75 1.75 0 0 1 3.5 14.25Z")
    val Note = icon("note", "M6 3.5h6l3 3v9.25c0 .41-.34.75-.75.75h-8.5a.75.75 0 0 1-.75-.75V4.25c0-.41.34-.75.75-.75Z", "M11.75 3.5V7H15M7.75 10.5h4.5M7.75 13.25h3")
    val Code = icon("code", "m7 6.5-3.5 3.5L7 13.5M13 6.5l3.5 3.5-3.5 3.5")
    val Play = icon("play", "M6.5 4.75v10.5L15 10Z")
    val Server = icon("server", rect(3.5f, 4f, 13f, 5f, 1.25f), rect(3.5f, 11f, 13f, 5f, 1.25f), "M6.5 6.5h.01M6.5 13.5h.01")
    val Eye = icon("eye", "M2.75 10S5.5 4.75 10 4.75 17.25 10 17.25 10 14.5 15.25 10 15.25 2.75 10 2.75 10Z", circle(10f, 10f, 2.25f))
    val Pen = icon("pen", "m12.5 4.5 3 3L7.75 15.25 4 16l.75-3.75Z")
    val Stop = icon("stop", rect(5.5f, 5.5f, 9f, 9f, 1.5f))
    val Chat = icon("chat", "M4 5.75C4 4.78 4.78 4 5.75 4h8.5C15.22 4 16 4.78 16 5.75v5.5c0 .97-.78 1.75-1.75 1.75H9.5L6 15.75V13h-.25C4.78 13 4 12.22 4 11.25Z")
    val Map = icon("map", circle(5.5f, 6f, 2f), circle(14.5f, 5.5f, 2f), circle(10.5f, 14.5f, 2f), "M7.4 6.4 12.5 5.8M6.6 7.7l2.8 5.1M13.6 7.3l-2.1 5.4")
    val Settings = icon("settings", circle(10f, 10f, 2.5f), "M10 2.75v2M10 15.25v2M17.25 10h-2M4.75 10h-2M15.1 4.9l-1.4 1.4M6.3 13.7l-1.4 1.4M15.1 15.1l-1.4-1.4M6.3 6.3 4.9 4.9")
    val X = icon("close", "m5.5 5.5 9 9M14.5 5.5l-9 9")
    val Chevron = icon("chevron", "m8 5 5 5-5 5")
    val Mic = icon("mic", rect(7.5f, 3f, 5f, 9f, 2.5f), "M5 9.5a5 5 0 0 0 10 0M10 14.5V17")
    val Send = icon("send", "M4 10 16 4.5 12.5 16l-2.25-4.75Z")
    val Clock = icon("clock", circle(10f, 10f, 6.5f), "M10 6.5V10l2.25 1.5")
    val Minus = icon("minus", "M4.5 10h11")
    val Check = icon("check", "m4.75 10.5 3.25 3.25 7.25-7.5")
    val Copy = icon("copy", rect(7f, 7f, 9f, 9f, 1.5f), "M13 7V5.5A1.5 1.5 0 0 0 11.5 4h-6A1.5 1.5 0 0 0 4 5.5v6A1.5 1.5 0 0 0 5.5 13H7")
    val Undo = icon("undo", "M7 5.5 4 8.5l3 3M4 8.5h7.25a4.25 4.25 0 0 1 0 8.5H9")
    val Trash = icon("trash", "M4 6h12M8 6V4.75c0-.41.34-.75.75-.75h2.5c.41 0 .75.34.75.75V6M5.5 6l.7 9.1c.06.79.72 1.4 1.5 1.4h4.6c.78 0 1.44-.61 1.5-1.4L14.5 6")
    val Fit = icon("fit", "M4 7.5V5a1 1 0 0 1 1-1h2.5M12.5 4H15a1 1 0 0 1 1 1v2.5M16 12.5V15a1 1 0 0 1-1 1h-2.5M7.5 16H5a1 1 0 0 1-1-1v-2.5")
}

/** One of [Icons], tinted (the text colour by default). */
@Composable
fun BjIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = t.fg, size: Dp = 18.dp, description: String? = null) =
    Icon(icon, description, modifier.size(size), tint = tint)
