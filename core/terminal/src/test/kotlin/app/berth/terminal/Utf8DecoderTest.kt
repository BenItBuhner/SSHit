package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8DecoderTest {
    private fun decode(vararg chunks: ByteArray): String {
        val d = Utf8Decoder()
        val sb = StringBuilder()
        for (c in chunks) d.decode(c, 0, c.size) { sb.appendCodePoint(it) }
        return sb.toString()
    }

    @Test
    fun `decodes one two three and four byte sequences`() {
        val s = "a\u00e9\u4e2d\uD83D\uDE00"
        assertEquals(s, decode(s.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `sequences split across chunks`() {
        val bytes = "\uD83D\uDE00".toByteArray(Charsets.UTF_8)
        assertEquals("\uD83D\uDE00", decode(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, 3), bytes.copyOfRange(3, 4)))
    }

    @Test
    fun `invalid bytes become replacement characters without eating following text`() {
        assertEquals("a\uFFFDb", decode(byteArrayOf(0x61, 0xFF.toByte(), 0x62)))
        assertEquals("\uFFFDb", decode(byteArrayOf(0xC3.toByte(), 0x62)))
        assertEquals("\uFFFD", decode(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `overlong and surrogate encodings are rejected`() {
        assertEquals("\uFFFD\uFFFD", decode(byteArrayOf(0xC0.toByte(), 0x80.toByte())))
        assertEquals("\uFFFD", decode(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())).take(1))
    }
}
