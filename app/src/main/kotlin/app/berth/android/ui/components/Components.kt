package app.berth.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.annotation.DrawableRes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.berth.android.R
import app.berth.android.ui.a11y.BerthMotion
import app.berth.android.ui.a11y.CappedFontScale
import app.berth.android.ui.a11y.LocalReducedMotion
import app.berth.android.ui.a11y.LocalTargetReach
import app.berth.android.ui.a11y.TouchTargetSize
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.a11y.spoken
import app.berth.android.ui.a11y.touchTarget
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

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

/**
 * 8 dp state dot; connecting and reconnecting add the one continuous motion in the app: a rotating
 * arc. The dot says its state to a screen reader as a state description ([description], the
 * state's own word unless the caller has a better one: `Waiting for the login`, `Loading`), which
 * a row or card merging its children then carries and announces when it changes; null says nothing,
 * for a dot beside text that already says it.
 */
@Composable
fun StatusDot(state: SessionState, modifier: Modifier = Modifier, size: Dp = 8.dp, description: String? = state.spoken()) {
    val c = Berth.colors
    val color = when (state) {
        SessionState.LIVE -> c.live
        SessionState.CONNECTING, SessionState.RECONNECTING, SessionState.IDLE -> c.pending
        SessionState.DETACHED, SessionState.CLOSED -> c.detached
        SessionState.FAILED -> c.danger
    }
    val spinning = state == SessionState.CONNECTING || state == SessionState.RECONNECTING
    // Read in the draw lambda only: each frame of the spin redraws this canvas and recomposes nothing.
    // Under reduced motion the arc holds still at its resting angle (spec A7), with no clock behind it.
    val rotation: State<Float>? = when {
        !spinning -> null
        LocalReducedMotion.current -> BerthMotion.staticArc
        else -> rememberInfiniteTransition(label = "reconnect-arc")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "arc")
    }
    Canvas(
        modifier
            .size(size + if (spinning) 6.dp else 0.dp)
            .then(if (description != null) Modifier.semantics { stateDescription = description } else Modifier),
    ) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(color, radius = size.toPx() / 2, center = center)
        if (rotation != null) {
            val stroke = 2.dp.toPx()
            val arcSize = this.size.minDimension - stroke
            drawArc(
                color = color,
                startAngle = rotation.value,
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

/** The alpha a disabled control's text and fill drop to; one value across buttons, rows and switches. */
const val DisabledAlpha = 0.5f

/**
 * The lines a text may take at the interface's font scale, from the [lines] the design gives it at
 * 1× (spec A11): a row is as wide at the 1.3× cap as at 1×, so the words that fill two lines at 1×
 * need a third there, and a title that fills its one line needs a second. The count is rounded up
 * ([lines] × the scale), so at 1× it is the design's own; a text that fits fewer lines still takes
 * fewer. A count with no limit stays unlimited.
 */
@Composable
fun linesAtFontScale(lines: Int): Int =
    if (lines == Int.MAX_VALUE) lines else ceil(lines * LocalDensity.current.fontScale).toInt().coerceAtLeast(lines)

/**
 * A list row: 12 dp radius, tonal step by state, leading swatch or icon, title and subtitle, and a
 * trailing column. Selection is the tonal step plus a 4 dp accent dot inside the padding (A6).
 * The title is one line unless [titleMaxLines] gives it more, for a title whose end matters as
 * much as its start (a forward's `bind → destination` breaks at the arrow rather than losing the
 * destination to the ellipsis). The subtitle takes a plain String or an [AnnotatedString] (mixed
 * Mono and Caption, accent spans) and may run to two lines, since a caption is often a sentence;
 * a caption that fits one line still takes one, and [subtitleMaxLines] narrows or widens that.
 * Both counts are the design's at 1× and grow with the interface's font scale ([linesAtFontScale]),
 * so what fits its lines at 1× is not cut at the cap (A11, 1.3×). [subtitleMinLines] holds lines
 * open before they have text, so a row whose second line arrives later (a forward's traffic once it
 * is up) does not grow when it does.
 * A row that is not [enabled] takes no tap, does not press, and draws its text at [DisabledAlpha].
 * [role] names what the tap does to a screen reader when the row is more than a row (a picker, a
 * switch); [interactionSource] lets a wrapper that owns the gesture (a toggleable) drive the
 * pressed tone, in which case the row itself takes no click.
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
    titleMaxLines: Int = 1,
    subtitleStyle: TextStyle = BerthType.caption,
    subtitleMaxLines: Int = 2,
    subtitleMinLines: Int = 1,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = Berth.colors
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The keyboard's focus (spec, Components): the row one tonal step up and its title in accent, while keys drive.
    val focused = enabled && interaction.showsFocus()
    val bg by animateColorAsState(
        when {
            pressed && enabled -> c.surface4
            selected || focused -> c.surface3
            else -> surface
        },
        tween(120),
        label = "row",
    )
    val textAlpha = if (enabled) 1f else DisabledAlpha
    val shownTitleColor = if (focused) c.accent else titleColor
    val titleLines = linesAtFontScale(titleMaxLines)
    val subtitleLines = linesAtFontScale(subtitleMaxLines)
    SelectionDotLayout(
        selected = selected,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(bg)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(enabled = enabled, interactionSource = interaction, indication = null, role = role, onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                } else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = titleStyle,
                    color = shownTitleColor.copy(alpha = shownTitleColor.alpha * textAlpha),
                    maxLines = titleLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.publishTitleLineCentre(titleStyle),
                )
                val subtitleColor = c.text2.copy(alpha = c.text2.alpha * textAlpha)
                when (subtitle) {
                    null -> Unit
                    is AnnotatedString -> Text(subtitle, style = subtitleStyle, color = subtitleColor, minLines = subtitleMinLines, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
                    else -> Text(subtitle.toString(), style = subtitleStyle, color = subtitleColor, minLines = subtitleMinLines, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = trailing)
            }
        }
    }
}

/** A selected row's 4 dp accent dot and the 8 dp between it and the row's content (A6). */
private val SelectionDot = 4.dp
private val SelectionDotGap = 8.dp

/**
 * The centre of a row title's first line, published by the title as an alignment line so the
 * accent dot beside the row can sit on it: the line climbs out through the Column and the Row
 * that hold the title, each adding the title's offset inside it, and reaches the layout that
 * places the dot as a position in the row's content.
 */
private val TitleLineCentre = HorizontalAlignmentLine { a, b -> minOf(a, b) }

/**
 * The title's [TitleLineCentre]: half its style's line height, the first line's own box under
 * `LineHeightStyle.Trim.None` whether the title runs to one line or two; half the measured
 * height for a style that gives no line height.
 */
private fun Modifier.publishTitleLineCentre(style: TextStyle): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val line = if (style.lineHeight.isSpecified) style.lineHeight.roundToPx() else placeable.height
    layout(placeable.width, placeable.height, mapOf(TitleLineCentre to line / 2)) { placeable.place(0, 0) }
}

/**
 * A row's content with, when [selected], the accent dot at its leading edge: the content keeps
 * the 12 dp the dot and its gap take, exactly as it did when the dot was the row's first child,
 * and the dot's centre sits on the title's first line ([TitleLineCentre]) rather than on the
 * row's. On a one-line row the two are the same point; on a row whose caption runs to a second
 * and a third line the dot stays beside the title instead of sliding down to the caption. The
 * content is the row's own `Row`, measured as before, so nothing but the dot moves.
 */
@Composable
private fun SelectionDotLayout(selected: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val accent = Berth.colors.accent
    val dot: @Composable () -> Unit = {
        if (selected) Box(Modifier.size(SelectionDot).clip(CircleShape).background(accent))
    }
    Layout(contents = listOf(dot, content), modifier = modifier) { (dotMeasurables, contentMeasurables), constraints ->
        val inset = if (selected) (SelectionDot + SelectionDotGap).roundToPx() else 0
        val row = contentMeasurables.single().measure(constraints.offset(horizontal = -inset))
        val dotPlaceable = dotMeasurables.singleOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.constrainWidth(row.width + inset), row.height) {
            row.placeRelative(inset, 0)
            if (dotPlaceable != null) {
                val centre = row[TitleLineCentre].takeIf { it != AlignmentLine.Unspecified } ?: row.height / 2
                dotPlaceable.placeRelative(0, centre - dotPlaceable.height / 2)
            }
        }
    }
}

