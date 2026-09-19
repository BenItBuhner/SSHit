package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    private class Recorder : TerminalListenerAdapter() {
        val responses = StringBuilder()
        var title = ""
        var bells = 0
        var clipboard: String? = null
        var cwd: String? = null
        val notifications = ArrayList<Pair<String, String>>()
        val marks = ArrayList<Pair<Char, String>>()
        var screenChanges = 0

        override fun onScreenChanged() { screenChanges++ }
        override fun onTitleChanged(title: String) { this.title = title }
        override fun onBell() { bells++ }
        override fun onResponse(data: ByteArray) { responses.append(String(data, Charsets.ISO_8859_1)) }
        override fun onClipboardWrite(text: String) { clipboard = text }
        override fun onWorkingDirectoryChanged(url: String) { cwd = url }
        override fun onNotification(title: String, body: String) { notifications += title to body }
        override fun onShellIntegration(mark: Char, param: String) { marks += mark to param }
    }

    private fun term(cols: Int = 10, rows: Int = 4, scrollback: Int = 100): Pair<TerminalEmulator, Recorder> {
        val r = Recorder()
        return TerminalEmulator(cols, rows, scrollback, r) to r
    }

    private fun TerminalEmulator.text(row: Int) = screenText()[row]

    @Test
    fun `prints text and advances the cursor`() {
        val (t, _) = term()
        t.write("hello")
        assertEquals("hello", t.text(0))
        assertEquals(5, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    @Test
    fun `wraps at the right margin only when the next character arrives`() {
        val (t, _) = term(cols = 5)
        t.write("abcde")
        assertEquals(4, t.cursorX)
        assertEquals(0, t.cursorY)
        t.write("f")
        assertEquals("abcde", t.text(0))
        assertEquals("f", t.text(1))
        assertTrue(t.line(0).wrapped)
        assertEquals(1, t.cursorX)
    }

    @Test
    fun `carriage return after a full line does not wrap`() {
        val (t, _) = term(cols = 5)
        t.write("abcde\r\nx")
        assertEquals("abcde", t.text(0))
        assertEquals("x", t.text(1))
        assertFalse(t.line(0).wrapped)
    }

    @Test
    fun `line feed at the bottom scrolls into scrollback`() {
        val (t, _) = term(cols = 5, rows = 2)
        t.write("one\r\ntwo\r\nthree")
        assertEquals("two", t.text(0))
        assertEquals("three", t.text(1))
        assertEquals(1, t.scrollbackSize)
        assertEquals("one", t.viewLine(0, 1).toText())
    }

    @Test
    fun `scrollback is bounded`() {
        val (t, _) = term(cols = 5, rows = 2, scrollback = 3)
        for (i in 0 until 10) t.write("$i\r\n")
        assertEquals(3, t.scrollbackSize)
    }

    @Test
    fun `cursor movement sequences clamp to the screen`() {
        val (t, _) = term(cols = 10, rows = 4)
        t.write("\u001b[2;3H")
        assertEquals(2, t.cursorX)
        assertEquals(1, t.cursorY)
        t.write("\u001b[99C")
        assertEquals(9, t.cursorX)
        t.write("\u001b[99A")
        assertEquals(0, t.cursorY)
        t.write("\u001b[3B")
        assertEquals(3, t.cursorY)
        t.write("\u001b[5D")
        assertEquals(4, t.cursorX)
        t.write("\u001b[H")
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    @Test
    fun `erase in line and display`() {
        val (t, _) = term(cols = 6, rows = 3)
        t.write("abcdef\r\nghijkl\r\nmnopqr")
        t.write("\u001b[2;3H\u001b[K")
        assertEquals("gh", t.text(1))
        t.write("\u001b[1;3H\u001b[1K")
        assertEquals("   def", t.text(0))
        t.write("\u001b[2;1H\u001b[J")
        assertEquals("   def", t.text(0))
        assertEquals("", t.text(1))
        assertEquals("", t.text(2))
        t.write("\u001b[2J")
        assertEquals("", t.text(0))
    }

    @Test
    fun `erase display 3 clears scrollback`() {
        val (t, _) = term(cols = 5, rows = 2)
        t.write("a\r\nb\r\nc")
        assertEquals(1, t.scrollbackSize)
        t.write("\u001b[3J")
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun `sgr sets colors and attributes`() {
        val (t, _) = term()
        t.write("\u001b[1;3;4;31;42mX\u001b[0mY")
        val l = t.line(0)
        assertEquals(TermColor.indexed(1), l.fg[0])
        assertEquals(TermColor.indexed(2), l.bg[0])
        assertTrue(l.attrs[0] and Attr.BOLD != 0)
        assertTrue(l.attrs[0] and Attr.ITALIC != 0)
        assertTrue(l.attrs[0] and Attr.UNDERLINE != 0)
        assertEquals(TermColor.COLOR_DEFAULT, l.fg[1])
        assertEquals(0, l.attrs[1])
    }

    @Test
    fun `sgr 256 color and truecolor in both separator forms`() {
        val (t, _) = term()
        t.write("\u001b[38;5;208mA\u001b[38:5:100mB\u001b[38;2;10;20;30mC\u001b[48:2::40:50:60mD\u001b[m")
        val l = t.line(0)
        assertEquals(TermColor.indexed(208), l.fg[0])
        assertEquals(TermColor.indexed(100), l.fg[1])
        assertEquals(TermColor.rgb(10, 20, 30), l.fg[2])
        assertEquals(TermColor.rgb(40, 50, 60), l.bg[3])
    }

    @Test
    fun `sgr bright colors and resets`() {
        val (t, _) = term()
        t.write("\u001b[91;104mA\u001b[39;49mB")
        val l = t.line(0)
        assertEquals(TermColor.indexed(9), l.fg[0])
        assertEquals(TermColor.indexed(12), l.bg[0])
        assertEquals(TermColor.COLOR_DEFAULT, l.fg[1])
        assertEquals(TermColor.COLOR_DEFAULT, l.bg[1])
    }

    @Test
    fun `underline style subparameter zero clears underline`() {
        val (t, _) = term()
        t.write("\u001b[4:3mA\u001b[4:0mB")
        assertTrue(t.line(0).attrs[0] and Attr.UNDERLINE != 0)
        assertEquals(0, t.line(0).attrs[1] and Attr.UNDERLINE)
    }

    @Test
    fun `scroll region confines line feeds`() {
        val (t, _) = term(cols = 5, rows = 4)
        t.write("a\r\nb\r\nc\r\nd")
        t.write("\u001b[2;3r")
        assertEquals(0, t.cursorY)
        t.write("\u001b[3;2Hx\n")
        assertEquals("a", t.text(0))
        assertEquals("cx", t.text(1))
        assertEquals("", t.text(2))
        assertEquals("d", t.text(3))
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun `reverse index at top of region scrolls down`() {
        val (t, _) = term(cols = 5, rows = 3)
        t.write("a\r\nb\r\nc")
        t.write("\u001b[1;1H\u001bM")
        assertEquals("", t.text(0))
        assertEquals("a", t.text(1))
        assertEquals("b", t.text(2))
    }

    @Test
    fun `insert and delete lines`() {
        val (t, _) = term(cols = 5, rows = 4)
        t.write("a\r\nb\r\nc\r\nd")
        t.write("\u001b[2;1H\u001b[L")
        assertEquals(listOf("a", "", "b", "c"), t.screenText())
        t.write("\u001b[1;1H\u001b[2M")
        assertEquals(listOf("b", "c", "", ""), t.screenText())
    }

    @Test
    fun `insert delete and erase characters`() {
        val (t, _) = term(cols = 8, rows = 1)
        t.write("abcdefgh")
        t.write("\u001b[1;3H\u001b[2@")
        assertEquals("ab  cdef", t.text(0))
        t.write("\u001b[1;1H\u001b[2P")
        assertEquals("  cdef", t.text(0))
        t.write("\u001b[1;3H\u001b[2X")
        assertEquals("    ef", t.text(0))
    }

    @Test
    fun `insert mode shifts existing text`() {
        val (t, _) = term(cols = 6, rows = 1)
        t.write("abc\u001b[1;1H\u001b[4hXY\u001b[4l")
        assertEquals("XYabc", t.text(0))
    }

    @Test
    fun `alternate screen preserves the main screen and restores the cursor`() {
        val (t, _) = term(cols = 10, rows = 3)
        t.write("main\u001b[2;2H")
        t.write("\u001b[?1049h")
        assertTrue(t.isAlternateScreen)
        assertEquals("", t.text(0))
        // xterm keeps the cursor position when switching buffers.
        assertEquals(1, t.cursorX)
        assertEquals(1, t.cursorY)
        t.write("\u001b[Halt")
        assertEquals("alt", t.text(0))
        t.write("\u001b[?1049l")
        assertFalse(t.isAlternateScreen)
        assertEquals("main", t.text(0))
        assertEquals(1, t.cursorX)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun `alternate screen never writes scrollback`() {
        val (t, _) = term(cols = 5, rows = 2)
        t.write("\u001b[?1049h")
        for (i in 0 until 10) t.write("$i\r\n")
        assertEquals(0, t.scrollbackSize)
        t.write("\u001b[?1049l")
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun `tabs use eight column stops and respect custom stops`() {
        val (t, _) = term(cols = 20, rows = 1)
        t.write("\t")
        assertEquals(8, t.cursorX)
        t.write("\t")
        assertEquals(16, t.cursorX)
        t.write("\t")
        assertEquals(19, t.cursorX)
        t.write("\u001b[3g\u001b[1;5H\u001bH\u001b[1;1H\t")
        assertEquals(4, t.cursorX)
        t.write("\u001b[Z")
        assertEquals(0, t.cursorX)
    }

    @Test
    fun `wide characters occupy two cells`() {
        val (t, _) = term(cols = 6, rows = 1)
        t.write("a\u4e2db")
        val l = t.line(0)
        assertEquals('a'.code, l.chars[0])
        assertEquals(0x4e2d, l.chars[1])
        assertTrue(l.attrs[1] and Attr.WIDE != 0)
        assertTrue(l.attrs[2] and Attr.WIDE_TAIL != 0)
        assertEquals('b'.code, l.chars[3])
        assertEquals("a\u4e2db", l.toText())
    }

    @Test
    fun `wide character at the last column wraps whole`() {
        val (t, _) = term(cols = 4, rows = 2)
        t.write("abc\u4e2d")
        assertEquals("abc", t.text(0))
        assertEquals("\u4e2d", t.text(1))
    }

    @Test
    fun `overwriting half of a wide character clears the other half`() {
        val (t, _) = term(cols = 6, rows = 1)
        t.write("\u4e2d\u001b[1;2Hx")
        val l = t.line(0)
        assertEquals(0, l.chars[0])
        assertEquals('x'.code, l.chars[1])
        assertEquals(0, l.attrs[0] and Attr.WIDE)
    }

    @Test
    fun `combining marks attach to the previous cell`() {
        val (t, _) = term()
        t.write("e\u0301x")
        assertEquals("e\u0301", t.line(0).cellText(0))
        assertEquals("x", t.line(0).cellText(1))
        assertEquals(2, t.cursorX)
    }

    @Test
    fun `invalid utf8 becomes replacement character`() {
        val (t, _) = term()
        t.write(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        assertEquals("a\uFFFDb", t.text(0))
    }

    @Test
    fun `utf8 split across writes decodes correctly`() {
        val (t, _) = term()
        val bytes = "\u4e2d".toByteArray(Charsets.UTF_8)
        t.write(bytes, 0, 1)
        t.write(bytes, 1, 2)
        assertEquals("\u4e2d", t.text(0))
    }

    @Test
    fun `device status report and cursor position`() {
        val (t, r) = term()
        t.write("\u001b[5n")
        assertEquals("\u001b[0n", r.responses.toString())
        r.responses.setLength(0)
        t.write("\u001b[3;4H\u001b[6n")
        assertEquals("\u001b[3;4R", r.responses.toString())
    }

    @Test
    fun `device attributes`() {
        val (t, r) = term()
        t.write("\u001b[c")
        assertTrue(r.responses.startsWith("\u001b[?62"))
        r.responses.setLength(0)
        t.write("\u001b[>c")
        assertEquals("\u001b[>41;354;0c", r.responses.toString())
    }

    @Test
    fun `window size report`() {
        val (t, r) = term(cols = 80, rows = 24)
        t.write("\u001b[18t")
        assertEquals("\u001b[8;24;80t", r.responses.toString())
    }

    @Test
    fun `bracketed paste wraps pasted text when enabled`() {
        val (t, _) = term()
        assertContentEquals("a\rb".toByteArray(), t.encodePaste("a\nb"))
        t.write("\u001b[?2004h")
        assertTrue(t.bracketedPaste)
        val pasted = String(t.encodePaste("a\r\nb"), Charsets.UTF_8)
        assertEquals("\u001b[200~a\rb\u001b[201~", pasted)
        t.write("\u001b[?2004l")
        assertFalse(t.bracketedPaste)
    }

    @Test
    fun `application cursor keys change arrow encoding`() {
        val (t, _) = term()
        assertContentEquals("\u001b[A".toByteArray(), t.encodeKey(TerminalKey.UP))
        t.write("\u001b[?1h")
        assertContentEquals("\u001bOA".toByteArray(), t.encodeKey(TerminalKey.UP))
        assertContentEquals("\u001b[1;5A".toByteArray(), t.encodeKey(TerminalKey.UP, Mod.CTRL))
    }

    @Test
    fun `titles bell and clipboard reach the listener`() {
        val (t, r) = term()
        t.write("\u001b]0;hello\u0007")
        assertEquals("hello", r.title)
        assertEquals("hello", t.title)
        t.write("\u001b]2;world\u001b\\")
        assertEquals("world", r.title)
        t.write("\u0007")
        assertEquals(1, r.bells)
        t.write("\u001b]52;c;aGVsbG8=\u0007")
        assertEquals("hello", r.clipboard)
    }

    @Test
    fun `shell integration and notifications reach the listener`() {
        val (t, r) = term()
        t.write("\u001b]7;file://host/home/ben\u0007")
        assertEquals("file://host/home/ben", r.cwd)
        t.write("\u001b]133;A\u0007\u001b]133;D;0\u0007")
        assertEquals(listOf('A' to "", 'D' to "0"), r.marks)
        t.write("\u001b]9;done\u0007")
        t.write("\u001b]777;notify;Build;finished\u0007")
        t.write("\u001b]99;i=1;kitty\u0007")
        assertEquals(listOf("" to "done", "Build" to "finished", "" to "kitty"), r.notifications)
    }

    @Test
    fun `osc color queries answer with the theme colors`() {
        val (t, r) = term()
        t.applyTheme(Palette.BERTH_DARK_ANSI, 0x112233, 0x445566)
        t.write("\u001b]10;?\u0007")
        assertEquals("\u001b]10;rgb:1111/2222/3333\u001b\\", r.responses.toString())
        r.responses.setLength(0)
        t.write("\u001b]11;?\u001b\\")
        assertEquals("\u001b]11;rgb:4444/5555/6666\u001b\\", r.responses.toString())
    }

    @Test
    fun `osc 4 changes and reports palette entries`() {
        val (t, r) = term()
        t.write("\u001b]4;1;rgb:ff/00/00\u0007")
        assertEquals(0xFF0000, t.palette[1])
        t.write("\u001b]4;1;?\u0007")
        assertEquals("\u001b]4;1;rgb:ffff/0000/0000\u001b\\", r.responses.toString())
        t.write("\u001b]104;1\u0007")
        assertEquals(Palette.defaultColor(1), t.palette[1])
    }

    @Test
    fun `mouse modes and encoding`() {
        val (t, _) = term(cols = 80, rows = 24)
        assertNull(t.encodeMouse(MouseButton.WHEEL_UP, 0, 0))
        t.write("\u001b[?1000h\u001b[?1006h")
        assertEquals(MouseTracking.NORMAL, t.mouseTracking)
        assertTrue(t.mouseSgrEncoding)
        assertContentEquals("\u001b[<0;5;7M".toByteArray(), t.encodeMouse(MouseButton.LEFT, 4, 6))
        assertContentEquals("\u001b[<0;5;7m".toByteArray(), t.encodeMouse(MouseButton.LEFT, 4, 6, release = true))
        assertContentEquals("\u001b[<64;1;1M".toByteArray(), t.encodeMouse(MouseButton.WHEEL_UP, 0, 0))
        assertNull(t.encodeMouse(MouseButton.LEFT, 4, 6, motion = true))
        t.write("\u001b[?1006l")
        assertContentEquals(byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), 32, 33, 33), t.encodeMouse(MouseButton.LEFT, 0, 0))
        t.write("\u001b[?1000l")
        assertEquals(MouseTracking.NONE, t.mouseTracking)
    }

    @Test
    fun `cursor style and visibility`() {
        val (t, _) = term()
        t.write("\u001b[4 q")
        assertEquals(CursorStyle(CursorShape.UNDERLINE, blinking = false), t.cursorStyle)
        t.write("\u001b[?25l")
        assertFalse(t.cursorVisible)
        t.write("\u001b[?25h")
        assertTrue(t.cursorVisible)
    }

    @Test
    fun `decrqm reports mode state`() {
        val (t, r) = term()
        t.write("\u001b[?2004\$p")
        assertEquals("\u001b[?2004;2\$y", r.responses.toString())
        r.responses.setLength(0)
        t.write("\u001b[?2004h\u001b[?2004\$p")
        assertEquals("\u001b[?2004;1\$y", r.responses.toString())
    }

    @Test
    fun `save and restore cursor with attributes`() {
        val (t, _) = term()
        t.write("\u001b[1;31m\u001b[2;3H\u001b7\u001b[m\u001b[Hx\u001b8y")
        val l = t.line(1)
        assertEquals('y'.code, l.chars[2])
        assertEquals(TermColor.indexed(1), l.fg[2])
        assertTrue(l.attrs[2] and Attr.BOLD != 0)
    }

    @Test
    fun `dec special graphics maps line drawing`() {
        val (t, _) = term()
        t.write("\u001b(0lqk\u001b(B")
        assertEquals("\u250c\u2500\u2510", t.text(0))
    }

    @Test
    fun `repeat previous character`() {
        val (t, _) = term()
        t.write("a\u001b[3b")
        assertEquals("aaaa", t.text(0))
    }

    @Test
    fun `screen alignment test fills the screen`() {
        val (t, _) = term(cols = 3, rows = 2)
        t.write("\u001b#8")
        assertEquals(listOf("EEE", "EEE"), t.screenText())
    }

    @Test
    fun `soft and full reset`() {
        val (t, r) = term()
        t.write("\u001b[?1h\u001b[?2004h\u001b[?1049hhello\u001b]0;t\u0007")
        t.write("\u001b[!p")
        assertFalse(t.applicationCursorKeys)
        assertTrue(t.bracketedPaste)
        assertTrue(t.isAlternateScreen)
        t.write("\u001bc")
        assertFalse(t.bracketedPaste)
        assertFalse(t.isAlternateScreen)
        assertEquals("", t.title)
        assertEquals("", r.title)
        assertEquals("", t.text(0))
    }

    @Test
    fun `resize without reflow on alternate screen`() {
        val (t, _) = term(cols = 10, rows = 4)
        t.write("\u001b[?1049h")
        t.write("abcdefghij\r\nsecond")
        t.resize(6, 3)
        assertEquals(6, t.cols)
        assertEquals(3, t.rows)
        assertEquals("abcdef", t.text(0))
        assertEquals("second", t.text(1))
    }

    @Test
    fun `resize reflows soft wrapped lines`() {
        val (t, _) = term(cols = 10, rows = 4)
        t.write("abcdefghijklmno\r\nxyz")
        assertEquals("abcdefghij", t.text(0))
        assertEquals("klmno", t.text(1))
        t.resize(20, 4)
        assertEquals("abcdefghijklmno", t.text(0))
        assertEquals("xyz", t.text(1))
        assertEquals(3, t.cursorX)
        assertEquals(1, t.cursorY)
        t.resize(5, 4)
        assertEquals(listOf("abcde", "fghij", "klmno", "xyz"), t.screenText())
        assertEquals(3, t.cursorX)
        assertEquals(3, t.cursorY)
    }

    @Test
    fun `resize taller pulls history back onto the screen`() {
        val (t, _) = term(cols = 5, rows = 2)
        t.write("a\r\nb\r\nc")
        assertEquals(1, t.scrollbackSize)
        t.resize(5, 3)
        assertEquals(listOf("a", "b", "c"), t.screenText())
        assertEquals(0, t.scrollbackSize)
        assertEquals(2, t.cursorY)
    }

    @Test
    fun `resize shorter moves top lines into scrollback`() {
        val (t, _) = term(cols = 5, rows = 4)
        t.write("a\r\nb\r\nc\r\nd")
        t.resize(5, 2)
        assertEquals(listOf("c", "d"), t.screenText())
        assertEquals(2, t.scrollbackSize)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun `resize to same size is a no-op`() {
        val (t, r) = term(cols = 5, rows = 2)
        t.write("ab")
        val before = r.screenChanges
        t.resize(5, 2)
        assertEquals(before, r.screenChanges)
    }

    @Test
    fun `resize of the height alone keeps every line as it is`() {
        val (t, _) = term(cols = 5, rows = 4)
        t.write("abcdefg\r\nx")
        assertEquals(listOf("abcde", "fg", "x", ""), t.screenText())
        val wrapped = t.line(0)
        val tail = t.line(1)
        val cursorLine = t.line(2)
        t.resize(5, 2)
        // The blank row went first, then the top row to history untouched; the rest are the same objects.
        assertEquals(1, t.scrollbackSize)
        assertTrue(t.viewLine(0, 1) === wrapped && wrapped.wrapped)
        assertTrue(t.line(0) === tail)
        assertTrue(t.line(1) === cursorLine)
        assertEquals(listOf("fg", "x"), t.screenText())
        assertEquals(1, t.cursorX)
        assertEquals(1, t.cursorY)
        t.resize(5, 6)
        // Growing pulls history back, still the same lines, and the cursor follows its line down.
        assertEquals(0, t.scrollbackSize)
        assertTrue(t.line(0) === wrapped && t.line(1) === tail && t.line(2) === cursorLine)
        assertEquals(2, t.cursorY)
    }

    @Test
    fun `reflow keeps lines that already fit and rewraps the rest`() {
        val (t, _) = term(cols = 10, rows = 4)
        t.write("short\r\n\u001b[44mblue\u001b[0m\r\nabcdefghijklmno")
        val short = t.line(0)
        val blue = t.line(1)
        t.resize(8, 4)
        assertEquals(listOf("short", "blue", "abcdefgh", "ijklmno"), t.screenText())
        // Lines that fit keep their identity and their cell colours; the long one was re-wrapped.
        assertTrue(t.line(0) === short)
        assertTrue(t.line(1) === blue)
        assertEquals(TermColor.indexed(4), t.line(1).bg[0])
        assertTrue(t.line(2).wrapped)
        assertFalse(t.line(3).wrapped)
        assertEquals(7, t.cursorX)
        assertEquals(3, t.cursorY)
        t.resize(20, 4)
        assertEquals(listOf("short", "blue", "abcdefghijklmno", ""), t.screenText())
        assertEquals(15, t.cursorX)
        assertEquals(2, t.cursorY)
    }

    @Test
    fun `reflow carries wide characters and combining marks`() {
        val (t, _) = term(cols = 8, rows = 3)
        t.write("a\u0301\u4F60\u597D\u4E16")
        assertEquals("a\u0301\u4F60\u597D\u4E16", t.text(0))
        assertEquals(7, t.cursorX)
        t.resize(4, 3)
        // "a" with its mark, then one wide glyph; the next wide glyph would split, so it wraps whole,
        // and the cursor that followed the last glyph lands at the start of a fresh row.
        assertEquals("a\u0301\u4F60", t.text(0))
        assertEquals("\u597D\u4E16", t.text(1))
        assertTrue(t.line(0).wrapped)
        assertEquals("a\u0301", t.line(0).cellText(0))
        assertEquals(0, t.cursorX)
        assertEquals(2, t.cursorY)
    }

    @Test
    fun `large writes are parsed in slices and each slice is announced`() {
        val (t, r) = term(cols = 80, rows = 24, scrollback = 10)
        val slice = TerminalEmulator.WRITE_SLICE_BYTES
        val text = ("x".repeat(79) + "\r\n").repeat(5 * slice / 81 + 1).take(5 * slice)
        t.write(text)
        assertEquals(5, r.screenChanges)
        assertEquals(24, t.screenText().size)
        // A multi-byte character straddling a slice boundary still decodes as one glyph.
        val (u, _) = term(cols = 80, rows = 2, scrollback = 0)
        val bytes = ByteArray(slice - 1) { 'a'.code.toByte() } + "\u4F60".toByteArray(Charsets.UTF_8) + "b".toByteArray()
        u.write(bytes)
        assertEquals("a".repeat((slice - 1) % 80) + "\u4F60b", u.text(1))
    }

    @Test
    fun `origin mode positions relative to the scroll region`() {
        val (t, _) = term(cols = 5, rows = 5)
        t.write("\u001b[2;4r\u001b[?6h\u001b[1;1Hx")
        assertEquals("x", t.text(1))
        t.write("\u001b[99;1Hy")
        assertEquals("y", t.text(3))
    }

    @Test
    fun `parser survives garbage and long strings`() {
        val (t, _) = term()
        t.write("\u001b[999999999999;;;;:::m")
        t.write("\u001b]0;" + "x".repeat(10_000) + "\u0007ok")
        assertEquals("ok", t.text(0))
        t.write("\u001b[?" + "9".repeat(50) + "hstill")
        assertEquals("okstill", t.text(0))
    }

    @Test
    fun `screen change notifications are coalesced per write`() {
        val (t, r) = term()
        t.write("abc")
        assertEquals(1, r.screenChanges)
        t.write("\u001b[?25l")
        assertEquals(2, r.screenChanges)
    }

    @Test
    fun `synchronized output defers notifications until the end`() {
        val (t, r) = term()
        t.write("\u001b[?2026h")
        val n = r.screenChanges
        t.write("hidden")
        assertEquals(n, r.screenChanges)
        t.write("\u001b[?2026l")
        assertTrue(r.screenChanges > n)
        assertEquals("hidden", t.text(0))
    }

    @Test
    fun `parses color specs`() {
        assertEquals(0xFF8000, TerminalEmulator.parseColorSpec("#ff8000"))
        assertEquals(0xFF8000, TerminalEmulator.parseColorSpec("rgb:ff/80/00"))
        assertEquals(0xFF8000, TerminalEmulator.parseColorSpec("rgb:ffff/8000/0000"))
        assertNull(TerminalEmulator.parseColorSpec("nonsense"))
    }

    @Test
    fun `viewLine reaches into scrollback`() {
        val (t, _) = term(cols = 5, rows = 2)
        t.write("a\r\nb\r\nc\r\nd")
        assertEquals("c", t.viewLine(0, 0).toText())
        assertEquals("b", t.viewLine(0, 1).toText())
        assertEquals("a", t.viewLine(0, 2).toText())
        assertNotNull(t.viewLine(1, 2))
        assertEquals("b", t.viewLine(1, 2).toText())
    }
}
