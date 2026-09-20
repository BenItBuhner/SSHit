package app.berth.android.ui.keyboard

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics

/**
 * The Stage's regions the keyboard moves between (spec A11, focus order), top to bottom: the tab
 * strip, a bar over the body (the scrollback search or the selection bar, where the strip was or
 * under it), the tab's body, and the Deck.
 */
enum class StageRegion { Strip, Bar, Body, Deck }

/**
 * Where the keyboard's focus is on the Stage, and the way to move it. Each region is a focus group
 * ([stageRegion]) that a chord enters as one ([focus]), landing on the control it names as its
 * entry ([entry]: the strip's first tab) or else on its first control by the focus system's own
 * search: the terminal for a shell tab's body, the first tunnel or folder row for the others, the
 * first key of the Deck or the collapsed strip that shows it; Tab and the arrows then walk the
 * region's controls, since a control has the focus rather than the region. A terminal takes Tab,
 * the arrows and Escape for the host, so the chords are the way out of it (Ctrl+Shift+S to the
 * strip, Ctrl+Shift+K to the Deck) and Escape from either is the way back. The Deck's region lies
 * inside the body's, being the shell tab's, so [region] names the innermost that holds the focus.
 */
@Stable
class StageFocus {
    private val requesters = StageRegion.entries.associateWith { FocusRequester() }
    private val entries = StageRegion.entries.associateWith { FocusRequester() }
    // How many of a region's nodes hold the focus. A region may be marked on more than one node (the
    // search bar and the selection bar are both the Bar), and the reports of a focus moving between
    // them, or of a body and the Deck inside it, land in either order; a count is right in every order.
    private val holders = mutableStateMapOf<StageRegion, Int>()

    /** The innermost region holding the focus; null while nothing on the Stage has it. */
    val region: StageRegion?
        get() = StageRegion.entries.lastOrNull { (holders[it] ?: 0) > 0 }

    /**
     * The region that last held the focus, for putting it back once the control it was on has gone
     * (a Deck hidden or shown, a search closed, a tab switched). [KeepStageFocus] keeps it from the
     * settled state between frames: a control's removal reports the loss for its own region and for
     * the ones around it in an order that is the node tree's, and a reading in between would name a
     * region the focus was only passing out through.
     */
    var last: StageRegion? by mutableStateOf(null)
        internal set

    fun requester(region: StageRegion): FocusRequester = requesters.getValue(region)

    /**
     * The control a chord into [region] lands on, for the region to put on it (the strip on its
     * first tab): a focus group entered as one lands on the control the focus system's search
     * finds first, which is the one nearest its top-left corner unless a control reaches above that
     * corner, as the header's count tile and overflow do by the target they take above the row
     * (a control in that beam beats every one out of it, whatever the distance), so out of touch
     * mode, when those are focusable, a search alone would land on the count tile. Optional: a
     * region with no entry, or whose entry is off the screen, is entered by the search.
     */
    fun entry(region: StageRegion): FocusRequester = entries.getValue(region)

    /**
     * The focus into [region], on its entry or else on its first control. False when the region
     * has none to give it: a body not yet on stage, a Deck the tab's state has taken down, a tab
     * kind with no Deck, a bar that is not open.
     */
    fun focus(region: StageRegion): Boolean =
        runCatching { entry(region).requestFocus() }.getOrDefault(false) || runCatching { requester(region).requestFocus() }.getOrDefault(false)

    /**
     * The focus back onto the Stage after the control holding it has gone: into the region it left,
     * else the body, else the strip, whose plus tab and header controls are always there. False only
     * when the Stage has no control at all to give it to.
     */
    fun restore(): Boolean = listOfNotNull(last, StageRegion.Body, StageRegion.Strip).distinct().any { focus(it) }

    internal fun report(region: StageRegion, hasFocus: Boolean) {
        holders[region] = ((holders[region] ?: 0) + (if (hasFocus) 1 else -1)).coerceAtLeast(0)
    }
}

@Composable
fun rememberStageFocus(): StageFocus = remember { StageFocus() }

/**
 * Marks a region of the Stage: a focus group the keyboard enters as one, a traversal group a
 * reader hears as one, and a report to [focus] of whether a control inside it holds the focus,
 * withdrawn when the region leaves the screen with the control still focused (a tab switched under
 * its terminal), which the focus system tells no one about. A region marked on more than one node
 * at once (the two panes of a split Stage are both the body, spec C23) is entered through the one
 * marked as its [entry]; the others report and group, and a chord into the region lands on the
 * entry's first control.
 */
fun Modifier.stageRegion(focus: StageFocus, region: StageRegion, entry: Boolean = true): Modifier = this
    .then(StageRegionElement(focus, region))
    .then(if (entry) Modifier.focusRequester(focus.requester(region)) else Modifier)
    .focusGroup()
    .semantics { isTraversalGroup = true }

private data class StageRegionElement(val focus: StageFocus, val region: StageRegion) : ModifierNodeElement<StageRegionNode>() {
    override fun create() = StageRegionNode(focus, region)
    override fun update(node: StageRegionNode) = node.bind(focus, region)
    override fun InspectorInfo.inspectableProperties() {
        name = "stageRegion"
        properties["region"] = region
    }
}

private class StageRegionNode(private var focus: StageFocus, private var region: StageRegion) : Modifier.Node(), FocusEventModifierNode {
    private var holding = false

    override fun onFocusEvent(focusState: FocusState) = hold(focusState.hasFocus)

    override fun onDetach() = hold(false)

    fun bind(focus: StageFocus, region: StageRegion) {
        if (focus === this.focus && region == this.region) return
        hold(false)
        this.focus = focus
        this.region = region
    }

    private fun hold(has: Boolean) {
        if (has == holding) return
        holding = has
        focus.report(region, has)
    }
}

/**
 * Keeps the keyboard's focus on the Stage while a hardware keyboard is attached, so no key is lost:
 * when the control holding it goes (the tab on stage changes, the Deck hides or shows, the search
 * closes, a tab is closed), the focus returns to the region it was in, or to the body, or to the
 * strip ([StageFocus.restore]), and a shell tab coming on stage is typed into at once. A Deck
 * hidden under a focused key hands the focus to the strip that stands in for it, and that strip
 * pressed hands it back to the Deck's first key. A focus that left the Stage for another control
 * in the window, the rail on an expanded width (spec A12) by Shift+Tab out of the strip, was moved
 * and not lost, and stays there ([LocalWindowFocus]). Only with a keyboard attached: focusing a
 * shell tab's terminal raises the soft keyboard, which a finger switching tabs did not ask for; a
 * D-pad without a keyboard finds its way in through the platform's own focus search on its first press.
 */
@Composable
fun KeepStageFocus(focus: StageFocus, enabled: Boolean = rememberHardwareKeyboardAttached()) {
    if (!enabled) return
    val window = LocalWindowFocus.current
    LaunchedEffect(focus, window) {
        snapshotFlow { focus.region }.collect { region ->
            if (region != null) {
                focus.last = region
                return@collect
            }
            // The frame that took the control away places what replaces it; the focus goes there next.
            withFrameNanos {}
            if (window?.held == true) return@collect
            if (focus.region == null) focus.restore()
        }
    }
}
