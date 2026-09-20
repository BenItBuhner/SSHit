package app.berth.android.ui.keyboard

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * What a Stage laid out in panes (spec C23) can do for a hardware keyboard, handed down to the
 * Stage's chord dispatcher without the Stage knowing how the panes are built. A one-pane Stage,
 * which is every phone in portrait, provides the empty value: the chords are still taken (a bound
 * chord never reaches the shell, whatever the width, so `Ctrl+Shift+D` does not turn into a
 * control character on a phone) and do nothing. The pane layer provides both when it is on screen:
 * [split] opens the Stage into two panes or, with two already open, is what the Session sheet's
 * Split does; [focusOtherPane] moves the keyboard to the other pane through the same path a touch
 * on it takes, so the pane the header marks focused, the Deck under it and the canvas the keys go
 * to agree, which is the point of a chord over a plain focus move (a Tab or D-pad press from one
 * canvas lands in the other's without telling the pane model, and the panes disagree; a canvas
 * takes those keys for the shell in any case).
 */
class PaneActions(
    val split: (() -> Unit)? = null,
    val focusOtherPane: (() -> Unit)? = null,
) {
    /** Whether the Stage on screen has panes to act on; the shortcut sheet says so beside the chords. */
    val available: Boolean get() = split != null || focusOtherPane != null

    companion object {
        /** A one-pane Stage. */
        val None: PaneActions = PaneActions()
    }
}

/** Provided by the pane layer over the Stage; the empty value everywhere else. */
val LocalPaneActions: ProvidableCompositionLocal<PaneActions> = compositionLocalOf { PaneActions.None }
