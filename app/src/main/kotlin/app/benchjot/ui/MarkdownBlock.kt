package app.benchjot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.DARK
import app.benchjot.core.EditKind
import app.benchjot.core.EditorPalette
import app.benchjot.core.FormatEdit
import app.benchjot.core.FormatState
import app.benchjot.core.LIGHT
import app.benchjot.core.LiveKind
import app.benchjot.core.LiveView
import app.benchjot.core.MarkdownFormat
import app.benchjot.core.Snapshot
import app.benchjot.core.UndoHistory
import app.benchjot.core.applyFormat
import app.benchjot.core.continueList
import app.benchjot.core.editorPalette
import app.benchjot.core.expandCheckShortcut
import app.benchjot.core.formatState
import app.benchjot.core.liveSpans
import app.benchjot.core.liveView
import app.benchjot.core.renderedMath
import app.benchjot.core.toggleCheck
import kotlin.math.roundToInt
import ru.noties.jlatexmath.JLatexMathDrawable

// One Markdown text block of a note, edited live (the spec: docs in scratch/live-notes-spec.md; the
// web's live-editor.tsx). Self-contained so a note can later hold several of them between code
// cells: each block owns its text, selection and UndoHistory. The one format bar above the keyboard
// is shared and acts on whichever block has focus (FormatBarController), the way CodeKeysRow acts on
// the focused code cell. Code cells never register here, so no command, shortcut or undo reaches them.

/** A Markdown block's text, selection and undo history. All edits go through here. */
@Stable
class MarkdownBlockState(initial: String, private val clock: () -> Long = System::currentTimeMillis) {
    var value by mutableStateOf(TextFieldValue(initial))
        private set
    private val history = UndoHistory(Snapshot(initial, 0, 0))
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    val focus = FocusRequester()

    val text: String get() = value.text
    val selectionMin: Int get() = value.selection.min
    val selectionMax: Int get() = value.selection.max

    /** What the format bar lights up at the selection. */
    val formats: FormatState get() = formatState(text, selectionMin, selectionMax)

    private fun sync() { canUndo = history.canUndo; canRedo = history.canRedo }

    private fun set(e: FormatEdit, kind: EditKind) {
        value = TextFieldValue(e.text, TextRange(e.start, e.end))
        history.record(Snapshot(e), kind, clock())
        sync()
    }

    /** The text field's change: typing, with Enter continuing a list and `[] ` becoming a checkbox. */
    fun onValueChange(next: TextFieldValue) {
        val prev = value
        val caret = prev.selection.start
        val oneCharTyped = prev.selection.collapsed && next.selection.collapsed && next.selection.start == caret + 1 &&
            next.text.length == prev.text.length + 1 && next.text.startsWith(prev.text.substring(0, caret)) && next.text.endsWith(prev.text.substring(caret))
        if (oneCharTyped) {
            when (next.text[caret]) {
                '\n' -> continueList(prev.text, caret)?.let { set(it, EditKind.Command); return }
                ' ' -> expandCheckShortcut(next.text, caret + 1)?.let { set(it, EditKind.Command); return }
            }
        }
        value = next
        history.record(Snapshot(next.text, next.selection.start, next.selection.end), EditKind.Typing, clock())
        sync()
    }

    /** One format bar command on the selection. */
    fun apply(format: MarkdownFormat) = set(applyFormat(text, selectionMin, selectionMax, format), EditKind.Command)

    /** A whole edit from elsewhere (a [[link]] completion), as one undo step. */
    fun replace(text: String, caret: Int) = set(FormatEdit(text, caret, caret), EditKind.Command)

    /** The checkbox on the line starting at `lineStart` flips; the cursor stays where it was. */
    fun toggleCheckAt(lineStart: Int) {
        val next = toggleCheck(text, lineStart)
        if (next != text) set(FormatEdit(next, selectionMin, selectionMax), EditKind.Command)
    }

    fun undo() { history.undo()?.let { value = TextFieldValue(it.text, TextRange(it.start, it.end)) }; sync() }
    fun redo() { history.redo()?.let { value = TextFieldValue(it.text, TextRange(it.start, it.end)) }; sync() }
}

/** The Markdown block the shared format bar acts on: the focused one (null: none), and the last one focused. */
@Stable
class FormatBarController {
    var target by mutableStateOf<MarkdownBlockState?>(null)
    var last by mutableStateOf<MarkdownBlockState?>(null)
        private set

    fun focused(block: MarkdownBlockState, has: Boolean) {
        if (has) { target = block; last = block } else if (target === block) target = null
    }
}

val LocalFormatBar = staticCompositionLocalOf { FormatBarController() }

/** The editor's colours for this theme, from core's one table (EditorPaletteTest checks every pair). */
@Composable
fun editorColors(): EditorColors {
    val dark = t.dark
    return remember(dark) { EditorColors(editorPalette(if (dark) DARK else LIGHT)) }
}

