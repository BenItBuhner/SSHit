package app.berth.android.ui.stage

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.berth.domain.model.HapticLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The haptic level in force (D3), provided at the app root from the Settings preference. */
val LocalHapticLevel = compositionLocalOf { HapticLevel.FULL }

/**
 * The haptic vocabulary of the Stage (UX spec D3), one instance per Deck. Every pattern is a
 * platform constant so the device's own tuning applies; the only timing here is the 40 ms gap of
 * the one-shot double tick and the 20 per second ceiling on Nub steps.
 */
class DeckHaptics(
    private val haptics: HapticFeedback,
    private val scope: CoroutineScope,
    val level: HapticLevel = HapticLevel.FULL,
) {
    private var lastNubTick = 0L

    /** Deck key tap: `KEYBOARD_TAP`. */
    fun keyTap() {
        if (level != HapticLevel.OFF) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    }

    /** A modifier key settled into [state]: one-shot is two light ticks 40 ms apart, lock one heavy tick, release one light tick. */
    fun modifier(state: LatchState) {
        when (level) {
            HapticLevel.OFF -> Unit
            HapticLevel.SUBTLE -> keyTap()
            HapticLevel.FULL -> when (state) {
                LatchState.ONE_SHOT -> scope.launch {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    delay(ONE_SHOT_GAP_MS)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                LatchState.LOCKED -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                LatchState.NONE -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

    /** One Nub step: a light tick, at most 20 per second. */
    fun nubStep() {
        if (level != HapticLevel.FULL) return
        val now = SystemClock.uptimeMillis()
        if (now - lastNubTick < NUB_TICK_MIN_GAP_MS) return
        lastNubTick = now
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    /** Hold on a key resolved to its hold action (the alternates strip): a heavy tick. */
    fun hold() {
        if (level == HapticLevel.FULL) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Autorepeat while a key is held: frequent ticks, same ceiling as the Nub. */
    fun repeatTick() = nubStep()

    /** Paste sent to the session: Confirm. */
    fun paste() {
        if (level == HapticLevel.FULL) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    /** One pinch step of terminal font size: a light tick. */
    fun fontStep() {
        if (level == HapticLevel.FULL) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    /** A dragged Deck key crossed a neighbour in the editor: a light tick. */
    fun reorderStep() {
        if (level == HapticLevel.FULL) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    /** Terminal bell while this session is on stage: a short buzz. */
    fun bell() {
        if (level != HapticLevel.OFF) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Hold-to-confirm completed: a heavy tick. */
    fun holdConfirmed() {
        if (level != HapticLevel.OFF) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    private companion object {
        const val ONE_SHOT_GAP_MS = 40L
        const val NUB_TICK_MIN_GAP_MS = 50L
    }
}

/** The Stage's [DeckHaptics] for [haptics], honouring [LocalHapticLevel]. */
@Composable
fun rememberDeckHaptics(haptics: HapticFeedback = LocalHapticFeedback.current): DeckHaptics {
    val scope = rememberCoroutineScope()
    val level = LocalHapticLevel.current
    return remember(haptics, level) { DeckHaptics(haptics, scope, level) }
}