/**
 * Row that opens a picker; the value sits at the trailing edge followed by a chevron. The [caption]
 * has up to two lines ([ListRow]), or what [captionLines] says. Not [enabled], the row says its
 * [value] (the reason nothing can be picked) and opens nothing.
 */
@Composable
fun PickerRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    captionLines: Int = 2,
    enabled: Boolean = true,
) {
    val c = Berth.colors
    val alpha = if (enabled) 1f else DisabledAlpha
    ListRow(
        title = title,
        subtitle = caption,
        subtitleMaxLines = captionLines,
        minHeight = TouchTargetSize,
        surface = Color.Transparent,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        role = Role.DropdownList,
        trailing = {
            Text(value, style = BerthType.body, color = c.text2.copy(alpha = c.text2.alpha * alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BerthIcon(BerthIcons.chevronRight, tint = c.text3.copy(alpha = c.text3.alpha * alpha), size = 20.dp)
        },
    )
}

/**
 * A switch row; the [caption] under the title has up to two lines ([ListRow]), or what [captionLines] says.
 * Not [enabled], the switch is drawn disabled and neither it nor the row takes a tap; the caption
 * is where the row says why. To a screen reader the row is the switch: one node carrying the
 * title, the caption and the on/off state, toggled by a tap anywhere on it; the switch itself is
 * silent, so a screen never has a run of anonymous `On`, `Off` controls.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    captionLines: Int = 2,
    enabled: Boolean = true,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    ListRow(
        title = title,
        subtitle = caption,
        subtitleMaxLines = captionLines,
        minHeight = TouchTargetSize,
        surface = Color.Transparent,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interaction,
            indication = null,
            onValueChange = onCheckedChange,
        ),
        interactionSource = interaction,
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics { },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.onAccent,
                    checkedTrackColor = c.accent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = c.text2,
                    uncheckedTrackColor = c.surface4,
                    uncheckedBorderColor = Color.Transparent,
                    disabledCheckedThumbColor = c.onAccent.copy(alpha = DisabledAlpha),
                    disabledCheckedTrackColor = c.accent.copy(alpha = DisabledAlpha),
                    disabledCheckedBorderColor = Color.Transparent,
                    disabledUncheckedThumbColor = c.text2.copy(alpha = DisabledAlpha),
                    disabledUncheckedTrackColor = c.surface4.copy(alpha = DisabledAlpha),
                    disabledUncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

/**
 * A [Panel]'s explanatory paragraph under a row: Caption in text.2, indented to the row's text. The
 * note is prose that carries a setting's meaning, so it takes the 4.5:1 A11 promises for anything
 * read; text.3 is for decoration (ages, ports, section labels), and a note set in it fell to 3.3:1
 * dark and under 3:1 on the light themes.
 */
@Composable
fun PanelNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = BerthType.caption, color = Berth.colors.text2, modifier = modifier.padding(start = 12.dp, top = 4.dp))
}

