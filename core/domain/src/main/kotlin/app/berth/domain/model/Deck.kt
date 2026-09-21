package app.berth.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Non-printing keys a Deck slot can send; names match the Berth Deck JSON vocabulary. */
enum class DeckKeyCode {
    ESC, TAB, ENTER, BACKSPACE, INS, DEL, HOME, END, PGUP, PGDN,
    UP, DOWN, LEFT, RIGHT,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

enum class DeckModifier { CTRL, ALT, SHIFT }

enum class DeckAppAction {
    HIDE_KEYBOARD, PASTE, TOGGLE_PREDICTIVE_TEXT, NEXT_SESSION, PREVIOUS_SESSION,
    SPLIT, DETACH, JUMP_TO_UNREAD, OPEN_SESSION_SHEET, NEXT_LAYER, PREVIOUS_LAYER, OPEN_DECK_EDITOR,
}

/**
 * What one gesture on a Deck key does. Modelled as data so layers are editable, importable and
 * exportable rather than hardcoded rows.
 */
@Serializable
sealed interface DeckAction {
    /** A non-printing key. */
    @Serializable @SerialName("key")
    data class Key(val key: DeckKeyCode) : DeckAction

    /** Arm or lock a modifier for the next character. */
    @Serializable @SerialName("modifier")
    data class Modifier(val modifier: DeckModifier) : DeckAction

    /** Literal text, sent as typed. */
    @Serializable @SerialName("text")
    data class Text(val text: String) : DeckAction

    /**
     * Modifiers plus a key or character, e.g. `CTRL c`, `SHIFT TAB`, written space-separated.
     * The last token is a [DeckKeyCode] name or a single character.
     */
    @Serializable @SerialName("combo")
    data class Combo(val combo: String) : DeckAction {
        val modifiers: Set<DeckModifier>
            get() = combo.trim().split(' ').dropLast(1).mapNotNull { runCatching { DeckModifier.valueOf(it.uppercase()) }.getOrNull() }.toSet()
        val target: String get() = combo.trim().substringAfterLast(' ')
    }

    /**
     * A tmux-style macro: space-separated tokens where `PREFIX` expands to the layer's prefix and
     * any other token is sent as a [Combo] target.
     */
    @Serializable @SerialName("macro")
    data class Macro(val macro: String) : DeckAction

    @Serializable @SerialName("snippet")
    data class Snippet(val snippetId: String) : DeckAction

    @Serializable @SerialName("app")
    data class App(val action: DeckAppAction) : DeckAction

    /** A horizontally scrollable strip of keys shown on hold (F1..F12). */
    @Serializable @SerialName("strip")
    data class Strip(val strip: List<DeckKeyCode>) : DeckAction
}

/** One slot on a Deck row. Exactly one of the special roles ([nub], [snippets], [layerKey]) or the actions apply. */
@Serializable
data class DeckKey(
    val tap: DeckAction? = null,
    val up: DeckAction? = null,
    val down: DeckAction? = null,
    val hold: DeckAction? = null,
    /** Label override; derived from [tap] when null. */
    val display: String? = null,
    /** The circular arrow key. */
    val nub: Boolean = false,
    /** Expands to the pinned snippet chips. */
    val snippets: Boolean = false,
) {
    val label: String
        get() = display ?: when (val t = tap) {
            is DeckAction.Key -> keyLabel(t.key)
            is DeckAction.Modifier -> t.modifier.name.lowercase().replaceFirstChar { it.uppercase() }
            is DeckAction.Text -> t.text
            is DeckAction.Combo -> t.combo
            is DeckAction.Macro -> t.macro
            is DeckAction.Snippet -> "snippet"
            is DeckAction.App -> t.action.name.lowercase().replace('_', ' ')
            is DeckAction.Strip -> "Fn"
            null -> if (nub) "Nub" else if (snippets) "Snippets" else ""
        }

    /** The swipe-up alternate's glyph, at the key's top right (UX spec C4). */
    val secondaryLabel: String? get() = up?.alternateLabel()

    /** The swipe-down alternate's glyph, at the key's bottom right while swipe down is on (UX spec D2). */
    val tertiaryLabel: String? get() = down?.alternateLabel()

    companion object {
        /** An alternate as its key shows it: `^C`, `S-Tab`, `M-x`, `Pfx d`; a snippet or app action is `...`. */
        fun DeckAction.alternateLabel(): String = when (this) {
            is DeckAction.Key -> keyLabel(key)
            is DeckAction.Text -> text
            is DeckAction.Combo -> combo.replace("CTRL ", "^").replace("SHIFT TAB", "S-Tab").replace("ALT ", "M-")
                // A control chord on a letter reads as `^C` (UX spec C4) whatever case the target was
                // typed in; Meta chords keep the letter as typed, because `M-x` and `M-X` are different chords.
                .let { if (it.length == 2 && it[0] == '^' && it[1].isLetter()) it.uppercase() else it }
            is DeckAction.Macro -> macro.replace("PREFIX", "Pfx")
            else -> "..."
        }

        fun keyLabel(key: DeckKeyCode): String = when (key) {
            DeckKeyCode.ESC -> "Esc"
            DeckKeyCode.TAB -> "Tab"
            DeckKeyCode.ENTER -> "Enter"
            DeckKeyCode.BACKSPACE -> "Bksp"
            DeckKeyCode.INS -> "Ins"
            DeckKeyCode.DEL -> "Del"
            DeckKeyCode.HOME -> "Home"
            DeckKeyCode.END -> "End"
            DeckKeyCode.PGUP -> "PgUp"
            DeckKeyCode.PGDN -> "PgDn"
            DeckKeyCode.UP -> "\u2191"
            DeckKeyCode.DOWN -> "\u2193"
            DeckKeyCode.LEFT -> "\u2190"
            DeckKeyCode.RIGHT -> "\u2192"
            else -> key.name
        }
    }
}

@Serializable
data class DeckLayer(
    val name: String,
    val keys: List<DeckKey>,
    /** tmux prefix for [DeckAction.Macro] expansion, e.g. `CTRL b`. */
    val prefix: String? = null,
)

enum class DeckReach { LEFT, RIGHT }

enum class DeckArrows { NUB, FOUR_KEYS, BOTH }

/**
 * Haptic levels (UX spec D3): Off, Subtle (Deck key taps only) and Full. A device preference, so it
 * lives beside [DeckLayout] rather than in it and an exported layout does not carry it.
 */
@Serializable
enum class HapticLevel { OFF, SUBTLE, FULL }

/**
 * What a hand does on this device's Deck (UX spec D2, C4): device preferences beside [DeckLayout],
 * like [HapticLevel], so an exported layout (E1) carries the keys and not one device's gestures.
 * One document, so a change to any field lands atomically.
 */
@Serializable
data class DeckSettings(
    /**
     * A swipe down on a key sends its tertiary action (D2: off by default). While on, a key with a
     * tertiary shows its glyph at the bottom right, the way the swipe-up alternate sits at the top right.
     */
    val swipeDown: Boolean = false,
    /** A horizontal swipe across the Deck's keys steps the layer, left for the next and right for the one before (D2). */
    val layerSwipe: Boolean = true,
    /**
     * Two rows on a window with the height for them (C4: two-row mode is the default on tablets),
     * whatever row count the layout saved; off, the layout's own rows stand everywhere.
     */
    val twoRowsOnLargeScreens: Boolean = true,
)

/** The whole Deck configuration; the shape of Berth Deck JSON (UX spec E1). */
@Serializable
data class DeckLayout(
    val version: Int = 1,
    val rows: Int = 1,
    val reach: DeckReach = DeckReach.RIGHT,
    @SerialName("height_dp") val heightDp: Int = 44,
    val arrows: DeckArrows = DeckArrows.NUB,
    val layers: List<DeckLayer>,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            classDiscriminator = "kind"
        }

