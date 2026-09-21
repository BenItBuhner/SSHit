package app.berth.domain.model

import kotlinx.serialization.Serializable

/**
 * What a hardware keyboard's Alt key does to a printable character (spec A11, C22).
 *
 * [ESC_PREFIX] sends Escape then the key, xterm's `metaSendsEscape`; every shell and editor reads
 * it as Meta, so it is the default. [META] sets the eighth bit of an ASCII character instead,
 * xterm's `eightBitInput`, for the few programs that want a real Meta byte; characters outside
 * ASCII, and every non-printing key, still take the Escape prefix and the CSI modifier parameter.
 */
@Serializable
enum class AltKeyMode { ESC_PREFIX, META }

/**
 * What every app chord starts with (spec C22, A44). [CTRL_SHIFT] is the default, since a plain Ctrl
 * key is the shell's; [META] is the keyboard's Cmd or Win key; [LEADER] is one key of the keyboard's
 * own the app takes for itself ([HardwareKeyboardSettings.leaderKey]), held with the key or tapped
 * before it the way a multiplexer's prefix is. The strip's browser conventions (Ctrl+Tab, Ctrl+1…9,
 * Ctrl+T, Ctrl+W) take no prefix and are untouched by this.
 */
@Serializable
enum class ChordPrefix {
    CTRL_SHIFT, META, LEADER;

    /** [key] under this prefix: the chord an action has until it is remapped. */
    fun chord(key: String): ChordKey = when (this) {
        CTRL_SHIFT -> ChordKey(key, ctrl = true, shift = true)
        META -> ChordKey(key, meta = true)
        LEADER -> ChordKey(key, leader = true)
    }
}

/**
 * The key that is the Leader under [ChordPrefix.LEADER]: a right-hand modifier, since the left ones
 * are how the shell gets Ctrl and Meta. Caps Lock is not offered: Android flips the lock itself
 * before an app sees the key.
 */
@Serializable
enum class LeaderKey { RIGHT_ALT, RIGHT_CTRL }

/**
 * Every chord the app takes under the prefix (spec C22's table), with the key each has until it is
 * remapped: the Stage's, the strip's four that are more than browser conventions, and pass-through.
 * Stored by name in a remap, so the names are part of what a saved settings document means.
 */
@Serializable
enum class ChordAction(val defaultKey: String) {
    NEW_TAB("T"),
    CLOSE_TAB("W"),
    TAB_SWITCHER("A"),
    JUMP_TO_UNREAD("U"),
    FIND("F"),
    COPY("C"),
    PASTE("V"),
    TOGGLE_DECK("E"),
    FONT_LARGER("EQUALS"),
    FONT_SMALLER("MINUS"),
    SPLIT("D"),
    FOCUS_OTHER_PANE("O"),
    FOCUS_STRIP("S"),
    FOCUS_DECK("K"),
    PASS_THROUGH("P"),
    SHORTCUT_SHEET("SLASH"),
}

/**
 * One chord as the keyboard sends it: a [key] by its name (a letter or digit as itself, else one of
 * [ChordKey.NAMED]: `SLASH`, `EQUALS`, `TAB`, `F5`, …) and the modifiers held with it. [leader] is the
 * Leader (spec C22) held or tapped before the key, in place of a modifier; a chord stored with it
 * is only ever pressed while the prefix is [ChordPrefix.LEADER].
 */
