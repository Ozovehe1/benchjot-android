package app.benchjot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.FormatState
import app.benchjot.core.ListKind
import app.benchjot.core.MarkdownFormat
import app.benchjot.core.SHEET_FORMATS

// The note body's format bar (the web's format-bar.tsx; order and states from the shared spec):
// Undo, Redo, Heading, Bold, List, Checklist fixed, then a scrolling row of Italic, Strike, Quote,
// Indent, Outdent, Code, Math, Link and "+" (a sheet with Divider and Table). Material 3 icon toggle
// buttons: 40dp chips in 48dp targets, the active format a filled chip, disabled dimmed but >= 3:1
// (colours from core's EditorPalette, checked by EditorPaletteTest).

private fun icon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)).build()

// Material icon paths (24dp grid, Apache 2.0).
private object Glyphs {
    val undo = icon("undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val redo = icon("redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z")
    val bold = icon("bold", "M15.6,10.79c0.97,-0.67 1.65,-1.77 1.65,-2.79 0,-2.26 -1.75,-4 -4,-4H7v14h7.04c2.09,0 3.71,-1.7 3.71,-3.79 0,-1.52 -0.86,-2.82 -2.15,-3.42zM10,6.5h3c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5h-3v-3zM13.5,15.5H10v-3h3.5c0.83,0 1.5,0.67 1.5,1.5s-0.67,1.5 -1.5,1.5z")
    val italic = icon("italic", "M10,4v3h2.21l-3.42,8H6v3h8v-3h-2.21l3.42,-8H18V4z")
    val strike = icon("strike", "M10,19h4v-3h-4v3zM5,4v3h5v3h4V7h5V4H5zM3,14h18v-2H3v2z")
    val bullets = icon("bullets", "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.67c-0.74,0 -1.33,0.6 -1.33,1.33s0.6,1.33 1.33,1.33 1.33,-0.6 1.33,-1.33 -0.59,-1.33 -1.33,-1.33zM7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z")
    val numbered = icon("numbered", "M2,17h2v0.5H3v1h1v0.5H2v1h3v-4H2v1zM3,8h1V4H2v1h1v3zM2,11h1.8L2,13.1v0.9h3v-1H3.2L5,10.9V10H2v1zM7,5v2h14V5H7zM7,19h14v-2H7v2zM7,13h14v-2H7v2z")
    val checklist = icon("checklist", "M22,7h-9v2h9V7zM22,15h-9v2h9V15zM5.54,11L2,7.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,11zM5.54,19L2,15.46l1.41,-1.41l2.12,2.12l4.24,-4.24l1.41,1.41L5.54,19z")
    val quote = icon("quote", "M6,17h3l2,-4V7H5v6h3zM14,17h3l2,-4V7h-6v6h3z")
    val indent = icon("indent", "M3,21h18v-2H3v2zM3,8v8l4,-4 -4,-4zM11,17h10v-2H11v2zM3,3v2h18V3H3zM11,9h10V7H11v2zM11,13h10v-2H11v2z")
    val outdent = icon("outdent", "M11,17h10v-2H11v2zM3,12l4,4V8l-4,4zM3,21h18v-2H3v2zM3,3v2h18V3H3zM11,9h10V7H11v2zM11,13h10v-2H11v2z")
    val code = icon("code", "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z")
    val math = icon("math", "M18,4H6v2l6.5,6L6,18v2h12v-3h-7l5,-5 -5,-5h7z")
    val link = icon("link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    val more = icon("more", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val divider = icon("divider", "M4,11h16v2H4z")
    val table = icon("table", "M10,10.02h5V21h-5zM17,21h3c1.1,0 2,-0.9 2,-2v-9h-5v11zM20,3H5c-1.1,0 -2,0.9 -2,2v3h19V5c0,-1.1 -0.9,-2 -2,-2zM3,19c0,1.1 0.9,2 2,2h3V10H3v9z")
}

/** Accessible name of each command (the web's labels). */
fun formatLabel(f: MarkdownFormat): String = when (f) {
    MarkdownFormat.Heading -> "Heading"; MarkdownFormat.Bold -> "Bold"; MarkdownFormat.List -> "List"; MarkdownFormat.Checklist -> "Checklist"
    MarkdownFormat.Italic -> "Italic"; MarkdownFormat.Strike -> "Strikethrough"; MarkdownFormat.Quote -> "Quote"
    MarkdownFormat.Indent -> "Indent"; MarkdownFormat.Outdent -> "Outdent"; MarkdownFormat.Code -> "Code"; MarkdownFormat.Math -> "Math"
    MarkdownFormat.Link -> "Link"; MarkdownFormat.Divider -> "Divider"; MarkdownFormat.Table -> "Table"
}

private fun glyph(f: MarkdownFormat, s: FormatState): ImageVector? = when (f) {
    MarkdownFormat.Heading -> null // a text label: H, H1, H2, H3
    MarkdownFormat.Bold -> Glyphs.bold; MarkdownFormat.Italic -> Glyphs.italic; MarkdownFormat.Strike -> Glyphs.strike
    MarkdownFormat.List -> if (s.list == ListKind.Ordered) Glyphs.numbered else Glyphs.bullets
    MarkdownFormat.Checklist -> Glyphs.checklist; MarkdownFormat.Quote -> Glyphs.quote
    MarkdownFormat.Indent -> Glyphs.indent; MarkdownFormat.Outdent -> Glyphs.outdent
    MarkdownFormat.Code -> Glyphs.code; MarkdownFormat.Math -> Glyphs.math; MarkdownFormat.Link -> Glyphs.link
    MarkdownFormat.Divider -> Glyphs.divider; MarkdownFormat.Table -> Glyphs.table
}

/** Whether a command's button is lit for the format at the cursor (null: an action, never lit). */
private fun active(f: MarkdownFormat, s: FormatState): Boolean? = when (f) {
    MarkdownFormat.Heading -> s.heading > 0
    MarkdownFormat.Bold -> s.bold; MarkdownFormat.Italic -> s.italic; MarkdownFormat.Strike -> s.strike
    MarkdownFormat.List -> s.list == ListKind.Bullet || s.list == ListKind.Ordered
    MarkdownFormat.Checklist -> s.list == ListKind.Check
    MarkdownFormat.Quote -> s.quote; MarkdownFormat.Code -> s.code; MarkdownFormat.Math -> s.math; MarkdownFormat.Link -> s.link
    MarkdownFormat.Indent, MarkdownFormat.Outdent, MarkdownFormat.Divider, MarkdownFormat.Table -> null
}

/** Row order: the fixed part, then the scrolling part (the "+" sheet's formats are not in the row). */
private val FIXED = listOf(MarkdownFormat.Heading, MarkdownFormat.Bold, MarkdownFormat.List, MarkdownFormat.Checklist)
private val SCROLLING = MarkdownFormat.entries.filter { it !in FIXED && it !in SHEET_FORMATS }

/** One 48dp bar button. `checked` null: a plain action (Material icon button); else a toggle with the active chip. */
@Composable
private fun BarButton(label: String, checked: Boolean?, enabled: Boolean, onClick: () -> Unit, state: String? = null, content: @Composable () -> Unit) {
    val c = editorColors()
    val shape = RoundedCornerShape(10.dp)
    // 40dp chip, 48dp touch target (Material's minimum interactive size).
    val m = Modifier.padding(horizontal = 4.dp).size(40.dp).semantics {
        contentDescription = label
        if (state != null) stateDescription = state
    }
    if (checked == null) {
        FilledIconButton(
            onClick = onClick, enabled = enabled, shape = shape, modifier = m,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Transparent, contentColor = c.glyph,
                disabledContainerColor = Color.Transparent, disabledContentColor = c.disabled,
            ),
        ) { content() }
    } else {
        FilledIconToggleButton(
            checked = checked, onCheckedChange = { onClick() }, enabled = enabled, shape = shape, modifier = m,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.Transparent, contentColor = c.glyph,
                disabledContainerColor = Color.Transparent, disabledContentColor = c.disabled,
                checkedContainerColor = c.chip, checkedContentColor = c.chipGlyph,
            ),
        ) { content() }
    }
}

