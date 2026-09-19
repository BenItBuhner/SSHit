package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasteClassifierTest {
    @Test
    fun `one plain line under the threshold pastes straight through`() {
        val a = PasteClassifier.analyze("ls -la /var/log")
        assertEquals(1, a.lines)
        assertEquals(0, a.lineBreaks)
        assertFalse(a.hasControlChars)
        assertFalse(a.needsPreview)
    }

    @Test
    fun `more than one line needs a preview and lines are counted as a person would`() {
        val a = PasteClassifier.analyze("cd /srv\ngit pull\n")
        assertEquals(2, a.lines)
        assertEquals(2, a.lineBreaks)
        assertTrue(a.isMultiLine)
        assertTrue(a.needsPreview)
        assertEquals(1, PasteClassifier.analyze("one line\n").lines)
        assertTrue(PasteClassifier.analyze("one line\n").needsPreview)
        assertEquals(1, PasteClassifier.analyze("a\r\nb").lineBreaks)
        assertEquals(2, PasteClassifier.analyze("a\r\nb").lines)
        assertEquals(2, PasteClassifier.analyze("a\rb").lines)
    }

    @Test
    fun `length past the threshold needs a preview`() {
        assertFalse(PasteClassifier.analyze("x".repeat(PasteClassifier.PREVIEW_CHARS)).needsPreview)
        val long = PasteClassifier.analyze("x".repeat(PasteClassifier.PREVIEW_CHARS + 1))
        assertTrue(long.isLong)
        assertTrue(long.needsPreview)
        // Characters are code points, so 150 kanji are 150 characters, not 300 bytes' worth.
        assertEquals(150, PasteClassifier.analyze("日".repeat(150)).chars)
    }

    @Test
    fun `control characters need a preview but tab and line breaks are not control`() {
        assertTrue(PasteClassifier.analyze("echo hi\u001b[A").hasControlChars)
        assertTrue(PasteClassifier.analyze("a\u0000b").hasControlChars)
        assertTrue(PasteClassifier.analyze("a\u007fb").hasControlChars)
        assertTrue(PasteClassifier.analyze("a\u0085b").hasControlChars)
        assertFalse(PasteClassifier.analyze("col1\tcol2").hasControlChars)
        assertFalse(PasteClassifier.analyze("col1\tcol2").needsPreview)
        assertFalse(PasteClassifier.analyze("a\nb").hasControlChars)
    }

    @Test
    fun `paste as one line joins trimmed lines with single spaces`() {
        assertEquals("cd /tmp ls", PasteClassifier.asOneLine("cd /tmp\n  ls  \n\n"))
        assertEquals("a b c", PasteClassifier.asOneLine("a\r\nb\rc"))
        assertEquals("", PasteClassifier.asOneLine("\n\n"))
    }

    @Test
    fun `stripping control keeps tabs and line breaks`() {
        assertEquals("echo hi\n\tdone", PasteClassifier.stripControl("echo \u001bhi\u0007\n\tdone\u007f"))
    }

    @Test
    fun `empty text is nothing to paste`() {
        val a = PasteClassifier.analyze("")
        assertEquals(0, a.lines)
        assertEquals(0, a.chars)
        assertFalse(a.needsPreview)
    }
}
