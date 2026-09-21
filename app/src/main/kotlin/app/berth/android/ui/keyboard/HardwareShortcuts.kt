package app.berth.android.ui.keyboard

import androidx.compose.ui.input.key.KeyEvent
import app.berth.android.ui.tabs.TabShortcuts
import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordConflict
import app.berth.domain.model.ChordKey
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable

/** What the Stage does for the app's chords (spec C22), the strip's four included; the keys are the [ChordTable]'s. */
interface StageShortcutActions {
    /** [ChordAction.NEW_TAB], and plain Ctrl+T while the strip has it: the New tab sheet. */
    fun newTab()

    /** [ChordAction.CLOSE_TAB], and plain Ctrl+W while the strip has it: the active tab closes. */
    fun closeTab()

    /** [ChordAction.TAB_SWITCHER]: the switcher sheet. */
    fun tabSwitcher()

    /** [ChordAction.JUMP_TO_UNREAD]: the most recent tab that needs the user comes on stage. */
    fun jumpToUnread()

    /** [ChordAction.FIND]: the search bar over the scrollback (spec C17); plain Ctrl+F is readline's forward-char and the shell's. */
    fun find()

    /** [ChordAction.COPY]: the selection to the Berth clipboard; nothing selected, nothing copied. */
    fun copy()

    /** [ChordAction.PASTE]: the clipboard into the terminal, through the paste preview (spec C18). */
    fun paste()

    /** [ChordAction.TOGGLE_DECK]: show or hide the Deck. */
    fun toggleDeck()

    /** [ChordAction.FONT_LARGER] and [ChordAction.FONT_SMALLER]: the terminal font one step up or down. */
    fun fontStep(step: Int)

    /** [ChordAction.SHORTCUT_SHEET]: the shortcut sheet. */
    fun shortcutSheet()

    /** [ChordAction.SPLIT]: the Stage into two panes, or back to one (spec C22, medium and expanded widths). */
    fun split()

    /**
     * [ChordAction.FOCUS_OTHER_PANE]: the keyboard to the other pane. `O` is tmux's `select-pane`
     * key, and the arrows and Tab are the shell's, so a chord is the one way out of a canvas that
     * does not go through a sheet.
     */
    fun focusOtherPane()

    /** [ChordAction.FOCUS_STRIP]: the keyboard's focus to the tab strip (spec A11), where Tab and the arrows walk the tabs. */
    fun focusStrip()

    /** [ChordAction.FOCUS_DECK]: the keyboard's focus to the Deck, entered at its grip; the collapsed strip when the Deck is hidden. */
    fun focusDeck()

    /**
     * Escape while the focus is in the strip or the Deck: back to the tab's body, the terminal on a
     * shell tab, closing the search if it was open. False when the body already holds the focus, so
     * that Escape reaches the host as its own.
     */
    fun returnToBody(): Boolean

    /**
     * Pass-through (spec C22, A46): while on, every key is the shell's but [ChordAction.PASS_THROUGH]
     * itself, which turns it off again. The Stage holds it, shows it in its chrome, and offers the
     * pill as the touch way out.
     */
    var passThrough: Boolean

    /**
     * The first plain Ctrl+W while it is the strip's (the settings say the hint has not been shown):
     * the one line that says Ctrl+W closes the tab here and offers the readline setting, shown in
     * place of the close, since a Ctrl+W from a shell's habits means delete-word and a live shell is
     * what it would cost. True when the hint stood and the close was held back this once; false with
     * nothing live on stage to hold it for (a Files tab, a detached one), and the close goes ahead.
     * Once per install: the Stage marks it seen.
     */
    fun ctrlWHint(): Boolean
}

