package app.berth.android.ui.terminal

import android.content.ClipboardManager
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import app.berth.terminal.Mod
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where keyboard input ends up: the Stage routes it through the Deck's latched modifiers to the session. */
interface TerminalInputSink {
    fun onText(text: String)
    fun onKey(key: TerminalKey, modifiers: Int = 0)
    fun onPaste(text: String) = onText(text)
}

/**
 * A sink that also hears which soft-keyboard connection feeds it, so a key of its own (a Deck key,
 * a hardware chord) can send a word the keyboard is still composing ahead of itself.
 */
interface ImeAwareSink : TerminalInputSink {
    var imeConnection: TerminalInputConnection?
}

/** Sends any text the soft keyboard is still composing, so what the caller sends next lands after it. */
fun TerminalInputSink.flushComposing() {
    (this as? ImeAwareSink)?.imeConnection?.flushComposing()
}

/**
 * The soft keyboard talks to the terminal through this connection. There is no editable text: every
 * committed character is sent to the host immediately, deletes become Backspace and Delete, and
 * Enter is a key. Composing text (a predictive keyboard's underlined word; CJK input) is held until
 * the keyboard commits or finishes it, so a word is sent once rather than letter by letter and
 * then again whole. The text before the cursor reads as empty on purpose: a keyboard that could
 * see what it committed would try to re-compose it (a backspace into a word, a long-press for an
 * accent) and send it a second time.
 */
class TerminalInputConnection(private val targetView: View, private val sink: TerminalInputSink) : BaseInputConnection(targetView, false) {
    private var composing: String = ""

    /** Whether the keyboard holds a word it has not committed. */
    val isComposing: Boolean get() = composing.isNotEmpty()

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // The commit replaces the composing word, which was never sent.
        composing = ""
        val s = text?.toString() ?: return true
        sendTextWithEnter(s)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composing = text?.toString() ?: ""
        return true
    }

    override fun finishComposingText(): Boolean {
        val word = composing
        composing = ""
        if (word.isNotEmpty()) sendTextWithEnter(word)
        return true
    }

    /**
     * A Deck key or a hardware chord is about to send: the word the keyboard is still composing goes
     * first, so `ls` then Enter reaches the host as `ls`, Enter and not the other way round. The
     * keyboard still holds the word, so the input is restarted and it starts afresh; otherwise its
     * eventual commit would send the word a second time.
     */
    fun flushComposing() {
        if (composing.isNotEmpty()) restart()
    }

    /**
     * Has the keyboard start over against the terminal's attributes as they are now (predictive
     * text turned on or off, spec C6), a word it was composing sent first rather than lost.
     */
    fun restart() {
        val word = composing
        composing = ""
        if (word.isNotEmpty()) sendTextWithEnter(word)
        targetView.context.getSystemService(InputMethodManager::class.java)?.restartInput(targetView)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = true

    /**
     * Deletes are keys: the text before the cursor is the host's, and a keyboard that has a composing
     * word shortens it with [setComposingText], so this always means the characters before the word.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceAtLeast(0)) { sink.onKey(TerminalKey.BACKSPACE) }
        repeat(afterLength.coerceAtLeast(0)) { sink.onKey(TerminalKey.DELETE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            // A string with no key of its own (an emoji, a character off the keyboard's map) arrives as one multi-key event.
            KeyEvent.ACTION_MULTIPLE -> if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) event.characters?.let { flushComposing(); sendTextWithEnter(it) }
            KeyEvent.ACTION_DOWN -> {
                flushComposing()
                val handled = handleKeyDown(event.keyCode, event.unicodeChar, modifiersOf(event), sink)
                if (!handled) super.sendKeyEvent(event)
            }
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        flushComposing()
        sink.onKey(TerminalKey.ENTER)
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (id == android.R.id.paste) {
            val clipboard = targetView.context.getSystemService(ClipboardManager::class.java)
            clipboard?.primaryClip?.getItemAt(0)?.coerceToText(targetView.context)?.toString()?.let { sink.onPaste(it) }
            return true
        }
        return false
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
    override fun getSelectedText(flags: Int): CharSequence? = null

    private fun sendTextWithEnter(s: String) {
        var start = 0
        while (start < s.length) {
            val nl = s.indexOf('\n', start)
            if (nl < 0) {
                sink.onText(s.substring(start))
                break
            }
            if (nl > start) sink.onText(s.substring(start, nl))
            sink.onKey(TerminalKey.ENTER)
            start = nl + 1
        }
    }
}

private fun modifiersOf(event: KeyEvent): Int {
    var m = 0
    if (event.isShiftPressed) m = m or Mod.SHIFT
    if (event.isAltPressed) m = m or Mod.ALT
    if (event.isCtrlPressed) m = m or Mod.CTRL
    if (event.isMetaPressed) m = m or Mod.META
    return m
}

/** Maps an Android key code to the terminal; returns false when the event is not for the terminal. */
fun handleKeyDown(keyCode: Int, unicodeChar: Int, modifiers: Int, sink: TerminalInputSink): Boolean {
    val key = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
        KeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.ENTER
        KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
        KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
        KeyEvent.KEYCODE_F1 -> TerminalKey.F1
        KeyEvent.KEYCODE_F2 -> TerminalKey.F2
        KeyEvent.KEYCODE_F3 -> TerminalKey.F3
        KeyEvent.KEYCODE_F4 -> TerminalKey.F4
        KeyEvent.KEYCODE_F5 -> TerminalKey.F5
        KeyEvent.KEYCODE_F6 -> TerminalKey.F6
        KeyEvent.KEYCODE_F7 -> TerminalKey.F7
        KeyEvent.KEYCODE_F8 -> TerminalKey.F8
        KeyEvent.KEYCODE_F9 -> TerminalKey.F9
        KeyEvent.KEYCODE_F10 -> TerminalKey.F10
        KeyEvent.KEYCODE_F11 -> TerminalKey.F11
        KeyEvent.KEYCODE_F12 -> TerminalKey.F12
        else -> null
    }
    if (key != null) {
        sink.onKey(key, modifiers)
        return true
    }
    if (keyCode == KeyEvent.KEYCODE_SPACE && modifiers and Mod.CTRL != 0) {
        sink.onText("\u0000")
        return true
    }
    // Ctrl and Alt strip the shift-only unicode; get the plain character back for the control mapping.
    val cp = when {
        unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0 -> return false
        unicodeChar > 0 -> unicodeChar
        else -> return false
    }
    if (modifiers and (Mod.CTRL or Mod.ALT or Mod.META) == 0) {
        sink.onText(String(Character.toChars(cp)))
    } else {
        sink.onKeyWithModifiers(cp, modifiers)
    }
    return true
}

