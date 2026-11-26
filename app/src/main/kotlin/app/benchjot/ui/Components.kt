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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.selected
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

/**
 * Secondary actions: text only. `strong` for a selected or emphasised one. text-sm, 48dp tall.
 * Pressed, a soft pill shows behind the text (drawn past its edges, so the layout doesn't move).
 */
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
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pill = t.tint
    Box(
        modifier.heightIn(min = 48.dp)
            .drawBehind {
                if (pressed) {
                    val pad = 8.dp.toPx()
                    val h = minOf(size.height, 36.dp.toPx())
                    drawRoundRect(pill, Offset(-pad, (size.height - h) / 2), Size(size.width + 2 * pad, h), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                }
            }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) {
        val ink = color ?: if (strong || pressed) t.fg else t.muted
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        if (icon != null) BjIcon(icon, tint = ink, size = 17.dp)
        Text(
            text, style = style.copy(textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null, fontWeight = if (strong) FontWeight.Medium else style.fontWeight),
            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )
        }
    }
}

/** Primary: the one main action (filled ash on charcoal). Soft: the action beside it. Neutral: outlined, for Stop. */
enum class ButtonVariant { Primary, Soft, Neutral }

/**
 * A filled button (the web's Button): a flat fill, a step lighter while pressed. [loading] shows a
 * spinner and holds it.
 */
@Composable
fun FilledBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, variant: ButtonVariant = ButtonVariant.Primary, small: Boolean = false, loading: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    val active = enabled && !loading
    // small: an action at the end of a settings row (still a 44dp touch target).
    val base = modifier
        .height(if (small) 44.dp else 48.dp)
        .alpha(if (!enabled) 0.45f else if (loading) 0.85f else 1f)
    val styled = when (variant) {
        ButtonVariant.Primary -> base.background(if (pressed) t.fg.copy(alpha = 0.88f) else t.fg, shape)
        ButtonVariant.Soft -> base.background(if (pressed) t.line else t.tint, shape).border(BorderStroke(1.dp, t.line), shape)
        ButtonVariant.Neutral -> base.background(if (pressed) t.tint else t.bg, shape).border(BorderStroke(1.dp, if (pressed) t.muted else t.faint), shape)
    }
    val ink = if (variant == ButtonVariant.Primary) t.bg else t.fg
    Box(
        styled.clickable(interactionSource = source, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .semantics { if (loading) stateDescription = "Working" }
            .padding(horizontal = if (small) 16.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) Spinner(ink) else if (icon != null) BjIcon(icon, tint = ink, size = 17.dp)
            Text(text, style = TextStyle(fontSize = if (small) 14.sp else 15.sp, fontWeight = FontWeight.Medium), color = ink, maxLines = 1)
        }
    }
}

/** A small turning ring (16dp) beside words that say what is under way. */
@Composable
fun Spinner(color: Color = t.muted, size: Dp = 16.dp) =
    androidx.compose.material3.CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp, trackColor = color.copy(alpha = 0.2f), strokeCap = StrokeCap.Round)

/**
 * Work in progress, said in words (the web's <Working>): a spinner and the label, announced
 * politely. [detail] in mono beside it ("step 2 of 8").
 */
@Composable
fun Working(label: String, modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier.heightIn(min = 32.dp).semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spinner()
        Text(
            label, Modifier.weight(1f, fill = false),
            style = Type.sm, color = t.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) Text(detail, style = Type.code.copy(fontSize = 12.sp), color = t.faint, maxLines = 1)
    }
}

/** A plain text segmented control: the chosen option reads in full text colour. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier, name: (String) -> String = { it }) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (v, label) ->
            TextBtn(label, { if (v != value) onChange(v) }, strong = v == value, style = Type.xs,
                modifier = Modifier.semantics { contentDescription = name(label); selected = v == value })
        }
    }
}

/** The list's tiny outcome dot, like the map: green = successful, red = failed (its name says which). */
@Composable
fun OutcomeDot(success: Boolean, name: String) {
    Box(Modifier.size(6.dp).background(if (success) t.ok else t.fail, CircleShape).semantics { contentDescription = name })
}

/**
 * A code cell's frame (the web's CELL_FRAME): the page's colour lifted a step, no drawn edge,
 * rounded 16dp, with room inside.
 */