@Serializable
data class ChordKey(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val leader: Boolean = false,
) {
    /** Whether anything but Shift is held: a key with none of them is typing, not a chord. */
    val hasModifier: Boolean get() = ctrl || alt || meta || leader

    /** Ctrl alone, the form the shell reads as a control character. */
    val ctrlOnly: Boolean get() = ctrl && !shift && !alt && !meta && !leader

    /** `Ctrl+Shift+F`, `Meta+=`, `Leader F`, `Alt+←`: the sheet's mono column. */
    fun label(): String = buildString {
        if (leader) append("Leader ")
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Meta+")
        append(keyLabel(key))
    }

    companion object {
        /** The keys a chord names other than letters and digits, each with the label the sheet shows for it. */
        val NAMED: Map<String, String> = mapOf(
            "SLASH" to "/", "EQUALS" to "=", "MINUS" to "\u2212", "GRAVE" to "`", "BACKSLASH" to "\\",
            "PERIOD" to ".", "COMMA" to ",", "SEMICOLON" to ";", "APOSTROPHE" to "'",
            "LEFT_BRACKET" to "[", "RIGHT_BRACKET" to "]",
            "SPACE" to "Space", "TAB" to "Tab", "ENTER" to "Enter", "ESCAPE" to "Esc", "BACKSPACE" to "Backspace",
            "DELETE" to "Delete", "INSERT" to "Insert", "HOME" to "Home", "END" to "End",
            "PAGE_UP" to "PgUp", "PAGE_DOWN" to "PgDn",
            "UP" to "\u2191", "DOWN" to "\u2193", "LEFT" to "\u2190", "RIGHT" to "\u2192",
        ) + (1..12).associate { "F$it" to "F$it" }

        /** Whether [key] is a name a chord can be built on: a letter, a digit, or one of [NAMED]. */
        fun isKey(key: String): Boolean = key.length == 1 && (key[0] in 'A'..'Z' || key[0] in '0'..'9') || key in NAMED

        fun keyLabel(key: String): String = NAMED[key] ?: key
    }
}

/**
 * What the phone's volume buttons do while a shell tab is on stage (spec A43, C20 "Volume
 * buttons"). [OFF] leaves them the volume's, and so does any other screen; the other four send a
 * pair to the shell, up then down: the arrows, Page Up and Page Down, the font a step larger and
 * smaller, or Ctrl+C and Enter.
 */
@Serializable
enum class VolumeButtons { OFF, ARROWS, PAGES, FONT_SIZE, INTERRUPT_AND_ENTER }

/**
 * Settings › Hardware keyboard, and the phone's own buttons (spec C22, C20). One document, so a
 * change to any field lands atomically; a field a build does not know is read past.
 */
@Serializable
data class HardwareKeyboardSettings(
    /** The app-wide Alt behaviour, over which a host may say otherwise. */
    val altKey: AltKeyMode = AltKeyMode.ESC_PREFIX,
    /** Hosts whose Alt behaviour differs from [altKey]; a host that inherits is absent. */
    val altKeyByHost: Map<String, AltKeyMode> = emptyMap(),
    /**
     * A hardware keyboard folds the Deck to its strip (spec C4, "Hardware keyboard attached"); this
     * is what the strip opens to while one is attached: one row of the Deck's modifiers and app
     * actions, since the letters have a keyboard and Ctrl and Alt latches and Paste still earn their
     * place. Off, the strip opens to the whole Deck.
     */
    val compactDeck: Boolean = true,
    /** What every app chord starts with (spec C22). */
    val chordPrefix: ChordPrefix = ChordPrefix.CTRL_SHIFT,
    /** Which key is the Leader while [chordPrefix] is [ChordPrefix.LEADER]. */
    val leaderKey: LeaderKey = LeaderKey.RIGHT_ALT,
    /** The chords the user rebound, whole chords in place of the prefix and the action's key; an action at its default is absent. */
    val remaps: Map<ChordAction, ChordKey> = emptyMap(),
    /** Whether the one-time hint after a first Ctrl+W closed a tab has been shown (spec C22, the readline setting). */
    val ctrlWHintSeen: Boolean = false,
    /** What the volume buttons do on the Stage (spec A43). */
    val volumeButtons: VolumeButtons = VolumeButtons.OFF,
) {
    /** The host's own Alt behaviour, or null when it follows [altKey]. */
    fun altKeyOverride(hostId: String): AltKeyMode? = altKeyByHost[hostId]

    /** What Alt does for [hostId], after the host's override and the app-wide choice. */
    fun altKeyFor(hostId: String?): AltKeyMode = hostId?.let { altKeyByHost[it] } ?: altKey

    /** The document with [hostId]'s Alt behaviour set, or following the app-wide choice again when [mode] is null. */
    fun withHostAltKey(hostId: String, mode: AltKeyMode?): HardwareKeyboardSettings = copy(
        altKeyByHost = if (mode == null) altKeyByHost - hostId else altKeyByHost + (hostId to mode),
    )

    /** The document without [hostId]'s override, for a host that was deleted. */
    fun withoutHost(hostId: String): HardwareKeyboardSettings = copy(altKeyByHost = altKeyByHost - hostId)

    /** [action]'s chord: its remap, or its key under the prefix. */
    fun chordFor(action: ChordAction): ChordKey = remaps[action] ?: chordPrefix.chord(action.defaultKey)

    /** The document with [action] on [chord], or back at its default when [chord] is null (or is the default itself). */
    fun withRemap(action: ChordAction, chord: ChordKey?): HardwareKeyboardSettings = copy(
        remaps = if (chord == null || chord == chordPrefix.chord(action.defaultKey)) remaps - action else remaps + (action to chord),
    )

    /**
     * The document under [prefix]. Leaving the Leader drops the remaps that were Leader chords,
     * since no key sends one without it; every other remap is a whole chord and stays.
     */
    fun withChordPrefix(prefix: ChordPrefix): HardwareKeyboardSettings = copy(
        chordPrefix = prefix,
        remaps = if (prefix == ChordPrefix.LEADER) remaps else remaps.filterValues { !it.leader },
    )
}

