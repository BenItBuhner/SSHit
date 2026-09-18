package app.berth.android.ui.deck

import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckModifier
import app.berth.domain.model.fullName
import app.berth.domain.model.title

/** The kinds of action a slot can hold, in the order the catalogue offers them. */
enum class ActionKind(val title: String) {
    KEY("Key"), TEXT("Text"), COMBO("Combo"), MODIFIER("Modifier"), MACRO("Macro"), APP("App"), SNIPPET("Snippet"), SPECIAL("Special");

    companion object {
        /** The chip to open on for an existing action; nothing bound starts on keys. */
        fun of(action: DeckAction?): ActionKind = when (action) {
            is DeckAction.Key -> KEY
            is DeckAction.Text -> TEXT
            is DeckAction.Combo -> COMBO
            is DeckAction.Modifier -> MODIFIER
            is DeckAction.Macro -> MACRO
            is DeckAction.App -> APP
            is DeckAction.Snippet -> SNIPPET
            is DeckAction.Strip -> SPECIAL
            null -> KEY
        }
    }
}

/** One pickable entry: a title, an optional caption and the action it binds; a null action clears the gesture. */
data class CatalogueEntry(val title: String, val caption: String?, val action: DeckAction?, val kind: ActionKind)

/** Everything the action catalogue can offer without typing, plus the composers' helpers. */
object DeckCatalogue {
    val FN_STRIP: DeckAction.Strip = DeckAction.Strip(DeckKeyCode.entries.filter { it.name.matches(Regex("F\\d+")) })

    val keys: List<CatalogueEntry> = DeckKeyCode.entries.map { code ->
        val label = DeckKey.keyLabel(code)
        CatalogueEntry(code.fullName(), label.takeIf { it != code.fullName() }, DeckAction.Key(code), ActionKind.KEY)
    }

    val modifiers: List<CatalogueEntry> = DeckModifier.entries.map { m ->
        CatalogueEntry(m.title(), "Tap arms it for the next key, a second tap locks it", DeckAction.Modifier(m), ActionKind.MODIFIER)
    }

    val app: List<CatalogueEntry> = DeckAppAction.entries.map { a ->
        val caption = when (a) {
            DeckAppAction.NEXT_LAYER, DeckAppAction.PREVIOUS_LAYER -> "Layer switch"
            DeckAppAction.SPLIT -> "Landscape and tablets"
            DeckAppAction.OPEN_DECK_EDITOR -> "Opens this editor"
            else -> null
        }
        CatalogueEntry(a.title(), caption, DeckAction.App(a), ActionKind.APP)
    }

    val special: List<CatalogueEntry> = listOf(
        CatalogueEntry("Fn strip", "F1 to F12 as a scrolling strip; usually bound to hold", FN_STRIP, ActionKind.SPECIAL),
        CatalogueEntry("None", "Clear this gesture", null, ActionKind.SPECIAL),
    )

    /** Everything that can be found by typing, in catalogue order. */
    val searchable: List<CatalogueEntry> = keys + modifiers + app + special

    /** Case-insensitive match on title, caption or key name; an empty query returns nothing so the browse view shows. */
    fun search(query: String): List<CatalogueEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchable.filter { e ->
            e.title.contains(q, ignoreCase = true) ||
                e.caption?.contains(q, ignoreCase = true) == true ||
                (e.action as? DeckAction.Key)?.key?.name?.contains(q, ignoreCase = true) == true
        }
    }

    /**
     * `CTRL ALT x` from the toggled modifiers and a target that is one character or a key name.
     * Anything else has no combo form, so the composer's Use button stays disabled.
     */
    fun combo(modifiers: Set<DeckModifier>, target: String): DeckAction.Combo? {
        val t = target.trim()
        if (t.isEmpty()) return null
        val key = runCatching { DeckKeyCode.valueOf(t.uppercase()) }.getOrNull()
        val normalized = when {
            key != null -> key.name
            t.codePointCount(0, t.length) == 1 -> t
            else -> return null
        }
        val ordered = listOf(DeckModifier.CTRL, DeckModifier.ALT, DeckModifier.SHIFT).filter { it in modifiers }
        return DeckAction.Combo((ordered.map { it.name } + normalized).joinToString(" "))
    }
}
