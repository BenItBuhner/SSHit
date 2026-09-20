package app.berth.android.ui.keyboard

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
    // How many of a region's nodes are on screen at all: a body coming on stage is seen here by
    // [KeepStageFocus], which has been waiting to put the focus in it.
    private val attached = mutableStateMapOf<StageRegion, Int>()

    /** The innermost region holding the focus; null while nothing on the Stage has it. */
    val region: StageRegion?
        get() = StageRegion.entries.lastOrNull { (holders[it] ?: 0) > 0 }

    /** Changes with every region node that comes on screen or leaves it; read to be told of one. */
    val attachments: Int
        get() = attached.values.sum()

    /**
     * How many times a region's node has left the screen with the focus in it: a tab switched or
     * closed under its terminal, the Deck folded under a key, the search closed under its field. The
     * focus system clears the focus then and gives it to the first control it finds, which no one
     * asked for; [KeepStageFocus] reads a change here as the word that what holds the focus next is
     * that placement, and puts the focus back where it was.
     */
    var removals: Int by mutableIntStateOf(0)
        private set

    /**
     * The region the user last put the focus in, by a chord or Escape ([focus]), by Tab or the arrows
     * into another region, or by a touch on a terminal; the body until then. [KeepStageFocus] keeps
     * the focus there: a control removed from under it has the focus put back into this region, or
     * the body when the region has nothing left to hold it, and a body coming on stage takes it if it
     * stands anywhere else meanwhile.
     */
    var want: StageRegion = StageRegion.Body
        internal set

    /** Whether the focus is in [want], or where the user moved it out of the Stage; false after a loss until it is. */
    internal var settled: Boolean = false

    // Whether what holds the focus next is the focus system's placement and not the user's move:
    // after a control was removed from under it, while nothing holds it, and from the start, until
    // a placement of the keeper's or the user's own settles it.
    private var placing = true

    // What [KeepStageFocus] saw last: the region, and the count of removals.
    private var seenRegion: StageRegion? = null
    private var seenRemovals = 0

    // The region whose node last reported losing the focus, with none reporting a gain since: the
    // clearing of the focus from a control on its way off the screen reaches the region's node
    // before the node's own leaving does, so the node cannot tell from what it holds then, and asks.
    private var lostLast: StageRegion? = null

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
     * The focus into [region] on purpose (a chord, Escape, the pane model): on its entry, or else on
     * its first control; that is then where the focus wants to be. False, and nothing changed, when
     * the region has no control to give it: a body not yet on stage, a Deck the tab's state has
     * taken down, a tab kind with no Deck, a bar that is not open.
     */
    fun focus(region: StageRegion): Boolean {
        if (!request(region)) return false
        want = region
        settled = true
        placing = false
        seenRegion = this.region
        return true
    }

    private fun request(region: StageRegion): Boolean =
        runCatching { entry(region).requestFocus() }.getOrDefault(false) || runCatching { requester(region).requestFocus() }.getOrDefault(false)

    /**
     * Reads what changed since the last look, for [KeepStageFocus]: a region node gone with the
     * focus in it ([removals]), or a window holding nothing, unsettles the focus, and what holds it
     * next is a placement, left to [settle]; a change of region otherwise is the user's move, into
     * another region, which is where the focus wants to be now, or out of the Stage to a control the
     * window still holds (the rail, a bar), where it stays. Nothing else changes anything.
     */
    internal fun observe(window: WindowFocus?) {
        val held = window?.held ?: true
        val now = region
        val moved = now != seenRegion
        seenRegion = now
        when {
            removals != seenRemovals -> {
                seenRemovals = removals
                settled = false
                placing = true
            }
            !held -> {
                settled = false
                placing = true
            }
            moved && !placing -> {
                if (now != null) want = now
                settled = true
            }
        }
    }

    /**
     * The focus back where it wants to be, for [KeepStageFocus]: left where it is when that is
     * [want] already (the placement, or the pane model, found the right region); else into [want],
     * else the body, which then is what it wants; failing both (no body on stage yet), onto the
     * strip so that a key is not lost, and still unsettled, so a body coming on stage takes it.
     */
    internal fun settle() {
        if (settled) return
        if (region == want) {
            settled = true
            placing = false
            return
        }
        for (target in listOf(want, StageRegion.Body).distinct()) {
            if (focus(target)) return
        }
        if (region == null) {
            request(StageRegion.Strip)
            seenRegion = region
        }
    }

    /** The keeper started (again): whatever holds the focus was not seen placed, and the next look verifies it. */
    internal fun unsettle() {
        settled = false
        placing = true
    }

    internal fun report(region: StageRegion, hasFocus: Boolean) {
        holders[region] = ((holders[region] ?: 0) + (if (hasFocus) 1 else -1)).coerceAtLeast(0)
        lostLast = if (hasFocus) null else region
    }

    internal fun attach(region: StageRegion, onScreen: Boolean) {
        attached[region] = ((attached[region] ?: 0) + (if (onScreen) 1 else -1)).coerceAtLeast(0)
    }

    /** A node of [region] leaving the screen, [holding] the focus or having just lost it to the leaving. */
    internal fun leaving(region: StageRegion, holding: Boolean) {
        if (!holding && lostLast != region) return
        lostLast = null
        removals++
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

    override fun onAttach() = focus.attach(region, true)

    override fun onDetach() {
        focus.leaving(region, holding)
        hold(false)
        focus.attach(region, false)
    }

    fun bind(focus: StageFocus, region: StageRegion) {
        if (focus === this.focus && region == this.region) return
        hold(false)
        if (isAttached) this.focus.attach(this.region, false)
        this.focus = focus
        this.region = region
        if (isAttached) focus.attach(region, true)
    }

    private fun hold(has: Boolean) {
        if (has == holding) return
        holding = has
        focus.report(region, has)
    }
}