fun Modifier.cellCard(edge: CellEdge? = null): Modifier = composed {
    val shape = RoundedCornerShape(16.dp)
    val ok = t.ok
    val fail = t.fail
    val faint = t.faint
    val run = t.run
    val pulse = if (edge == CellEdge.Running) androidx.compose.animation.core.rememberInfiniteTransition(label = "edge").animateFloat(
        0.25f, 0.8f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
    ).value else 1f
    this.padding(top = 20.dp).fillMaxWidth()
        .background(t.raise, shape)
        .drawBehind {
            // The cell's state on its left edge (the web's cell edge): ran, failed, running, or changed since.
            if (edge == null) return@drawBehind
            val x = 1.5.dp.toPx()
            val top = 20.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val color = when (edge) { CellEdge.Ok -> ok.copy(alpha = 0.7f); CellEdge.Failed -> fail; CellEdge.Running -> run.copy(alpha = pulse); CellEdge.Stale -> faint }
            drawLine(
                color, Offset(x, top), Offset(x, bottom), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = if (edge == CellEdge.Stale) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null,
            )
        }
        .padding(horizontal = 16.dp, vertical = 4.dp)
}

/** A cell's state on its edge. */
enum class CellEdge { Ok, Failed, Running, Stale }

/**
 * A raised card (the web's `card` utility): a flat surface a step lighter than the page, a hairline
 * edge, rounded 16dp. For grouped rows, project cards, the Assistant's composer.
 */
fun Modifier.card(radius: Dp = 16.dp): Modifier = composed {
    val shape = RoundedCornerShape(radius)
    // A surface, not a box: the raised fill alone, no drawn border (the web's `card`).
    this.background(t.raise, shape)
}

/** A section's label above its card: plain words a step quieter than the text (the web's `eyebrow`). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = Type.eyebrow, color = t.muted)

/** 1dp hairline in the line colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(t.line))

/** Draws a hairline under the content (border-b border-line). */
fun Modifier.bottomLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
}

/** A hairline down the left edge (the folded steps under an answer). */
fun Modifier.leftLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0.5.dp.toPx(), 0f), Offset(0.5.dp.toPx(), size.height), 1.dp.toPx())
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

// ------------------------------------------------------------------ the Assistant at work
// (the web's .shimmer-text, .breathe and .skeleton): a light sweeping across the status words instead
// of a spinner, a breathing dot for the step in progress, and the shape of the reply before its first
// words. Still when the phone's animations are off.

/** Whether the phone allows animation (Settings → Accessibility → Remove animations sets the scale to 0). */
@Composable
fun motionAllowed(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}

@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.sm) {
    if (!motionAllowed()) { Text(text, modifier, style = style, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis); return }
    val shift by androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shimmer",
    )
    val muted = t.muted; val fg = t.fg
    Text(
        text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = style.copy(brush = object : androidx.compose.ui.graphics.ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
                val w = size.width.coerceAtLeast(1f)
                val x = shift * w
                return androidx.compose.ui.graphics.LinearGradientShader(
                    Offset(x - w * 0.35f, 0f), Offset(x + w * 0.35f, 0f),
                    listOf(muted, fg, muted), listOf(0f, 0.5f, 1f),
                )
            }
        }),
    )
}

@Composable
fun BreathingDot(size: Dp = 8.dp) {
    val pulse = if (motionAllowed()) {
        val v by androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe").animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "breathe",
        )
        v
    } else 1f
    Box(Modifier.size(size).graphicsLayer { alpha = pulse; scaleX = 0.85f + 0.15f * pulse; scaleY = 0.85f + 0.15f * pulse }.background(t.fg, androidx.compose.foundation.shape.CircleShape))
}

/** Three lines in the shape of the reply that's coming (before the Assistant's first words). */
@Composable
fun SkeletonLines() {
    val a = if (motionAllowed()) {
        val v by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "skeleton",
        )
        v
    } else 1f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in listOf(0.92f, 0.78f, 0.54f)) Box(Modifier.fillMaxWidth(f).height(12.dp).graphicsLayer { alpha = a }.background(t.tint, RoundedCornerShape(4.dp)))
    }
}