/**
 * The Stage's hardware chords in one dispatcher, run before the terminal sees a key (spec C22). A
 * key event is read as a chord ([ChordReader]) and looked up in the [ChordTable] the settings make:
 * every app action under its prefix, Ctrl+Shift by default, or as the user rebound it. What no
 * action has falls to the strip's fixed keys ([TabShortcuts]; Ctrl+T and Ctrl+W while the readline
 * setting leaves them the strip's), then to a plain Escape while the focus is out of the tab's body
 * ([StageShortcutActions.returnToBody]), and everything else reaches the terminal: the plain Ctrl
 * keys are the shell's (Ctrl+F is readline's forward-char, every pager's forward key), and so is any
 * chord nobody bound. Two exceptions run the other way: a Leader chord nobody bound is still
 * swallowed, since the Leader is the key the app took for itself and the shell should never see
 * half of it (which is why the Leader is Right Ctrl unless chosen otherwise: Right Alt types on an
 * AltGr layout, [app.berth.domain.model.LeaderKey]); and in pass-through every key reaches the
 * terminal (the Leader's own included) but the chord that ends it. The first plain Ctrl+W of an
 * install is held back for its hint ([StageShortcutActions.ctrlWHint]); every one after closes the tab.
 */
class HardwareShortcuts(
    private val tabs: TabShortcuts,
    val stage: StageShortcutActions,
    private val reader: ChordReader = ChordReader(),
) {
    fun handle(event: KeyEvent, table: ChordTable): Boolean {
        val settings = table.settings
        val leaderKey = settings.leaderKey.takeIf { settings.chordPrefix == ChordPrefix.LEADER }
        val read = when (val read = reader.read(event, leaderKey)) {
            ChordRead.Consumed -> return !stage.passThrough
            ChordRead.Ignored -> return false
            is ChordRead.Chord -> read
        }
        val action = read.candidates.firstNotNullOfOrNull(table::actionFor)
        if (stage.passThrough) {
            if (action != ChordAction.PASS_THROUGH) return false
            stage.passThrough = false
            return true
        }
        if (action != null) {
            perform(action)
            return true
        }
        if (read.leader) return true
        if (tabs.handle(event)) return true
        val chord = read.chord
        if (chord.ctrlOnly && !table.ctrlTabKeysReachTerminal) {
            when (chord.key) {
                "T" -> { stage.newTab(); return true }
                "W" -> {
                    if (settings.ctrlWHintSeen || !stage.ctrlWHint()) stage.closeTab()
                    return true
                }
            }
        }
        if (chord.key == "ESCAPE" && !chord.hasModifier && !chord.shift) return stage.returnToBody()
        return false
    }

    private fun perform(action: ChordAction) {
        when (action) {
            ChordAction.NEW_TAB -> stage.newTab()
            ChordAction.CLOSE_TAB -> stage.closeTab()
            ChordAction.TAB_SWITCHER -> stage.tabSwitcher()
            ChordAction.JUMP_TO_UNREAD -> stage.jumpToUnread()
            ChordAction.FIND -> stage.find()
            ChordAction.COPY -> stage.copy()
            ChordAction.PASTE -> stage.paste()
            ChordAction.TOGGLE_DECK -> stage.toggleDeck()
            ChordAction.FONT_LARGER -> stage.fontStep(1)
            ChordAction.FONT_SMALLER -> stage.fontStep(-1)
            ChordAction.SPLIT -> stage.split()
            ChordAction.FOCUS_OTHER_PANE -> stage.focusOtherPane()
            ChordAction.FOCUS_STRIP -> stage.focusStrip()
            ChordAction.FOCUS_DECK -> stage.focusDeck()
            ChordAction.PASS_THROUGH -> stage.passThrough = true
            ChordAction.SHORTCUT_SHEET -> stage.shortcutSheet()
        }
    }
}

/**
 * One line of the shortcut sheet: the keys as shown and what they do. A row with a [chord] is one of
 * the app's actions and can be rebound; [remapped] says it has been, [default] is the chord it had
 * under the prefix, and [takes] names what the shell loses to the chord it has now, when it loses
 * anything (a remap to readline's Ctrl+F: `readline's forward-char`).
 */
data class ShortcutEntry(
    val keys: String,
    val action: String,
    val chord: ChordAction? = null,
    val remapped: Boolean = false,
    val default: String? = null,
    val takes: String? = null,
)

