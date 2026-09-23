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
 * How the chords the legacy encoding folds together are told apart, as the program asked for it.
 * [LEGACY] is xterm's default: Ctrl+Shift+A is Ctrl+A, Ctrl+Space is NUL, Ctrl+I is Tab and Alt+X
 * is ESC x. xterm's modifyOtherKeys (XTMODKEYS, `CSI > 4 ; n m`) sends such a chord as
 * `CSI 27 ; mod ; code ~`, or `CSI code ; mod u` under formatOtherKeys (XTFMTKEYS, `CSI > 4 ; 1 f`);
 * the kitty keyboard protocol's flags (`CSI > flags u`) send it as `CSI key ; mod u`, and win when
 * both are set.
 */
data class KeyboardProtocol(
    /** 0 off; 1 the chords beyond the usual Shift and Ctrl; 2 every modifier, but Shift alone on a character. Kept at 0 to 2. */
    val modifyOtherKeys: Int = 0,
    /** 0 for `CSI 27 ; mod ; code ~`, 1 for `CSI code ; mod u`. */
    val formatOtherKeys: Int = 0,
    /** The kitty keyboard protocol's progressive enhancements, of those in [KITTY_SUPPORTED]. */
    val kittyFlags: Int = 0,
) {
    companion object {
        val LEGACY = KeyboardProtocol()
        const val KITTY_DISAMBIGUATE = 1
        const val KITTY_ALTERNATE_KEYS = 4

        /**
         * Key-release events (2), every key as an escape (8) and the text a key made (16) all need
         * a key behind every character, which a soft keyboard's committed text does not have; a
         * program that asks for them is told, by the flags it reads back, that it has only these.
         */
        const val KITTY_SUPPORTED = KITTY_DISAMBIGUATE or KITTY_ALTERNATE_KEYS
    }
}

/**
 * Produces the byte sequences for keys and printable text. Pure and stateless; the emulator
 * supplies the current application-cursor/keypad modes and the [KeyboardProtocol] the program asked for.
 */
object KeyEncoder {
    private const val ESC = 0x1B
    private const val ALL_MODS = Mod.SHIFT or Mod.ALT or Mod.CTRL or Mod.META
    private const val CHORD = Mod.ALT or Mod.CTRL or Mod.META

