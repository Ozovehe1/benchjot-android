package app.benchjot.ui

import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benchjot.core.MATH_SCHEME
import app.benchjot.core.MathDoc
import app.benchjot.core.MathPiece
import app.benchjot.core.NoteIndexItem
import app.benchjot.core.NoteLink
import app.benchjot.core.Segment
import app.benchjot.core.extractMath
import app.benchjot.core.mathPieceAt
import app.benchjot.core.parseLinks
import app.benchjot.core.resolveLink
import app.benchjot.core.isOpenableLink
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownAnnotator
import com.mikepenz.markdown.utils.codeSpanStyle
import com.mikepenz.markdown.utils.linkTextSpanStyle
import androidx.compose.foundation.text.appendInlineContent
import com.mikepenz.markdown.utils.MARKDOWN_TAG_URL
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import ru.noties.jlatexmath.JLatexMathDrawable

private const val NOTE_SCHEME = "benchjot-note:"
/** KaTeX sets math at 1.21em of the surrounding text. */
private const val KATEX_SCALE = 1.21f
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** [[links]] become Markdown links to the note they resolve to; a link to a missing note stays plain text. */
fun linkifyNotes(markdown: String, notes: List<NoteIndexItem>): String = parseLinks(markdown).joinToString("") { s ->
    when (s) {
        is Segment.Text -> s.text
        is Segment.Link -> {
            val target = resolveLink(NoteLink(s.label, s.id), notes)
            val label = s.label.replace("[", "\\[").replace("]", "\\]")
            if (target != null) "[$label]($NOTE_SCHEME${target.id})" else label
        }
    }
}

/**
 * The note preview: Markdown with tappable note links (the web's markdown.tsx +
 * remark-note-links) and math (remark-math + KaTeX on the web; JLaTeXMath drawn
 * natively here). Math pieces arrive as bjmath: image placeholders (core's
 * extractMath); paragraphs holding them are drawn here, every other paragraph by
 * the Markdown library as before.
 */
@Composable
fun NoteMarkdown(text: String, notes: List<NoteIndexItem>, onOpenNote: (String) -> Unit) {
    val system = LocalUriHandler.current
    val handler = remember(system, onOpenNote) {
        object : UriHandler {
            override fun openUri(uri: String) {
                when {
                    uri.startsWith(NOTE_SCHEME) -> onOpenNote(uri.removePrefix(NOTE_SCHEME))
                    isOpenableLink(uri) -> runCatching { system.openUri(uri) } // http, https, mailto only
                }
            }
        }
    }
    val doc = remember(text, notes) { extractMath(text).let { MathDoc(linkifyNotes(it.markdown, notes), it.pieces) } }
    val color = t.fg
    // Placeholders become inline content ids ("bjmath:N") the paragraph below can size one by one.
    val annotator = remember(doc) {
        DefaultMarkdownAnnotator { content, child ->
            if (child.type != MarkdownElementTypes.IMAGE) return@DefaultMarkdownAnnotator false
            val link = child.find(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content)?.toString()
            val piece = link?.let { mathPieceAt(it, doc) } ?: return@DefaultMarkdownAnnotator false
            appendInlineContent(link, piece.source)
            true
        }
    }
    val components = remember(doc, color) {
        markdownComponents(paragraph = { model -> MathParagraph(model.content, model.node, doc, color, annotator) })
    }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(content = doc.markdown, components = components, annotator = annotator)
    }
}

private fun ASTNode.find(type: org.intellij.markdown.IElementType): ASTNode? =
    children.firstNotNullOfOrNull { if (it.type == type) it else it.find(type) }

@Composable
private fun MathParagraph(content: String, node: ASTNode, doc: MathDoc, color: Color, annotator: com.mikepenz.markdown.model.MarkdownAnnotator) {
    val typography = LocalMarkdownTypography.current
    val style = typography.paragraph
    val text = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content, node, typography.linkTextSpanStyle, typography.codeSpanStyle, annotator)
        pop()
    }
    val ids = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length).map { it.item }.filter { it.startsWith(MATH_SCHEME) }
    if (ids.isEmpty()) {
        com.mikepenz.markdown.compose.elements.MarkdownText(text, style = style)
        return
    }
    val fontSize = if (style.fontSize == TextUnit.Unspecified) 15.sp else style.fontSize
    val density = LocalDensity.current
    val single = ids.singleOrNull()?.let { mathPieceAt(it, doc) }
    if (single != null && single.display && text.text.trim() == single.source) {
        DisplayMath(single, fontSize, color)
        return
    }
    val inline = remember(ids, doc, fontSize, color, density) {
        ids.distinct().associateWith { id ->
            val piece = mathPieceAt(id, doc)!!
            val img = renderMath(piece, fontSize * KATEX_SCALE, color, density)
            // A placeholder is sized in sp: bitmap px / (density * fontScale).
            fun sp(px: Int) = (px / density.density / density.fontScale).sp
            InlineTextContent(
                if (img != null) Placeholder(sp(img.width), sp(img.height), PlaceholderVerticalAlign.TextCenter)
                else Placeholder((piece.source.length * 0.55f * fontSize.value).sp, fontSize, PlaceholderVerticalAlign.TextCenter),
            ) {
                if (img != null) Image(img, contentDescription = piece.tex, modifier = Modifier.fillMaxSize())
                else Text(piece.source, style = TextStyle(fontSize = fontSize), color = color, maxLines = 1)
            }
        }
    }
    LinkText(text, style, inline)
}

/** Display math on its own line, centred like KaTeX's display mode; wide formulas scroll sideways. */
@Composable
private fun DisplayMath(piece: MathPiece, fontSize: TextUnit, color: Color) {
    val density = LocalDensity.current
    val img = remember(piece, fontSize, color, density) { renderMath(piece, fontSize * KATEX_SCALE, color, density) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (img != null) {
            Image(img, contentDescription = piece.tex, modifier = Modifier.size(with(density) { img.width.toDp() }, with(density) { img.height.toDp() }))
        } else {
            Text(piece.source, style = TextStyle(fontSize = fontSize), color = color)
        }
    }
}

/** A paragraph with inline math: text with placeholders, links still tappable (as the library's MarkdownText). */
@Composable
private fun LinkText(text: AnnotatedString, style: TextStyle, inline: Map<String, InlineTextContent>) {
    val uri = LocalUriHandler.current
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val modifier = if (text.getStringAnnotations(MARKDOWN_TAG_URL, 0, text.length).isEmpty()) Modifier else Modifier.pointerInput(text) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val at = layout.value?.getOffsetForPosition(down.position) ?: return@awaitEachGesture
            val url = text.getStringAnnotations(MARKDOWN_TAG_URL, at, at).firstOrNull()?.item ?: return@awaitEachGesture
            down.consume()
            waitForUpOrCancellation()?.let { it.consume(); runCatching { uri.openUri(url) } }
        }
    }
    Text(text, modifier, style = style, color = t.fg, inlineContent = inline, onTextLayout = { layout.value = it })
}

/** Draws TeX with JLaTeXMath into a bitmap; null when the TeX doesn't parse (shown as its source, like KaTeX's throwOnError: false). */
private fun renderMath(piece: MathPiece, fontSize: TextUnit, color: Color, density: Density): ImageBitmap? = runCatching {
    val px = with(density) { fontSize.toPx() }
    val drawable = JLatexMathDrawable.builder(piece.tex.ifBlank { " " }).textSize(px).color(color.toArgb()).padding(0).build()
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    drawable.setBounds(0, 0, w, h)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    drawable.draw(Canvas(bmp))
    bmp.asImageBitmap()
}.getOrNull()