/** A titled run of the shortcut sheet, with a [note] under the rows for what is said once rather than per row. */
data class ShortcutGroup(val title: String, val entries: List<ShortcutEntry>, val note: String? = null)

/** The sheet's title for [action]; the pane chords say more through [shortcutGroups]'s [panes]. */
fun chordTitle(action: ChordAction): String = when (action) {
    ChordAction.NEW_TAB -> "New tab"
    ChordAction.CLOSE_TAB -> "Close tab"
    ChordAction.TAB_SWITCHER -> "Tab switcher"
    ChordAction.JUMP_TO_UNREAD -> "Jump to the tab that needs you"
    ChordAction.FIND -> "Find in scrollback"
    ChordAction.COPY -> "Copy the selection"
    ChordAction.PASTE -> "Paste"
    ChordAction.TOGGLE_DECK -> "Show or hide the Deck"
    ChordAction.FONT_LARGER -> "Font larger"
    ChordAction.FONT_SMALLER -> "Font smaller"
    ChordAction.SPLIT -> "Split the Stage"
    ChordAction.FOCUS_OTHER_PANE -> "Focus the other pane"
    ChordAction.FOCUS_STRIP -> "Focus the tab strip"
    ChordAction.FOCUS_DECK -> "Focus the Deck"
    ChordAction.PASS_THROUGH -> "Pass-through to the shell"
    ChordAction.SHORTCUT_SHEET -> "This sheet"
}

/** `Ctrl+Shift`, `the Leader, Right Ctrl`: the prefix as the sheet names it in a sentence. */
fun ChordTable.prefixPhrase(): String = when (settings.chordPrefix) {
    ChordPrefix.CTRL_SHIFT -> "Ctrl+Shift"
    ChordPrefix.LEADER -> "the Leader, ${settings.leaderKey.label()}, held with the key or tapped before it"
}

/**
 * Why the sheet refuses [chord] for an action, or what taking it costs, as the line under the row
 * (spec C22, conflict detection): [ChordConflict.Shell] is allowed and the line is a warning.
 */
fun conflictText(conflict: ChordConflict, chord: ChordKey): String = when (conflict) {
    ChordConflict.Typing -> "${chord.label()} alone is typing. Hold Ctrl or Alt with it."
    is ChordConflict.Taken -> "${chord.label()} is taken: ${chordTitle(conflict.by)}."
    is ChordConflict.Strip -> "${chord.label()} is the strip\u2019s: ${conflict.what}."
    is ChordConflict.System -> "${chord.label()} is Android\u2019s: ${conflict.what}."
    is ChordConflict.Signal -> "${chord.label()} is ${conflict.what}; the shell keeps it."
    is ChordConflict.Shell -> "Takes ${conflict.what} from the shell."
}

/** The sheet's separator between two chords on one row (`Ctrl+T · Ctrl+Shift+T`); a reader hears "or". */
const val CHORD_SEPARATOR = " \u00B7 "

/**
 * What the shortcut sheet lists (spec C22), as the dispatcher above and the terminal actually
 * behave, from the [table] the settings make: the strip's fixed keys, then every app action under
 * its chord, rebound or not, one row per action. New tab and Close tab carry the plain Ctrl+T and
 * Ctrl+W beside their chords while those are the strip's. [panes] says whether the Stage on screen
 * has panes for the pane chords to act on, since the sheet lists them either way (they are taken
 * either way) and says when they wait for a wider screen.
 */
