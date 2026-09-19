package app.berth.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.R
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import kotlin.math.abs

// ---- Swatch, dots, rings ------------------------------------------------------------------------

/** Host or workspace swatch: colour fill with a two-letter monogram; radius follows the size (A5). */
@Composable
fun Swatch(
    color: SwatchColor,
    monogram: String,
    size: Dp,
    modifier: Modifier = Modifier,
    state: SessionState? = null,
    attention: Boolean = false,
    showLiveDot: Boolean = true,
) {
    val radius = when {
        size <= 24.dp -> BerthRadius.swatchSmall
        size <= 40.dp -> BerthRadius.swatch
        else -> 12.dp
    }
    val fontSize = when {
        size <= 24.dp -> 10
        size <= 40.dp -> 13
        else -> 18
    }
    val c = Berth.colors
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                if (attention) {
                    val inset = 3.dp.toPx()
                    drawRoundRect(
                        color = c.attention,
                        topLeft = Offset(-inset, -inset),
                        size = Size(this.size.width + inset * 2, this.size.height + inset * 2),
                        cornerRadius = CornerRadius((radius + 3.dp).toPx()),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(radius))
                .background(color.rgb.toColor()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                monogram.take(2),
                style = BerthType.label.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp),
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
            )
        }
        if (state != null && (state != SessionState.LIVE || showLiveDot)) {
            StatusDot(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(2.dp, 2.dp),
            )
        }
    }
}

/** 8 dp state dot; connecting and reconnecting add the one continuous motion in the app: a rotating arc. */
@Composable
fun StatusDot(state: SessionState, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val c = Berth.colors
    val color = when (state) {
        SessionState.LIVE -> c.live
        SessionState.CONNECTING, SessionState.RECONNECTING, SessionState.IDLE -> c.pending
        SessionState.DETACHED, SessionState.CLOSED -> c.detached
        SessionState.FAILED -> c.danger
    }
    val spinning = state == SessionState.CONNECTING || state == SessionState.RECONNECTING
    val rotation = if (spinning) {
        val t = rememberInfiniteTransition(label = "reconnect-arc")
        val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "arc")
        r
    } else 0f
    Canvas(modifier.size(size + if (spinning) 6.dp else 0.dp)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(color, radius = size.toPx() / 2, center = center)
        if (spinning) {
            val stroke = 2.dp.toPx()
            val arcSize = this.size.minDimension - stroke
            drawArc(
                color = color,
                startAngle = rotation,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(arcSize, arcSize),
                style = Stroke(width = stroke),
            )
        }
    }
}

// ---- Layout primitives ----------------------------------------------------------------------------

/** Caption label above a section or panel: uppercase, tracked, text.2. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Berth.colors.text2) {
    Text(text.uppercase(), style = BerthType.caption, color = color, modifier = modifier)
}

/**
 * The surface a [Panel] paints behind its content, so a control inside it can choose a tonal step
 * that stays legible against it. Null outside any panel.
 */
val LocalPanelSurface = compositionLocalOf<Color?> { null }

