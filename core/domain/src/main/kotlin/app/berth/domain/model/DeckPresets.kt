package app.berth.domain.model

/** A named starting layout for the Deck editor. */
data class DeckPreset(val id: String, val name: String, val caption: String, val layout: DeckLayout)

/** The stock layout and three curated alternatives; the editor offers them and they are safe to reapply. */
object DeckPresets {
    private fun text(tap: String, up: String? = null) = DeckKey(tap = DeckAction.Text(tap), up = up?.let { DeckAction.Text(it) })

    private fun stockLayer(name: String): DeckLayer = DeckLayout.default().layers.first { it.name == name }

    val DEFAULT = DeckPreset("default", "Default", "Base, Symbols, Nav/Fn, tmux and Snippets", DeckLayout.default())

    val VIM = DeckPreset(
        id = "vim",
        name = "Vim",
        caption = "Escape, colon, search, undo and operators up front",
        layout = DeckLayout(
            layers = listOf(
                DeckLayer(
                    name = "Vim",
                    keys = listOf(
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC), up = DeckAction.Text("`")),
                        text(":", "/"),
                        DeckKey(tap = DeckAction.Modifier(DeckModifier.CTRL), up = DeckAction.Combo("CTRL c"), display = "Ctrl"),
                        DeckKey(tap = DeckAction.Text("u"), up = DeckAction.Combo("CTRL r")),
                        text(".", "n"),
                        DeckKey(tap = DeckAction.Macro(":w ENTER"), up = DeckAction.Macro(":q ENTER"), display = ":w"),
                        DeckKey(nub = true),
                    ),
                ),
                DeckLayer(
                    name = "Operators",
                    keys = listOf(
                        text("dd", "D"),
                        text("yy", "Y"),
                        text("p", "P"),
                        text("gg", "G"),
                        text(">>", "<<"),
                        text("*", "#"),
                        text("%", "``"),
                    ),
                ),
                stockLayer("Symbols"),
                stockLayer("Nav/Fn"),
            ),
        ),
    )

    val TMUX = DeckPreset(
        id = "tmux",
        name = "tmux",
        caption = "Prefix, windows, panes and zoom on the first layer",
        layout = DeckLayout(
            layers = listOf(
                // The stock tmux layer minus its scroll key, which frees a slot for the Nub; the terminal scrolls by drag.
                stockLayer("tmux").let { l -> l.copy(keys = l.keys.filter { it.display != "scroll" } + DeckKey(nub = true)) },
                stockLayer("Base"),
                stockLayer("Symbols"),
                stockLayer("Nav/Fn"),
            ),
        ),
    )

    val MINIMAL = DeckPreset(
        id = "minimal",
        name = "Minimal",
        caption = "One row: Esc, Tab, Ctrl, dash, slash and the Nub",
        layout = DeckLayout(
            heightDp = 40,
            layers = listOf(
                DeckLayer(
                    name = "Base",
                    keys = listOf(
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC), up = DeckAction.Text("`")),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.TAB), up = DeckAction.Combo("SHIFT TAB")),
                        DeckKey(tap = DeckAction.Modifier(DeckModifier.CTRL), up = DeckAction.Combo("CTRL c"), display = "Ctrl"),
                        text("-", "|"),
                        text("/", "\\"),
                        DeckKey(nub = true),
                    ),
                ),
            ),
        ),
    )

    val all: List<DeckPreset> = listOf(DEFAULT, VIM, TMUX, MINIMAL)

    fun byId(id: String): DeckPreset? = all.firstOrNull { it.id == id }
}