/** A printable character typed with Ctrl or Alt held; the sink encodes it. */
fun TerminalInputSink.onKeyWithModifiers(codePoint: Int, modifiers: Int) {
    if (this is ModifierAwareSink) onCodePoint(codePoint, modifiers) else onText(String(Character.toChars(codePoint)))
}

interface ModifierAwareSink : TerminalInputSink {
    fun onCodePoint(codePoint: Int, modifiers: Int)
}

/**
 * The character a key types as its third level, the one Right Alt reaches where it is AltGr: `@` on a
 * German layout's Q, `{ [ ] }` on its 7 8 9 0, `\` on ß, `|` on the key beside the left Shift. It is what
 * the layout's map gives the key under the modifiers held ([character] is the map's own lookup,
 * `KeyCharacterMap.get`), for a key with a character under Right Alt and none under Left Alt: the
 * map's `ralt` row, which only the right key reaches. 0 for a key with no third level, so Right Alt
 * is the chord's Alt there the way the left one is; and 0 where the layout puts the same character
 * under either Alt (Generic.kcm, the US layout, has ç on C and ß on S that way, and its accents on
 * E I N U, as a Mac's Option has them), since Alt+C is Meta+C to a terminal whichever Alt is held.
 * A dead key's accent comes back as the map gives it, with `KeyCharacterMap.COMBINING_ACCENT` set.
 */
fun thirdLevelCharacter(character: (keyCode: Int, metaState: Int) -> Int, keyCode: Int, metaState: Int): Int {
    if (metaState and KeyEvent.META_ALT_RIGHT_ON == 0) return 0
    if (character(keyCode, KeyEvent.META_ALT_RIGHT_ON) == 0 || character(keyCode, KeyEvent.META_ALT_LEFT_ON) != 0) return 0
    return character(keyCode, metaState)
}

/**
 * Hardware keyboard events arriving through Compose. Ctrl and Alt are the chord's: the key's base
 * character goes with them (Ctrl+C is `^C`, Alt+F is Meta+F). Right Alt is the layout's first: where
 * it is AltGr, the third-level character it reaches is typed as it is ([thirdLevelCharacter]).
 */
