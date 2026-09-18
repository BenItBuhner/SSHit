package app.berth.terminal

/** The 256-entry xterm palette as 0xRRGGBB values. Slots 0..15 default to the Berth Dark theme. */
object Palette {
    /** Berth Dark ANSI colors: the emulator's defaults until a theme is applied. */
    val BERTH_DARK_ANSI: IntArray = intArrayOf(
        0x1F1D1A, 0xD9776B, 0x8FB573, 0xE0A458, 0x7A9CD6, 0xC387B8, 0x7AD3C6, 0xC9C4BB,
        0x5C5853, 0xE89486, 0xA6CB8C, 0xEBBB79, 0x9BB6E6, 0xD6A3CC, 0x98E0D5, 0xF3EFE8,
    )

    const val BERTH_DARK_FOREGROUND = 0xECE8E1
    const val BERTH_DARK_BACKGROUND = 0x121110

    private val CUBE_LEVELS = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)

    fun defaultColor(index: Int): Int = when {
        index < 16 -> BERTH_DARK_ANSI[index]
        index < 232 -> {
            val i = index - 16
            val r = CUBE_LEVELS[i / 36]
            val g = CUBE_LEVELS[(i / 6) % 6]
            val b = CUBE_LEVELS[i % 6]
            (r shl 16) or (g shl 8) or b
        }
        else -> {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }

    fun defaultPalette(): IntArray = IntArray(256) { defaultColor(it) }
}