@Composable
private fun FormatButton(f: MarkdownFormat, s: FormatState, onFormat: (MarkdownFormat) -> Unit) {
    val lit = active(f, s)
    val state = when (f) {
        MarkdownFormat.Heading -> if (s.heading > 0) "Heading ${s.heading}" else "Off"
        MarkdownFormat.List -> when (s.list) { ListKind.Bullet -> "Bulleted"; ListKind.Ordered -> "Numbered"; else -> "Off" }
        else -> lit?.let { if (it) "On" else "Off" }
    }
    BarButton(formatLabel(f), checked = lit, enabled = true, onClick = { onFormat(f) }, state = state) {
        val g = glyph(f, s)
        if (g != null) Icon(g, contentDescription = null, modifier = Modifier.size(22.dp))
        else Text(if (s.heading > 0) "H${s.heading}" else "H", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** The bar for one Markdown block. `onMore` opens the Divider / Table sheet (held by the screen, which outlives focus). */
@Composable
fun FormatBar(block: MarkdownBlockState, onMore: () -> Unit) {
    val c = editorColors()
    val s = block.formats
    Row(
        Modifier.fillMaxWidth().background(c.bar).topLine(t.line).heightIn(min = 56.dp).semantics { contentDescription = "Format" }.testTag("format-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton("Undo", checked = null, enabled = block.canUndo, onClick = block::undo) { Icon(Glyphs.undo, null, Modifier.size(22.dp)) }
            BarButton("Redo", checked = null, enabled = block.canRedo, onClick = block::redo) { Icon(Glyphs.redo, null, Modifier.size(22.dp)) }
            FIXED.forEach { FormatButton(it, s, block::apply) }
        }
        Box(Modifier.width(1.dp).height(28.dp).background(c.disabled))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SCROLLING.forEach { FormatButton(it, s, block::apply) }
            BarButton("More formats", checked = null, enabled = true, onClick = onMore) { Icon(Glyphs.more, null, Modifier.size(22.dp)) }
        }
    }
}

/** The "+" sheet: block inserts (Divider, Table) as 56dp Material list rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(onPick: (MarkdownFormat) -> Unit, onDismiss: () -> Unit) {
    val c = editorColors()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bar, contentColor = c.text, scrimColor = c.bar.copy(alpha = 0.6f)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SHEET_FORMATS.forEach { f ->
                ListItem(
                    headlineContent = { Text(formatLabel(f), style = Type.body) },
                    leadingContent = { glyph(f, FormatState())?.let { Icon(it, null, Modifier.size(24.dp)) } },
                    colors = ListItemDefaults.colors(containerColor = c.bar, headlineColor = c.text, leadingIconColor = c.glyph),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).plainClick { onPick(f) }.semantics { contentDescription = "Insert ${formatLabel(f)}" },
                )
            }
        }
    }
}
