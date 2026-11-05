package app.benchjot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.MarkdownFormat

// The note body's Markdown format bar (the web's format-bar.tsx): a slim, quiet row of 48dp
// buttons pinned above the keyboard while the body is being edited. Each runs core applyFormat.

private fun glyph(f: MarkdownFormat): Pair<String, TextStyle> {
    val base = TextStyle(fontSize = 14.sp)
    return when (f) {
        MarkdownFormat.Bold -> "B" to base.copy(fontWeight = FontWeight.SemiBold)
        MarkdownFormat.Italic -> "I" to base.copy(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif)
        MarkdownFormat.Heading -> "H" to base.copy(fontWeight = FontWeight.SemiBold)
        MarkdownFormat.List -> "•" to base
        MarkdownFormat.Code -> "<>" to base.copy(fontFamily = Type.mono, fontSize = 12.sp)
        MarkdownFormat.Math -> "∑" to base
        MarkdownFormat.Link -> "[ ]" to base.copy(fontFamily = Type.mono, fontSize = 12.sp)
    }
}

private fun name(f: MarkdownFormat) = f.name // Bold, Italic, Heading, List, Code, Math, Link (the web's names)

@Composable
fun FormatBar(onFormat: (MarkdownFormat) -> Unit) {
    Row(Modifier.fillMaxWidth().topLine(t.line).horizontalScroll(rememberScrollState()).semantics { contentDescription = "Format" }.testTag("format-bar")) {
        MarkdownFormat.entries.forEach { f ->
            val (g, style) = glyph(f)
            Box(Modifier.size(48.dp).plainClick { onFormat(f) }.semantics { contentDescription = name(f) }, contentAlignment = Alignment.Center) {
                Text(g, style = style, color = t.muted)
            }
        }
    }
}
