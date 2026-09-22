package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keys under each keyboard protocol, row by row against the references: the kitty keyboard
 * protocol's specification (sw.kovidgoyal.net/kitty/keyboard-protocol) for its legacy tables and
 * its disambiguated forms, xterm's ctlseqs ("Alt and Meta Keys") and manpage for modifyOtherKeys
 * and formatOtherKeys, with xterm's `input.c` for which keys level 1 leaves alone, and the
 * modified-keys page's control-key examples. Where Berth parts from a reference, the row says so.
 * Then the emulator: the sequences that set, push, pop and query the protocol.
 */
class KeyProtocolTest {
    private val legacy = KeyboardProtocol.LEGACY
    private val kitty = KeyboardProtocol(kittyFlags = KeyboardProtocol.KITTY_DISAMBIGUATE)
    private val kittyAlternates = KeyboardProtocol(kittyFlags = KeyboardProtocol.KITTY_DISAMBIGUATE or KeyboardProtocol.KITTY_ALTERNATE_KEYS)
    private val xterm1 = KeyboardProtocol(modifyOtherKeys = 1)
    private val xterm1u = KeyboardProtocol(modifyOtherKeys = 1, formatOtherKeys = 1)
    private val xterm2 = KeyboardProtocol(modifyOtherKeys = 2)
    private val xterm2u = KeyboardProtocol(modifyOtherKeys = 2, formatOtherKeys = 1)

    private val ctrl = Mod.CTRL
    private val alt = Mod.ALT
    private val shift = Mod.SHIFT

    private fun key(k: TerminalKey, mod: Int = 0, p: KeyboardProtocol = legacy, appKeypad: Boolean = false) =
        show(KeyEncoder.encode(k, mod, applicationCursorKeys = false, applicationKeypad = appKeypad, protocol = p))

    private fun text(c: Char, mod: Int = 0, p: KeyboardProtocol = legacy, base: Char? = null, altSendsMeta: Boolean = false) =
        show(KeyEncoder.encodeText(c.code, mod, altSendsMeta, p, base?.code ?: 0))

