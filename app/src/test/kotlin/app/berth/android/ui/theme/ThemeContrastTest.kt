package app.berth.android.ui.theme

import androidx.compose.ui.graphics.Color
import app.berth.domain.model.AccentPreset
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast the spec promises (A11: `text.1` and `text.2` meet 4.5:1 on every surface, `text.3`
 * is decorative; `on.accent` is computed for contrast), measured on the tokens themselves for both
 * bundled variants at both ends of the tone slider, the true-black variant, both contrast settings,
 * every interface preset and every accent preset. The screenshot audit samples the same pairs off
 * the pixels; this is the number the pixels come from.
 */
class ThemeContrastTest {
    /** WCAG AA for text. */
    private val text = 4.5f

    /** WCAG AA for large text and for a control's own colour against what it sits on. */
    private val component = 3f

    private fun check(what: String, foreground: Color, background: Color, floor: Float) {
        val ratio = foreground.contrastAgainst(background)
        assertTrue("$what: ${"%.2f".format(ratio)}:1, needs $floor:1", ratio >= floor)
    }

    private val themes: List<Pair<String, InterfaceTheme>> = buildList {
        addAll(InterfaceTheme.presets)
        for (variant in listOf(InterfaceVariant.DARK, InterfaceVariant.TRUE_BLACK, InterfaceVariant.LIGHT)) {
            for (tone in listOf(0f, 1f)) {
                for (contrast in InterfaceContrast.entries) {
                    add("$variant tone $tone $contrast" to InterfaceTheme(variant = variant, tone = tone, contrast = contrast))
                }
            }
        }
    }

    private fun colorsOf(theme: InterfaceTheme): BerthColors = berthColors(theme, dark = theme.isDark(systemDark = true))

    @Test
    fun `text 1 and text 2 read on every surface of every bundled theme`() {
        for ((name, theme) in themes) {
            val c = colorsOf(theme)
            for (step in 0..4) {
                check("$name text1 on surface$step", c.text1, c.surface(step), text)
                check("$name text2 on surface$step", c.text2, c.surface(step), text)
            }
        }
    }

    @Test
    fun `high contrast lifts text 3 to at least large-text contrast on every surface`() {
        for ((name, theme) in themes.filter { it.second.contrast == InterfaceContrast.HIGH }) {
            val c = colorsOf(theme)
            for (step in 0..4) check("$name text3 on surface$step", c.text3, c.surface(step), component)
        }
    }

    @Test
    fun `the state colours stand out from the surfaces they dot`() {
        // Live, attention and danger each also have words beside them; the dot still has to show.
        // Detached is text.3 by design (decorative, the title greys with it) and the pending dot is
        // the accent, whose fill against a light page is the accent model's question, not a token's.
        for ((name, theme) in themes) {
            val c = colorsOf(theme)
            for (step in 0..2) {
                check("$name live on surface$step", c.live, c.surface(step), component)
                check("$name attention on surface$step", c.attention, c.surface(step), component)
                check("$name danger on surface$step", c.danger, c.surface(step), component)
            }
            check("$name onDanger on danger", c.onDanger, c.danger, text)
        }
    }

    @Test
    fun `every accent preset carries a label at text contrast, on both variants`() {
        for (preset in AccentPreset.entries) {
            for (variant in listOf(InterfaceVariant.DARK, InterfaceVariant.LIGHT)) {
                val c = colorsOf(InterfaceTheme(variant = variant, accent = preset.rgb))
                check("$preset $variant onAccent on accent", c.onAccent, c.accent, text)
            }
        }
    }

    @Test
    fun `the ink on an accent is whichever of the two reads better`() {
        // Copper is a mid-tone: near-white on it is 2:1, near-black 8:1; the threshold that chose
        // by luminance alone sent it the near-white ink.
        assertEquals(Color(0xFF1A1408), onAccentFor(AccentPreset.COPPER.rgb.toColor()))
        assertEquals(Color(0xFF1A1408), onAccentFor(AccentPreset.VERDIGRIS.rgb.toColor()))
        assertEquals(Color(0xFF1A1408), onAccentFor(AccentPreset.BONE.rgb.toColor()))
        // A dark custom accent takes the near-white ink.
        assertEquals(Color(0xFFFFF8EE), onAccentFor(Color(0xFF4A4F8F)))
        assertEquals(Color(0xFFFFF8EE), onAccentFor(Color.Black))
        assertEquals(Color(0xFF1A1408), onAccentFor(Color.White))
        // Whatever the accent, the chosen ink is never the worse of the two.
        for (rgb in listOf(0x000000, 0x333333, 0x606060, 0x808080, 0xA0A0A0, 0xFFFFFF, 0xE0A458, 0x5FB3A1, 0x7A9CD6, 0x2F8F7E, 0x4E6E9E)) {
            val accent = rgb.toColor()
            val chosen = onAccentFor(accent).contrastAgainst(accent)
            val other = maxOf(Color(0xFF1A1408).contrastAgainst(accent), Color(0xFFFFF8EE).contrastAgainst(accent))
            assertEquals("accent ${Integer.toHexString(rgb)}", other, chosen, 0.0001f)
        }
    }

    @Test
    fun `contrast ratio matches the WCAG reference points`() {
        assertEquals(21f, Color.White.contrastAgainst(Color.Black), 0.01f)
        assertEquals(1f, Color.Red.contrastAgainst(Color.Red), 0.0001f)
        // #767676 on white is the canonical 4.54:1 boundary colour.
        assertEquals(4.54f, Color(0xFF767676).contrastAgainst(Color.White), 0.01f)
    }
}
