package app.berth.android.ui.stage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.berth.android.session.TerminalSession
import app.berth.android.ui.terminal.ImeAwareSink
import app.berth.android.ui.terminal.ModifierAwareSink
import app.berth.android.ui.terminal.TerminalInputConnection
import app.berth.android.ui.terminal.flushComposing
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckModifier
import app.berth.terminal.Mod
import app.berth.terminal.TerminalKey

enum class LatchState { NONE, ONE_SHOT, LOCKED }

/** The Deck's Ctrl / Alt / Shift latches: one-shot clears after the next character, locked stays. */
class ModifierLatch {
    var ctrl by mutableStateOf(LatchState.NONE)
    var alt by mutableStateOf(LatchState.NONE)
    var shift by mutableStateOf(LatchState.NONE)

    fun state(m: DeckModifier): LatchState = when (m) {
        DeckModifier.CTRL -> ctrl
        DeckModifier.ALT -> alt
        DeckModifier.SHIFT -> shift
    }

    /** Tap: none becomes one-shot, one-shot becomes locked, locked releases. */
    fun tap(m: DeckModifier) {
        val next = when (state(m)) {
            LatchState.NONE -> LatchState.ONE_SHOT
            LatchState.ONE_SHOT -> LatchState.LOCKED
            LatchState.LOCKED -> LatchState.NONE
        }
        set(m, next)
    }

    fun set(m: DeckModifier, s: LatchState) = when (m) {
        DeckModifier.CTRL -> ctrl = s
        DeckModifier.ALT -> alt = s
        DeckModifier.SHIFT -> shift = s
    }

    val bits: Int
        get() {
            var b = 0
            if (ctrl != LatchState.NONE) b = b or Mod.CTRL
            if (alt != LatchState.NONE) b = b or Mod.ALT
            if (shift != LatchState.NONE) b = b or Mod.SHIFT
            return b
        }

    val isActive: Boolean get() = bits != 0

    fun consumeOneShots() {
        if (ctrl == LatchState.ONE_SHOT) ctrl = LatchState.NONE
        if (alt == LatchState.ONE_SHOT) alt = LatchState.NONE
        if (shift == LatchState.ONE_SHOT) shift = LatchState.NONE
    }

    fun clear() {
        ctrl = LatchState.NONE
        alt = LatchState.NONE
        shift = LatchState.NONE
    }
}

/**
 * Routes everything typed or tapped to the session through the Deck's latches, and turns
 * [DeckAction]s into bytes. One instance per Stage.
 */