/** A [PanelNote] whose [text] carries its own runs, a stronger lead among them; the rest is text.2. */
@Composable
fun PanelNote(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(text, style = BerthType.caption, color = Berth.colors.text2, modifier = modifier.padding(start = 12.dp, top = 4.dp))
}

// ---- Candidate rows -----------------------------------------------------------------------------------

/**
 * A candidate row that is ticked or not, for what an import offers (spec A16, C13): a [ListRow]
 * that toggles as a checkbox, so a screen reader hears `checked, git.example.com` rather than a
 * button whose description changes, with the dot as its only mark. A [warning] under the facts,
 * in the danger tint, is what ticking the row would undo: the saved key a conflicting one replaces.
 * A [note] is the same line in the subtitle's own tone, for a tick that takes nothing away: the
 * saved key a bundled one of another type is added beside. [surface] is the row's own tone;
 * inside a [Panel] it is transparent, the panel's being the tone.
 */
@Composable
fun TickRow(
    title: String,
    subtitle: String,
    ticked: Boolean,
    onTicked: (Boolean) -> Unit,
    warning: String? = null,
    note: String? = null,
    surface: Color = Berth.colors.surface2,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    ListRow(
        title = title,
        subtitle = when {
            warning != null -> buildAnnotatedString {
                append(subtitle)
                append("\n")
                withStyle(SpanStyle(color = c.danger)) { append(warning) }
            }
            note != null -> "$subtitle\n$note"
            else -> subtitle
        },
        subtitleMaxLines = if (warning == null && note == null) 2 else 4,
        minHeight = 52.dp,
        surface = surface,
        modifier = Modifier.toggleable(value = ticked, role = Role.Checkbox, interactionSource = interaction, indication = null, onValueChange = onTicked),
        interactionSource = interaction,
        leading = { TickDot(ticked) },
    )
}

