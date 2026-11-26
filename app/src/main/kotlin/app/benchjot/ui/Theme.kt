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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.benchjot.R
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
    /** A raised surface (cards, sheets). */
    val raise: Color = bg,
    /** Running (amber). */
    val run: Color = fg,
    /** App chrome a step below the work. */
    val shell: Color = bg,
)

internal fun hex(s: String) = Color(0xFF000000 or s.removePrefix("#").toLong(16))

private fun Palette.tokens(dark: Boolean) = Tokens(
    hex(bg), hex(fg), hex(muted), hex(faint), hex(line), hex(tint), hex(ok), hex(fail),
    hex(hlKeyword), hex(hlString), hex(hlNumber), hex(hlComment), hex(hlType), dark, hex(raise), hex(run), hex(shell),
)

val DarkTokens = DARK.tokens(true)
val LightTokens = LIGHT.tokens(false)

val LocalTokens = staticCompositionLocalOf { DarkTokens }

/**
 * The type, as the web's: Newsreader (a reading serif) for titles, Inter for the interface,
 * JetBrains Mono for code. Bundled in res/font (SIL Open Font License), so every phone shows the same.
 */
object Type {
    val display = FontFamily(
        Font(R.font.newsreader_regular, FontWeight.Normal),
        Font(R.font.newsreader_medium, FontWeight.Medium),
        Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val sans = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )
    val mono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal), Font(R.font.jetbrains_mono_medium, FontWeight.Medium))
    val body = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 1.5.em)
    val sm = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp)
    val xs = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp)
    /** Section and sheet titles. */
    val xl = TextStyle(fontFamily = display, fontSize = 24.sp, lineHeight = 1.2.em, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em)
    /** A note's title (the web's 28px serif). */
    val title = TextStyle(fontFamily = display, fontSize = 28.sp, lineHeight = 1.15.em, fontWeight = FontWeight.Medium, letterSpacing = (-0.012).em)
    /** A screen's title. */
    val h1 = TextStyle(fontFamily = display, fontSize = 32.sp, lineHeight = 1.1.em, fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em)
    /** Small spaced capitals above a section ("eyebrow"). */
    val eyebrow = TextStyle(fontFamily = sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
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
        // Material's own text (dialogs, menus, the Markdown renderer) in Inter too, titles in Newsreader.
        val base = androidx.compose.material3.Typography()
        val typography = androidx.compose.material3.Typography(
            displayLarge = base.displayLarge.copy(fontFamily = Type.display), displayMedium = base.displayMedium.copy(fontFamily = Type.display), displaySmall = base.displaySmall.copy(fontFamily = Type.display),
            headlineLarge = base.headlineLarge.copy(fontFamily = Type.display), headlineMedium = base.headlineMedium.copy(fontFamily = Type.display), headlineSmall = base.headlineSmall.copy(fontFamily = Type.display),
            titleLarge = base.titleLarge.copy(fontFamily = Type.display), titleMedium = base.titleMedium.copy(fontFamily = Type.sans), titleSmall = base.titleSmall.copy(fontFamily = Type.sans),
            bodyLarge = base.bodyLarge.copy(fontFamily = Type.sans), bodyMedium = base.bodyMedium.copy(fontFamily = Type.sans), bodySmall = base.bodySmall.copy(fontFamily = Type.sans),
            labelLarge = base.labelLarge.copy(fontFamily = Type.sans), labelMedium = base.labelMedium.copy(fontFamily = Type.sans), labelSmall = base.labelSmall.copy(fontFamily = Type.sans),
        )
        MaterialTheme(colorScheme = scheme, typography = typography) {
            androidx.compose.material3.ProvideTextStyle(Type.body, content)
        }
    }
}
