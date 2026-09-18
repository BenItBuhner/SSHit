package app.berth.terminal

/**
 * Cell colors are packed into an Int: bits 24..25 hold the kind, the low 24 bits the payload.
 *
 *  - [COLOR_DEFAULT]: use the theme's default foreground/background.
 *  - indexed: payload is a 0..255 palette index.
 *  - rgb: payload is 0xRRGGBB.
 */
object TermColor {
    const val COLOR_DEFAULT: Int = 0
    private const val KIND_SHIFT = 24
    private const val KIND_MASK = 0x3 shl KIND_SHIFT
    const val KIND_DEFAULT = 0
    const val KIND_INDEXED = 1
    const val KIND_RGB = 2

    fun indexed(index: Int): Int = (KIND_INDEXED shl KIND_SHIFT) or (index and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int =
        (KIND_RGB shl KIND_SHIFT) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun rgb(packed: Int): Int = (KIND_RGB shl KIND_SHIFT) or (packed and 0xFFFFFF)

    fun kind(color: Int): Int = (color and KIND_MASK) ushr KIND_SHIFT

    fun index(color: Int): Int = color and 0xFF

    fun rgbValue(color: Int): Int = color and 0xFFFFFF

    fun isDefault(color: Int): Boolean = kind(color) == KIND_DEFAULT
}

/** Bit flags stored in a cell's attribute word. */
object Attr {
    const val BOLD = 1 shl 0
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val INVISIBLE = 1 shl 6
    const val STRIKETHROUGH = 1 shl 7

    /** This cell holds the first half of a double-width character. */
    const val WIDE = 1 shl 8

    /** This cell is the unused second half of a double-width character. */
    const val WIDE_TAIL = 1 shl 9

    /** Attributes that SGR manipulates; layout flags are excluded. */
    const val SGR_MASK = BOLD or DIM or ITALIC or UNDERLINE or BLINK or INVERSE or INVISIBLE or STRIKETHROUGH
}

enum class CursorShape { BLOCK, UNDERLINE, BAR }

data class CursorStyle(val shape: CursorShape, val blinking: Boolean) {
    companion object {
        val DEFAULT = CursorStyle(CursorShape.BLOCK, blinking = true)

        /** Decodes a DECSCUSR parameter. */
        fun fromDecscusr(value: Int): CursorStyle = when (value) {
            0, 1 -> CursorStyle(CursorShape.BLOCK, blinking = true)
            2 -> CursorStyle(CursorShape.BLOCK, blinking = false)
            3 -> CursorStyle(CursorShape.UNDERLINE, blinking = true)
            4 -> CursorStyle(CursorShape.UNDERLINE, blinking = false)
            5 -> CursorStyle(CursorShape.BAR, blinking = true)
            6 -> CursorStyle(CursorShape.BAR, blinking = false)
            else -> DEFAULT
        }
    }
}