/**
 * The mark on a pinned endpoint's row, in the tick's place: a lock, in the subtitle's tone, since
 * the row is read and not offered, and an unticked dot there would read as a box that will not
 * tick. Decorative; the row's second line says what the lock means.
 */
@Composable
fun PinLock() {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        BerthIcon(BerthIcons.lock, tint = Berth.colors.text2, size = 16.dp)
    }
}

/** The tick on a candidate row: an 8 dp dot, accent when the key or host will import, text.3 when it will not. The row's own state says which; the dot is the picture. */
@Composable
private fun TickDot(ticked: Boolean) {
    val c = Berth.colors
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ticked) c.accent else c.text3),
        )
    }
}

// ---- Menus ------------------------------------------------------------------------------------------

/**
 * The app's menu: M3's [DropdownMenu] on a surface two tonal steps above whatever it opens over,
 * since the shadow it comes with does not read on these tones. Inside a surface.2 [Panel] that is
 * surface.4; on a bare screen or a sheet (no panel, taken as surface.1) it is surface.3. Call it
 * where a [DropdownMenu] would go: inside the [Box] that holds the row it hangs from, or inside a
 * [TrailingMenuAnchor] there to have it open under the row's value rather than over its title.
 * The menu is a window of its own, so the interface's font cap is applied again inside it
 * ([CappedFontScale]), as a sheet's is.
 */
@Composable
fun BerthMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Berth.colors
    val below = LocalPanelSurface.current ?: c.surface1
    val step = c.stepOf(below) ?: 1
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = c.surface(step + 2),
        shape = RoundedCornerShape(BerthRadius.row),
    ) {
        CappedFontScale { content() }
    }
}

/**
 * One line of a [BerthMenu]: Body text, accent when it is the [selected] value, danger when
 * [destructive], with an optional [leading] mark (a host's [Swatch]) where the items are things
 * that can share a name.
 */
@Composable
fun BerthMenuItem(text: String, onClick: () -> Unit, selected: Boolean = false, destructive: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val c = Berth.colors
    DropdownMenuItem(
        // The accent says selected to the eye; a screen reader hears it from the state.
        modifier = Modifier.semantics { this.selected = selected },
        text = {
            Text(
                text,
                style = BerthType.body,
                color = when {
                    destructive -> c.danger
                    selected -> c.accent
                    else -> c.text1
                },
            )
        },
        onClick = onClick,
        leadingIcon = leading,
    )
}