/**
 * Why a chord cannot be an action's, or what taking it costs (spec C22, "conflict detection against
 * the shell-bound set"). [Taken], [Strip], [Signal] and [Typing] block; [Shell] is said and allowed,
 * since the readline keys are the user's to give up (the sheet already offers Ctrl+T and Ctrl+W).
 */
sealed interface ChordConflict {
    val blocks: Boolean

    /** The chord is another action's already. */
    data class Taken(val by: ChordAction) : ChordConflict {
        override val blocks: Boolean get() = true
    }

    /** A chord of the strip's that never reaches the shell: Ctrl+Tab, Ctrl+Shift+Tab, Ctrl+1…9, and Ctrl+T, Ctrl+W unless handed to the shell. */
    data class Strip(val what: String) : ChordConflict {
        override val blocks: Boolean get() = true
    }

    /** Ctrl+C, Ctrl+D, Ctrl+Z or Ctrl+\: a signal the shell must keep, or nothing that runs can be stopped. */
    data class Signal(val what: String) : ChordConflict {
        override val blocks: Boolean get() = true
    }

    /** No Ctrl, Alt, Meta or Leader on the key: typing, not a chord. */
    data object Typing : ChordConflict {
        override val blocks: Boolean get() = true
    }

    /** The shell has the chord: readline's key, a Meta key, a modified arrow or a function key. Taken here, the shell never sees it. */
    data class Shell(val what: String) : ChordConflict {
        override val blocks: Boolean get() = false
    }
}

/**
 * The app's chords as the dispatcher and the sheet read them: each action's chord under the
 * settings, the action a pressed chord is, and what a chord would take from the shell. Built once
 * per settings document; [actionFor] is a lookup over sixteen entries.
 */
