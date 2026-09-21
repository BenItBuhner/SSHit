package app.berth.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mono roles draw the ASCII they are given: JetBrains Mono's ligatures and contextual
 * alternates are off on every one of them, in both font settings, so a randomart, a fingerprint or
 * an `ssh-keygen` line on a sheet is the server's glyph for glyph; the sans roles leave the font
 * its defaults.
 */
class BerthTypeTest {
    @Test
    fun `the mono roles have ligatures and contextual alternates off`() {
        for (roles in listOf(BerthType, BerthTypeSystem)) {
            assertEquals("-liga, -calt", roles.mono.fontFeatureSettings)
            assertEquals("-liga, -calt", roles.monoLarge.fontFeatureSettings)
            assertEquals("-liga, -calt", roles.monoBody.fontFeatureSettings)
            assertEquals("the terminal's off case and the interface's are one string", MonoFontFeatures, roles.mono.fontFeatureSettings)
        }
    }

    @Test
    fun `a copy for a size keeps the features off`() {
        assertEquals(MonoFontFeatures, BerthType.mono.copy(fontSize = BerthType.body.fontSize, lineHeight = BerthType.body.lineHeight).fontFeatureSettings)
    }

    @Test
    fun `the sans roles keep the font's defaults`() {
        for (style in listOf(BerthType.display, BerthType.title, BerthType.headline, BerthType.body, BerthType.bodyMedium, BerthType.label, BerthType.caption)) {
            assertNull(style.fontFeatureSettings)
        }
    }
}