/**
 * The app's popup: Compose's [Popup], a window of its own that [positionProvider] places over the
 * screen and [properties] govern, for what hangs off one element without being a menu's list of
 * lines (the Deck's row of alternate chips risen over a held key). It has no dismiss callback: it
 * is open while its caller composes it and gone when the caller stops, so whatever raised it owns
 * the finger or the state that puts it away. A window provides its density afresh from its
 * Context, so the interface's font cap ([CappedFontScale], spec A11) is applied again inside it;
 * every window the app opens, a [BerthSheet], a [BerthMenu] or this, is held to that rule.
 */
@Composable
fun BerthPopup(positionProvider: PopupPositionProvider, properties: PopupProperties, content: @Composable () -> Unit) {
    Popup(popupPositionProvider = positionProvider, properties = properties) {
        CappedFontScale(content)
    }
}

/**
 * Where a row's menu hangs from: a zero-width anchor down the row's trailing edge, so a
 * [BerthMenu] inside it opens end-aligned under the row's value and leaves the titles of the rows
 * beneath uncovered. Goes in the [Box] that holds the row, after it.
 */
@Composable
fun BoxScope.TrailingMenuAnchor(content: @Composable () -> Unit) {
    Box(Modifier.matchParentSize().wrapContentWidth(Alignment.End)) { content() }
}

/**
 * The leading counterpart of [TrailingMenuAnchor]: a zero-width anchor [inset] from the row's
 * leading edge, so a [BerthMenu] inside it opens start-aligned under the row's title (the row's
 * padding plus its glyph column and gap), where the label it belongs to is, rather than at the far
 * edge over whatever sits there. Goes in the [Box] that holds the row, after it.
 */
@Composable
fun BoxScope.LeadingMenuAnchor(inset: Dp, content: @Composable () -> Unit) {
    Box(Modifier.matchParentSize().padding(start = inset).wrapContentWidth(Alignment.Start)) { content() }
}

// ---- Slider ---------------------------------------------------------------------------------------

/**
 * Slider re-skinned with the tokens (A1; C19 draws Tone as `warm ────o──── cool`): a 2 dp track on
 * surface.4, a 16 dp accent knob cut out of the track by a 2 dp ring in the enclosing [surface]
 * (the panel's when inside one, otherwise the sheet's surface.1), and a 44 dp touch row. With
 * [neutral] set the slider is bipolar: a notch marks the neutral point and the accent fill runs from
 * there to the knob; without it the fill runs from the start of the range. [steps] discrete values
 * strictly between the ends (as the M3 slider counts them) snap every path that sets the value,
 * the accessibility one included; 0 keeps it continuous.
 */
@Composable
fun BerthSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    neutral: Float? = null,
    enabled: Boolean = true,
    surface: Color = LocalPanelSurface.current ?: Berth.colors.surface1,
    steps: Int = 0,
) {
    val c = Berth.colors
    val span = valueRange.endInclusive - valueRange.start
    val insetPx = with(LocalDensity.current) { SliderInset.toPx() }
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var rawX by remember { mutableFloatStateOf(0f) }
    // The knob grows under the finger; under reduced motion it is simply larger while held.
    val knob by animateDpAsState(if (dragging) 10.dp else 8.dp, BerthMotion.transform(spring()), label = "slider knob")
    val alpha = if (enabled) 1f else 0.5f

    fun fraction(v: Float) = if (span == 0f) 0f else ((v - valueRange.start) / span).coerceIn(0f, 1f)
    fun snap(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        if (steps <= 0) return f
        val intervals = steps + 1
        return (f * intervals).roundToInt().toFloat() / intervals
    }
    fun set(fraction: Float) = onValueChange(valueRange.start + snap(fraction) * span)
    fun setFromX(x: Float) {
        val usable = widthPx - 2 * insetPx
        if (usable <= 0f) return
        set((x - insetPx) / usable)
    }
    val drag = rememberDraggableState { delta ->
        // Clamped to the track, so a finger that overshoots an end does not have to travel back
        // through the overshoot before the knob follows it again.
        rawX = (rawX + delta).coerceIn(insetPx, (widthPx - insetPx).coerceAtLeast(insetPx))
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
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { set(fraction(it)); true }
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

/**
 * 28 dp chip, radius 8; selected chips step up to surface.4. The chip is a filter or a pick, so it
 * is a toggle to a screen reader (`Name, selected`) and sits centred in a 48 dp target box that
 * takes the tap; [modifier] sizes the chip itself.
 */
@Composable
fun Chip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, mono: Boolean = false, onClick: () -> Unit) {
    val c = Berth.colors
    Box(
        Modifier
            .selectable(selected = selected, role = Role.Button, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier
                .height(28.dp)
                .clip(RoundedCornerShape(BerthRadius.swatch))
                .background(if (selected) c.surface4 else c.surface2)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = if (mono) BerthType.mono else BerthType.label, color = c.text1, maxLines = 1)
        }
    }
}

