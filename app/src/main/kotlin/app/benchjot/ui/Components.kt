package app.benchjot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.benchjot.core.APP_NAME
import app.benchjot.core.Mark

// The few shared pieces of UI, as the web's src/components/ui.tsx: a centred
// column, the logo, a top bar, and buttons. Every tap target is at least 48dp tall.

val t: Tokens @Composable get() = LocalTokens.current

/** max-w-[720px] px-5, centred. */
@Composable
fun PageColumn(modifier: Modifier = Modifier, maxWidth: Dp = 720.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 20.dp), content = content)
    }
}

private val markPaths = Mark.PATHS.map { PathParser().parsePathString(it).toPath() }

/** The mark from brand.ts, the same drawing the icons are made from, in the text colour. */
@Composable
fun LogoMark(size: Dp = 28.dp, color: Color = t.fg) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / Mark.VIEW_BOX
        scale(s, s, pivot = Offset.Zero) {
            markPaths.forEach { drawPath(it, color, style = Stroke(width = Mark.STROKE.toFloat(), cap = StrokeCap.Square, join = StrokeJoin.Miter)) }
            val c = Mark.CURSOR
            drawRect(color, topLeft = Offset(c.x.toFloat(), c.y.toFloat()), size = Size(c.width.toFloat(), c.height.toFloat()))
        }
    }
}

/** Mark plus wordmark, going home. `compact` drops the wordmark. */
@Composable
fun Logo(compact: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .then(if (onClick != null) Modifier.plainClick(onClick = onClick).semantics { contentDescription = "$APP_NAME home" } else Modifier)
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LogoMark()
        if (!compact) Text(APP_NAME.lowercase(), style = TextStyle(fontFamily = Type.mono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.025).em), color = t.fg)
    }
}

/** Header: logo (and an optional back link, e.g. "All notes") on the left, actions on the right. 64dp tall. */
@Composable
fun TopBar(onHome: (() -> Unit)?, back: Pair<String, () -> Unit>? = null, right: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Logo(compact = back != null, onClick = onHome)
            if (back != null) TextBtn(back.first, back.second)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), content = right)
    }
}

/** Tap without a ripple (the web sets the tap highlight to transparent). */
fun Modifier.plainClick(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, enabled = enabled, role = role, onClick = onClick)

/** Secondary actions: text only. `strong` for a selected or emphasised one. text-sm, 48dp tall. */
@Composable
fun TextBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    enabled: Boolean = true,
    style: TextStyle = Type.sm,
    center: Boolean = false,
    color: Color? = null,
    underline: Boolean = false,
) {
    Box(
        modifier.heightIn(min = 48.dp).plainClick(enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text, style = style.copy(textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null),
            color = color ?: if (strong) t.fg else t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
    }
}

enum class ButtonVariant { Primary, Neutral }

/** The one primary action on a screen (filled ash on charcoal). Neutral (outlined) for Stop. */
@Composable
fun FilledBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, variant: ButtonVariant = ButtonVariant.Primary) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val base = modifier.height(48.dp).alpha(if (!enabled) 0.5f else if (pressed) 0.8f else 1f)
    val styled = if (variant == ButtonVariant.Primary) base.background(t.fg, shape) else base.border(BorderStroke(1.dp, t.muted), shape)
    Box(
        styled.clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = if (variant == ButtonVariant.Primary) t.bg else t.fg, maxLines = 1)
    }
}

/** A plain text segmented control: the chosen option reads in full text colour. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (v, label) ->
            TextBtn(label, { if (v != value) onChange(v) }, strong = v == value, style = Type.xs,
                modifier = Modifier.semantics { contentDescription = label })
        }
    }
}

/** The list's tiny outcome dot (ash = successful, muted red = failed). */
@Composable
fun OutcomeDot(success: Boolean, name: String) {
    Box(Modifier.size(6.dp).background(if (success) t.ok else t.fail, CircleShape).semantics { contentDescription = name })
}

/** 1dp hairline in the line colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(t.line))

/** Draws a hairline under the content (border-b border-line). */
fun Modifier.bottomLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
}

fun Modifier.topLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
}

/** Inputs: 48dp, a bottom hairline that darkens on focus, 16sp (as the web's inputs), faint placeholder. */
@Composable
fun UnderlineField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    password: Boolean = false,
    style: TextStyle = TextStyle(fontSize = 16.sp),
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style.copy(color = t.fg),
        cursorBrush = SolidColor(t.fg),
        keyboardOptions = keyboard,
        keyboardActions = actions,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().height(48.dp).bottomLine(if (focused) t.faint else t.line).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = t.faint)
                inner()
            }
        },
    )
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.sm, color: Color = t.muted) =
    Text(text, modifier, style = style, color = color)

/** A screen's background, edge to edge, content inside the safe area. */
@Composable
fun Screen(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxWidth().then(Modifier.background(t.bg))
            .then(Modifier.padding(0.dp)),
        content = content,
    )
}
