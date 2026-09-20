package app.berth.android.ui.a11y

import androidx.compose.foundation.clickable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.domain.model.SessionState

/**
 * The smallest target a control offers a finger or a switch-access cursor (spec A11 asks 44; the
 * accessibility pass raises every control to 48, the Android accessibility scanner's floor).
 */
val TouchTargetSize: Dp = 48.dp

/**
 * Extra target a header lends the controls in its fixed slots above their visual, out of the
 * status-bar inset it owns (the ribbon's [app.berth.android.ui.tabs.TabStripStyle.topReach]), so a
 * 40 dp row answers a 48 dp target without the row growing. The header provides it; an icon
 * action or count tile placed in the header reads it as its default reach. Zero everywhere else.
 */
val LocalTargetReach = compositionLocalOf { 0.dp }

/**
 * Grows a control's layout to at least [minWidth] by [minHeight] and centres the control in the
 * grown box, so the visual (a 28 dp chip, a 44 dp button) keeps its size while the box that takes
 * the tap, and that accessibility measures, is a full target. Goes *after* the modifier that makes
 * the control clickable (`Modifier.clickable(...).touchTarget()`), so the clickable wraps the grown
 * box; before it, the grown space would take no tap. A control already that big is left alone.
 * Compose grows the *hit* area of a clickable to 48 dp on its own, but only into space no neighbour
 * claims, which a row of chips or a header of icon actions never has; a real layout box is what
 * survives the neighbours.
 */
fun Modifier.touchTarget(minWidth: Dp = TouchTargetSize, minHeight: Dp = TouchTargetSize): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = maxOf(placeable.width, minWidth.roundToPx()).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = maxOf(placeable.height, minHeight.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) {
        placeable.placeRelative((width - placeable.width) / 2, (height - placeable.height) / 2)
    }
}

/**
 * A text link's full target without its footprint: for a label that is a control in a column of
 * text (a panel's `Details`, a field's `Clear`), where the 48 dp box [touchTarget] makes would
 * push the lines around it apart. The label is hit, focused and measured by accessibility at
 * least [minHeight] tall, centred on its own line, while the layout around it still sees the
 * line's height alone; the extra target lies over the gaps above and below, which is where a
 * finger aiming at a one-line label lands anyway. The tap is this modifier's ([onClick], with
 * [role] and [onClickLabel] for a reader), since the grown box has to sit inside the tap and the
 * kept footprint outside it. No indication: a pressed tone over the gaps would show the box.
 */
fun Modifier.reachingClickable(
    onClick: () -> Unit,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    minHeight: Dp = TouchTargetSize,
): Modifier = this
    .then(KeepFootprint)
    .clickable(enabled = enabled, interactionSource = null, indication = null, role = role, onClickLabel = onClickLabel, onClick = onClick)
    .then(GrowTarget(minHeight))

/** Inside the tap: measured at least [minHeight] tall with the content centred; tells intrinsics the content's own height, for [KeepFootprint]. */
private data class GrowTarget(val minHeight: Dp) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val height = maxOf(placeable.height, minHeight.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int = measurable.maxIntrinsicHeight(width)
}

/** Outside the tap: reports the content's own height, read through the intrinsics, and centres the grown box on it. */
private data object KeepFootprint : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val own = measurable.minIntrinsicHeight(constraints.maxWidth)
        val placeable = measurable.measure(constraints)
        val height = minOf(own, placeable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
    }
}

/**
 * Lets a row of targets take [amount] of width from the margin on either side: the content is
 * measured [amount] wider than the space it is given, at both ends, and placed [amount] to the
 * start of it, so the 4 dp of target box around a 40 dp swatch lies in the margin while the swatch
 * itself stays on the margin line, the way the header's icon actions sit in theirs. For a grid
 * whose cells would otherwise fall a dp short of a target. Nothing is drawn in the margin: the
 * boxes there are empty target.
 */
fun Modifier.bleedInto(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedWidth) amount.roundToPx() * 2 else 0
    val wider = constraints.copy(
        minWidth = if (constraints.minWidth == constraints.maxWidth) constraints.maxWidth + extra else constraints.minWidth,
        maxWidth = constraints.maxWidth + extra,
    )
    val placeable = measurable.measure(wider)
    val width = (placeable.width - extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}

/** The state a session's dot stands for, as TalkBack should say it beside the row or tab that carries the dot. */
fun SessionState.spoken(): String = when (this) {
    SessionState.LIVE -> "Live"
    SessionState.IDLE, SessionState.CONNECTING -> "Connecting"
    SessionState.RECONNECTING -> "Reconnecting"
    SessionState.DETACHED -> "Detached"
    SessionState.FAILED -> "Failed"
    SessionState.CLOSED -> "Closed"
}

/**
 * The accessibility actions of a row the user can move within its list and take out of it: Move
 * up, Move down and Remove, offered through TalkBack's actions menu and to switch access, for a
 * row whose tap and long-press both open a menu and so give a screen reader no way to reorder.
 * A null [onMoveUp] or [onMoveDown] (the first or last row) leaves that action out; [onRemove]
 * null leaves Remove out. Merges with the row's own actions.
 */
fun Modifier.reorderActions(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    removeLabel: String = "Remove",
): Modifier = semantics {
    customActions = buildList {
        if (onMoveUp != null) add(CustomAccessibilityAction("Move up") { onMoveUp(); true })
        if (onMoveDown != null) add(CustomAccessibilityAction("Move down") { onMoveDown(); true })
        if (onRemove != null) add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
    }
}

/** A state a screen reader reads after the control's name (`Live`, `2 of 2 up`, `Waiting for the login`). */
fun Modifier.spokenState(state: String): Modifier = semantics { stateDescription = state }