/**
 * One colour in a row of colours to choose from (a host's swatch, a group's, the interface accent):
 * the colour at `indicator` radius inside a [size] step at `swatch` radius that turns surface.4 when
 * chosen (concentric), centred in a 48 dp target that takes the tap, so a row of them keeps a 48 dp
 * pitch and no two share a target. To a screen reader it is a radio button named for its colour,
 * [name], with its chosen state; the colour itself is never the only thing that says which is which.
 */
@Composable
fun ColorOption(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    inset: Dp = 4.dp,
) {
    val c = Berth.colors
    Box(
        modifier
            .selectable(selected = selected, role = Role.RadioButton, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = name }
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(BerthRadius.swatch))
                .background(if (selected) c.surface4 else c.surface2)
                .padding(inset),
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(BerthRadius.indicator)).background(color))
        }
    }
}

/** The spoken name of a [SwatchColor] (`Copper`, `Verdigris`), for the option that carries it. */
fun SwatchColor.spokenName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

// ---- Buttons ----------------------------------------------------------------------------------------

enum class ButtonKind { PRIMARY, SECONDARY, TEXT, DESTRUCTIVE }

/**
 * The spec's button (A9): a 44 dp fill at the row radius with a Label, in a 48 dp target. A row
 * that wants a shorter fill beside its text (a tunnel row's Open and Retry at 36) says so through
 * [fillHeight] rather than a height modifier, which would shrink the target with the fill. A width
 * the caller sets (`fillMaxWidth`, a `weight`) is the fill's as well as the target's: the target
 * box hands its minimum on to the fill, so a sheet's stacked answers run edge to edge as one Box
 * with those modifiers would, while a button left to itself wraps its label.
 */
