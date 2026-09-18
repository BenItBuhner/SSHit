package app.berth.terminal

/**
 * Incremental UTF-8 decoder following the WHATWG algorithm: sequences may be split across
 * [decode] calls, and malformed input is replaced with U+FFFD per maximal subpart so a bad byte
 * from the remote can never wedge the terminal or swallow the text that follows it.
 */
class Utf8Decoder {
    private var pending = 0
    private var remaining = 0
    private var lower = 0x80
    private var upper = 0xBF

    fun decode(bytes: ByteArray, offset: Int, length: Int, sink: (Int) -> Unit) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            i++
            if (remaining > 0) {
                if (b in lower..upper) {
                    lower = 0x80
                    upper = 0xBF
                    pending = (pending shl 6) or (b and 0x3F)
                    remaining--
                    if (remaining == 0) sink(pending)
                    continue
                }
                reset()
                sink(REPLACEMENT)
                // Fall through: the byte that broke the sequence is decoded on its own.
            }
            when {
                b < 0x80 -> sink(b)
                b in 0xC2..0xDF -> start(b and 0x1F, 1)
                b in 0xE0..0xEF -> {
                    if (b == 0xE0) lower = 0xA0
                    if (b == 0xED) upper = 0x9F
                    start(b and 0x0F, 2)
                }
                b in 0xF0..0xF4 -> {
                    if (b == 0xF0) lower = 0x90
                    if (b == 0xF4) upper = 0x8F
                    start(b and 0x07, 3)
                }
                else -> sink(REPLACEMENT)
            }
        }
    }

    private fun start(initial: Int, continuation: Int) {
        pending = initial
        remaining = continuation
    }

    fun reset() {
        pending = 0
        remaining = 0
        lower = 0x80
        upper = 0xBF
    }

    companion object {
        const val REPLACEMENT = 0xFFFD
    }
}
