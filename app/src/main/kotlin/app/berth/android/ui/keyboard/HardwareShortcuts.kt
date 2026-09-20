package app.berth.android.ui.keyboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.berth.android.ui.tabs.TabShortcuts

/** What the Stage does for the chords beyond the tab strip's (spec C22). */
interface StageShortcutActions {
    /** Ctrl+F, Ctrl+Shift+F: the search bar over the scrollback (spec C17). */
    fun find()

    /** Ctrl+Shift+C: the selection to the Berth clipboard; nothing selected, nothing copied. */
    fun copy()

    /** Ctrl+Shift+V: the clipboard into the terminal, through the paste preview (spec C18). */
    fun paste()

    /** Ctrl+Shift+E: show or hide the Deck. */
    fun toggleDeck()

    /** Ctrl+Shift+= and Ctrl+Shift+−: the terminal font one step up or down. */
    fun fontStep(step: Int)

    /** Ctrl+Shift+/ (Ctrl+?): the shortcut sheet. */
    fun shortcutSheet()
}

/**
 * The Stage's hardware chords in one dispatcher, run before the terminal sees a key: the tab strip's
 * (spec C3, [TabShortcuts]) first, then search, copy and paste through the Berth clipboard, the Deck,
 * the font size and the shortcut sheet (spec C22). Ctrl+F is readline's forward-char, so like Ctrl+T
 * and Ctrl+W it yields to the terminal when [ctrlTabKeysReachTerminal] is on and Ctrl+Shift+F still
 * searches. Every chord is Ctrl with or without Shift; a chord Alt or Meta joins, and anything not
 * named here, reaches the terminal.
 */
class HardwareShortcuts(
    private val tabs: TabShortcuts,
    private val stage: StageShortcutActions,
) {
    fun handle(event: KeyEvent, ctrlTabKeysReachTerminal: Boolean): Boolean {
        if (tabs.handle(event, ctrlTabKeysReachTerminal)) return true
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shift = event.isShiftPressed
        return when (event.key) {
            Key.F -> if (shift || !ctrlTabKeysReachTerminal) { stage.find(); true } else false
            Key.C -> if (shift) { stage.copy(); true } else false
            Key.V -> if (shift) { stage.paste(); true } else false
            Key.E -> if (shift) { stage.toggleDeck(); true } else false
            // Ctrl+Shift+= is Ctrl and the plus sign on a US layout; keyboards with a plus key of their own send it directly.
            Key.Equals -> if (shift) { stage.fontStep(1); true } else false
            Key.Plus, Key.NumPadAdd -> { stage.fontStep(1); true }
            Key.Minus -> if (shift) { stage.fontStep(-1); true } else false
            Key.NumPadSubtract -> { stage.fontStep(-1); true }
            Key.Slash -> if (shift) { stage.shortcutSheet(); true } else false
            else -> false
        }
    }
}

/** One line of the shortcut sheet: the keys as shown and what they do. */
data class ShortcutEntry(val keys: String, val action: String)

/** A titled run of the shortcut sheet. */
data class ShortcutGroup(val title: String, val entries: List<ShortcutEntry>)

/**
 * What the shortcut sheet lists (spec C22), as the dispatchers above and the terminal actually
 * behave; [ctrlTabKeysReachTerminal] moves Ctrl+T, Ctrl+W and Ctrl+F to the terminal's side.
 */
fun shortcutGroups(ctrlTabKeysReachTerminal: Boolean): List<ShortcutGroup> {
    val tabChords = buildList {
        add(ShortcutEntry("Ctrl+Tab", "Next tab"))
        add(ShortcutEntry("Ctrl+Shift+Tab", "Previous tab"))
        add(ShortcutEntry("Ctrl+1 \u2026 Ctrl+8", "Tab 1 to 8"))
        add(ShortcutEntry("Ctrl+9", "Last tab"))
        if (ctrlTabKeysReachTerminal) {
            add(ShortcutEntry("Ctrl+Shift+T", "New tab"))
            add(ShortcutEntry("Ctrl+Shift+W", "Close tab"))
        } else {
            add(ShortcutEntry("Ctrl+T", "New tab"))
            add(ShortcutEntry("Ctrl+W", "Close tab"))
        }
        add(ShortcutEntry("Ctrl+Shift+A", "Tab switcher"))
        add(ShortcutEntry("Ctrl+Shift+U", "Jump to the tab that needs you"))
    }
    val stageChords = listOf(
        ShortcutEntry(if (ctrlTabKeysReachTerminal) "Ctrl+Shift+F" else "Ctrl+F", "Find in scrollback"),
        ShortcutEntry("Ctrl+Shift+C", "Copy the selection"),
        ShortcutEntry("Ctrl+Shift+V", "Paste"),
        ShortcutEntry("Ctrl+Shift+E", "Show or hide the Deck"),
        ShortcutEntry("Ctrl+Shift+=  \u00B7  Ctrl+Shift+\u2212", "Font size"),
        ShortcutEntry("Ctrl+Shift+/", "This sheet"),
    )
    val terminalKeys = buildList {
        add(ShortcutEntry("Ctrl+letter", "Control characters, ^C to ^Z"))
        add(ShortcutEntry("Alt+key", "Meta: Escape then the key, or the eighth bit (Settings \u203A Hardware keyboard)"))
        add(ShortcutEntry("Shift, Ctrl, Alt + arrows", "Modified arrows, Home, End, Page Up and Down"))
        add(ShortcutEntry("F1 \u2026 F12", "Function keys, with any modifier"))
        add(ShortcutEntry("Esc, Tab, Insert, Delete", "As on the host"))
        if (ctrlTabKeysReachTerminal) add(ShortcutEntry("Ctrl+T, Ctrl+W, Ctrl+F", "Readline's transpose, delete word and forward"))
    }
    return listOf(
        ShortcutGroup("Tabs", tabChords),
        ShortcutGroup("Stage", stageChords),
        ShortcutGroup("Terminal", terminalKeys),
    )
}
