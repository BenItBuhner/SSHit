package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharWidthTest {
    @Test
    fun `ascii and latin are single width`() {
        assertEquals(1, CharWidth.of('a'.code))
        assertEquals(1, CharWidth.of(0xE9))
        assertEquals(1, CharWidth.of(0x2500)) // box drawing
    }

    @Test
    fun `cjk hangul and emoji are double width`() {
        assertEquals(2, CharWidth.of(0x4E2D))
        assertEquals(2, CharWidth.of(0xAC00))
        assertEquals(2, CharWidth.of(0x1F600))
        assertEquals(2, CharWidth.of(0xFF21)) // fullwidth A
    }

    @Test
    fun `combining marks and zero width joiners are zero width`() {
        assertEquals(0, CharWidth.of(0x0301))
        assertEquals(0, CharWidth.of(0x200D))
        assertEquals(0, CharWidth.of(0xFE0F))
        assertTrue(CharWidth.isCombining(0x0301))
        assertFalse(CharWidth.isCombining('a'.code))
    }

    @Test
    fun `control characters are zero width`() {
        assertEquals(0, CharWidth.of(0x07))
        assertEquals(0, CharWidth.of(0x7F))
    }
}