class EditorColors(p: EditorPalette) {
    private fun hex(s: String) = Color(0xFF000000 or s.removePrefix("#").toLong(16))
    val bar = hex(p.bar); val glyph = hex(p.glyph); val disabled = hex(p.disabled); val chip = hex(p.chip); val chipGlyph = hex(p.chipGlyph)
    val text = hex(p.text); val marker = hex(p.marker); val done = hex(p.done); val quote = hex(p.quote); val quoteBar = hex(p.quoteBar)
    val link = hex(p.link); val codeText = hex(p.codeText); val codeBg = hex(p.codeBg); val listGlyph = hex(p.listGlyph); val checkMark = hex(p.checkMark); val rule = hex(p.rule); val placeholder = hex(p.placeholder)
}

/** How each live kind looks (null: no style of its own). */
private fun styleOf(k: LiveKind, c: EditorColors): SpanStyle? = when (k) {
    LiveKind.H1 -> SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    LiveKind.H2 -> SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    LiveKind.H3 -> SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    LiveKind.Marker -> SpanStyle(color = c.marker)
    LiveKind.Quote -> SpanStyle(color = c.quote, fontStyle = FontStyle.Italic)
    LiveKind.Ordered -> SpanStyle(color = c.listGlyph, fontWeight = FontWeight.Medium)
    LiveKind.Done -> SpanStyle(color = c.done, textDecoration = TextDecoration.LineThrough)
    LiveKind.Divider -> SpanStyle(color = Color.Transparent) // the rule is drawn
    LiveKind.Fence, LiveKind.Table -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, color = c.codeText, background = c.codeBg)
    LiveKind.Bold -> SpanStyle(fontWeight = FontWeight.SemiBold)
    LiveKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    LiveKind.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    LiveKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, color = c.codeText, background = c.codeBg)
    // Math on the active line (or TeX JLaTeXMath can't parse): styled source. Elsewhere it is drawn (drawLive).
    LiveKind.Math -> SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = c.codeText, background = c.codeBg)
    LiveKind.Link -> SpanStyle(color = c.link, textDecoration = TextDecoration.Underline)
    LiveKind.Wikilink -> SpanStyle(color = c.link, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
    LiveKind.Hide, LiveKind.Bullet, LiveKind.CheckOpen, LiveKind.CheckDone, LiveKind.Indent -> null
}

/** A formula laid out for the editor: drawn at `scale` (fitted to the line height), `width` x `height` px on screen. */
private class Formula(val drawable: JLatexMathDrawable, val scale: Float) {
    val width = drawable.intrinsicWidth * scale
    val height = drawable.intrinsicHeight * scale
}

/** The formulas of `texes` that JLaTeXMath renders, at body size, no taller than a line. */
@Composable
private fun rememberFormulas(texes: List<String>, color: Color): Map<String, Formula> {
    val density = LocalDensity.current
    return remember(texes.toSet(), color, density) {
        val px = with(density) { BODY_STYLE.fontSize.toPx() }
        val maxHeight = with(density) { BODY_STYLE.lineHeight.toPx() } * 0.9f
        texes.distinct().mapNotNull { tex ->
            mathDrawable(tex, px, color)?.let { d ->
                d.setBounds(0, 0, d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
                tex to Formula(d, minOf(1f, maxHeight / d.intrinsicHeight.coerceAtLeast(1)))
            }
        }.toMap()
    }
}

/** The live view as a VisualTransformation: the field's text stays the Markdown source. */
private class LiveTransformation(
    private val source: String,
    private val view: LiveView,
    private val colors: EditorColors,
    /** Each rendered formula's width, given to its placeholder as letter spacing so the text makes room for it. */
    private val formulaWidths: Map<String, TextUnit>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // The view is built for `source`; a frame that already has newer text gets the identity until it recomposes.
        if (text.text != source) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(view.text)
        for (s in view.spans) styleOf(s.kind, colors)?.let { b.addStyle(it, s.from, s.to) }
        for (g in view.glyphs) g.tex?.let { formulaWidths[it] }?.let { b.addStyle(SpanStyle(letterSpacing = it), g.at, g.at + 1) }
        return TransformedText(b.toAnnotatedString(), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = view.displayOffset(offset)
            override fun transformedToOriginal(offset: Int) = view.sourceOffset(offset)
        })
    }

    override fun equals(other: Any?) = other is LiveTransformation && other.view === view && other.colors === colors && other.formulaWidths == formulaWidths
    override fun hashCode() = System.identityHashCode(view)
}

private val BODY_STYLE = TextStyle(fontSize = 15.sp, lineHeight = 28.sp)

/**
 * One live Markdown block. Markers show (dimmed) only on the lines the cursor touches; elsewhere the
 * text reads formatted, with bullets, numbers and tappable checkboxes drawn in. `label` is the
 * accessible name, `placeholder` the hint while empty (the label unless given).
 */
