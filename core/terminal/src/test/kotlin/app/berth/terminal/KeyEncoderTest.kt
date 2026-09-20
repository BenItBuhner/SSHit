package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KeyEncoderTest {
    private fun enc(key: TerminalKey, mod: Int = 0, appCursor: Boolean = false, appKeypad: Boolean = false) =
        String(KeyEncoder.encode(key, mod, appCursor, appKeypad), Charsets.ISO_8859_1)

    @Test
    fun `arrows in normal and application mode`() {
        assertEquals("\u001b[A", enc(TerminalKey.UP))
        assertEquals("\u001b[B", enc(TerminalKey.DOWN))
        assertEquals("\u001b[C", enc(TerminalKey.RIGHT))
        assertEquals("\u001b[D", enc(TerminalKey.LEFT))
        assertEquals("\u001bOA", enc(TerminalKey.UP, appCursor = true))
        assertEquals("\u001b[1;2A", enc(TerminalKey.UP, Mod.SHIFT))
        assertEquals("\u001b[1;3D", enc(TerminalKey.LEFT, Mod.ALT))
        assertEquals("\u001b[1;5C", enc(TerminalKey.RIGHT, Mod.CTRL))
        assertEquals("\u001b[1;6C", enc(TerminalKey.RIGHT, Mod.CTRL or Mod.SHIFT))
    }

    @Test
    fun `navigation keys`() {
        assertEquals("\u001b[H", enc(TerminalKey.HOME))
        assertEquals("\u001b[F", enc(TerminalKey.END))
        assertEquals("\u001bOH", enc(TerminalKey.HOME, appCursor = true))
        assertEquals("\u001b[2~", enc(TerminalKey.INSERT))
        assertEquals("\u001b[3~", enc(TerminalKey.DELETE))
        assertEquals("\u001b[5~", enc(TerminalKey.PAGE_UP))
        assertEquals("\u001b[6~", enc(TerminalKey.PAGE_DOWN))
        assertEquals("\u001b[3;5~", enc(TerminalKey.DELETE, Mod.CTRL))
    }

    @Test
    fun `function keys`() {
        assertEquals("\u001bOP", enc(TerminalKey.F1))
        assertEquals("\u001bOS", enc(TerminalKey.F4))
        assertEquals("\u001b[15~", enc(TerminalKey.F5))
        assertEquals("\u001b[24~", enc(TerminalKey.F12))
        assertEquals("\u001b[1;2P", enc(TerminalKey.F1, Mod.SHIFT))
    }

    @Test
    fun `editing keys`() {
        assertEquals("\r", enc(TerminalKey.ENTER))
        assertEquals("\u001bOM", enc(TerminalKey.ENTER, appKeypad = true))
        assertEquals("\t", enc(TerminalKey.TAB))
        assertEquals("\u001b[Z", enc(TerminalKey.TAB, Mod.SHIFT))
        assertEquals("\u007f", enc(TerminalKey.BACKSPACE))
        assertEquals("\u0008", enc(TerminalKey.BACKSPACE, Mod.CTRL))
        assertEquals("\u001b\u007f", enc(TerminalKey.BACKSPACE, Mod.ALT))
        assertEquals("\u001b", enc(TerminalKey.ESCAPE))
    }

    @Test
    fun `control characters`() {
        assertContentEquals(byteArrayOf(0x03), KeyEncoder.encodeText('c'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x03), KeyEncoder.encodeText('C'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x00), KeyEncoder.encodeText(' '.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x1B), KeyEncoder.encodeText('['.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x1F), KeyEncoder.encodeText('-'.code, Mod.CTRL))
        assertContentEquals(byteArrayOf(0x1B, 'x'.code.toByte()), KeyEncoder.encodeText('x'.code, Mod.ALT))
        assertContentEquals(byteArrayOf(0x1B, 0x01), KeyEncoder.encodeText('a'.code, Mod.ALT or Mod.CTRL))
        assertContentEquals("\u00e9".toByteArray(Charsets.UTF_8), KeyEncoder.encodeText(0xE9, 0))
        assertContentEquals("1".toByteArray(), KeyEncoder.encodeText('1'.code, Mod.CTRL))
    }

    @Test
    fun `Alt as Meta sets the eighth bit of ASCII and falls back to the Escape prefix elsewhere`() {
        // xterm's eightBitInput: Alt+x is x with bit 7 set, Alt+Ctrl+a is 0x01 with bit 7 set.
        assertContentEquals(byteArrayOf(0xF8.toByte()), KeyEncoder.encodeText('x'.code, Mod.ALT, altSendsMeta = true))
        assertContentEquals(byteArrayOf(0x81.toByte()), KeyEncoder.encodeText('a'.code, Mod.ALT or Mod.CTRL, altSendsMeta = true))
        assertContentEquals(byteArrayOf(0xD8.toByte()), KeyEncoder.encodeText('X'.code, Mod.ALT or Mod.SHIFT, altSendsMeta = true))
        // Outside ASCII there is no eighth bit to set; Escape leads as usual.
        assertContentEquals(byteArrayOf(0x1B) + "\u00e9".toByteArray(Charsets.UTF_8), KeyEncoder.encodeText(0xE9, Mod.ALT, altSendsMeta = true))
        // Without Alt the flag changes nothing, and the default stays the Escape prefix.
        assertContentEquals("x".toByteArray(), KeyEncoder.encodeText('x'.code, 0, altSendsMeta = true))
        assertContentEquals(byteArrayOf(0x1B, 'x'.code.toByte()), KeyEncoder.encodeText('x'.code, Mod.ALT, altSendsMeta = false))
    }

    @Test
    fun `every hardware modifier reaches the CSI parameter on navigation and function keys`() {
        // 1 + Shift(1) + Alt(2) + Ctrl(4): Home, End, PgUp, PgDn and the arrows all carry it.
        assertEquals("\u001b[1;2H", enc(TerminalKey.HOME, Mod.SHIFT))
        assertEquals("\u001b[1;5F", enc(TerminalKey.END, Mod.CTRL))
        assertEquals("\u001b[1;3H", enc(TerminalKey.HOME, Mod.ALT))
        assertEquals("\u001b[5;5~", enc(TerminalKey.PAGE_UP, Mod.CTRL))
        assertEquals("\u001b[6;2~", enc(TerminalKey.PAGE_DOWN, Mod.SHIFT))
        assertEquals("\u001b[1;7A", enc(TerminalKey.UP, Mod.CTRL or Mod.ALT))
        assertEquals("\u001b[1;8B", enc(TerminalKey.DOWN, Mod.CTRL or Mod.ALT or Mod.SHIFT))
        // Application cursor mode yields to the modifier form, as xterm does.
        assertEquals("\u001b[1;5D", enc(TerminalKey.LEFT, Mod.CTRL, appCursor = true))
        // F1 to F4 take the SS3 form plain and the CSI 1;m form modified; F5 upwards the tilde form.
        assertEquals("\u001b[1;5Q", enc(TerminalKey.F2, Mod.CTRL))
        assertEquals("\u001b[17;3~", enc(TerminalKey.F6, Mod.ALT))
        assertEquals("\u001b[24;6~", enc(TerminalKey.F12, Mod.CTRL or Mod.SHIFT))
        // Escape and the editing keys with Alt: Escape leads.
        assertEquals("\u001b\u001b", enc(TerminalKey.ESCAPE, Mod.ALT))
        assertEquals("\u001b\r", enc(TerminalKey.ENTER, Mod.ALT))
        assertEquals("\u001b\t", enc(TerminalKey.TAB, Mod.ALT))
        // Ctrl+Shift+letter is the same C0 control as Ctrl+letter without modifyOtherKeys.
        assertContentEquals(byteArrayOf(0x01), KeyEncoder.encodeText('A'.code, Mod.CTRL or Mod.SHIFT))
    }

    @Test
    fun `mouse encodings`() {
        assertContentEquals("\u001b[<2;10;20M".toByteArray(), MouseEncoder.encode(MouseButton.RIGHT, 9, 19, 0, release = false, motion = false, sgr = true))
        assertContentEquals("\u001b[<32;10;20M".toByteArray(), MouseEncoder.encode(MouseButton.LEFT, 9, 19, 0, release = false, motion = true, sgr = true))
        assertContentEquals("\u001b[<16;1;1M".toByteArray(), MouseEncoder.encode(MouseButton.LEFT, 0, 0, Mod.CTRL, release = false, motion = false, sgr = true))
        assertContentEquals("\u001b[<4;1;1M".toByteArray(), MouseEncoder.encode(MouseButton.LEFT, 0, 0, Mod.SHIFT, release = false, motion = false, sgr = true))
        assertContentEquals(byteArrayOf(0x1B, 0x5B, 0x4D, (32 + 3).toByte(), 33, 33), MouseEncoder.encode(MouseButton.LEFT, 0, 0, 0, release = true, motion = false, sgr = false))
        assertContentEquals(byteArrayOf(0x1B, 0x5B, 0x4D, (32 + 65).toByte(), 34, 35), MouseEncoder.encode(MouseButton.WHEEL_DOWN, 1, 2, 0, release = false, motion = false, sgr = false))
    }
}