fun handleComposeKeyEvent(event: androidx.compose.ui.input.key.KeyEvent, sink: TerminalInputSink): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    var mods = 0
    if (event.isShiftPressed) mods = mods or Mod.SHIFT
    if (event.isAltPressed) mods = mods or Mod.ALT
    if (event.isCtrlPressed) mods = mods or Mod.CTRL
    if (event.isMetaPressed) mods = mods or Mod.META
    val native = event.nativeKeyEvent
    // Pure modifier presses are not input.
    when (event.key) {
        Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight, Key.CapsLock, Key.Function -> return false
    }
    // A word the soft keyboard is still composing goes before the hardware key.
    sink.flushComposing()
    // A key's third level under Right Alt, AltGr on a German or Nordic layout: `@` on Q is the layout's
    // own character, typed as it is, and the Alt that reached it is no chord's.
    val third = if (mods and Mod.ALT != 0 && mods and Mod.CTRL == 0) thirdLevelCharacter(native.keyCharacterMap::get, native.keyCode, native.metaState) else 0
    if (third > 0 && third and KeyCharacterMap.COMBINING_ACCENT == 0) return handleKeyDown(native.keyCode, third, mods and Mod.ALT.inv(), sink)
    val unicode = if (mods and (Mod.CTRL or Mod.ALT) != 0) {
        // unicodeChar with Ctrl held is 0 on Android; recover the base character.
        val base = native.getUnicodeChar(native.metaState and KeyEvent.META_SHIFT_MASK)
        if (base > 0) base else event.utf16CodePoint
    } else event.utf16CodePoint
    return handleKeyDown(native.keyCode, unicode, mods, sink)
}

/**
 * Attaches the terminal input connection to a focusable node; the IME session lives while focused.
 * With [predictive] on (spec C6, the Session sheet's row) the keyboard may suggest words; a flip
 * while the keyboard is up restarts the input so it reads the change at once.
 */
fun Modifier.terminalInput(sink: TerminalInputSink, predictive: Boolean = false): Modifier = this then TerminalInputElement(sink, predictive)

private data class TerminalInputElement(val sink: TerminalInputSink, val predictive: Boolean) : ModifierNodeElement<TerminalInputNode>() {
    override fun create(): TerminalInputNode = TerminalInputNode(sink, predictive)
    override fun update(node: TerminalInputNode) {
        node.sink = sink
        node.setPredictive(predictive)
    }
}

/**
 * What the terminal tells the keyboard about itself: plain text with no suggestions, no autocorrect
 * and no capitalisation (the visible-password variation is the one flag every keyboard, Samsung's
 * included, honours as "do not correct this"), a request that nothing typed here be learned, no
 * full-screen or extracted editor, and Enter as a key rather than an action. With [predictive] on
 * (spec C6) it is plain text the keyboard may suggest for, with the same request not to learn
 * from it. Both are asks the keyboard is free to ignore: `IME_FLAG_NO_PERSONALIZED_LEARNING` is
 * honoured by Gboard and Samsung Keyboard and by any other only as it chooses, and without the
 * no-suggestions flag whether words are corrected as well as suggested is the keyboard's call; so
 * no caption promises either, and only a phone with a given keyboard shows what it does.
 */
fun configureTerminalEditorInfo(outAttributes: EditorInfo, predictive: Boolean = false) {
    outAttributes.inputType = if (predictive) InputType.TYPE_CLASS_TEXT else {
        InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
        EditorInfo.IME_ACTION_NONE or
        EditorInfo.IME_FLAG_NO_ENTER_ACTION
    outAttributes.initialSelStart = 0
    outAttributes.initialSelEnd = 0
    outAttributes.initialCapsMode = 0
}

private class TerminalInputNode(var sink: TerminalInputSink, private var predictive: Boolean) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {
    private var session: Job? = null
    private var connection: TerminalInputConnection? = null

    /** A keyboard already up re-reads the attributes through a restart; the next one reads them as it connects. */
    fun setPredictive(on: Boolean) {
        if (predictive == on) return
        predictive = on
        connection?.restart()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) {
            if (session == null) {
                session = coroutineScope.launch {
                    establishTextInputSession {
                        startInputMethod(
                            object : PlatformTextInputMethodRequest {
                                // Called again on every restart of the input, so the connection is always the live one.
                                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                                    configureTerminalEditorInfo(outAttributes, predictive)
                                    val c = TerminalInputConnection(view, sink)
                                    connection = c
                                    (sink as? ImeAwareSink)?.imeConnection = c
                                    return c
                                }
                            },
                        )
                    }
                }
            }
        } else {
            endSession()
        }
    }

    override fun onDetach() = endSession()

    private fun endSession() {
        session?.cancel()
        session = null
        val c = connection ?: return
        connection = null
        (sink as? ImeAwareSink)?.let { if (it.imeConnection === c) it.imeConnection = null }
    }
}