class StageInput(
    private val session: () -> TerminalSession?,
    val latch: ModifierLatch,
    private val onAppAction: (DeckAppAction) -> Unit,
    private val onSnippet: (String) -> Unit = {},
    /** Where a paste from the keyboard's own menu goes; the Stage routes it through the preview (spec C18). */
    private val pasteHook: ((String) -> Unit)? = null,
) : ModifierAwareSink, ImeAwareSink {
    /** The soft keyboard's live connection, set by the canvas' input modifier while the terminal has focus. */
    override var imeConnection: TerminalInputConnection? = null

    override fun onText(text: String) {
        val s = session() ?: return
        val bits = latch.bits
        if (bits == 0) {
            s.sendText(text)
        } else {
            // Shift on a latch applies to letters; the keyboard already produced the shifted glyph otherwise.
            val shifted = if (bits and Mod.SHIFT != 0) text.uppercase() else text
            s.sendText(shifted, bits and Mod.SHIFT.inv())
            latch.consumeOneShots()
        }
    }

    override fun onKey(key: TerminalKey, modifiers: Int) {
        val s = session() ?: return
        s.sendKey(key, modifiers or latch.bits)
        latch.consumeOneShots()
    }

    override fun onCodePoint(codePoint: Int, modifiers: Int) {
        val s = session() ?: return
        val bits = modifiers or latch.bits
        // Through the session, not the encoder: it applies the host's Alt behaviour and tells the history tracker the line was edited by a chord.
        s.sendText(String(Character.toChars(codePoint)), bits and Mod.SHIFT.inv())
        latch.consumeOneShots()
    }

    override fun onPaste(text: String) {
        val hook = pasteHook
        if (hook != null) hook(text) else session()?.paste(text)
    }

    fun dispatch(action: DeckAction, layer: DeckLayer?) {
        // A word the soft keyboard is still composing goes first, so a Deck Enter lands after `ls`, not before it,
        // and a latch tapped mid-word applies to the next character rather than to the whole word.
        flushComposing()
        when (action) {
            is DeckAction.Key -> onKey(action.key.toTerminalKey())
            is DeckAction.Modifier -> latch.tap(action.modifier)
            is DeckAction.Text -> onText(action.text)
            is DeckAction.Combo -> combo(action.modifiers, action.target)
            is DeckAction.Macro -> macro(action.macro, layer)
            is DeckAction.Snippet -> onSnippet(action.snippetId)
            is DeckAction.App -> onAppAction(action.action)
            is DeckAction.Strip -> Unit
        }
    }

    private fun combo(modifiers: Set<DeckModifier>, target: String) {
        var bits = 0
        if (DeckModifier.CTRL in modifiers) bits = bits or Mod.CTRL
        if (DeckModifier.ALT in modifiers) bits = bits or Mod.ALT
        if (DeckModifier.SHIFT in modifiers) bits = bits or Mod.SHIFT
        val key = runCatching { DeckKeyCode.valueOf(target.uppercase()) }.getOrNull()
        when {
            key != null -> onKey(key.toTerminalKey(), bits)
            target.isNotEmpty() -> {
                val cp = target.codePointAt(0)
                val glyph = if (bits and Mod.SHIFT != 0) Character.toUpperCase(cp) else cp
                if (bits and Mod.SHIFT.inv() == 0) onText(String(Character.toChars(glyph))) else onCodePoint(glyph, bits and Mod.SHIFT.inv())
            }
        }
    }

    /** `PREFIX d` sends the layer's prefix combo then `d`; any token may itself be a key name. */
    private fun macro(text: String, layer: DeckLayer?) {
        for (token in text.trim().split(' ').filter { it.isNotEmpty() }) {
            when {
                token == "PREFIX" -> {
                    val prefix = DeckAction.Combo(layer?.prefix ?: "CTRL b")
                    combo(prefix.modifiers, prefix.target)
                }
                runCatching { DeckKeyCode.valueOf(token.uppercase()) }.isSuccess && token.length > 1 -> onKey(DeckKeyCode.valueOf(token.uppercase()).toTerminalKey())
                else -> onText(token)
            }
        }
    }
}

fun DeckKeyCode.toTerminalKey(): TerminalKey = when (this) {
    DeckKeyCode.ESC -> TerminalKey.ESCAPE
    DeckKeyCode.TAB -> TerminalKey.TAB
    DeckKeyCode.ENTER -> TerminalKey.ENTER
    DeckKeyCode.BACKSPACE -> TerminalKey.BACKSPACE
    DeckKeyCode.INS -> TerminalKey.INSERT
    DeckKeyCode.DEL -> TerminalKey.DELETE
    DeckKeyCode.HOME -> TerminalKey.HOME
    DeckKeyCode.END -> TerminalKey.END
    DeckKeyCode.PGUP -> TerminalKey.PAGE_UP
    DeckKeyCode.PGDN -> TerminalKey.PAGE_DOWN
    DeckKeyCode.UP -> TerminalKey.UP
    DeckKeyCode.DOWN -> TerminalKey.DOWN
    DeckKeyCode.LEFT -> TerminalKey.LEFT
    DeckKeyCode.RIGHT -> TerminalKey.RIGHT
    DeckKeyCode.F1 -> TerminalKey.F1
    DeckKeyCode.F2 -> TerminalKey.F2
    DeckKeyCode.F3 -> TerminalKey.F3
    DeckKeyCode.F4 -> TerminalKey.F4
    DeckKeyCode.F5 -> TerminalKey.F5
    DeckKeyCode.F6 -> TerminalKey.F6
    DeckKeyCode.F7 -> TerminalKey.F7
    DeckKeyCode.F8 -> TerminalKey.F8
    DeckKeyCode.F9 -> TerminalKey.F9
    DeckKeyCode.F10 -> TerminalKey.F10
    DeckKeyCode.F11 -> TerminalKey.F11
    DeckKeyCode.F12 -> TerminalKey.F12
}

/** Keys that autorepeat while held. */
val DeckKeyCode.repeats: Boolean
    get() = this == DeckKeyCode.UP || this == DeckKeyCode.DOWN || this == DeckKeyCode.LEFT || this == DeckKeyCode.RIGHT ||
        this == DeckKeyCode.BACKSPACE || this == DeckKeyCode.DEL