    /** Bytes the way the references write them: `CSI `, `SS3 ` and `ESC ` spelled out, other controls and Space in hex. */
    private fun show(bytes: ByteArray): String {
        val s = String(bytes, Charsets.UTF_8)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            when {
                c == '\u001b' && next == '[' -> { out.append("CSI "); i++ }
                c == '\u001b' && next == 'O' -> { out.append("SS3 "); i++ }
                c == '\u001b' -> out.append("ESC ")
                c.code <= 0x20 || c.code == 0x7F -> out.append("0x${c.code.toString(16)} ")
                else -> out.append(c)
            }
            i++
        }
        return out.toString().trimEnd()
    }

    // ---- legacy, against kitty's tables of it ---------------------------------------------------

    @Test
    fun `the legacy encoding is kitty's C0 controls table, Alt+Shift+Tab's ESC CSI Z among it`() {
        val columns = listOf(0, ctrl, alt, shift, ctrl or shift, alt or shift, ctrl or alt)
        val rows = mapOf(
            TerminalKey.ENTER to listOf("0xd", "0xd", "ESC 0xd", "0xd", "0xd", "ESC 0xd", "ESC 0xd"),
            TerminalKey.ESCAPE to listOf("ESC", "ESC", "ESC ESC", "ESC", "ESC", "ESC ESC", "ESC ESC"),
            TerminalKey.BACKSPACE to listOf("0x7f", "0x8", "ESC 0x7f", "0x7f", "0x8", "ESC 0x7f", "ESC 0x8"),
            TerminalKey.TAB to listOf("0x9", "0x9", "ESC 0x9", "CSI Z", "CSI Z", "ESC CSI Z", "ESC 0x9"),
        )
        for ((k, expected) in rows) assertEquals(expected, columns.map { key(k, it) }, "$k")
        assertEquals(listOf("0x20", "0x0", "ESC 0x20", "0x20", "0x0", "ESC 0x20", "ESC 0x0"), columns.map { text(' ', it) }, "Space")
    }

    @Test
    fun `Ctrl maps ASCII onto C0 by kitty's legacy ctrl table, and leaves the keys it does not list alone`() {
        val table = mutableMapOf(
            ' ' to 0, '/' to 31, '0' to 48, '1' to 49, '2' to 0, '3' to 27, '4' to 28, '5' to 29, '6' to 30,
            '7' to 31, '8' to 127, '9' to 57, '?' to 127, '@' to 0, '[' to 27, '\\' to 28, ']' to 29,
            '^' to 30, '_' to 31, '~' to 30,
        )
        for (c in 'a'..'z') table[c] = c - 'a' + 1
        for ((c, byte) in table) assertEquals(show(byteArrayOf(byte.toByte())), text(c, ctrl), "Ctrl+$c")
        for (c in "`=;',.") assertEquals(show(byteArrayOf(c.code.toByte())), text(c, ctrl), "Ctrl+$c")
        // Berth's one addition to the table, as it has always sent it: Ctrl+- is Ctrl+_.
        assertEquals("0x1f", text('-', ctrl))
    }

    @Test
    fun `kitty's example encodings, legacy in every column but Ctrl+Shift, which is kitty's CSI u there and xterm's control here`() {
        // Plain, shift, alt, ctrl, shift+alt, alt+ctrl; the shifted columns type the shifted character.
        fun row(plain: Char, shifted: Char) = listOf(
            text(plain), text(shifted, shift), text(plain, alt), text(plain, ctrl), text(shifted, alt or shift), text(plain, alt or ctrl),
        )
        assertEquals(listOf("i", "I", "ESC i", "0x9", "ESC I", "ESC 0x9"), row('i', 'I'))
        assertEquals(listOf("3", "#", "ESC 3", "ESC", "ESC #", "ESC ESC"), row('3', '#'))
        assertEquals(listOf(";", ":", "ESC ;", ";", "ESC :", "ESC ;"), row(';', ':'))
        // kitty's legacy mode sends Ctrl+Shift as CSI u; Berth's legacy mode is xterm's, where Shift adds nothing to Ctrl.
        assertEquals(listOf("0x9", "#", ":"), listOf(text('I', ctrl or shift), text('#', ctrl or shift), text(':', ctrl or shift)))
        // Under the disambiguate flag they are kitty's own column, the key named by its unshifted character.
        assertEquals(
            listOf("CSI 105;6u", "CSI 51;6u", "CSI 59;6u"),
            listOf(text('I', ctrl or shift, kitty, 'i'), text('#', ctrl or shift, kitty, '3'), text(':', ctrl or shift, kitty, ';')),
        )
    }

    // ---- kitty's disambiguate flag --------------------------------------------------------------

    @Test
    fun `the disambiguate flag sends Esc and the modified Enter, Tab and Backspace as CSI u, and leaves the three their bytes unmodified`() {
        val rows = listOf(
            key(TerminalKey.ESCAPE, 0, kitty) to "CSI 27u",
            key(TerminalKey.ESCAPE, shift, kitty) to "CSI 27;2u",
            key(TerminalKey.ESCAPE, alt, kitty) to "CSI 27;3u",
            key(TerminalKey.ESCAPE, ctrl, kitty) to "CSI 27;5u",
            // So a shell a crashed program left in the mode still takes `reset` and Enter.
            key(TerminalKey.ENTER, 0, kitty) to "0xd",
            key(TerminalKey.ENTER, 0, kitty, appKeypad = true) to "0xd",
            key(TerminalKey.TAB, 0, kitty) to "0x9",
            key(TerminalKey.BACKSPACE, 0, kitty) to "0x7f",
            key(TerminalKey.ENTER, shift, kitty) to "CSI 13;2u",
            key(TerminalKey.ENTER, alt, kitty) to "CSI 13;3u",
            key(TerminalKey.ENTER, ctrl, kitty) to "CSI 13;5u",
            key(TerminalKey.TAB, shift, kitty) to "CSI 9;2u",
            key(TerminalKey.TAB, ctrl, kitty) to "CSI 9;5u",
            key(TerminalKey.TAB, ctrl or shift, kitty) to "CSI 9;6u",
            key(TerminalKey.BACKSPACE, alt, kitty) to "CSI 127;3u",
            key(TerminalKey.BACKSPACE, ctrl, kitty) to "CSI 127;5u",
        )
        for ((actual, expected) in rows) assertEquals(expected, actual)
    }

    @Test
    fun `the disambiguate flag keeps the functional keys' forms from kitty's table, and F3 is CSI 13 ~ once modified`() {
        val rows = listOf(
            key(TerminalKey.INSERT, 0, kitty) to "CSI 2~",
            key(TerminalKey.DELETE, ctrl, kitty) to "CSI 3;5~",
            key(TerminalKey.UP, 0, kitty) to "CSI A",
            key(TerminalKey.UP, shift, kitty) to "CSI 1;2A",
            key(TerminalKey.HOME, 0, kitty) to "CSI H",
            key(TerminalKey.END, ctrl, kitty) to "CSI 1;5F",
            key(TerminalKey.PAGE_DOWN, 0, kitty) to "CSI 6~",
            key(TerminalKey.F1, 0, kitty) to "SS3 P",
            key(TerminalKey.F1, ctrl, kitty) to "CSI 1;5P",
            key(TerminalKey.F3, 0, kitty) to "SS3 R",
            // Not CSI 1;2R: that reads as a cursor position report, which the spec's note removed it for.
            key(TerminalKey.F3, shift, kitty) to "CSI 13;2~",
            key(TerminalKey.F4, alt, kitty) to "CSI 1;3S",
            key(TerminalKey.F5, 0, kitty) to "CSI 15~",
            key(TerminalKey.F12, ctrl, kitty) to "CSI 24;5~",
        )
        for ((actual, expected) in rows) assertEquals(expected, actual)
    }

    @Test
    fun `the disambiguate flag sends alt, ctrl, ctrl+alt and shift+alt with a key as CSI u, the key its unshifted character, and text as text`() {
        val rows = listOf(
            // Ctrl+C is a key event, not SIGINT; Ctrl+I is not Tab, Ctrl+M not Enter, Ctrl+[ not Esc, Ctrl+Space not NUL.
            text('c', ctrl, kitty) to "CSI 99;5u",
            text('i', ctrl, kitty) to "CSI 105;5u",
            text('m', ctrl, kitty) to "CSI 109;5u",
            text('[', ctrl, kitty) to "CSI 91;5u",
            text(' ', ctrl, kitty, ' ') to "CSI 32;5u",
            text(' ', ctrl or shift, kitty, ' ') to "CSI 32;6u",
            // "If the user presses ctrl+shift+a the escape code would be CSI 97;modifiers u. It must not be CSI 65; modifiers u."
            text('A', ctrl or shift, kitty, 'a') to "CSI 97;6u",
            text('A', ctrl or shift, kitty) to "CSI 97;6u",
            // A capital with a chord and no Shift bit was typed with Shift.
            text('A', ctrl, kitty) to "CSI 97;6u",
            text('x', alt, kitty) to "CSI 120;3u",
            text('[', alt, kitty) to "CSI 91;3u",
            text('a', ctrl or alt, kitty) to "CSI 97;7u",
            text('I', alt or shift, kitty, 'i') to "CSI 105;4u",
            text('a', Mod.META, kitty) to "CSI 97;9u",
            text('\u00e9', ctrl, kitty) to "CSI 233;5u",
            // The protocol overrides the host's Alt-sends-Meta, as xterm's modifyOtherKeys overrides metaSendsEscape.
            text('x', alt, kitty, altSendsMeta = true) to "CSI 120;3u",
            text('a', 0, kitty) to "a",
            text('A', shift, kitty) to "A",
            text('!', shift, kitty, '1') to "!",
            text(' ', shift, kitty, ' ') to "0x20",
            text('\u00e9', 0, kitty) to "\u00e9",
        )
        for ((actual, expected) in rows) assertEquals(expected, actual)
    }

    @Test
    fun `the alternate keys flag names the shifted character beside the key, only with Shift, and only where the key was an escape already`() {
        assertEquals("CSI 97:65;6u", text('A', ctrl or shift, kittyAlternates, 'a'))
        assertEquals("CSI 49:33;6u", text('!', ctrl or shift, kittyAlternates, '1'))
        assertEquals("CSI 105:73;4u", text('I', alt or shift, kittyAlternates, 'i'))
        assertEquals("CSI 97;5u", text('a', ctrl, kittyAlternates, 'a'))
        assertEquals("CSI 9;2u", key(TerminalKey.TAB, shift, kittyAlternates))
        assertEquals("CSI 49;6u", text('!', ctrl or shift, kitty, '1'))
        // "A pure enhancement to the form of the escape code": alone it changes nothing.
        val alone = KeyboardProtocol(kittyFlags = KeyboardProtocol.KITTY_ALTERNATE_KEYS)
        assertEquals(listOf("0x1", "ESC", "CSI Z"), listOf(text('a', ctrl, alone), key(TerminalKey.ESCAPE, 0, alone), key(TerminalKey.TAB, shift, alone)))
    }

    // ---- xterm's modifyOtherKeys and formatOtherKeys --------------------------------------------

    @Test
    fun `modifyOtherKeys 1 keeps the usual Shift and Ctrl and encodes the other modifiers, as ctlseqs has it`() {
        val rows = listOf(
            // ctlseqs: "alt-Tab sends CSI 2 7 ; 3 ; 9 ~", and "CSI 9 ; 3 u" under formatOtherKeys.
            key(TerminalKey.TAB, alt, xterm1) to "CSI 27;3;9~",
            key(TerminalKey.TAB, alt, xterm1u) to "CSI 9;3u",
            text('x', alt, xterm1) to "CSI 27;3;120~",
            // ctlseqs: metaSendsEscape applies "unless the modifyOtherKeys resource is set".
            text('x', alt, xterm1, altSendsMeta = true) to "CSI 27;3;120~",
            // "The usual shift- and control-modifiers work as expected."
            text('a', ctrl, xterm1) to "0x1",
            text('A', shift, xterm1) to "A",
            key(TerminalKey.TAB, shift, xterm1) to "CSI Z",
            key(TerminalKey.BACKSPACE, 0, xterm1) to "0x7f",
            key(TerminalKey.BACKSPACE, ctrl, xterm1) to "0x8",
            key(TerminalKey.ESCAPE, ctrl, xterm1) to "ESC",
            // The manpage's exception for "Control-Space to make a NUL", and the other keys Ctrl makes a control of.
            text(' ', ctrl, xterm1) to "0x0",
            text(' ', ctrl or shift, xterm1) to "0x0",
            text('2', ctrl, xterm1) to "0x0",
            text('/', ctrl, xterm1) to "0x1f",
            text('\\', ctrl, xterm1) to "0x1c",
            // Beyond them: the chord legacy folds into Ctrl+A, and control keys with no C0 form (the modified-keys page's).
            text('A', ctrl or shift, xterm1) to "CSI 27;6;65~",
            key(TerminalKey.TAB, ctrl, xterm1) to "CSI 27;5;9~",
            text(',', ctrl, xterm1) to "CSI 27;5;44~",
            text('.', ctrl, xterm1) to "CSI 27;5;46~",
            text(' ', alt, xterm1) to "CSI 27;3;32~",
            key(TerminalKey.ESCAPE, alt, xterm1) to "CSI 27;3;27~",
            key(TerminalKey.ENTER, 0, xterm1) to "0xd",
            key(TerminalKey.ENTER, shift, xterm1) to "CSI 27;2;13~",
            // input.c: Shift is a punctuation key's own unless Ctrl is held too.
            text('!', alt or shift, xterm1) to "CSI 27;3;33~",
            text('!', ctrl or shift, xterm1) to "CSI 27;6;33~",
            // Cursor and function keys keep modifyCursorKeys' and modifyFunctionKeys' form.
            key(TerminalKey.UP, ctrl, xterm1) to "CSI 1;5A",
            key(TerminalKey.F5, shift, xterm1) to "CSI 15;2~",
        )
        for ((actual, expected) in rows) assertEquals(expected, actual)
    }

    @Test
    fun `modifyOtherKeys 2 applies every modifier, Shift+Tab included, and Shift alone stays the character's`() {
        val rows = listOf(
            // ctlseqs: "shift-Tab sends CSI 2 7 ; 2 ; 9 ~ rather than CSI Z".
            key(TerminalKey.TAB, shift, xterm2) to "CSI 27;2;9~",
            key(TerminalKey.TAB, ctrl, xterm2) to "CSI 27;5;9~",
            text(' ', ctrl, xterm2) to "CSI 27;5;32~",
            text(' ', shift, xterm2) to "CSI 27;2;32~",
            text('a', ctrl, xterm2) to "CSI 27;5;97~",
            text('A', ctrl or shift, xterm2) to "CSI 27;6;65~",
            text('A', shift, xterm2) to "A",
            text('!', shift, xterm2) to "!",
            // The modified-keys page's control-/ and control-\.
            text('/', ctrl, xterm2) to "CSI 27;5;47~",
            text('\\', ctrl, xterm2) to "CSI 27;5;92~",
            text(',', ctrl, xterm2) to "CSI 27;5;44~",
            text('!', alt or shift, xterm2) to "CSI 27;4;33~",
            key(TerminalKey.BACKSPACE, ctrl, xterm2) to "CSI 27;5;127~",
            key(TerminalKey.ESCAPE, ctrl, xterm2) to "CSI 27;5;27~",
            key(TerminalKey.ENTER, ctrl, xterm2) to "CSI 27;5;13~",
            key(TerminalKey.ENTER, 0, xterm2) to "0xd",
            key(TerminalKey.UP, ctrl, xterm2) to "CSI 1;5A",
            // formatOtherKeys 1 swaps the form for CSI code ; mod u.
            text(' ', ctrl, xterm2u) to "CSI 32;5u",
            text('A', ctrl or shift, xterm2u) to "CSI 65;6u",
            key(TerminalKey.TAB, shift, xterm2u) to "CSI 9;2u",
        )
        for ((actual, expected) in rows) assertEquals(expected, actual)
    }

    @Test
    fun `kitty's flags win where the program asked for both`() {
        val both = KeyboardProtocol(modifyOtherKeys = 2, formatOtherKeys = 1, kittyFlags = KeyboardProtocol.KITTY_DISAMBIGUATE)
        assertEquals(listOf("CSI 97;5u", "CSI 9;2u", "CSI 27u"), listOf(text('a', ctrl, both), key(TerminalKey.TAB, shift, both), key(TerminalKey.ESCAPE, 0, both)))
    }

    // ---- the emulator ---------------------------------------------------------------------------

    private class Recorder : TerminalListenerAdapter() {
        val responses = StringBuilder()
        override fun onResponse(data: ByteArray) { responses.append(String(data, Charsets.ISO_8859_1)) }

        fun take(): String = responses.toString().also { responses.setLength(0) }
    }

    private fun term(): Pair<TerminalEmulator, Recorder> {
        val r = Recorder()
        return TerminalEmulator(20, 4, 10, r) to r
    }

    private fun TerminalEmulator.kittyFlags(r: Recorder): String {
        write("\u001b[?u")
        return r.take()
    }

    @Test
    fun `CSI = u sets, adds and takes away kitty's flags by its mode, CSI ? u reports them, and only the flags Berth has are kept`() {
        val (t, r) = term()
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        assertEquals(KeyboardProtocol.LEGACY, t.keyboardProtocol)
        t.write("\u001b[=1u")
        assertEquals("\u001b[?1u", t.kittyFlags(r))
        assertEquals(KeyboardProtocol(kittyFlags = 1), t.keyboardProtocol)
        t.write("\u001b[=4;2u")
        assertEquals("\u001b[?5u", t.kittyFlags(r))
        t.write("\u001b[=1;3u")
        assertEquals("\u001b[?4u", t.kittyFlags(r))
        // Key releases, every key as an escape and the text beside it: asked for, refused, and the program reads back what it has.
        t.write("\u001b[=31u")
        assertEquals("\u001b[?5u", t.kittyFlags(r))
        t.write("\u001b[=0u")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
    }

    @Test
    fun `pushes and pops restore the flags before them, a pop past the bottom resets them, and a full stack drops its oldest`() {
        val (t, r) = term()
        t.write("\u001b[>1u")
        t.write("\u001b[>5u")
        assertEquals("\u001b[?5u", t.kittyFlags(r))
        t.write("\u001b[<u")
        assertEquals("\u001b[?1u", t.kittyFlags(r))
        t.write("\u001b[<u")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        t.write("\u001b[<u")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        t.write("\u001b[>1u\u001b[>5u\u001b[<2u")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        // A push with its flags omitted pushes 0.
        t.write("\u001b[=1u\u001b[>u")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        t.write("\u001b[<u")
        assertEquals("\u001b[?1u", t.kittyFlags(r))

        // Flags 5, then one push more than the stack keeps: the 5 underneath is the entry dropped.
        t.write("\u001b[=5u")
        repeat(TerminalEmulator.KITTY_STACK_DEPTH + 1) { t.write("\u001b[>1u") }
        t.write("\u001b[<${TerminalEmulator.KITTY_STACK_DEPTH}u")
        assertEquals("\u001b[?1u", t.kittyFlags(r))
        t.write("\u001b[<u")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
    }

    @Test
    fun `the main and alternate screens keep their own flags, the alternate one starting empty each time, and a reset clears both`() {
        val (t, r) = term()
        t.write("\u001b[=1u")
        t.write("\u001b[?1049h")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        t.write("\u001b[>5u")
        assertEquals("\u001b[?5u", t.kittyFlags(r))
        t.write("\u001b[?1049l")
        assertEquals("\u001b[?1u", t.kittyFlags(r))
        t.write("\u001b[?1049h")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        t.write("\u001b[=5u\u001b[?1049l\u001b[>4;2m")
        t.write("\u001bc")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
        assertEquals(KeyboardProtocol.LEGACY, t.keyboardProtocol)
        t.write("\u001b[?1049h")
        assertEquals("\u001b[?0u", t.kittyFlags(r))
    }

    @Test
    fun `XTMODKEYS sets modifyOtherKeys, resets it with no value or no resource, XTQMODKEYS reports it, and CSI gt 4 n turns it off`() {
        val (t, r) = term()
        t.write("\u001b[>4;1m")
        assertEquals(KeyboardProtocol(modifyOtherKeys = 1), t.keyboardProtocol)
        t.write("\u001b[?4m")
        assertEquals("\u001b[>4;1m", r.take())
        t.write("\u001b[>4;2m")
        assertEquals(2, t.keyboardProtocol.modifyOtherKeys)
        // Level 3 would send unmodified keys too; Berth keys send at 2 there, and the query says so.
        t.write("\u001b[>4;3m\u001b[?4m")
        assertEquals("\u001b[>4;2m", r.take())
        t.write("\u001b[>4m")
        assertEquals(0, t.keyboardProtocol.modifyOtherKeys)
        t.write("\u001b[>4;2m\u001b[>m")
        assertEquals(0, t.keyboardProtocol.modifyOtherKeys)
        t.write("\u001b[>4;2m\u001b[>4n")
        assertEquals(0, t.keyboardProtocol.modifyOtherKeys)
        // The other resources are fixed at what Berth sends: cursor and function keys modified the way xterm's default 2 does.
        t.write("\u001b[>1;0m\u001b[?1m\u001b[?2m\u001b[?3m")
        assertEquals("\u001b[>1;2m\u001b[>2;2m\u001b[>3;0m", r.take())
        assertEquals(KeyboardProtocol.LEGACY, t.keyboardProtocol)
    }

    @Test
    fun `XTFMTKEYS picks formatOtherKeys' form and XTQFMTKEYS reports it`() {
        val (t, r) = term()
        t.write("\u001b[>4;2m\u001b[>4;1f")
        assertEquals(KeyboardProtocol(modifyOtherKeys = 2, formatOtherKeys = 1), t.keyboardProtocol)
        t.write("\u001b[?4g")
        assertEquals("\u001b[>4;1f", r.take())
        t.write("\u001b[>4f\u001b[?4g")
        assertEquals("\u001b[>4;0f", r.take())
        t.write("\u001b[>4;1f\u001b[>f\u001b[?4g")
        assertEquals("\u001b[>4;0f", r.take())
    }

    @Test
    fun `the emulator encodes keys with the protocol the program asked for, and legacy again once it lets go`() {
        val (t, _) = term()
        fun keys() = listOf(show(t.encodeText('a'.code, ctrl)), show(t.encodeText(' '.code, ctrl, base = ' '.code)), show(t.encodeKey(TerminalKey.ESCAPE)))
        assertEquals(listOf("0x1", "0x0", "ESC"), keys())
        t.write("\u001b[>1u")
        assertEquals(listOf("CSI 97;5u", "CSI 32;5u", "CSI 27u"), keys())
        t.write("\u001b[<u\u001b[>4;2m")
        assertEquals(listOf("CSI 27;5;97~", "CSI 27;5;32~", "ESC"), keys())
        t.reset()
        assertEquals(listOf("0x1", "0x0", "ESC"), keys())
    }
}
