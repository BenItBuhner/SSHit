package app.berth.terminal

/** Non-printing keys that need an escape sequence. Printable text goes through [KeyEncoder.encodeText]. */
enum class TerminalKey {
    UP, DOWN, LEFT, RIGHT,
    HOME, END, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
    ENTER, TAB, BACKSPACE, ESCAPE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Modifier bit flags for [KeyEncoder]. */
object Mod {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
    const val META = 8
}

/**
 * Produces the xterm byte sequences for keys and printable text. Pure and stateless; the emulator
 * supplies the current application-cursor/keypad modes.
 */
object KeyEncoder {
    private const val ESC = 0x1B

    fun encode(key: TerminalKey, modifiers: Int, applicationCursorKeys: Boolean, applicationKeypad: Boolean): ByteArray {
        val mod = modifiers and (Mod.SHIFT or Mod.ALT or Mod.CTRL or Mod.META)
        return when (key) {
            TerminalKey.UP -> cursorKey('A', mod, applicationCursorKeys)
            TerminalKey.DOWN -> cursorKey('B', mod, applicationCursorKeys)
            TerminalKey.RIGHT -> cursorKey('C', mod, applicationCursorKeys)
            TerminalKey.LEFT -> cursorKey('D', mod, applicationCursorKeys)
            TerminalKey.HOME -> cursorKey('H', mod, applicationCursorKeys)
            TerminalKey.END -> cursorKey('F', mod, applicationCursorKeys)
            TerminalKey.INSERT -> tildeKey(2, mod)
            TerminalKey.DELETE -> tildeKey(3, mod)
            TerminalKey.PAGE_UP -> tildeKey(5, mod)
            TerminalKey.PAGE_DOWN -> tildeKey(6, mod)
            TerminalKey.F1 -> pfKey('P', mod)
            TerminalKey.F2 -> pfKey('Q', mod)
            TerminalKey.F3 -> pfKey('R', mod)
            TerminalKey.F4 -> pfKey('S', mod)
            TerminalKey.F5 -> tildeKey(15, mod)
            TerminalKey.F6 -> tildeKey(17, mod)
            TerminalKey.F7 -> tildeKey(18, mod)
            TerminalKey.F8 -> tildeKey(19, mod)
            TerminalKey.F9 -> tildeKey(20, mod)
            TerminalKey.F10 -> tildeKey(21, mod)
            TerminalKey.F11 -> tildeKey(23, mod)
            TerminalKey.F12 -> tildeKey(24, mod)
            TerminalKey.ENTER -> {
                val base = if (applicationKeypad && mod == 0) ascii(ESC, 'O'.code, 'M'.code) else ascii('\r'.code)
                withAlt(base, mod)
            }
            TerminalKey.TAB -> when {
                mod and Mod.SHIFT != 0 -> csi("Z")
                else -> withAlt(ascii('\t'.code), mod)
            }
            TerminalKey.BACKSPACE -> withAlt(ascii(if (mod and Mod.CTRL != 0) 0x08 else 0x7F), mod)
            TerminalKey.ESCAPE -> withAlt(ascii(ESC), mod)
        }
    }

    /**
     * Encodes a printable code point typed with [modifiers]. Ctrl maps letters and the usual
     * punctuation onto C0 controls; Alt prefixes ESC (xterm's `metaSendsEscape`), or with
     * [altSendsMeta] sets the eighth bit of an ASCII result instead (`eightBitInput`); Shift is
     * assumed to be already applied to the code point by the keyboard.
     */
    fun encodeText(codePoint: Int, modifiers: Int, altSendsMeta: Boolean = false): ByteArray {
        var cp = codePoint
        if (modifiers and Mod.CTRL != 0) {
            val ctrl = controlFor(cp)
            if (ctrl >= 0) cp = ctrl
        }
        if (altSendsMeta && modifiers and Mod.ALT != 0 && cp in 0..0x7F) return byteArrayOf((cp or 0x80).toByte())
        val utf8 = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
        return withAlt(utf8, modifiers)
    }

    /** The C0 control produced by Ctrl plus [cp], or -1 when Ctrl does not change the character. */
    fun controlFor(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '@'.code, '2'.code -> 0
        '['.code, '3'.code -> 0x1B
        '\\'.code, '4'.code -> 0x1C
        ']'.code, '5'.code -> 0x1D
        '^'.code, '6'.code -> 0x1E
        '_'.code, '7'.code, '-'.code -> 0x1F
        '?'.code, '8'.code -> 0x7F
        else -> -1
    }

    private fun cursorKey(final: Char, mod: Int, application: Boolean): ByteArray = when {
        mod != 0 -> csi("1;${modParam(mod)}$final")
        application -> ascii(ESC, 'O'.code, final.code)
        else -> csi(final.toString())
    }

    private fun pfKey(final: Char, mod: Int): ByteArray =
        if (mod != 0) csi("1;${modParam(mod)}$final") else ascii(ESC, 'O'.code, final.code)

    private fun tildeKey(code: Int, mod: Int): ByteArray =
        if (mod != 0) csi("$code;${modParam(mod)}~") else csi("$code~")

    /** xterm modifier parameter: 1 + Shift(1) + Alt(2) + Ctrl(4) + Meta(8). */
    private fun modParam(mod: Int): Int = 1 + mod

    private fun withAlt(bytes: ByteArray, mod: Int): ByteArray =
        if (mod and Mod.ALT != 0) byteArrayOf(ESC.toByte()) + bytes else bytes

    private fun csi(body: String): ByteArray = ("\u001b[" + body).toByteArray(Charsets.US_ASCII)

    private fun ascii(vararg codes: Int): ByteArray = ByteArray(codes.size) { codes[it].toByte() }
}

/** Mouse buttons in xterm numbering. */
object MouseButton {
    const val LEFT = 0
    const val MIDDLE = 1
    const val RIGHT = 2
    const val RELEASE = 3
    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65
}

/** Encodes mouse reports for the tracking mode the application enabled. */
object MouseEncoder {
    /**
     * @param button one of [MouseButton]; use [MouseButton.RELEASE] for X10-style releases.
     * @param col zero-based column, [row] zero-based row.
     * @param release true for a button release (SGR encoding uses a distinct final byte).
     * @param motion true when this is a drag/motion report (adds 32 to the button code).
     */
    fun encode(button: Int, col: Int, row: Int, modifiers: Int, release: Boolean, motion: Boolean, sgr: Boolean): ByteArray {
        var code = button
        if (modifiers and Mod.SHIFT != 0) code += 4
        if (modifiers and Mod.ALT != 0) code += 8
        if (modifiers and Mod.CTRL != 0) code += 16
        if (motion) code += 32
        return if (sgr) {
            val final = if (release) 'm' else 'M'
            "\u001b[<$code;${col + 1};${row + 1}$final".toByteArray(Charsets.US_ASCII)
        } else {
            val b = if (release) MouseButton.RELEASE + (code and 0xFC) else code
            // Legacy encoding cannot represent coordinates above 223.
            val x = (col + 1).coerceAtMost(223) + 32
            val y = (row + 1).coerceAtMost(223) + 32
            byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (b + 32).toByte(), x.toByte(), y.toByte())
        }
    }
}