@Composable
fun BerthButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
    fillHeight: Dp = 44.dp,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The keyboard's focus takes the pressed tone; the label goes accent where the fill can carry it
    // (a primary button's fill is the accent, a destructive one's label says danger first).
    val focused = enabled && interaction.showsFocus()
    val raised = pressed || focused
    val fill = when (kind) {
        ButtonKind.PRIMARY -> if (raised) c.accent.copy(alpha = 0.85f) else c.accent
        ButtonKind.SECONDARY, ButtonKind.DESTRUCTIVE -> if (raised) c.surface4 else c.surface3
        ButtonKind.TEXT -> if (raised) c.surface2 else Color.Transparent
    }
    val label = when (kind) {
        ButtonKind.PRIMARY -> c.onAccent
        ButtonKind.SECONDARY -> if (focused) c.accent else c.text1
        ButtonKind.TEXT -> c.accent
        ButtonKind.DESTRUCTIVE -> c.danger
    }
    // The fill is the spec's 44 dp (or the caller's shorter one); the box that takes the tap is 48.
    Box(
        modifier
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .touchTarget(),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier
                .defaultMinSize(minHeight = fillHeight, minWidth = 44.dp)
                .clip(RoundedCornerShape(BerthRadius.row))
                .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.5f))
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = BerthType.label, color = if (enabled) label else label.copy(alpha = 0.5f), maxLines = 1)
        }
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
                val focused = interaction.showsFocus()
                val fill by animateColorAsState(
                    when {
                        selected -> c.surface4
                        pressed || focused -> c.surface3
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
                    Text(option, style = BerthType.label, color = if (focused) c.accent else if (selected) c.text1 else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // The same bridge BasicTextField's String overload uses: selection and composition live here,
    // the text is the caller's, and the caller only hears about changes to the text.
    var fieldState by remember { mutableStateOf(TextFieldValue(text = value)) }
    val fieldValue = fieldState.copy(text = value)
    SideEffect {
        if (fieldValue.selection != fieldState.selection || fieldValue.composition != fieldState.composition) fieldState = fieldValue
    }
    var lastText by remember(value) { mutableStateOf(value) }
    BerthField(
        value = fieldValue,
        onValueChange = { next ->
            fieldState = next
            val changed = lastText != next.text
            lastText = next.text
            if (changed) onValueChange(next.text)
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        helper = helper,
        isError = isError,
        mono = mono,
        password = password,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailing = trailing,
    )
}

/**
 * [BerthField] with the caller owning selection as well as text, for fields that open with part of
 * the value selected (a file's stem under Rename); [focusRequester] lets a sheet focus it on open.
 */
@Composable
fun BerthField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
    focusRequester: FocusRequester? = null,
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
                modifier = Modifier
                    .weight(1f)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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
                        if (value.text.isEmpty() && placeholder != null) {
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
            // In a panel the field's box is the panel's own surface, unseen, and its bottom half (the
            // 44 dp less the line of text) is the gap to the label under it; the helper stands where
            // that half was, so it carries the same gap rather than leaving the next label 4 dp off.
            val bottom = if (LocalPanelSurface.current != null) FieldHelperPanelGap else 0.dp
            Text(helper, style = BerthType.caption.copy(letterSpacing = 0.sp), color = if (isError) c.danger else c.text3, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = bottom))
        }
    }
}

/** What a field's box leaves under its text: (44 dp − the body's 22 sp line) / 2, given to a helper inside a [Panel]. */
private val FieldHelperPanelGap = 11.dp

// ---- Sheets and misc ---------------------------------------------------------------------------------

/**
 * 32 x 4 handle in text.3 centred in the sheet's handle zone. The zone is the sheet's one control
 * (the sheet wraps it in a tap that closes or expands and in the drag), so it is a full 48 dp
 * target (the spec's 20 was the pill's clearance, not a target) and names itself to a screen
 * reader, which then hears the sheet's own actions after it.
 */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(TouchTargetSize)
            .semantics { contentDescription = "Drag handle" },
        contentAlignment = Alignment.Center,
    ) {
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
fun SheetTitle(title: String, caption: String? = null, color: Color = Berth.colors.text1, captionColor: Color = Berth.colors.text2) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = BerthType.headline, color = color)
        if (caption != null) Text(caption, style = BerthType.caption, color = captionColor)
    }
}

/**
 * Empty state: a short stance and up to three lines of actions, no illustration. A line is one
 * action, or the primary with a secondary beside it where a second way in belongs on the first
 * line (the empty Hosts library's Import a bundle), the secondary wrapping under the primary
 * where the line is too short for both; text actions take a line each under them.
 */
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

