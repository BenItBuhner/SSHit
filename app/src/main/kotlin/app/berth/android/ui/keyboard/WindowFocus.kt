package app.berth.android.ui.keyboard

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Whether the keyboard's focus is anywhere in the window's content. The Stage keeps the focus on
 * itself while a keyboard is attached ([KeepStageFocus]), putting it back when the control holding
 * it goes; but a focus that left for the rail beside the Stage (spec A12), or for a bar laid over
 * it, was moved, not lost, and stays where the user put it. The focus system tells a region only
 * that it no longer holds the focus, the same in both cases; the window's root tells them apart,
 * since it still holds the focus in the first. The shell marks its root with [windowFocus] and
 * provides the value through [LocalWindowFocus]; where nothing does (a Stage composed alone), the
 * Stage takes every departure for a loss, which on a phone it is.
 */
@Stable
class WindowFocus {
    var held: Boolean by mutableStateOf(false)
        internal set
}

val LocalWindowFocus = compositionLocalOf<WindowFocus?> { null }

/** Marks the window's root: [focus] holds while any control under this node has the focus. */
fun Modifier.windowFocus(focus: WindowFocus): Modifier = onFocusChanged { focus.held = it.hasFocus }