/** Radius 20 surface.2 panel with 16 dp padding and an optional caption above the content. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    label: String? = null,
    surface: Color = Berth.colors.surface2,
    padding: Dp = BerthSpace.panelPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        if (label != null) {
            SectionLabel(label, Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BerthRadius.panel))
                .background(surface)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompositionLocalProvider(LocalPanelSurface provides surface) { content() }
        }
    }
}

/**
 * A list row: 12 dp radius, tonal step by state, leading swatch or icon, title and subtitle, and a
 * trailing column. Selection is the tonal step plus a 4 dp accent dot inside the padding (A6).
 * The subtitle takes a plain String or an [AnnotatedString] (mixed Mono and Caption, accent spans).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: CharSequence? = null,
    selected: Boolean = false,
    surface: Color = Berth.colors.surface2,
    minHeight: Dp = 56.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    titleColor: Color = Berth.colors.text1,
    titleStyle: TextStyle = BerthType.bodyMedium,
    subtitleStyle: TextStyle = BerthType.caption,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            pressed -> c.surface4
            selected -> c.surface3
            else -> surface
        },
        tween(120),
        label = "row",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(bg)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                } else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = titleStyle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (subtitle) {
                null -> Unit
                is AnnotatedString -> Text(subtitle, style = subtitleStyle, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else -> Text(subtitle.toString(), style = subtitleStyle, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = trailing)
        }
    }
}

/** Row that opens a picker; the value sits at the trailing edge followed by a chevron. */
@Composable
fun PickerRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, caption: String? = null) {
    val c = Berth.colors
    ListRow(
        title = title,
        subtitle = caption,
        minHeight = 44.dp,
        surface = Color.Transparent,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Text(value, style = BerthType.body, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp)
        },
    )
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, caption: String? = null) {
    val c = Berth.colors
    ListRow(
        title = title,
        subtitle = caption,
        minHeight = 44.dp,
        surface = Color.Transparent,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onAccent,
                    checkedTrackColor = c.accent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = c.text2,
                    uncheckedTrackColor = c.surface4,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

// ---- Slider ---------------------------------------------------------------------------------------

/**
 * Slider re-skinned with the tokens (A1; C19 draws Tone as `warm ────o──── cool`): a 2 dp track on
 * surface.4, a 16 dp accent knob cut out of the track by a 2 dp ring in the panel's [surface], and
 * a 44 dp touch row. With [neutral] set the slider is bipolar: a notch marks the neutral point and
 * the accent fill runs from there to the knob; without it the fill runs from the start of the range.
 */
@Composable
fun BerthSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    neutral: Float? = null,
    enabled: Boolean = true,
    surface: Color = Berth.colors.surface2,
) {
    val c = Berth.colors
    val span = valueRange.endInclusive - valueRange.start
    val insetPx = with(LocalDensity.current) { SliderInset.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var rawX by remember { mutableFloatStateOf(0f) }
    val knob by animateDpAsState(if (dragging) 10.dp else 8.dp, label = "slider knob")
    val alpha = if (enabled) 1f else 0.5f

    fun fraction(v: Float) = if (span == 0f) 0f else ((v - valueRange.start) / span).coerceIn(0f, 1f)
    fun setFromX(x: Float) {
        val usable = widthPx - 2 * insetPx
        if (usable <= 0f) return
        onValueChange(valueRange.start + ((x - insetPx) / usable).coerceIn(0f, 1f) * span)
    }
    val drag = rememberDraggableState { delta ->
        rawX += delta
        setFromX(rawX)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { widthPx = it.width }
            .focusable(enabled)
            .semantics {
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { onValueChange(it.coerceIn(valueRange)); true }
            }
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { setFromX(it.x) }) }
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { start ->
                    rawX = start.x
                    dragging = true
                    setFromX(start.x)
                },
                onDragStopped = { dragging = false },
            )
            .drawBehind {
                val y = size.height / 2
                val x0 = insetPx
                val x1 = size.width - insetPx
                fun xAt(f: Float) = x0 + (x1 - x0) * f
                val track = 2.dp.toPx()
                drawLine(c.surface4.copy(alpha = alpha), Offset(x0, y), Offset(x1, y), track, StrokeCap.Round)
                val kx = xAt(fraction(value))
                val from = xAt(fraction(neutral ?: valueRange.start))
                if (abs(kx - from) > 0.5f) {
                    drawLine(c.accent.copy(alpha = alpha), Offset(from, y), Offset(kx, y), track, StrokeCap.Round)
                }
                if (neutral != null) {
                    drawRoundRect(
                        color = c.text3.copy(alpha = alpha),
                        topLeft = Offset(from - 1.dp.toPx(), y - 4.dp.toPx()),
                        size = Size(2.dp.toPx(), 8.dp.toPx()),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
                val r = knob.toPx()
                drawCircle(surface, r + 2.dp.toPx(), Offset(kx, y))
                drawCircle(c.accent.copy(alpha = alpha), r, Offset(kx, y))
            },
    )
}

/** Horizontal inset of the slider track so the 20 dp pressed knob stays inside the row. */
private val SliderInset = 10.dp

/** 22 dp pill on surface.3 with Caption text; ports use Mono. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, mono: Boolean = false, color: Color = Berth.colors.surface3, textColor: Color = Berth.colors.text2) {
    Box(
        modifier
            .height(22.dp)
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (mono) BerthType.mono.copy(fontSize = BerthType.caption.fontSize) else BerthType.caption, color = textColor, maxLines = 1)
    }
}

/** 28 dp chip, radius 8; selected chips step up to surface.4. */
@Composable
fun Chip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, mono: Boolean = false, onClick: () -> Unit) {
    val c = Berth.colors
    Box(
        modifier
            .height(28.dp)
            .clip(RoundedCornerShape(BerthRadius.swatch))
            .background(if (selected) c.surface4 else c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (mono) BerthType.mono else BerthType.label, color = c.text1, maxLines = 1)
    }
}

// ---- Buttons ----------------------------------------------------------------------------------------

enum class ButtonKind { PRIMARY, SECONDARY, TEXT, DESTRUCTIVE }

@Composable
fun BerthButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when (kind) {
        ButtonKind.PRIMARY -> if (pressed) c.accent.copy(alpha = 0.85f) else c.accent
        ButtonKind.SECONDARY, ButtonKind.DESTRUCTIVE -> if (pressed) c.surface4 else c.surface3
        ButtonKind.TEXT -> if (pressed) c.surface2 else Color.Transparent
    }
    val label = when (kind) {
        ButtonKind.PRIMARY -> c.onAccent
        ButtonKind.SECONDARY -> c.text1
        ButtonKind.TEXT -> c.accent
        ButtonKind.DESTRUCTIVE -> c.danger
    }
    Box(
        modifier
            .defaultMinSize(minHeight = 44.dp, minWidth = 44.dp)
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.5f))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BerthType.label, color = if (enabled) label else label.copy(alpha = 0.5f), maxLines = 1)
    }
}