/**
 * Keeps the keyboard's focus on the Stage while a hardware keyboard is attached, so no key is lost.
 * The focus is where the user last put it ([StageFocus.want]: the body until a chord, a key or a
 * touch moves it), and stays there as the controls under it come and go: when the control holding
 * it is removed (the tab on stage changes, the Deck hides or shows, the search closes, a tab is
 * closed), the focus system clears the focus and hands it to the first control it finds, the
 * strip's first tab or the rail's first row on a tablet, which no one asked for; the keeper is told
 * of the removal by the region's node on its way out ([StageFocus.removals]), reads what holds the
 * focus next as that placement, and puts the focus back into the region it was in, or into the
 * body, or onto the strip while nothing else is there. A shell tab coming on stage is typed into at
 * once, on a cold start as after a switch, whenever the focus stands on such a placement: a body
 * coming on screen wakes the keeper ([StageFocus.attachments]) and it takes the focus, the frame
 * after, once the body is laid out. A Deck hidden under a focused key hands the focus to the strip
 * that stands in for it, and that strip pressed hands it back to the Deck's first key, both by this
 * route. A focus that left the Stage for another control in the window, the rail on an expanded
 * width (spec A12) by Shift+Tab out of the strip, was moved and not lost, and stays there
 * ([WindowFocus.held]); a sheet is a window of its own, and the control that had the focus has it
 * still when the sheet closes. Only with a keyboard attached: focusing a shell tab's terminal
 * raises the soft keyboard, which a finger switching tabs did not ask for; a D-pad without a
 * keyboard finds its way in through the platform's own focus search on its first press.
 *
 * The window is the shell's ([LocalWindowFocus]) or, for a Stage composed alone, the Stage's own
 * root marked with [windowFocus]; with neither, a focus that left the Stage is taken as moved.
 */
@Composable
fun KeepStageFocus(focus: StageFocus, enabled: Boolean = rememberHardwareKeyboardAttached(), window: WindowFocus? = LocalWindowFocus.current) {
    if (!enabled) return
    LaunchedEffect(focus, window) {
        // A keyboard attached, now or from the start: whatever holds the focus was not placed under the keeper's eye.
        focus.unsettle()
        // Woken by a change of region, a removal, the window taking or dropping the focus, or a region
        // node coming or going; what is read is the state now, never the emission, which the next
        // change may have overtaken.
        snapshotFlow { listOf(focus.region?.ordinal, window?.held, focus.removals, focus.attachments) }.collect {
            focus.observe(window)
            if (focus.settled) return@collect
            // The frame that removed a control places what replaces it, and lays out a body that came.
            withFrameNanos {}
            focus.observe(window)
            focus.settle()
        }
    }
}
