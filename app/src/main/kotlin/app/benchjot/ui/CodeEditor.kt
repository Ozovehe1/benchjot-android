package app.benchjot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.TokenKind
import app.benchjot.core.highlight
import app.benchjot.core.languageConfig
import app.benchjot.core.symbolKeys

// The native code editor: a monospace text field with a small greyscale highlighter
// (core Highlight), the error line tinted, auto-indent on Enter, and the web's
// symbol row (Tab, the language's keys, common symbols, Undo) above the keyboard.

/** The code block being typed in, for the symbol row. */
@Stable
class CodeKeysTarget(val language: String, val insert: (String) -> Unit, val undo: () -> Unit)

@Stable
class CodeKeysController { var target by mutableStateOf<CodeKeysTarget?>(null) }

val LocalCodeKeys = staticCompositionLocalOf { CodeKeysController() }

/** A code block's text plus a small undo history. */
@Stable
class CodeState(initial: String) {
    var field by mutableStateOf(TextFieldValue(initial))
        private set
    private val history = ArrayDeque<TextFieldValue>()
    val text get() = field.text

    fun change(next: TextFieldValue, indent: String) {
        val prev = field
        var value = next
        // Enter keeps the previous line's indentation, as CodeMirror does.
        if (next.text.length == prev.text.length + 1 && next.selection.collapsed) {
            val at = next.selection.start - 1
            if (at >= 0 && next.text[at] == '\n' && next.text.removeRange(at, at + 1) == prev.text) {
                val lineStart = next.text.lastIndexOf('\n', at - 1) + 1
                val lead = next.text.substring(lineStart, at).takeWhile { it == ' ' || it == '\t' }
                val extra = if (next.text.substring(lineStart, at).trimEnd().endsWith(":") || next.text.substring(lineStart, at).trimEnd().endsWith("{")) indent else ""
                val ins = lead + extra
                if (ins.isNotEmpty()) value = TextFieldValue(next.text.substring(0, at + 1) + ins + next.text.substring(at + 1), TextRange(at + 1 + ins.length))
            }
        }
        if (value.text != prev.text) { history.addLast(prev); if (history.size > 200) history.removeFirst() }
        field = value
    }

    fun insert(s: String) {
        val f = field
        val start = minOf(f.selection.start, f.selection.end)
        val end = maxOf(f.selection.start, f.selection.end)
        history.addLast(f)
        field = TextFieldValue(f.text.substring(0, start) + s + f.text.substring(end), TextRange(start + s.length))
    }

    fun replaceAll(text: String) {
        history.addLast(field)
        field = TextFieldValue(text, TextRange(text.length))
    }

    fun undo() { history.removeLastOrNull()?.let { field = it } }
}

private class Highlighter(private val language: String, private val tones: Map<TokenKind, SpanStyle>, private val errorLine: Int?, private val errorBg: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val code = text.text
        val b = AnnotatedString.Builder(code)
        if (errorLine != null && errorLine >= 1) {
            var start = 0
            repeat(errorLine - 1) { start = code.indexOf('\n', start).let { if (it < 0) code.length else it + 1 } }
            if (start < code.length) {
                val end = code.indexOf('\n', start).let { if (it < 0) code.length else it }
                b.addStyle(SpanStyle(background = errorBg), start, end)
            }
        }
        for (tok in highlight(code, language)) tones[tok.kind]?.let { b.addStyle(it, tok.start, tok.end) }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is Highlighter && other.language == language && other.errorLine == errorLine && other.tones == tones
    override fun hashCode() = language.hashCode() * 31 + (errorLine ?: 0)
}

@Composable
fun CodeEditor(state: CodeState, language: String, label: String, errorLine: Int?, onChange: () -> Unit) {
    val keys = LocalCodeKeys.current
    val tk = t
    val tones = remember(tk) {
        mapOf(
            TokenKind.Keyword to SpanStyle(color = tk.hlKeyword, fontWeight = FontWeight.Medium),
            TokenKind.Str to SpanStyle(color = tk.hlString),
            TokenKind.Number to SpanStyle(color = tk.hlNumber),
            TokenKind.Comment to SpanStyle(color = tk.hlComment),
            TokenKind.Type to SpanStyle(color = tk.hlType),
        )
    }
    val transform = remember(language, errorLine, tones) { Highlighter(language, tones, errorLine, tk.fg.copy(alpha = 0.07f)) }
    val indent = languageConfig(language).indent
    val target = remember(language, state) { CodeKeysTarget(language, { s -> state.insert(if (s == "Tab") indent else s); onChange() }, { state.undo(); onChange() }) }
    BasicTextField(
        value = state.field,
        onValueChange = { v -> val before = state.text; state.change(v, indent); if (state.text != before) onChange() },
        textStyle = Type.code.copy(color = t.fg),
        cursorBrush = SolidColor(t.fg),
        visualTransformation = transform,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp)
            .semantics { contentDescription = label }
            .onFocusChanged { f -> if (f.isFocused) keys.target = target else if (keys.target === target) keys.target = null },
        decorationBox = { inner ->
            Box {
                if (state.text.isEmpty()) Text("Code", style = Type.code, color = t.faint)
                inner()
            }
        },
    )
}

/** The slim symbol row pinned above the keyboard while a code block is focused. */
@Composable
fun CodeKeysRow(target: CodeKeysTarget, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(t.bg).topLine(t.line).horizontalScroll(rememberScrollState())) {
        (symbolKeys(target.language) + "undo").forEach { k ->
            Box(
                Modifier.height(48.dp).widthIn(min = 44.dp)
                    .plainClick { if (k == "undo") target.undo() else target.insert(k) }
                    .padding(horizontal = 10.dp)
                    .semantics { contentDescription = when (k) { "Tab" -> "Tab"; "undo" -> "Undo"; else -> "Insert $k" } },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (k == "Tab") "tab" else k, style = TextStyle(fontFamily = Type.mono, fontSize = 15.sp), color = t.fg)
            }
        }
    }
}