/**
 * Segmented control (A9): a row-radius track with 2 dp inner padding holding 36 dp options; the
 * selected option is a surface.4 thumb at the track radius minus the padding, so the corners nest.
 * The track is surface.2, stepping down to surface.1 inside a surface.2 [Panel] so it still reads.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val track = if (LocalPanelSurface.current == c.surface2) c.surface1 else c.surface2
    val inset = 2.dp
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(track)
            .padding(inset)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(inset),
    ) {
        options.forEachIndexed { index, option ->
            key(index) {
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val fill by animateColorAsState(
                    when {
                        selected -> c.surface4
                        pressed -> c.surface3
                        else -> Color.Transparent
                    },
                    tween(120),
                    label = "segment",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(BerthRadius.row - inset))
                        .background(fill)
                        .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option, style = BerthType.label, color = if (selected) c.text1 else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- Fields -------------------------------------------------------------------------------------------

/** Filled field, radius 12: surface.2 idle and surface.3 focused; the label sits above, never floats inside. */
@Composable
fun BerthField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    isError: Boolean = false,
    mono: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg by animateColorAsState(if (focused) c.surface3 else c.surface2, tween(120), label = "field")
    val style = (if (mono) BerthType.mono.copy(fontSize = BerthType.body.fontSize, lineHeight = BerthType.body.lineHeight) else BerthType.body).copy(color = c.text1)
    Column(modifier) {
        if (label != null) {
            Text(label.uppercase(), style = BerthType.caption, color = if (isError) c.danger else if (focused) c.accent else c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(BerthRadius.row))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = style,
                singleLine = singleLine,
                minLines = minLines,
                enabled = enabled,
                interactionSource = interaction,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = style, color = c.text3, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                        }
                        inner()
                    }
                },
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (helper != null) {
            Text(helper, style = BerthType.caption.copy(letterSpacing = 0.sp), color = if (isError) c.danger else c.text3, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

// ---- Sheets and misc ---------------------------------------------------------------------------------

/** 32 x 4 handle in text.3 centred in a 20 dp zone. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(32.dp, 4.dp)
                .clip(CircleShape)
                .background(Berth.colors.text3),
        )
    }
}

/** Header for a sheet: Headline text with an optional Caption beneath. */
@Composable
fun SheetTitle(title: String, caption: String? = null, color: Color = Berth.colors.text1) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = BerthType.headline, color = color)
        if (caption != null) Text(caption, style = BerthType.caption, color = Berth.colors.text2)
    }
}

/** Empty state: a short stance and up to three actions, no illustration. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.padding(horizontal = BerthSpace.screenMargin),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = BerthType.headline, color = Berth.colors.text1)
        Text(body, style = BerthType.body, color = Berth.colors.text2)
        Spacer(Modifier.height(12.dp))
        actions()
    }
}

/** Screen header: title at the leading edge, actions trailing; 56 tall with the screen margin. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = BerthSpace.screenMargin - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(onClick = onBack, description = "Back") {
                BerthIcon(BerthIcons.back)
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = BerthType.title, color = Berth.colors.text1, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
    }
}

/** 44 dp touch target for a glyph or icon; no fill until pressed. */
@Composable
fun IconAction(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (pressed) Berth.colors.surface3 else Color.Transparent)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            // The glyph is decoration; screen readers get the action's name, not the character.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Text glyph used as an icon; kept for callers that still pass characters. Prefer [BerthIcon]. */
@Composable
fun Glyph(text: String, color: Color = Berth.colors.text2, size: Int = 20) {
    Text(text, style = BerthType.label.copy(fontSize = size.sp, lineHeight = (size + 4).sp), color = color)
}

/**
 * The glyph set (A8): custom marks drawn on a 24 dp grid with a 1.75 dp round-capped stroke, shipped
 * as vector drawables so no icon font or Material icon pack is bundled.
 */
object BerthIcons {
    /** Three stacked rounded rectangles offset by 2 dp; opens the rail. */
    @DrawableRes val workspace: Int = R.drawable.glyph_workspace
    @DrawableRes val moreVert: Int = R.drawable.glyph_more_vert
    @DrawableRes val moreHoriz: Int = R.drawable.glyph_more_horiz
    @DrawableRes val back: Int = R.drawable.glyph_back
    @DrawableRes val add: Int = R.drawable.glyph_add
    @DrawableRes val chevronRight: Int = R.drawable.glyph_chevron_right
}

/** One glyph from [BerthIcons], tinted `text.2` unless told otherwise; decorative, so no description. */
@Composable
fun BerthIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Berth.colors.text2, size: Dp = 24.dp) {
    Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

object Paddings {
    val screen = PaddingValues(horizontal = BerthSpace.screenMargin)
}