class ChordTable(
    val settings: HardwareKeyboardSettings,
    /** Whether Ctrl+T and Ctrl+W are the shell's readline keys rather than the strip's (Settings › Hardware keyboard). */
    val ctrlTabKeysReachTerminal: Boolean = false,
) {
    val chords: Map<ChordAction, ChordKey> = ChordAction.entries.associateWith(settings::chordFor)

    fun chord(action: ChordAction): ChordKey = chords.getValue(action)

    /** The action [chord] is bound to, or null for a chord that is nobody's and reaches the terminal. */
    fun actionFor(chord: ChordKey): ChordAction? = chords.entries.firstOrNull { it.value == chord }?.key

    /** Whether [action] has left its default. */
    fun isRemapped(action: ChordAction): Boolean = action in settings.remaps

    /**
     * What binding [action] to [chord] would collide with, or null when the chord is free. Another
     * action's chord, the strip's, a signal and a bare key are refused; a key the shell has is named
     * and left to the user.
     */
    fun conflict(action: ChordAction, chord: ChordKey): ChordConflict? {
        if (!chord.hasModifier && chord.key !in FUNCTION_KEYS) return ChordConflict.Typing
        actionFor(chord)?.takeIf { it != action }?.let { return ChordConflict.Taken(it) }
        stripChord(chord)?.let { return ChordConflict.Strip(it) }
        if (chord.ctrlOnly) SIGNALS[chord.key]?.let { return ChordConflict.Signal(it) }
        return shellKey(chord)?.let(ChordConflict::Shell)
    }

    private fun stripChord(chord: ChordKey): String? {
        if (!chord.ctrl || chord.alt || chord.meta || chord.leader) return null
        return when {
            chord.key == "TAB" -> if (chord.shift) "Previous tab" else "Next tab"
            chord.shift -> null
            chord.key.length == 1 && chord.key[0] in '1'..'8' -> "Tab ${chord.key}"
            chord.key == "9" -> "Last tab"
            chord.key == "T" && !ctrlTabKeysReachTerminal -> "New tab"
            chord.key == "W" && !ctrlTabKeysReachTerminal -> "Close tab"
            else -> null
        }
    }

    /** The shell's claim on [chord] as one phrase (`readline's forward-char`), or null when it has none. */
    private fun shellKey(chord: ChordKey): String? = when {
        chord.leader || chord.meta -> null
        chord.key in FUNCTION_KEYS -> "the host's ${ChordKey.keyLabel(chord.key)}"
        chord.ctrl && chord.alt -> "Meta and a control character to the shell"
        chord.alt -> "Meta+${ChordKey.keyLabel(chord.key)} to the shell"
        chord.ctrlOnly -> READLINE[chord.key]?.let { "readline's $it" }
            ?: if (chord.key in MODIFIED_KEYS) "the shell's Ctrl+${ChordKey.keyLabel(chord.key)}" else null
        chord.ctrl && chord.shift && chord.key in MODIFIED_KEYS -> "the shell's Ctrl+Shift+${ChordKey.keyLabel(chord.key)}"
        else -> null
    }

    private companion object {
        val FUNCTION_KEYS: Set<String> = (1..12).mapTo(HashSet()) { "F$it" }

        /** The keys the terminal sends with a modifier parameter, so the shell reads each modified form as its own. */
        val MODIFIED_KEYS: Set<String> = setOf("UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "INSERT", "DELETE", "BACKSPACE", "ENTER", "TAB", "SPACE")

        /** The control characters a shell reads as signals; taking one leaves the user unable to stop what runs. */
        val SIGNALS: Map<String, String> = mapOf(
            "C" to "the shell's interrupt",
            "D" to "the shell's end-of-file",
            "Z" to "the shell's suspend",
            "BACKSLASH" to "the shell's quit",
        )

        /** Readline's default bindings for the plain Ctrl keys, as the shell has them on the default install. */
        val READLINE: Map<String, String> = mapOf(
            "A" to "beginning-of-line", "B" to "backward-char", "E" to "end-of-line", "F" to "forward-char",
            "G" to "abort", "H" to "backspace", "I" to "Tab", "J" to "Enter", "K" to "kill-line", "L" to "clear-screen",
            "M" to "Enter", "N" to "next-history", "O" to "operate-and-get-next", "P" to "previous-history",
            "Q" to "resume output", "R" to "reverse-search-history", "S" to "stop output", "T" to "transpose-chars",
            "U" to "unix-line-discard", "V" to "quoted-insert", "W" to "unix-word-rubout", "X" to "prefix",
            "Y" to "yank", "SPACE" to "set-mark", "SLASH" to "undo", "MINUS" to "undo",
            "LEFT_BRACKET" to "Escape", "RIGHT_BRACKET" to "character-search",
        )
    }
}
