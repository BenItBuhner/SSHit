package app.berth.android.ui.stage

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlin.math.abs

/**
 * The divider's drag band, on the container of the two panes (spec C23, A11): the 12 dp gap and
 * the reach over each pane's edge that make it a 48 dp target answer a horizontal pull that starts
 * anywhere in the band, taken here, on the panes' parent, after what is under the finger has had
 * the touch. So the band yields to what it lies over. A tap on a control in it (the focused pane's
 * ×, an action of the state pill, a Files row) is that control's, since a press only cancels for a
 * move somebody consumed; a vertical scroll that starts a few dp from a pane's edge is the
 * terminal's or the list's, which consume it, and a pull that leaves the band's axis first is not
 * the divider's; a horizontal pull over a terminal's last columns, which the canvas leaves alone,
 * is the divider's, as it is over the gap. A band laid over the panes took every touch in it, and
 * a control at a pane's inner edge lost 18 dp of its 48 to the divider, the size the accessibility
 * tree then reported for it.
 *
 * [centre] is the gap's middle in this node's own coordinates and [reach] is half the band, both in
 * px; [onDrag] is given the finger's travel since the last call, the first the travel past the
 * touch slop. Read at the time of the touch, so a container laid out again mid-gesture, or between
 * gestures, drives the fraction with the widths it has now.
 */
fun Modifier.paneDividerDrag(
    centre: Float,
    reach: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = this then PaneDividerDragElement(centre, reach, onDragStart, onDrag, onDragEnd)

private data class PaneDividerDragElement(
    val centre: Float,
    val reach: Float,
    val onDragStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onDragEnd: () -> Unit,
) : ModifierNodeElement<PaneDividerDragNode>() {
    override fun create() = PaneDividerDragNode(centre, reach, onDragStart, onDrag, onDragEnd)

    override fun update(node: PaneDividerDragNode) {
        node.centre = centre
        node.reach = reach
        node.onDragStart = onDragStart
        node.onDrag = onDrag
        node.onDragEnd = onDragEnd
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "paneDividerDrag"
        properties["centre"] = centre
        properties["reach"] = reach
    }
}

private class PaneDividerDragNode(
    var centre: Float,
    var reach: Float,
    var onDragStart: () -> Unit,
    var onDrag: (Float) -> Unit,
    var onDragEnd: () -> Unit,
) : DelegatingNode() {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    // The main pass: the panes' own controls and gestures have seen the touch first.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (abs(down.position.x - centre) > reach) return@awaitEachGesture
                    var over = 0f
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, past ->
                        change.consume()
                        over = past
                    } ?: return@awaitEachGesture
                    onDragStart()
                    onDrag(over)
                    horizontalDrag(start.id) { change ->
                        // The travel is read before the change is consumed: a consumed change reports none.
                        val dx = change.positionChange().x
                        change.consume()
                        onDrag(dx)
                    }
                    onDragEnd()
                }
            },
        )
    }
}
