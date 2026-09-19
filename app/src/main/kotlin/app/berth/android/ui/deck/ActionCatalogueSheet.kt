package app.berth.android.ui.deck

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
import app.berth.domain.model.Snippet
import app.berth.domain.model.describe
import app.berth.domain.model.pretty
import app.berth.domain.model.title

/**
 * The searchable action catalogue (UX spec C5): type to find a key, an app action or one of the
 * saved [snippets], or browse by kind. Text, combos and macros are composed in place; a snippet is
 * picked by name and bound by id. Picking an entry binds it to [gesture] at once and closes the sheet.
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
    snippets: List<Snippet> = emptyList(),
) {
    val c = Berth.colors
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ActionKind.of(current)) }
    val results = remember(query, snippets) { DeckCatalogue.search(query, snippets) }

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
                    ActionKind.SNIPPET -> SnippetPicker(current as? DeckAction.Snippet, snippets, onPick)
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

// Each composer is one field and one button; whatever needs saying is the field's helper line.

@Composable
private fun TextComposer(current: DeckAction.Text?, onPick: (DeckAction) -> Unit) {
    var text by remember { mutableStateOf(current?.text ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BerthField(text, { text = it }, label = "Text", mono = true, placeholder = "ls -la", helper = "Sent as typed; a macro ending in ENTER runs a command.")
        BerthButton("Use \u201C${text.take(18)}\u201D".takeIf { text.isNotEmpty() } ?: "Use", onClick = { onPick(DeckAction.Text(text)) }, kind = ButtonKind.PRIMARY, enabled = text.isNotEmpty())
    }
}

/** Modifiers and the key on one line, so the chord is composed where it is read. */
@Composable
private fun ComboComposer(current: DeckAction.Combo?, onPick: (DeckAction) -> Unit) {
    var mods by remember { mutableStateOf(current?.modifiers ?: setOf(DeckModifier.CTRL)) }
    var target by remember { mutableStateOf(current?.target ?: "") }
    val combo = DeckCatalogue.combo(mods, target)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in DeckModifier.entries) {
                Chip(m.title(), selected = m in mods) { mods = if (m in mods) mods - m else mods + m }
            }
            Spacer(Modifier.width(2.dp))
            BerthField(
                value = target,
                onValueChange = { target = it },
                modifier = Modifier.weight(1f),
                mono = true,
                placeholder = "c, TAB, F5",
                isError = target.isNotBlank() && combo == null,
            )
        }
        Text(
            combo?.pretty() ?: "One character or a key name such as TAB, ENTER or F5",
            style = BerthType.caption,
            color = if (target.isNotBlank() && combo == null) Berth.colors.danger else Berth.colors.text3,
            modifier = Modifier.padding(start = 4.dp),
        )
        BerthButton(combo?.let { "Use ${it.pretty()}" } ?: "Use", onClick = { combo?.let(onPick) }, kind = ButtonKind.PRIMARY, enabled = combo != null)
    }
}

@Composable
private fun MacroComposer(current: DeckAction.Macro?, onPick: (DeckAction) -> Unit) {
    var text by remember { mutableStateOf(current?.macro ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BerthField(text, { text = it }, label = "Macro", mono = true, placeholder = "PREFIX c", helper = "Space-separated: PREFIX is the layer's tmux prefix, ENTER and TAB are keys, anything else is typed.")
        BerthButton("Use", onClick = { onPick(DeckAction.Macro(text.trim())) }, kind = ButtonKind.PRIMARY, enabled = text.isNotBlank())
    }
}

/**
 * The saved snippets as rows; the key binds to the one picked and takes its name as its label.
 * Without any, the id field stays so a layout shared from another device can still be wired.
 */
@Composable
private fun SnippetPicker(current: DeckAction.Snippet?, snippets: List<Snippet>, onPick: (DeckAction) -> Unit) {
    if (snippets.isEmpty()) {
        var id by remember { mutableStateOf(current?.snippetId ?: "") }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BerthField(id, { id = it }, label = "Snippet id", mono = true, placeholder = "restart-nginx", helper = "No snippets saved yet; the Snippets screen makes them. The key sends the snippet with this id.")
            BerthButton("Use", onClick = { onPick(DeckAction.Snippet(id.trim())) }, kind = ButtonKind.PRIMARY, enabled = id.isNotBlank())
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (entry in DeckCatalogue.snippets(snippets)) EntryRow(entry, selected = entry.action == current) { entry.action?.let(onPick) }
        Text("Tap runs the snippet, or asks for its placeholders first. Pinned snippets also fill the Snippets slot.", style = BerthType.caption, color = Berth.colors.text3, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    }
}
