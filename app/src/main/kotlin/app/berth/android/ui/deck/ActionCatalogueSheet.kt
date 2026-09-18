package app.berth.android.ui.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckGesture
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckModifier
import app.berth.domain.model.describe
import app.berth.domain.model.pretty
import app.berth.domain.model.title

/**
 * The searchable action catalogue (UX spec C5): type to find a key or app action, or browse by
 * kind. Text, combos, macros and snippets are composed in place. Picking an entry binds it to
 * [gesture] at once and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ActionCatalogueSheet(
    gesture: DeckGesture,
    slotTitle: String,
    current: DeckAction?,
    allowNub: Boolean,
    onPick: (DeckAction?) -> Unit,
    onNub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ActionKind.of(current)) }
    val results = remember(query) { DeckCatalogue.search(query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        scrimColor = c.scrim,
        dragHandle = { SheetHandle() },
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetTitle("${gesture.title} \u00B7 $slotTitle", current?.describe()?.let { "Now $it" } ?: "Nothing bound yet")
            BerthField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search keys and actions",
                trailing = if (query.isNotEmpty()) {
                    { Text("Clear", style = BerthType.label, color = c.accent, modifier = Modifier.clickable { query = "" }) }
                } else null,
            )
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    Text("Nothing matches. Text, combos, macros and snippets are composed under their chips.", style = BerthType.caption, color = c.text3)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (entry in results.take(14)) EntryRow(entry, selected = entry.action == current) { onPick(entry.action) }
                    }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in ActionKind.entries) Chip(k.title, selected = kind == k) { kind = k }
                }
                when (kind) {
                    ActionKind.KEY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (entry in DeckCatalogue.keys) {
                            val code = (entry.action as DeckAction.Key).key
                            Chip(DeckKey.keyLabel(code), selected = entry.action == current, mono = DeckKey.keyLabel(code).length <= 3) { onPick(entry.action) }
                        }
                    }
                    ActionKind.MODIFIER -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (entry in DeckCatalogue.modifiers) EntryRow(entry, selected = entry.action == current) { onPick(entry.action) }
                        Text("Modifiers belong on tap; swipe up on a modifier key usually sends a combo instead.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    }
                    ActionKind.TEXT -> TextComposer(current as? DeckAction.Text, onPick)
                    ActionKind.COMBO -> ComboComposer(current as? DeckAction.Combo, onPick)
                    ActionKind.MACRO -> MacroComposer(current as? DeckAction.Macro, onPick)
                    ActionKind.SNIPPET -> SnippetComposer(current as? DeckAction.Snippet, onPick)
                    ActionKind.APP -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (entry in DeckCatalogue.app) EntryRow(entry, selected = entry.action == current) { onPick(entry.action) }
                    }
                    ActionKind.SPECIAL -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (allowNub) {
                            ListRow("Nub", subtitle = "The round arrow key: tap sends Up, drag sends arrows", surface = Color.Transparent, minHeight = 44.dp, onClick = onNub)
                        }
                        for (entry in DeckCatalogue.special) EntryRow(entry, selected = entry.action == current) { onPick(entry.action) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: CatalogueEntry, selected: Boolean, onClick: () -> Unit) {
    ListRow(entry.title, subtitle = entry.caption, surface = Color.Transparent, minHeight = 44.dp, selected = selected, onClick = onClick)
}

@Composable
private fun TextComposer(current: DeckAction.Text?, onPick: (DeckAction) -> Unit) {
    val c = Berth.colors
    var text by remember { mutableStateOf(current?.text ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BerthField(text, { text = it }, label = "Text", mono = true, placeholder = "ls -la", helper = "Sent exactly as typed. To run a command, use a macro ending in ENTER.")
        BerthButton("Use \u201C${text.take(18)}\u201D".takeIf { text.isNotEmpty() } ?: "Use", onClick = { onPick(DeckAction.Text(text)) }, kind = ButtonKind.PRIMARY, enabled = text.isNotEmpty())
        Text(
            "Labels longer than two characters show in the interface font; one or two characters use the terminal's mono font.",
            style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ComboComposer(current: DeckAction.Combo?, onPick: (DeckAction) -> Unit) {
    val c = Berth.colors
    var mods by remember { mutableStateOf(current?.modifiers ?: setOf(DeckModifier.CTRL)) }
    var target by remember { mutableStateOf(current?.target ?: "") }
    val combo = DeckCatalogue.combo(mods, target)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in DeckModifier.entries) {
                Chip(m.title(), selected = m in mods) { mods = if (m in mods) mods - m else mods + m }
            }
        }
        BerthField(
            value = target,
            onValueChange = { target = it },
            label = "Key",
            mono = true,
            placeholder = "c, TAB, F5",
            helper = combo?.pretty() ?: "One character or a key name such as TAB, ENTER or F5",
            isError = target.isNotBlank() && combo == null,
        )
        BerthButton(combo?.let { "Use ${it.pretty()}" } ?: "Use", onClick = { combo?.let(onPick) }, kind = ButtonKind.PRIMARY, enabled = combo != null)
        Text("Combos send the chord once without latching a modifier.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun MacroComposer(current: DeckAction.Macro?, onPick: (DeckAction) -> Unit) {
    val c = Berth.colors
    var text by remember { mutableStateOf(current?.macro ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BerthField(text, { text = it }, label = "Macro", mono = true, placeholder = "PREFIX c", helper = "Space-separated. PREFIX becomes the layer's tmux prefix; ENTER, TAB and other key names are sent as keys; anything else is typed.")
        BerthButton("Use", onClick = { onPick(DeckAction.Macro(text.trim())) }, kind = ButtonKind.PRIMARY, enabled = text.isNotBlank())
        Text("Examples: `PREFIX d` detaches tmux, `:w ENTER` saves in Vim, `git status ENTER` runs a command.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SnippetComposer(current: DeckAction.Snippet?, onPick: (DeckAction) -> Unit) {
    val c = Berth.colors
    var id by remember { mutableStateOf(current?.snippetId ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BerthField(id, { id = it }, label = "Snippet id", mono = true, placeholder = "restart-nginx", helper = "The key sends the snippet with this id when tapped.")
        BerthButton("Use", onClick = { onPick(DeckAction.Snippet(id.trim())) }, kind = ButtonKind.PRIMARY, enabled = id.isNotBlank())
        Text("Snippets are stored by id so a Deck can be shared before the snippets themselves; the snippets screen arrives later.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp))
    }
}