@Composable
fun MarkdownBlockEditor(state: MarkdownBlockState, label: String, modifier: Modifier = Modifier, placeholder: String = label, minHeight: Dp = 96.dp) {
    val bar = LocalFormatBar.current
    val colors = editorColors()
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = state.text
    // Active lines: the ones the selection touches while focused (line bounds, so moving within a line doesn't rebuild).
    val activeFrom = if (focused) (if (state.selectionMin == 0) 0 else text.lastIndexOf('\n', state.selectionMin - 1) + 1) else -1
    val activeTo = if (focused) text.indexOf('\n', state.selectionMax).let { if (it < 0) text.length else it } else -1
    val spans = remember(text, activeFrom, activeTo) { liveSpans(text, activeFrom, activeTo) }
    val formulas = rememberFormulas(renderedMath(text, spans), colors.text)
    val view = remember(spans, formulas) { liveView(text, spans) { it in formulas } }
    val density = LocalDensity.current
    val widths = remember(formulas, density) { formulas.mapValues { with(density) { it.value.width.toSp() } } }
    val transform = remember(view, colors, widths) { LiveTransformation(text, view, colors, widths) }

    BasicTextField(
        value = state.value,
        onValueChange = state::onValueChange,
        textStyle = BODY_STYLE.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        visualTransformation = transform,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight).focusRequester(state.focus)
            .onFocusChanged { focused = it.isFocused; bar.focused(state, it.isFocused) }
            .semantics { contentDescription = label }
            .drawBehind { layout?.takeIf { it.layoutInput.text.text == view.text }?.let { drawLive(it, view, colors, formulas) } },
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = BODY_STYLE, color = colors.placeholder)
                inner()
                layout?.takeIf { it.layoutInput.text.text == view.text }?.let { CheckBoxes(it, view, state, colors) }
            }
        },
    )
}

/** Each checklist item's box: a 48dp tappable toggle centred on its glyph, flipping only its own line. */
@Composable
private fun CheckBoxes(l: TextLayoutResult, view: LiveView, state: MarkdownBlockState, colors: EditorColors) {
    val half = with(LocalDensity.current) { 24.dp.toPx() }
    for (g in view.glyphs) {
        if (g.kind != LiveKind.CheckOpen && g.kind != LiveKind.CheckDone) continue
        val done = g.kind == LiveKind.CheckDone
        val c = l.getBoundingBox(g.at).center
        Box(
            Modifier.offset { IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt()) }.size(48.dp)
                .toggleable(value = done, role = Role.Checkbox, onValueChange = { state.toggleCheckAt(g.sourceLine) })
                .semantics { contentDescription = "Checklist item" },
        ) { CheckGlyph(done, colors) }
    }
}

/** Bullets, quote bars, divider rules and formulas, drawn behind the text (checkboxes are their own tappable boxes). */
private fun DrawScope.drawLive(l: TextLayoutResult, view: LiveView, colors: EditorColors, formulas: Map<String, Formula>) {
    for (g in view.glyphs) {
        val box = l.getBoundingBox(g.at)
        val line = l.getLineForOffset(g.at)
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        when (g.kind) {
            LiveKind.Bullet -> drawCircle(colors.listGlyph, radius = 3.dp.toPx(), center = box.center)
            LiveKind.Quote -> drawRect(colors.quoteBar, Offset(box.left + 2.dp.toPx(), top + 2.dp.toPx()), Size(3.dp.toPx(), bottom - top - 4.dp.toPx()))
            LiveKind.Math -> g.tex?.let { formulas[it] }?.let { f -> drawFormula(f, Offset(box.left + (box.width - f.width) / 2, (top + bottom - f.height) / 2)) }
            else -> Unit
        }
    }
    for (s in view.spans) if (s.kind == LiveKind.Divider) {
        val line = l.getLineForOffset(s.from)
        val y = (l.getLineTop(line) + l.getLineBottom(line)) / 2
        drawLine(colors.rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawFormula(f: Formula, at: Offset) = drawIntoCanvas { c ->
    c.save()
    c.translate(at.x, at.y)
    c.scale(f.scale, f.scale)
    f.drawable.draw(c.nativeCanvas)
    c.restore()
}

/** A Material-style 18dp checkbox: outlined when open, filled with a check when done. */
@Composable
private fun CheckGlyph(done: Boolean, c: EditorColors) {
    Canvas(Modifier.size(48.dp)) {
        val s = 18.dp.toPx()
        val tl = Offset((size.width - s) / 2, (size.height - s) / 2)
        val r = CornerRadius(3.dp.toPx())
        if (done) {
            drawRoundRect(c.listGlyph, tl, Size(s, s), r)
            val p = Path().apply {
                moveTo(tl.x + s * 0.22f, tl.y + s * 0.52f)
                lineTo(tl.x + s * 0.42f, tl.y + s * 0.72f)
                lineTo(tl.x + s * 0.78f, tl.y + s * 0.32f)
            }
            drawPath(p, c.checkMark, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            val w = 2.dp.toPx()
            drawRoundRect(c.listGlyph, tl + Offset(w / 2, w / 2), Size(s - w, s - w), r, style = Stroke(w))
        }
    }
}
