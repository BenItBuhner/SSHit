package app.berth.domain

import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckModifier
import app.berth.domain.model.HexColorSerializer
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.ReconnectBackoff
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelTest {
    @Test
    fun `default deck round-trips through json and matches the spec`() {
        val layout = DeckLayout.default()
        assertEquals(listOf("Base", "Symbols", "Nav/Fn", "tmux", "Snippets"), layout.layers.map { it.name })
        val base = layout.layers[0]
        assertEquals(listOf("Esc", "Tab", "Ctrl", "Alt", "-", "/", "Nub"), base.keys.map { it.label })
        assertEquals(listOf("`", "S-Tab", "^c", "^r", "|", "\\", null), base.keys.map { it.secondaryLabel })
        assertEquals("CTRL b", layout.layers[3].prefix)

        val json = layout.toJson()
        assertTrue(json.contains("\"height_dp\": 44"))
        val back = DeckLayout.fromJson(json)
        assertEquals(layout, back)
    }

    @Test
    fun `combo parsing splits modifiers from the target`() {
        val combo = DeckAction.Combo("CTRL SHIFT c")
        assertEquals(setOf(DeckModifier.CTRL, DeckModifier.SHIFT), combo.modifiers)
        assertEquals("c", combo.target)
        assertEquals("TAB", DeckAction.Combo("SHIFT TAB").target)
    }

    @Test
    fun `deck json accepts hand-written keys`() {
        val text = """
            {"layers":[{"name":"Mine","keys":[{"tap":{"kind":"key","key":"HOME"}},{"tap":{"kind":"text","text":"ls"},"up":{"kind":"combo","combo":"CTRL l"}}]}]}
        """.trimIndent()
        val layout = DeckLayout.fromJson(text)
        assertEquals(1, layout.rows)
        assertEquals(DeckAction.Key(DeckKeyCode.HOME), layout.layers[0].keys[0].tap)
        assertEquals("ls", layout.layers[0].keys[1].label)
    }

    @Test
    fun `terminal theme json uses hex colours`() {
        val json = TerminalTheme.BERTH_DARK.toJson()
        assertTrue(json.contains("\"background\": \"#121110\""))
        assertTrue(json.contains("\"cursor_text\""))
        val back = TerminalTheme.fromJson(json)
        assertEquals(TerminalTheme.BERTH_DARK, back)
        assertEquals(0xABCDEF, HexColorSerializer.parse("#abcdef"))
        assertEquals(0xFFFFFF, HexColorSerializer.parse("fff"))
    }

    @Test
    fun `monograms follow the spec rule`() {
        assertEquals("PW", Host.monogramFor("prod-web"))
        assertEquals("DP", Host.monogramFor("db primary"))
        assertEquals("BA", Host.monogramFor("bastion"))
        assertEquals("X?", Host.monogramFor("x"))
        assertEquals("AG", Host.monogramFor("api_gateway"))
    }

    @Test
    fun `swatch colour for a name is stable`() {
        assertEquals(SwatchColor.forName("prod-web"), SwatchColor.forName("prod-web"))
    }

    @Test
    fun `snippet placeholders are detected with defaults`() {
        val s = Snippet(id = "1", name = "tail", body = "tail -n {{lines:100}} -f {{file}} {{lines}}")
        assertEquals(listOf("lines" to "100", "file" to null), s.placeholders())
    }

    @Test
    fun `reconnect backoff schedule`() {
        assertEquals(listOf(1, 2, 4, 8, 15, 30, 60, 60), (0 until 8).map(ReconnectBackoff::delaySeconds))
        assertTrue(ReconnectBackoff.shouldRetry(14 * 60_000L, PersistencePolicy(reconnectMinutes = 15)))
        assertFalse(ReconnectBackoff.shouldRetry(15 * 60_000L, PersistencePolicy(reconnectMinutes = 15)))
        assertTrue(ReconnectBackoff.shouldRetry(Long.MAX_VALUE / 2, PersistencePolicy(reconnectMinutes = 0)))
    }
}
