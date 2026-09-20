package app.berth.android.ui.keyboard

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * What a Stage laid out in panes (spec C23, `PaneStageScreen`) does for a hardware keyboard,
 * handed down to the Stage's chord dispatcher without the Stage knowing how the panes are built. A
 * one-pane Stage, which is every phone in portrait, provides the empty value: the chords are still
 * taken (a bound chord never reaches the shell, whatever the width, so `Ctrl+Shift+D` does not
 * turn into a control character on a phone) and do nothing. The pane layer provides both while it
 * is on screen: [split] is what Overflow's Split or Unsplit row does, the Stage into two panes or
 * back to one; [focusOtherPane] moves the keyboard to the other pane through the same path a touch
 * on it takes, so the pane the header marks focused, the Deck under it and the canvas the keys go
 * to agree. A canvas takes Tab and the arrows for the shell, so the chord is the one way out of a
 * terminal into the pane beside it that does not go through a sheet.
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
