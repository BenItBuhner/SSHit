package app.berth.android.ui.terminal

import android.content.ClipboardManager
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
 * The soft keyboard talks to the terminal through this connection. There is no editable text: every
 * committed character is sent to the host immediately, deletes become Backspace, and Enter is a key.
 * Composing text (predictive keyboards) is held until the keyboard finishes it, so words are not
 * sent letter by letter and then re-sent.
 */
class TerminalInputConnection(private val targetView: View, private val sink: TerminalInputSink) : BaseInputConnection(targetView, false) {
    private var composing: String = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
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
        if (composing.isNotEmpty()) sendTextWithEnter(composing)
        composing = ""
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (composing.isNotEmpty()) {
            composing = composing.dropLast(beforeLength.coerceAtMost(composing.length))
            return true
        }
        repeat(beforeLength.coerceAtLeast(0)) { sink.onKey(TerminalKey.BACKSPACE) }
        repeat(afterLength.coerceAtLeast(0)) { sink.onKey(TerminalKey.DELETE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val handled = handleKeyDown(event.keyCode, event.unicodeChar, modifiersOf(event), sink)
        if (!handled) super.sendKeyEvent(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
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

/** Hardware keyboard events arriving through Compose. */
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
    val unicode = if (mods and (Mod.CTRL or Mod.ALT) != 0) {
        // unicodeChar with Ctrl held is 0 on Android; recover the base character.
        val base = native.getUnicodeChar(native.metaState and KeyEvent.META_SHIFT_MASK)
        if (base > 0) base else event.utf16CodePoint
    } else event.utf16CodePoint
    return handleKeyDown(native.keyCode, unicode, mods, sink)
}

/** Attaches the terminal input connection to a focusable node; the IME session lives while focused. */
fun Modifier.terminalInput(sink: TerminalInputSink): Modifier = this then TerminalInputElement(sink)

private data class TerminalInputElement(val sink: TerminalInputSink) : ModifierNodeElement<TerminalInputNode>() {
    override fun create(): TerminalInputNode = TerminalInputNode(sink)
    override fun update(node: TerminalInputNode) {
        node.sink = sink
    }
}

private class TerminalInputNode(var sink: TerminalInputSink) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {
    private var session: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) {
            if (session == null) {
                session = coroutineScope.launch {
                    establishTextInputSession {
                        startInputMethod(
                            object : PlatformTextInputMethodRequest {
                                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                                    outAttributes.inputType = InputType.TYPE_CLASS_TEXT or
                                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                    outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                                        EditorInfo.IME_ACTION_NONE or
                                        EditorInfo.IME_FLAG_NO_ENTER_ACTION
                                    outAttributes.initialSelStart = 0
                                    outAttributes.initialSelEnd = 0
                                    return TerminalInputConnection(view, sink)
                                }
                            },
                        )
                    }
                }
            }
        } else {
            session?.cancel()
            session = null
        }
    }

    override fun onDetach() {
        session?.cancel()
        session = null
    }
}
