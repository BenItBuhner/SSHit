package app.berth.domain

import app.berth.domain.model.ColorMath
import app.berth.domain.model.DECK_MAX_KEYS_PER_LAYER
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckGesture
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckModifier
import app.berth.domain.model.DeckPresets
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TermuxExtraKeys
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TerminalThemes
import app.berth.domain.model.ThemeSlot
import app.berth.domain.model.Workspace
import app.berth.domain.model.action
import app.berth.domain.model.addLayer
import app.berth.domain.model.describe
import app.berth.domain.model.insertKey
import app.berth.domain.model.isEmpty
import app.berth.domain.model.moveKey
import app.berth.domain.model.moveLayer
import app.berth.domain.model.removeKey
import app.berth.domain.model.removeLayer
import app.berth.domain.model.renameLayer
import app.berth.domain.model.resetLayer
import app.berth.domain.model.setKey
import app.berth.domain.model.warnings
import app.berth.domain.model.withAction
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDomainTest {
    // ---- terminal theme editing -----------------------------------------------------------------

    @Test
    fun `every theme slot reads back what was written`() {
        var theme = TerminalTheme.BERTH_DARK
        val slots = (0..15).map { ThemeSlot.Ansi(it) } + ThemeSlot.named
        slots.forEachIndexed { i, slot ->
            val rgb = 0x010101 * (i + 1)
            theme = theme.with(slot, rgb)
            assertEquals(rgb, theme.color(slot), "slot ${slot.title}")
        }
        assertEquals(16, theme.ansi.size)
        assertEquals(TerminalTheme.BERTH_DARK.id, theme.id)
    }

    @Test
    fun `bold inherits when cleared and other slots ignore null`() {
        val bold = TerminalTheme.BERTH_DARK.with(ThemeSlot.Bold, 0xFFFFFF)
        assertEquals(0xFFFFFF, bold.bold)
        assertNull(bold.with(ThemeSlot.Bold, null).bold)
        assertEquals(TerminalTheme.BERTH_DARK, TerminalTheme.BERTH_DARK.with(ThemeSlot.Background, null))
    }

    @Test
    fun `duplicating a stock theme yields an editable copy`() {
        val copy = TerminalTheme.NORD.duplicate("nord-mine", "Nord mine")
        assertEquals("nord-mine", copy.id)
        assertEquals("Nord mine", copy.name)
        assertFalse(copy.builtIn)
        assertEquals(TerminalTheme.NORD.ansi, copy.ansi)
        assertEquals("Nord copy", TerminalTheme.NORD.duplicate("x").name)
    }

    @Test
    fun `curated presets are complete, unique and round-trip`() {
        val ids = TerminalTheme.builtIns.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(TerminalTheme.builtIns.size >= 5)
        for (t in TerminalTheme.builtIns) {
            assertTrue(t.builtIn, t.id)
            assertEquals(16, t.ansi.size, t.id)
            assertEquals(t, TerminalTheme.fromJson(t.toJson()), t.id)
            assertTrue(ColorMath.contrast(t.foreground, t.background) >= 4.0, "${t.id} foreground contrast")
        }
        assertTrue(TerminalTheme.BERTH_DARK.isDark)
        assertFalse(TerminalTheme.BERTH_LIGHT.isDark)
        assertTrue(TerminalTheme.SOLARIZED_DARK.isDark)
    }

    @Test
    fun `the gallery ships the spec's eighteen stock themes in its order`() {
        assertEquals(
            listOf(
                "Berth Dark", "Berth Light", "Catppuccin Mocha", "Catppuccin Latte", "Gruvbox Dark", "Gruvbox Light", "Nord",
                "Solarized Dark", "Solarized Light", "Ros\u00E9 Pine", "Tokyo Night", "Kanagawa", "Everforest", "Dracula",
                "One Dark", "Ayu", "Modus Vivendi", "Modus Operandi",
            ),
            TerminalTheme.builtIns.map { it.name },
        )
        val light = setOf(
            TerminalTheme.BERTH_LIGHT_ID, TerminalTheme.CATPPUCCIN_LATTE_ID, TerminalTheme.GRUVBOX_LIGHT_ID,
            TerminalTheme.SOLARIZED_LIGHT_ID, TerminalTheme.MODUS_OPERANDI_ID,
        )
        for (t in TerminalTheme.builtIns) {
            assertEquals(t.id !in light, t.isDark, "${t.id} is ${if (t.id in light) "light" else "dark"}")
            assertNotNull(t.suggestedAccent, "${t.id} suggests an accent")
            assertTrue(t.selection != t.background, "${t.id} selection shows")
        }
    }

    @Test
    fun `hsl round-trips within a channel step`() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0xE0A458, 0x121110, 0x7A9CD6, 0x8FB573, 0x0000FF, 0x123456)) {
            val hsl = ColorMath.toHsl(rgb)
            val back = ColorMath.fromHsl(hsl[0], hsl[1], hsl[2])
            assertTrue(abs(ColorMath.red(back) - ColorMath.red(rgb)) <= 1, "red of %06x".format(rgb))
            assertTrue(abs(ColorMath.green(back) - ColorMath.green(rgb)) <= 1, "green of %06x".format(rgb))
            assertTrue(abs(ColorMath.blue(back) - ColorMath.blue(rgb)) <= 1, "blue of %06x".format(rgb))
        }
        assertEquals(0xFF0000, ColorMath.fromHsl(0f, 1f, 0.5f))
        assertEquals(0x808080, ColorMath.fromHsl(200f, 0f, 0.5f))
    }

    @Test
    fun `contrast and luminance follow wcag`() {
        assertEquals(21.0, ColorMath.contrast(0x000000, 0xFFFFFF), 0.01)
        assertEquals(1.0, ColorMath.contrast(0x777777, 0x777777), 0.001)
        assertTrue(ColorMath.luminance(0xFFFFFF) > ColorMath.luminance(0x808080))
        assertTrue(ColorMath.luminance(0x808080) > ColorMath.luminance(0x000000))
        assertEquals(0x808080, ColorMath.mix(0x000000, 0xFFFFFF, 0.5f))
        assertEquals(0x000000, ColorMath.mix(0x000000, 0xFFFFFF, 0f))
        assertEquals(0xFFFFFF, ColorMath.mix(0x000000, 0xFFFFFF, 1f))
    }

    @Test
    fun `spec theme json without id and with bold inherit imports`() {
        val text = """
            {
              "name": "Berth Dark",
              "ansi": ["#1F1D1A","#D9776B","#8FB573","#E0A458","#7A9CD6","#C387B8","#7AD3C6","#C9C4BB",
                       "#5C5853","#E89486","#A6CB8C","#EBBB79","#9BB6E6","#D6A3CC","#98E0D5","#F3EFE8"],
              "background": "#121110", "foreground": "#ECE8E1",
              "cursor": "#E0A458", "cursor_text": "#121110",
              "selection": "#3A3630", "bold": "inherit", "links": "#7A9CD6",
              "suggested_accent": "#E0A458"
            }
        """.trimIndent()
        val theme = assertNotNull(TerminalThemes.import(text))
        assertEquals("berth-dark", theme.id)
        assertNull(theme.bold)
        assertFalse(theme.builtIn)
        assertEquals(TerminalTheme.BERTH_DARK.ansi, theme.ansi)
        assertEquals(0xE0A458, theme.suggestedAccent)
        assertEquals("berth-dark-2", assertNotNull(TerminalThemes.import(text, idSuffix = "2")).id)
    }

    @Test
    fun `exported theme json imports back unchanged`() {
        val mine = TerminalTheme.GRUVBOX_DARK.duplicate("mine", "Mine").with(ThemeSlot.Bold, 0xFFFFFF)
        val back = assertNotNull(TerminalThemes.import(mine.toJson()))
        assertEquals(mine, back)
    }

    @Test
    fun `windows terminal schemes import and garbage does not`() {
        val scheme = """
            {"name": "Campbell", "black": "#0C0C0C", "red": "#C50F1F", "green": "#13A10E", "yellow": "#C19C00",
             "blue": "#0037DA", "purple": "#881798", "cyan": "#3A96DD", "white": "#CCCCCC",
             "brightBlack": "#767676", "brightRed": "#E74856", "brightGreen": "#16C60C", "brightYellow": "#F9F1A5",
             "brightBlue": "#3B78FF", "brightPurple": "#B4009E", "brightCyan": "#61D6D6", "brightWhite": "#F2F2F2",
             "background": "#0C0C0C", "foreground": "#CCCCCC", "cursorColor": "#FFFFFF", "selectionBackground": "#FFFFFF"}
        """.trimIndent()
        val theme = assertNotNull(TerminalThemes.import(scheme))
        assertEquals("campbell", theme.id)
        assertEquals(0x0037DA, theme.ansi[4])
        assertEquals(0xB4009E, theme.ansi[13])
        assertEquals(0xFFFFFF, theme.cursor)
        assertEquals(0x0C0C0C, theme.cursorText)
        assertNull(TerminalThemes.import("not json"))
        assertNull(TerminalThemes.import("{\"name\": \"x\"}"))
        assertNull(TerminalThemes.import("{\"ansi\": [\"#fff\"], \"background\": \"#000\", \"foreground\": \"#fff\"}"))
        assertEquals(2, TerminalThemes.importAll("[${TerminalTheme.NORD.toJson()}, $scheme]").size)
    }

    @Test
    fun `slugs are stable and never empty`() {
        assertEquals("catppuccin-mocha", TerminalThemes.slug("Catppuccin Mocha"))
        assertEquals("theme", TerminalThemes.slug("***"))
        assertEquals("my-theme-2", TerminalThemes.slug(" My  Theme 2! "))
    }

    // ---- interface theme and workspace ---------------------------------------------------------

    @Test
    fun `interface theme keeps its radius scale through json and defaults to one`() {
        assertEquals(1f, InterfaceTheme.DEFAULT.radiusScale)
        val soft = InterfaceTheme(radiusScale = 1.3f)
        assertEquals(soft, InterfaceTheme.fromJson(soft.toJson()))
        assertEquals(InterfaceTheme.DEFAULT, InterfaceTheme.fromJson("{\"tone\": 0.15}"))
        assertTrue(InterfaceTheme.presets.map { it.first }.containsAll(listOf("Graphite", "Black", "Paper")))
    }

    @Test
    fun `workspace terminal theme is optional and serialises`() {
        val ws = Workspace("w", "Work", SwatchColor.SLATE, "W", createdAt = 1L)
        assertNull(ws.terminalThemeId)
        val themed = ws.copy(terminalThemeId = TerminalTheme.NORD_ID)
        assertEquals(TerminalTheme.NORD_ID, themed.terminalThemeId)
    }

    // ---- deck editing ---------------------------------------------------------------------------

    private val deck = DeckLayout.default()

    @Test
    fun `keys move, insert, replace and remove within a layer`() {
        val moved = deck.moveKey(0, 0, 2)
        assertEquals(listOf("Tab", "Ctrl", "Esc", "Alt", "-", "/", "Nub"), moved.layers[0].keys.map { it.label })
        val back = moved.moveKey(0, 2, 0)
        assertEquals(deck, back)
        assertEquals(deck, deck.moveKey(0, 9, 0))

        val inserted = deck.insertKey(0, 1, DeckKey(tap = DeckAction.Key(DeckKeyCode.HOME)))
        assertEquals("Home", inserted.layers[0].keys[1].label)
        assertEquals(8, inserted.layers[0].keys.size)
        assertEquals(deck.layers[1], inserted.layers[1])

        val removed = inserted.removeKey(0, 1)
        assertEquals(deck, removed)

        val replaced = deck.setKey(0, 6, DeckKey(tap = DeckAction.Key(DeckKeyCode.ENTER)))
        assertEquals("Enter", replaced.layers[0].keys[6].label)
        assertFalse(replaced.layers[0].keys[6].nub)
    }

    @Test
    fun `a layer never exceeds the key ceiling`() {
        var layout = DeckLayout(layers = listOf(deck.layers[0]))
        repeat(20) { layout = layout.insertKey(0, layout.layers[0].keys.size) }
        assertEquals(DECK_MAX_KEYS_PER_LAYER, layout.layers[0].keys.size)
    }

    @Test
    fun `layers add with unique names, rename, reorder, reset and never vanish entirely`() {
        val added = deck.addLayer("Base")
        assertEquals("Base 2", added.layers.last().name)
        assertEquals("Base 3", added.addLayer("Base").layers.last().name)
        assertEquals("Layer", deck.addLayer("   ").layers.last().name)

        assertEquals("Home", deck.renameLayer(0, " Home ").layers[0].name)
        assertEquals("Base", deck.renameLayer(0, "").layers[0].name)

        val moved = deck.moveLayer(3, 0)
        assertEquals(listOf("tmux", "Base", "Symbols", "Nav/Fn", "Snippets"), moved.layers.map { it.name })

        var one = DeckLayout(layers = listOf(deck.layers[0]))
        one = one.removeLayer(0)
        assertEquals(1, one.layers.size)
        assertEquals(4, deck.removeLayer(1).layers.size)

        val broken = deck.setKey(1, 0, DeckKey())
        val restored = broken.resetLayer(1)
        assertEquals(deck.layers[1], restored.layers[1])
        val custom = deck.addLayer("Mine", listOf(DeckKey(tap = DeckAction.Text("x"))))
        assertTrue(custom.resetLayer(custom.layers.lastIndex).layers.last().keys.isEmpty())
    }

    @Test
    fun `gestures bind actions and binding clears special roles`() {
        val nub = DeckKey(nub = true)
        assertTrue(nub.action(DeckGesture.TAP) == null)
        val bound = nub.withAction(DeckGesture.TAP, DeckAction.Key(DeckKeyCode.ESC))
        assertFalse(bound.nub)
        assertEquals(DeckAction.Key(DeckKeyCode.ESC), bound.action(DeckGesture.TAP))
        val held = bound.withAction(DeckGesture.HOLD, DeckAction.Strip(listOf(DeckKeyCode.F1, DeckKeyCode.F2)))
        assertEquals("F1\u2013F2 strip", held.hold!!.describe())
        assertTrue(DeckKey().isEmpty)
        assertFalse(held.withAction(DeckGesture.TAP, null).isEmpty)
        assertTrue(held.withAction(DeckGesture.TAP, null).withAction(DeckGesture.HOLD, null).isEmpty)
    }

    @Test
    fun `actions describe themselves for the slot panel`() {
        assertEquals("Escape", DeckAction.Key(DeckKeyCode.ESC).describe())
        assertEquals("Ctrl (modifier)", DeckAction.Modifier(DeckModifier.CTRL).describe())
        assertEquals("Ctrl+C", DeckAction.Combo("CTRL c").describe())
        assertEquals("Shift+Tab", DeckAction.Combo("SHIFT TAB").describe())
        assertEquals("Ctrl+Alt+Del", DeckAction.Combo("CTRL ALT DEL").describe())
        assertEquals("Text \u201Cls -la\u201D", DeckAction.Text("ls -la").describe())
        assertEquals("Macro Prefix d", DeckAction.Macro("PREFIX d").describe())
        assertEquals("Snippet restart", DeckAction.Snippet("restart").describe())
        assertEquals("Hide keyboard", DeckAction.App(DeckAppAction.HIDE_KEYBOARD).describe())
    }

    @Test
    fun `warnings flag crowded and empty layers`() {
        assertTrue(deck.warnings().isEmpty())
        var crowded = deck
        repeat(3) { crowded = crowded.insertKey(0, 0) }
        assertTrue(crowded.warnings().any { it.startsWith("Base: 10 keys") })
        val emptied = deck.addLayer("Blank")
        assertTrue(emptied.warnings().any { it.startsWith("Blank: no keys") })
    }

    @Test
    fun `presets are distinct, valid and round-trip through json`() {
        assertEquals(listOf("default", "vim", "tmux", "minimal"), DeckPresets.all.map { it.id })
        for (preset in DeckPresets.all) {
            assertTrue(preset.layout.layers.isNotEmpty(), preset.id)
            assertEquals(preset.layout, DeckLayout.fromJson(preset.layout.toJson()), preset.id)
            assertTrue(preset.layout.warnings().isEmpty(), "${preset.id}: ${preset.layout.warnings()}")
        }
        assertEquals(DeckLayout.default(), DeckPresets.DEFAULT.layout)
        assertEquals("tmux", DeckPresets.TMUX.layout.layers[0].name)
        assertEquals(DeckAction.Macro(":w ENTER"), DeckPresets.VIM.layout.layers[0].keys[5].tap)
        assertEquals(1, DeckPresets.MINIMAL.layout.layers.size)
        assertEquals(DeckPresets.VIM, DeckPresets.byId("vim"))
        assertNull(DeckPresets.byId("nope"))
    }

    // ---- termux extra-keys --------------------------------------------------------------------

    @Test
    fun `termux properties with continuation lines and popups import`() {
        val props = """
            # termux.properties
            extra-keys-style = default
            extra-keys = [['ESC','/','-','HOME','UP','END','PGUP'], \
                          ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN'], \
                          [{key: 'BKSP', popup: 'DEL'}, {macro: 'CTRL f d', display: 'tmux exit'}, 'SCROLL', 'KEYBOARD']]
        """.trimIndent()
        val layout = assertNotNull(TermuxExtraKeys.parse(props))
        assertEquals(3, layout.layers.size)
        assertEquals(2, layout.rows)
        assertEquals(DeckArrows.FOUR_KEYS, layout.arrows)
        assertEquals(listOf("Esc", "/", "-", "Home", "\u2191", "End", "PgUp"), layout.layers[0].keys.map { it.label })
        assertEquals(DeckAction.Modifier(DeckModifier.CTRL), layout.layers[1].keys[1].tap)
        val third = layout.layers[2].keys
        assertEquals(3, third.size)
        assertEquals(DeckAction.Key(DeckKeyCode.BACKSPACE), third[0].tap)
        assertEquals(DeckAction.Key(DeckKeyCode.DEL), third[0].up)
        assertEquals(DeckAction.Macro("CTRL f d"), third[1].tap)
        assertEquals("tmux exit", third[1].display)
        assertEquals(DeckAction.App(DeckAppAction.HIDE_KEYBOARD), third[2].tap)
    }

    @Test
    fun `bare arrays, single rows and unknown names import as text`() {
        val single = assertNotNull(TermuxExtraKeys.parse("['ESC', 'λ', \"F1\", 'FN']"))
        assertEquals(1, single.layers.size)
        assertEquals("Base", single.layers[0].name)
        assertEquals(DeckAction.Text("\u03BB"), single.layers[0].keys[1].tap)
        assertEquals(DeckAction.Key(DeckKeyCode.F1), single.layers[0].keys[2].tap)
        assertTrue(single.layers[0].keys[3].hold is DeckAction.Strip)
        assertNull(TermuxExtraKeys.parse("just words"))
        assertNull(TermuxExtraKeys.parse("{\"layers\": []}"))
        assertNull(TermuxExtraKeys.parse("extra-keys-style = arrows-only"))
    }

    @Test
    fun `export produces termux syntax that imports back`() {
        val exported = TermuxExtraKeys.export(DeckLayout.default())
        assertTrue(exported.startsWith("extra-keys = [["))
        assertTrue(exported.contains("{key: 'ESC', popup: '`'}"))
        assertTrue(exported.contains("{macro: 'CTRL b d', display: 'Pfx'}") || exported.contains("{macro: 'CTRL b', display: 'Pfx', popup: {macro: 'CTRL b d'}}"))
        val back = assertNotNull(TermuxExtraKeys.parse(exported))
        assertEquals(4, back.layers.size)
        assertEquals(DeckAction.Key(DeckKeyCode.ESC), back.layers[0].keys[0].tap)
        assertEquals(DeckAction.Text("`"), back.layers[0].keys[0].up)
        assertEquals(listOf("\u2190", "\u2193", "\u2191", "\u2192"), back.layers[0].keys.takeLast(4).map { it.label })
        assertEquals("Pfx", back.layers[3].keys[0].display)
    }

    @Test
    fun `quotes and backslashes survive a termux round-trip`() {
        val layout = DeckLayout(layers = listOf(app.berth.domain.model.DeckLayer("Base", listOf(DeckKey(tap = DeckAction.Text("'")), DeckKey(tap = DeckAction.Text("\\"))))))
        val back = assertNotNull(TermuxExtraKeys.parse(TermuxExtraKeys.export(layout)))
        assertEquals(listOf("'", "\\"), back.layers[0].keys.map { it.label })
    }
}
