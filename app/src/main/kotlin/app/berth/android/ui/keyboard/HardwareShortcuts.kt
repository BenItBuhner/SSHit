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
    /** Ctrl+Shift+F: the search bar over the scrollback (spec C17, C22); plain Ctrl+F is readline's forward-char and the shell's. */
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

    /** Ctrl+Shift+D: the Stage into two panes, or back to one (spec C22, medium and expanded widths; [PaneActions.split]). */
    fun split()

    /**
     * Ctrl+Shift+O: the keyboard to the other pane ([PaneActions.focusOtherPane]). `O` is tmux's
     * `select-pane` key, and the arrows and Tab are the shell's, so a chord is the one way out of a
     * canvas that does not go through a sheet.
     */
    fun focusOtherPane()

    /** Ctrl+Shift+S: the keyboard's focus to the tab strip ([StageFocus], spec A11), where Tab and the arrows walk the tabs. */
    fun focusStrip()

    /** Ctrl+Shift+K: the keyboard's focus to the Deck, entered at its grip, its keys pressed by Enter; the collapsed strip when the Deck is hidden. */
    fun focusDeck()

    /**
     * Escape while the focus is in the strip or the Deck: back to the tab's body, the terminal on a
     * shell tab, closing the search if it was open. False when the body already holds the focus, so
     * that Escape reaches the host as its own.
     */
    fun returnToBody(): Boolean
}

/**
 * The Stage's hardware chords in one dispatcher, run before the terminal sees a key: the tab strip's
 * (spec C3, [TabShortcuts]) first, then search, copy and paste through the Berth clipboard, the Deck,
 * the font size and the shortcut sheet (spec C22). Every one of the Stage's chords is Ctrl+Shift and
 * a key: the plain Ctrl keys are the shell's (Ctrl+F is readline's forward-char, every pager's
 * forward key), and the only plain Ctrl keys the app takes are the strip's, with Ctrl+T and Ctrl+W
 * handed back by [ctrlTabKeysReachTerminal]. A chord Alt or Meta joins, and anything not named here,
 * reaches the terminal. The one key taken without Ctrl is a plain Escape while the focus is out of
 * the tab's body, which brings it back ([StageShortcutActions.returnToBody]); from the terminal
 * itself Escape is the host's.
 */
class HardwareShortcuts(
    private val tabs: TabShortcuts,
    private val stage: StageShortcutActions,
) {
    fun handle(event: KeyEvent, ctrlTabKeysReachTerminal: Boolean): Boolean {
        if (tabs.handle(event, ctrlTabKeysReachTerminal)) return true
        if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isMetaPressed) return false
        if (event.key == Key.Escape) return !event.isCtrlPressed && !event.isShiftPressed && stage.returnToBody()
        if (!event.isCtrlPressed) return false
        val shift = event.isShiftPressed
        return when (event.key) {
            Key.F -> if (shift) { stage.find(); true } else false
            Key.C -> if (shift) { stage.copy(); true } else false
            Key.V -> if (shift) { stage.paste(); true } else false
            Key.E -> if (shift) { stage.toggleDeck(); true } else false
            // Ctrl+Shift+= is Ctrl and the plus sign on a US layout; keyboards with a plus key of their own send it directly.
            Key.Equals -> if (shift) { stage.fontStep(1); true } else false
            Key.Plus, Key.NumPadAdd -> { stage.fontStep(1); true }
            Key.Minus -> if (shift) { stage.fontStep(-1); true } else false
            Key.NumPadSubtract -> { stage.fontStep(-1); true }
            Key.Slash -> if (shift) { stage.shortcutSheet(); true } else false
            Key.D -> if (shift) { stage.split(); true } else false
            Key.O -> if (shift) { stage.focusOtherPane(); true } else false
            Key.S -> if (shift) { stage.focusStrip(); true } else false
            Key.K -> if (shift) { stage.focusDeck(); true } else false
            else -> false
        }
    }
}

/** One line of the shortcut sheet: the keys as shown and what they do. */
data class ShortcutEntry(val keys: String, val action: String)

/** A titled run of the shortcut sheet, with a [note] under the rows for what is said once rather than per row. */
data class ShortcutGroup(val title: String, val entries: List<ShortcutEntry>, val note: String? = null)

/**
 * What the shortcut sheet lists (spec C22), as the dispatchers above and the terminal actually
 * behave; [ctrlTabKeysReachTerminal] moves Ctrl+T and Ctrl+W to the terminal's side, and [panes]
 * says whether the Stage on screen has panes for the pane chords to act on, since the sheet lists
 * them either way (they are taken either way) and says when they wait for a wider screen.
 */
fun shortcutGroups(ctrlTabKeysReachTerminal: Boolean, panes: Boolean = false): List<ShortcutGroup> {
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
        ShortcutEntry("Ctrl+Shift+F", "Find in scrollback"),
        ShortcutEntry("Ctrl+Shift+C", "Copy the selection"),
        ShortcutEntry("Ctrl+Shift+V", "Paste"),
        ShortcutEntry("Ctrl+Shift+E", "Show or hide the Deck"),
        ShortcutEntry("Ctrl+Shift+=  \u00B7  Ctrl+Shift+\u2212", "Font size"),
        ShortcutEntry("Ctrl+Shift+S", "Focus the tab strip"),
        ShortcutEntry("Ctrl+Shift+K", "Focus the Deck"),
        ShortcutEntry("Esc", "Back to the terminal"),
        ShortcutEntry("Ctrl+Shift+/", "This sheet"),
    )
    val paneChords = listOf(
        ShortcutEntry("Ctrl+Shift+D", if (panes) "Split the Stage in two, or back to one" else "Split the Stage, on a wide screen"),
        ShortcutEntry("Ctrl+Shift+O", if (panes) "Focus the other pane" else "Focus the other pane, when the Stage is split"),
    )
    val terminalKeys = buildList {
        add(ShortcutEntry("Ctrl+letter", "Control characters, ^C to ^Z"))
        add(ShortcutEntry("Alt+key", "Meta: Escape then the key, or the eighth bit"))
        add(ShortcutEntry("Shift, Ctrl, Alt + arrows", "Modified arrows, Home, End, Page Up and Down"))
        add(ShortcutEntry("F1 \u2026 F12", "Function keys, with any modifier"))
        add(ShortcutEntry("Esc, Tab, Insert, Delete", "As on the host"))
        if (ctrlTabKeysReachTerminal) add(ShortcutEntry("Ctrl+T, Ctrl+W", "Readline's transpose and delete word"))
    }
    return listOf(
        ShortcutGroup("Tabs", tabChords),
        // The walking rule once, under the rows, rather than in each focus row's action.
        ShortcutGroup("Stage", stageChords, note = "On the strip and the Deck, Tab and the arrows walk, Enter presses. Esc from the strip, the Deck or the search is back to the terminal."),
        ShortcutGroup("Panes", paneChords),
        ShortcutGroup("Terminal", terminalKeys, note = "Which Alt is per host: Settings \u203A Hardware keyboard. Unbound combinations always reach the terminal."),
    )
}
