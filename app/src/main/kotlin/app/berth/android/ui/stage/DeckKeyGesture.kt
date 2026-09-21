package app.berth.android.ui.stage

import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import kotlin.math.abs

/**
 * The gesture on one Deck key (UX spec D2), as a machine fed the pointer's events and told when
 * its wait ran out, so what a finger means is decided in one place and can be driven without a
 * finger. [DeckKeyView] feeds it in the key's own coordinates and applies what it returns.
 *
 * A tap sends the primary. A swipe of [threshold] up sends the secondary and, with [swipeDown] on,
 * one down sends the tertiary; a vertical swipe is decided at release, so a finger can come back.
 * A swipe of [threshold] across the key, with [layerSwipe] on, steps the layer once, right away,
 * and the rest of the touch is spent. A finger still for [HoldMs] fires the key's hold action, or
 * autorepeats a repeating key on the Nub's cadence (180, 90, then 45 ms as the hold goes on, spec
 * C4), or raises the alternates as a popover: the finger slides onto one and lets go to send it,
 * or lets go anywhere else to send nothing. A finger that has moved past the slop is a swipe in
 * the making and never a hold.
 *
 * Time is the caller's: every event comes with its `now` in one time base (a pointer event's
 * uptime), [waitMs] says how long to wait from a `now` before calling [timeout], and a wait that
 * ran out moves the caller's clock by exactly the wait. A hold is measured from the finger's
 * arrival, so a finger that jitters inside the slop still holds.
 */
class DeckKeyGesture(
    val key: DeckKey,
    private val repeating: Boolean,
    private val swipeDown: Boolean,
    private val layerSwipe: Boolean,
    private val threshold: Float,
    private val slop: Float,
) {
    /** What one event came to; the caller sends, ticks or shows accordingly. */
    sealed interface Effect {
        /** Send [action] the way a tap sends: the key's own pattern follows. */
        data class Fire(val action: DeckAction) : Effect

        /** The key's hold action fired ([DeckKey.hold]). */
        data class Hold(val action: DeckAction) : Effect

        /** One autorepeat of the primary; [first] for the one the hold itself sends. */
        data class Repeat(val action: DeckAction, val first: Boolean) : Effect

        /** The alternates rose as a popover; [popover] holds them from here on. */
        data class Alternates(val actions: List<DeckAction>) : Effect

        /** The finger crossed the Deck: 1 for the next layer, -1 for the one before (spec D2: left previous, right next). */
        data class LayerStep(val direction: Int) : Effect
    }

    private enum class HoldKind { NONE, ACTION, REPEAT, POPOVER }

    /** The alternates a hold raises: the secondary, then the tertiary, whatever the swipe settings. */
    val alternates: List<DeckAction> = listOfNotNull(key.up, key.down)

    private val holds: HoldKind = when {
        key.hold != null -> HoldKind.ACTION
        repeating -> HoldKind.REPEAT
        alternates.isNotEmpty() -> HoldKind.POPOVER
        else -> HoldKind.NONE
    }

    /** The vertical swipe in progress, for the key's preview: 1 up, -1 down, 0 none. */
    var swipe: Int = 0
        private set

    /** The popover's chips while it is up. */
    var popover: List<DeckAction>? = null
        private set

    /** The chip the finger is over, or null for none (a release then sends nothing). */
    var hovered: Int? = null
        private set

    /** Nothing more will come of this touch: a layer step fired, or the finger lifted. */
    var done: Boolean = false
        private set

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var holdFired = false
    private var repeats = 0
    private var deadline: Long? = null
    private var chipsLeft = 0f
    private var chipWidth = 0f

    /** The finger came down at ([x], [y]) at [now]. */
    fun down(x: Float, y: Float, now: Long) {
        downX = x
        downY = y
        deadline = if (holds != HoldKind.NONE) now + HoldMs else null
    }

    /** How long to wait from [now] before [timeout], or null to wait for the finger alone. */
    fun waitMs(now: Long): Long? = deadline?.let { (it - now).coerceAtLeast(0L) }

    /** Where the popover's chips are, in the key's coordinates: the first begins at [left], each [width] wide. */
    fun chipsAt(left: Float, width: Float) {
        chipsLeft = left
        chipWidth = width
    }

    /** The finger is at ([x], [y]). */
    fun move(x: Float, y: Float): Effect? {
        if (done) return null
        val dx = x - downX
        val dy = y - downY
        if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
            moved = true
            // A finger on the move is never a hold; a hold already fired keeps its cadence.
            if (!holdFired) deadline = null
        }
        val chips = popover
        if (chips != null) {
            hovered = if (moved && chipWidth > 0f && x >= chipsLeft) ((x - chipsLeft) / chipWidth).toInt().takeIf { it < chips.size } else null
            return null
        }
        if (holdFired) return null
        if (layerSwipe && abs(dx) >= threshold && abs(dx) > abs(dy)) {
            done = true
            swipe = 0
            return Effect.LayerStep(if (dx > 0) 1 else -1)
        }
        swipe = when {
            dy < -threshold -> 1
            dy > threshold && swipeDown -> -1
            else -> 0
        }
        return null
    }

    /** The finger lifted: the swipe or the tap it came to, the chip it rests on, or nothing after a hold. */
    fun up(): Effect? {
        if (done) return null
        done = true
        val released = swipe
        swipe = 0
        deadline = null
        val chips = popover
        if (chips != null) return hovered?.let { Effect.Fire(chips[it]) }
        if (holdFired) return null
        val action = when (released) {
            1 -> key.up ?: key.tap
            -1 -> key.down ?: key.tap
            else -> key.tap
        }
        return action?.let { Effect.Fire(it) }
    }

    /** The wait [waitMs] gave ran out. */
    fun timeout(): Effect? {
        val due = deadline ?: return null
        if (done) return null
        if (!holdFired) {
            holdFired = true
            return when (holds) {
                HoldKind.ACTION -> {
                    deadline = null
                    key.hold?.let { Effect.Hold(it) }
                }
                HoldKind.REPEAT -> {
                    repeats = 1
                    deadline = due + repeatIntervalMs(repeats)
                    key.tap?.let { Effect.Repeat(it, first = true) }
                }
                HoldKind.POPOVER -> {
                    deadline = null
                    popover = alternates
                    Effect.Alternates(alternates)
                }
                HoldKind.NONE -> {
                    deadline = null
                    null
                }
            }
        }
        if (holds != HoldKind.REPEAT) {
            deadline = null
            return null
        }
        repeats++
        deadline = due + repeatIntervalMs(repeats)
        return key.tap?.let { Effect.Repeat(it, first = false) }
    }

    companion object {
        /** A hold is the spec's long-press: 400 ms (spec C3, D2). */
        const val HoldMs = 400L

        /**
         * The Nub's rings as a cadence (spec C4): the first repeats at 180 ms, then 90, then 45 as the
         * hold goes on, so a held Backspace clears a word before it runs and runs before it is tiresome.
         */
        fun repeatIntervalMs(repeat: Int): Long = when {
            repeat <= SlowRepeats -> 180L
            repeat <= SlowRepeats + MediumRepeats -> 90L
            else -> 45L
        }

        private const val SlowRepeats = 4
        private const val MediumRepeats = 8
    }
}
