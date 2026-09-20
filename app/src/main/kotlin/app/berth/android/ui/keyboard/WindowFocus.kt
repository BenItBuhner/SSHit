package app.berth.android.ui.keyboard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * What the window's root sees of the keyboard's focus: whether any control in the window holds it.
 * The Stage keeps the focus on itself while a keyboard is attached ([KeepStageFocus]), and this is
 * how it tells a focus that left the Stage for the rail beside it (spec A12), or for a bar laid over
 * it, which the window still holds and which stays where the user put it, from a focus that nothing
 * holds: at the start, before anything has taken it, or after a control cleared it outright. A
 * sheet is a window of its own and takes nothing from this one: the control that had the focus
 * keeps it, and has it again when the sheet closes.
 *
 * Read only as it stands when the keeper looks: a focus moving from one control to another passes
 * through a moment in which the window holds nothing, in the same call that then grants it to the
 * next control, and that moment is never seen from a snapshot, which is why it is a value and not a
 * count. The shell marks its root with [windowFocus] and provides the value through
 * [LocalWindowFocus]; a Stage composed alone marks its own root and stands in for the window.
 */
@Stable
class WindowFocus {
    var held: Boolean by mutableStateOf(false)
        internal set
}

val LocalWindowFocus = compositionLocalOf<WindowFocus?> { null }

/** Marks the window's root: [focus] holds while any control under this node has the focus. */
fun Modifier.windowFocus(focus: WindowFocus): Modifier = onFocusChanged { focus.held = it.hasFocus }