/**
 * Screen header: title at the leading edge, actions trailing; 56 tall with the screen margin. The
 * leading slot is the back action when [onBack] is given, or [navigation] when a screen has another
 * way out of a mode (a selection header's close), so every header state shares one geometry.
 * [actions] stays the last parameter, so a caller's trailing lambda is its actions: when
 * [navigation] arrived after it, four screens' trailing lambdas quietly became their navigation
 * slot, and their actions stood at the leading edge in place of the back action.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // The icon actions are 48 dp targets around 44 dp circles, so they sit flush (the 2 dp of target
    // either side of each circle is the 4 dp gap there was) and the margin gives back the 2 dp.
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = BerthSpace.screenMargin - 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            navigation != null -> {
                navigation()
                Spacer(Modifier.width(2.dp))
            }
            onBack != null -> {
                IconAction(onClick = onBack, description = "Back") {
                    BerthIcon(BerthIcons.back)
                }
                Spacer(Modifier.width(2.dp))
            }
            else -> Spacer(Modifier.width(10.dp))
        }
        Text(title, style = BerthType.title, color = Berth.colors.text1, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * A glyph or icon in a 44 dp circle that fills when pressed, inside a 48 dp touch target. The
 * target is required rather than requested, so inside a shorter row (the 40 dp ribbon) it stays a
 * 48 dp square centred on the row instead of squashing, and the circle stays a circle. [reach] is
 * extra target above the circle for a header that lends its status-bar inset (the ribbon's
 * [app.berth.android.ui.tabs.TabStripStyle.topReach]): the target grows upward by it and the circle
 * stays centred on the row beneath, the way the tabs beside it do. [onLongClick], when given, is a
 * second action on the same target, named to a screen reader by [longClickLabel].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconAction(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reach: Dp = LocalTargetReach.current,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = enabled && interaction.showsFocus()
    Box(
        modifier
            .requiredSize(width = TouchTargetSize, height = TouchTargetSize + reach)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(enabled = enabled, interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
                } else {
                    Modifier.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                },
            )
            // The glyph is decoration; screen readers get the action's name, not the character.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
                if (onLongClick != null && enabled) this.onLongClick(label = longClickLabel) { onLongClick(); true }
            }
            .padding(top = reach),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize(44.dp)
                .clip(CircleShape)
                .background(if (pressed || focused) Berth.colors.surface3 else Color.Transparent),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
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
    @DrawableRes val folder: Int = R.drawable.glyph_folder
    @DrawableRes val file: Int = R.drawable.glyph_file
    @DrawableRes val link: Int = R.drawable.glyph_link
    /** A cross; closes the active tab, switcher cards and a Files selection. */
    @DrawableRes val close: Int = R.drawable.glyph_close
    @DrawableRes val check: Int = R.drawable.glyph_check
    @DrawableRes val edit: Int = R.drawable.glyph_edit
    @DrawableRes val terminal: Int = R.drawable.glyph_terminal
    @DrawableRes val upload: Int = R.drawable.glyph_upload
    @DrawableRes val download: Int = R.drawable.glyph_download
    @DrawableRes val home: Int = R.drawable.glyph_home
    @DrawableRes val transfers: Int = R.drawable.glyph_transfers
    @DrawableRes val lock: Int = R.drawable.glyph_lock
    @DrawableRes val copy: Int = R.drawable.glyph_copy
    @DrawableRes val trash: Int = R.drawable.glyph_trash
    /** A ring with a handle; the New tab sheet's host filter. */
    @DrawableRes val search: Int = R.drawable.glyph_search
    /** The library as glyphs (spec C7), for the drawer's 72 dp column: a rack, a key, a tunnel's mouth, a run of code, three sliders. */
    @DrawableRes val hosts: Int = R.drawable.glyph_hosts
    @DrawableRes val key: Int = R.drawable.glyph_key
    @DrawableRes val tunnel: Int = R.drawable.glyph_tunnel
    @DrawableRes val snippet: Int = R.drawable.glyph_snippet
    @DrawableRes val settings: Int = R.drawable.glyph_settings
}

/** One glyph from [BerthIcons], tinted `text.2` unless told otherwise; decorative, so no description. */
@Composable
fun BerthIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Berth.colors.text2, size: Dp = 24.dp) {
    Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

object Paddings {
    val screen = PaddingValues(horizontal = BerthSpace.screenMargin)
}