        fun fromJson(text: String): DeckLayout = json.decodeFromString(serializer(), text)

        /** The stock layout from the UX spec. */
        fun default(): DeckLayout = DeckLayout(
            layers = listOf(
                DeckLayer(
                    name = "Base",
                    keys = listOf(
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC), up = DeckAction.Text("`")),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.TAB), up = DeckAction.Combo("SHIFT TAB")),
                        DeckKey(tap = DeckAction.Modifier(DeckModifier.CTRL), up = DeckAction.Combo("CTRL c"), display = "Ctrl"),
                        DeckKey(tap = DeckAction.Modifier(DeckModifier.ALT), up = DeckAction.Combo("CTRL r"), display = "Alt"),
                        DeckKey(tap = DeckAction.Text("-"), up = DeckAction.Text("|")),
                        DeckKey(tap = DeckAction.Text("/"), up = DeckAction.Text("\\")),
                        DeckKey(nub = true),
                    ),
                ),
                DeckLayer(
                    name = "Symbols",
                    keys = listOf(
                        DeckKey(tap = DeckAction.Text("|"), up = DeckAction.Text("&")),
                        DeckKey(tap = DeckAction.Text("\\"), up = DeckAction.Text(";")),
                        DeckKey(tap = DeckAction.Text("~"), up = DeckAction.Text("'")),
                        DeckKey(tap = DeckAction.Text("`"), up = DeckAction.Text("\"")),
                        DeckKey(tap = DeckAction.Text("^"), up = DeckAction.Text("[")),
                        DeckKey(tap = DeckAction.Text("{"), up = DeckAction.Text("]")),
                        DeckKey(tap = DeckAction.Text("}"), up = DeckAction.Text("<")),
                    ),
                ),
                DeckLayer(
                    name = "Nav/Fn",
                    keys = listOf(
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.HOME)),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.END)),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.PGUP)),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.PGDN)),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.INS)),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.DEL)),
                        DeckKey(
                            tap = DeckAction.Modifier(DeckModifier.SHIFT),
                            hold = DeckAction.Strip(listOf(DeckKeyCode.F1, DeckKeyCode.F2, DeckKeyCode.F3, DeckKeyCode.F4, DeckKeyCode.F5, DeckKeyCode.F6, DeckKeyCode.F7, DeckKeyCode.F8, DeckKeyCode.F9, DeckKeyCode.F10, DeckKeyCode.F11, DeckKeyCode.F12)),
                        ),
                    ),
                ),
                DeckLayer(
                    name = "tmux",
                    prefix = "CTRL b",
                    keys = listOf(
                        DeckKey(tap = DeckAction.Macro("PREFIX"), up = DeckAction.Macro("PREFIX d"), display = "Pfx"),
                        DeckKey(tap = DeckAction.Macro("PREFIX p"), display = "<win"),
                        DeckKey(tap = DeckAction.Macro("PREFIX n"), display = "win>"),
                        DeckKey(tap = DeckAction.Macro("PREFIX c"), display = "new"),
                        DeckKey(tap = DeckAction.Macro("PREFIX \""), up = DeckAction.Macro("PREFIX %"), display = "split"),
                        DeckKey(tap = DeckAction.Macro("PREFIX z"), display = "zoom"),
                        DeckKey(tap = DeckAction.Macro("PREFIX ["), display = "scroll"),
                    ),
                ),
                DeckLayer(name = "Snippets", keys = listOf(DeckKey(snippets = true))),
            ),
        )
    }
}