    fun encode(
        key: TerminalKey,
        modifiers: Int,
        applicationCursorKeys: Boolean,
        applicationKeypad: Boolean,
        protocol: KeyboardProtocol = KeyboardProtocol.LEGACY,
    ): ByteArray {
        val mod = modifiers and ALL_MODS
        if (protocol.kittyFlags and KeyboardProtocol.KITTY_DISAMBIGUATE != 0) {
            kittyKey(key, mod)?.let { return it }
        } else if (protocol.modifyOtherKeys > 0) {
            xtermOtherKey(key, mod, protocol)?.let { return it }
        }
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
            TerminalKey.TAB -> withAlt(if (mod and Mod.SHIFT != 0) csi("Z") else ascii('\t'.code), mod)
            TerminalKey.BACKSPACE -> withAlt(ascii(if (mod and Mod.CTRL != 0) 0x08 else 0x7F), mod)
            TerminalKey.ESCAPE -> withAlt(ascii(ESC), mod)
        }
    }

    /**
     * Encodes a printable code point typed with [modifiers]. Ctrl maps letters and the usual
     * punctuation onto C0 controls; Alt prefixes ESC (xterm's `metaSendsEscape`), or with
     * [altSendsMeta] sets the eighth bit of an ASCII result instead (`eightBitInput`); Shift is
     * assumed to be already applied to the code point by the keyboard, and a capital typed with a
     * chord counts as Shift held. Under a [protocol] the program asked for, a chord the legacy
     * encoding folds together is sent as the protocol's own sequence instead, which overrides
     * [altSendsMeta] the way xterm's modifyOtherKeys overrides metaSendsEscape. [base] is the
     * key's character with no modifier (`1` for a Shift+1 that typed `!`), 0 where no key is
     * known; kitty's sequences name the key by it.
     */
    fun encodeText(
        codePoint: Int,
        modifiers: Int,
        altSendsMeta: Boolean = false,
        protocol: KeyboardProtocol = KeyboardProtocol.LEGACY,
        base: Int = 0,
    ): ByteArray {
        val kitty = protocol.kittyFlags and KeyboardProtocol.KITTY_DISAMBIGUATE != 0
        if (kitty || protocol.modifyOtherKeys > 0) {
            var mod = modifiers and ALL_MODS
            if (mod and CHORD != 0 && Character.isUpperCase(codePoint) && Character.toLowerCase(codePoint) != codePoint) mod = mod or Mod.SHIFT
            if (kitty) {
                if (mod and CHORD != 0) return kittyText(codePoint, mod, protocol.kittyFlags, base)
            } else {
                val reported = xtermTextModifiers(codePoint, mod, protocol.modifyOtherKeys)
                if (reported != 0) {
                    val code = if (reported and Mod.SHIFT != 0) Character.toUpperCase(codePoint) else codePoint
                    return otherKey(code, reported, protocol.formatOtherKeys)
                }
            }
        }
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
        '^'.code, '6'.code, '~'.code -> 0x1E
        '_'.code, '7'.code, '-'.code, '/'.code -> 0x1F
        '?'.code, '8'.code -> 0x7F
        else -> -1
    }

    /**
     * The kitty protocol's disambiguated form of a key, or null where it is the legacy one. Esc is
     * `CSI 27 u`; Enter, Tab and Backspace keep their legacy bytes unmodified, so a shell a program
     * left in this mode by crashing still takes `reset` and Enter; F3 is `CSI 13 ~` because
     * `CSI 1 ; mod R` reads as a cursor position report.
     */
    private fun kittyKey(key: TerminalKey, mod: Int): ByteArray? = when (key) {
        TerminalKey.ESCAPE -> csiU(27, mod)
        TerminalKey.ENTER -> if (mod == 0) ascii('\r'.code) else csiU(13, mod)
        TerminalKey.TAB -> if (mod == 0) ascii('\t'.code) else csiU(9, mod)
        TerminalKey.BACKSPACE -> if (mod == 0) ascii(0x7F) else csiU(127, mod)
        TerminalKey.F3 -> if (mod == 0) null else csi("13;${modParam(mod)}~")
        else -> null
    }

    /**
     * `CSI key ; mod u`, the key named by its unshifted character; with the alternate-keys flag, a
     * chord with Shift names the shifted character too, as `key:shifted`.
     */
    private fun kittyText(cp: Int, mod: Int, flags: Int, base: Int): ByteArray {
        val key = if (base > 0) base else Character.toLowerCase(cp)
        val shifted = if (cp == key) Character.toUpperCase(cp) else cp
        val alternate = flags and KeyboardProtocol.KITTY_ALTERNATE_KEYS != 0 && mod and Mod.SHIFT != 0 && shifted != key
        return csi(if (alternate) "$key:$shifted;${modParam(mod)}u" else "$key;${modParam(mod)}u")
    }

    /**
     * xterm's modifyOtherKeys for the keys with a legacy byte of their own. At level 1 the usual
     * Shift and Ctrl keep their meaning (Shift+Tab is back-tab's `CSI Z`, Backspace is left
     * alone, Ctrl+Escape is ESC) and other modifiers encode the key; at level 2 every modifier
     * applies, Shift+Tab included.
     */
    private fun xtermOtherKey(key: TerminalKey, mod: Int, protocol: KeyboardProtocol): ByteArray? {
        if (mod == 0) return null
        val all = protocol.modifyOtherKeys >= 2
        val code = when (key) {
            TerminalKey.ENTER -> 13
            TerminalKey.TAB -> if (!all && mod == Mod.SHIFT) return null else 9
            TerminalKey.BACKSPACE -> if (!all) return null else 127
            TerminalKey.ESCAPE -> if (!all && mod and (Mod.ALT or Mod.META) == 0) return null else 27
            else -> return null
        }
        return otherKey(code, mod, protocol.formatOtherKeys)
    }

    /**
     * The modifiers modifyOtherKeys reports a character typed with, or 0 where it goes in its
     * legacy form. At level 2 every modifier applies but Shift alone, which is the character's
     * (Shift+Space, which has no character of its own, is told apart). Level 1 follows xterm's
     * `input.c` by kind of key: Ctrl alone or Shift alone keeps a letter's legacy form, and
     * `@ [ \ ] ^ _` and the rest of 0x40 to 0x7E with it; a key Ctrl makes a C0 control of (Space,
     * the digits 2 to 8, `/ ? -`) keeps it under any mix of Ctrl and Shift; any other character
     * is Shift's own unless Ctrl is held too.
     */
    private fun xtermTextModifiers(cp: Int, mod: Int, level: Int): Int {
        if (mod == 0) return 0
        if (level >= 2) return if (mod == Mod.SHIFT && cp != ' '.code) 0 else mod
        return when {
            cp in 0x40..0x7E -> if (mod == Mod.CTRL || mod == Mod.SHIFT) 0 else mod
            controlFor(cp) >= 0 -> if (mod and (Mod.ALT or Mod.META) == 0) 0 else mod
            mod and Mod.CTRL == 0 -> mod and Mod.SHIFT.inv()
            else -> mod
        }
    }

    private fun otherKey(code: Int, mod: Int, format: Int): ByteArray =
        if (format == 1) csi("$code;${modParam(mod)}u") else csi("27;${modParam(mod)};$code~")

    private fun csiU(code: Int, mod: Int): ByteArray = if (mod == 0) csi("${code}u") else csi("$code;${modParam(mod)}u")

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
