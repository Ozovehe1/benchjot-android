package app.benchjot.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.benchjot.core.DARK
import app.benchjot.core.LIGHT
import app.benchjot.core.Palette

// The web's design tokens (globals.css, via core's Palette) as Compose colours,
// and its type scale (Tailwind sizes the web uses: text-xs 12, text-sm 14, body 15,
// text-xl 20, headings 28). System sans and monospace, like the web on Android.

@Immutable
data class Tokens(
    val bg: Color, val fg: Color, val muted: Color, val faint: Color, val line: Color, val tint: Color,
    val ok: Color, val fail: Color,
    val hlKeyword: Color, val hlString: Color, val hlNumber: Color, val hlComment: Color, val hlType: Color,
    val dark: Boolean,
)

private fun hex(s: String) = Color(0xFF000000 or s.removePrefix("#").toLong(16))

private fun Palette.tokens(dark: Boolean) = Tokens(
    hex(bg), hex(fg), hex(muted), hex(faint), hex(line), hex(tint), hex(ok), hex(fail),
    hex(hlKeyword), hex(hlString), hex(hlNumber), hex(hlComment), hex(hlType), dark,
)

val DarkTokens = DARK.tokens(true)
val LightTokens = LIGHT.tokens(false)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

object Type {
    val mono = FontFamily.Monospace
    val body = TextStyle(fontSize = 15.sp, lineHeight = 1.5.em)
    val sm = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val xs = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val xl = TextStyle(fontSize = 20.sp, lineHeight = 1.375.em, fontWeight = FontWeight.SemiBold)
    val h1 = TextStyle(fontSize = 28.sp, lineHeight = 1.15.em, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.025).em)
    val code = TextStyle(fontFamily = mono, fontSize = 13.5.sp, lineHeight = 1.65.em)
    val output = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 1.625.em)
}

/** System / Light / Dark, as the web's Settings > Theme. Stored on the device. */
enum class ThemeChoice(val label: String) { System("System"), Light("Light"), Dark("Dark") }

object ThemePref {
    private const val KEY = "theme"
    val current = mutableStateOf(ThemeChoice.System)
    fun load(context: Context) {
        val v = context.getSharedPreferences("benchjot", Context.MODE_PRIVATE).getString(KEY, null)
        current.value = ThemeChoice.entries.firstOrNull { it.name == v } ?: ThemeChoice.System
    }
    fun set(context: Context, choice: ThemeChoice) {
        context.getSharedPreferences("benchjot", Context.MODE_PRIVATE).edit().putString(KEY, choice.name).apply()
        current.value = choice
    }
}

@Composable
fun isDark(): Boolean = when (ThemePref.current.value) {
    ThemeChoice.System -> isSystemInDarkTheme()
    ThemeChoice.Light -> false
    ThemeChoice.Dark -> true
}

@Composable
fun BenchjotTheme(content: @Composable () -> Unit) {
    val t = if (isDark()) DarkTokens else LightTokens
    // Material components (text selection, the markdown renderer) read these; no colour accent anywhere.
    val scheme = if (t.dark) {
        darkColorScheme(primary = t.fg, onPrimary = t.bg, background = t.bg, onBackground = t.fg, surface = t.bg, onSurface = t.fg,
            surfaceVariant = t.tint, onSurfaceVariant = t.muted, outline = t.line, secondary = t.muted, error = t.fail)
    } else {
        lightColorScheme(primary = t.fg, onPrimary = t.bg, background = t.bg, onBackground = t.fg, surface = t.bg, onSurface = t.fg,
            surfaceVariant = t.tint, onSurfaceVariant = t.muted, outline = t.line, secondary = t.muted, error = t.fail)
    }
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