fun shortcutGroups(table: ChordTable, panes: Boolean = false): List<ShortcutGroup> {
    val settings = table.settings
    fun entry(action: ChordAction, title: String = chordTitle(action), also: String? = null): ShortcutEntry {
        val chord = table.chord(action)
        return ShortcutEntry(
            keys = listOfNotNull(also, chord.label()).joinToString(CHORD_SEPARATOR),
            action = title,
            chord = action,
            remapped = table.isRemapped(action),
            default = settings.chordPrefix.chord(action.defaultKey).label(),
            takes = (table.conflict(action, chord) as? ChordConflict.Shell)?.what,
        )
    }
    val readline = table.ctrlTabKeysReachTerminal
    val tabChords = listOf(
        ShortcutEntry("Ctrl+Tab", "Next tab"),
        ShortcutEntry("Ctrl+Shift+Tab", "Previous tab"),
        ShortcutEntry("Ctrl+1 \u2026 Ctrl+8", "Tab 1 to 8"),
        ShortcutEntry("Ctrl+9", "Last tab"),
        entry(ChordAction.NEW_TAB, also = "Ctrl+T".takeUnless { readline }),
        entry(ChordAction.CLOSE_TAB, also = "Ctrl+W".takeUnless { readline }),
        entry(ChordAction.TAB_SWITCHER),
        entry(ChordAction.JUMP_TO_UNREAD),
    )
    val stageChords = listOf(
        entry(ChordAction.FIND),
        entry(ChordAction.COPY),
        entry(ChordAction.PASTE),
        entry(ChordAction.TOGGLE_DECK),
        entry(ChordAction.FONT_LARGER),
        entry(ChordAction.FONT_SMALLER),
        entry(ChordAction.FOCUS_STRIP),
        entry(ChordAction.FOCUS_DECK),
        entry(ChordAction.PASS_THROUGH),
        entry(ChordAction.SHORTCUT_SHEET),
    )
    val paneChords = listOf(
        entry(ChordAction.SPLIT, if (panes) "Split the Stage in two, or back to one" else "Split the Stage, on a wide screen"),
        entry(ChordAction.FOCUS_OTHER_PANE, if (panes) "Focus the other pane" else "Focus the other pane, when the Stage is split"),
    )
    val terminalKeys = buildList {
        add(ShortcutEntry("Ctrl+letter", "Control characters, ^C to ^Z"))
        add(ShortcutEntry("Alt+key", "Meta: Escape then the key, or the eighth bit"))
        add(ShortcutEntry("Shift, Ctrl, Alt + arrows", "Modified arrows, Home, End, Page Up and Down"))
        add(ShortcutEntry("F1 \u2026 F12", "Function keys, with any modifier"))
        add(ShortcutEntry("Esc, Tab, Insert, Delete", "As on the host"))
        if (readline) add(ShortcutEntry("Ctrl+T, Ctrl+W", "Readline\u2019s transpose and delete word"))
    }
    val leaderNote = if (settings.chordPrefix == ChordPrefix.LEADER) " The Leader, ${settings.leaderKey.label()}, never does: it is the app\u2019s." else ""
    return listOf(
        // The fixed rows look like the rows a tap rebinds, so the note says which are the strip's own.
        ShortcutGroup(
            "Tabs",
            tabChords,
            note = if (readline) "Ctrl+Tab and Ctrl+1\u20269 are the strip\u2019s own. Ctrl+T and Ctrl+W are the shell\u2019s (Settings \u203A Hardware keyboard); the chords open and close tabs."
            else "Ctrl+Tab and Ctrl+1\u20269 are the strip\u2019s own. Settings \u203A Hardware keyboard hands Ctrl+T and Ctrl+W to the shell; the chords still open and close tabs.",
        ),
        // The walking rule once, under the rows, rather than in each focus row's action; Esc is part
        // of it (it is Esc in the terminal, back to the terminal only from what the chords focus), so
        // it is said here and is no row of the app's chords.
        ShortcutGroup("Stage", stageChords, note = "Tab and the arrows walk the strip and the Deck, Enter presses; Esc from them or the search is back to the terminal. Pass-through sends every key to the shell, the chords too, until its chord again or a tap on its pill."),
        ShortcutGroup("Panes", paneChords),
        ShortcutGroup("Terminal", terminalKeys, note = "Which Alt is per host: Settings \u203A Hardware keyboard. Unbound combinations always reach the terminal.$leaderNote"),
    )
}
