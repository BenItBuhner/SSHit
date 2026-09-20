package app.berth.android.ui.deck

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.BerthSlider
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.io.rememberOpenTextFile
import app.berth.android.ui.io.rememberSaveTextFile
import app.berth.android.ui.io.shareText
import app.berth.android.ui.stage.Deck
import app.berth.android.ui.stage.DeckEditing
import app.berth.android.ui.stage.ModifierLatch
import app.berth.android.ui.stage.StageInput
import app.berth.android.ui.stage.usableLayers
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.android.ui.themes.MenuItem
import app.berth.android.ui.themes.PasteTextSheet
import app.berth.android.ui.themes.RenameSheet
import app.berth.domain.model.DECK_MAX_KEYS_PER_LAYER
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckGesture
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckPreset
import app.berth.domain.model.DeckPresets
import app.berth.domain.model.DeckReach
import app.berth.domain.model.Snippet
import app.berth.domain.model.TermuxExtraKeys
import app.berth.domain.model.action
import app.berth.domain.model.addLayer
import app.berth.domain.model.describe
import app.berth.domain.model.insertKey
import app.berth.domain.model.moveKey
import app.berth.domain.model.moveLayer
import app.berth.domain.model.removeKey
import app.berth.domain.model.removeLayer
import app.berth.domain.model.renameLayer
import app.berth.domain.model.resetLayer
import app.berth.domain.model.setKey
import app.berth.domain.model.setLayerPrefix
import app.berth.domain.model.warnings
import app.berth.domain.model.withAction
import kotlin.math.roundToInt

private const val UNDO_DEPTH = 60

/** Deck heights the spec allows (A11: 44, 40–52) and the step the Height slider snaps to. */
private val HEIGHTS = 40f..52f

/** The A9 heights 40, 44, 48 and 52: two stops strictly between the ends. */
private const val HEIGHT_STOPS = 2

/**
 * The Deck editor (UX spec C5). The preview is the real [Deck] composable in editing mode, so what
 * is shown is exactly what the Stage renders; every change is written to settings at once, which
 * is what makes the Deck behind the editor follow along. Undo walks back through those changes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeckEditorScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val context = LocalContext.current
    val stored by vm.deckLayout.collectAsState()
    val terminalTheme by vm.defaultTerminalTheme.collectAsState()
    val snippets by vm.snippets.collectAsState()
    // The Stage shows the pinned snippets in scope for its session; the editor, with no session, shows every pinned one.
    val pinned = remember(snippets) { snippets.filter { it.pinnedToDeck }.sortedBy { it.name.lowercase() } }
    var draft by remember { mutableStateOf(stored) }
    val history = remember { mutableStateListOf<DeckLayout>() }
    var layer by rememberSaveable { mutableIntStateOf(0) }
    var slot by remember { mutableStateOf<Int?>(null) }
    var gesture by remember { mutableStateOf<DeckGesture?>(null) }
    var layerMenu by remember { mutableStateOf<Int?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var prefixing by remember { mutableStateOf(false) }
    var labelling by remember { mutableStateOf(false) }
    var addingLayer by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val saver = rememberSaveTextFile()
    val previewInput = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }

    val layerIndex = layer.coerceIn(0, (draft.layers.size - 1).coerceAtLeast(0))
    val current = draft.layers.getOrNull(layerIndex)
    val keys = current?.keys.orEmpty()
    val slotIndex = slot?.takeIf { it in keys.indices }
    val key = slotIndex?.let { keys[it] }

    /** Applies [next] live and remembers where it came from for Undo. */
    fun edit(next: DeckLayout) {
        if (next == draft) return
        history.add(draft)
        if (history.size > UNDO_DEPTH) history.removeAt(0)
        draft = next
        vm.setDeckLayout(next)
        note = null
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        draft = previous
        vm.setDeckLayout(previous)
        layer = layer.coerceIn(0, (previous.layers.size - 1).coerceAtLeast(0))
    }

    fun importText(text: String) {
        val parsed = runCatching { DeckLayout.fromJson(text) }.getOrNull() ?: TermuxExtraKeys.parse(text)
        if (parsed == null || parsed.layers.isEmpty()) {
            importError = "That text is neither Berth Deck JSON nor Termux extra-keys."
            return
        }
        edit(parsed)
        layer = 0
        slot = null
        importing = false
        importError = null
        note = "Imported ${parsed.layers.size} ${if (parsed.layers.size == 1) "layer" else "layers"}."
    }
    val openFile = rememberOpenTextFile { text -> importing = true; importText(text) }

    val usable = draft.usableLayers(hasSnippets = pinned.isNotEmpty())
    val previewIndex = current?.let { usable.indexOf(it) } ?: -1
    val editing = remember(slotIndex, layerIndex, keys.size) {
        DeckEditing(
            selectedSlot = slotIndex,
            onSelectSlot = { slot = it },
            onMoveKey = { from, to -> edit(draft.moveKey(layerIndex, from, to)); slot = to },
        )
    }

    /** Appends an empty key to the shown layer, selects it and opens the catalogue for its tap. */
    fun addKey() {
        edit(draft.insertKey(layerIndex, Int.MAX_VALUE))
        slot = draft.layers[layerIndex].keys.lastIndex
        gesture = DeckGesture.TAP
    }
    val canAddKey = current != null && keys.size < DECK_MAX_KEYS_PER_LAYER

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Deck", onBack = onBack) {
            // Done is the one accent action in the header; Undo reads as a quiet neighbour in text.2.
            QuietAction("Undo", onClick = ::undo, enabled = history.isNotEmpty())
            BerthButton("Done", onClick = onBack, kind = ButtonKind.TEXT)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.sectionGap),
        ) {
            // ---- preview ------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Preview", Modifier.padding(start = 4.dp))
                // A strip, not a panel: clipped at `row` so the corner arcs end before the outer
                // keys begin (they sit `DeckEdge` in) instead of slicing them as `panel` did.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(BerthRadius.row))
                        .background(c.surface0)
                        .semantics { contentDescription = "Deck preview" },
                ) {
                    Box(Modifier.fillMaxWidth().height(22.dp).background(terminalTheme.background.toColor()).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                        Text("ben@homelab:~$ \u258C", style = BerthType.mono, color = terminalTheme.foreground.toColor(), maxLines = 1)
                    }
                    if (previewIndex >= 0) {
                        Deck(
                            layout = draft,
                            layerIndex = previewIndex,
                            onLayerIndexChange = { i -> usable.getOrNull(i)?.let { shown -> layer = draft.layers.indexOf(shown); slot = null } },
                            input = previewInput,
                            editing = editing,
                            snippets = pinned,
                        )
                    } else {
                        Text(
                            "This layer is only the Snippets slot, which fills with the snippets pinned to the Deck; none are pinned yet, so the Stage skips it.",
                            style = BerthType.caption,
                            color = c.text3,
                            modifier = Modifier.background(c.surface1).fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        )
                    }
                }
                // The screen's one line of help; a note about the last edit takes its place.
                Text(
                    note ?: "Tap a key to edit it, hold to reorder; the Stage follows along.",
                    style = BerthType.caption,
                    color = if (note != null) c.text1 else c.text3,
                    modifier = Modifier.padding(start = 4.dp),
                )
                for (warning in draft.warnings()) {
                    Text(warning, style = BerthType.caption, color = c.attention, modifier = Modifier.padding(start = 4.dp))
                }
            }

            // ---- layers -------------------------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Layers", Modifier.padding(start = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.layers.forEachIndexed { i, l ->
                        Box {
                            LayerChip(
                                text = l.name,
                                selected = i == layerIndex,
                                onClick = { if (i == layerIndex) layerMenu = i else { layer = i; slot = null } },
                                onLongClick = { layerMenu = i },
                            )
                            DropdownMenu(expanded = layerMenu == i, onDismissRequest = { layerMenu = null }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                                MenuItem("Rename") { layerMenu = null; layer = i; renaming = true }
                                MenuItem(if (l.prefix == null) "Set tmux prefix" else "tmux prefix \u00B7 ${l.prefix}") { layerMenu = null; layer = i; prefixing = true }
                                if (i > 0) MenuItem("Move left") { layerMenu = null; edit(draft.moveLayer(i, i - 1)); layer = i - 1 }
                                if (i < draft.layers.lastIndex) MenuItem("Move right") { layerMenu = null; edit(draft.moveLayer(i, i + 1)); layer = i + 1 }
                                MenuItem("Reset layer") { layerMenu = null; edit(draft.resetLayer(i)); slot = null }
                                if (draft.layers.size > 1) MenuItem("Delete layer", destructive = true) { layerMenu = null; edit(draft.removeLayer(i)); layer = i.coerceAtMost(draft.layers.lastIndex); slot = null }
                            }
                        }
                    }
                    AddLayerChip(onClick = { addingLayer = true })
                }
            }

            // ---- slot ---------------------------------------------------------------------------
            Panel(label = if (key != null) "Slot ${slotIndex!! + 1} \u00B7 ${key.label.ifBlank { "empty" }}" else "Slot") {
                when {
                    // The empty state carries the discoverability hints, so the screen itself stays quiet.
                    key == null -> {
                        Text("Tap a key in the preview to edit it. The current layer's chip opens its menu.", style = BerthType.body, color = c.text2)
                        if (canAddKey) {
                            Spacer(Modifier.height(8.dp))
                            BerthButton("Add key", onClick = ::addKey)
                        }
                    }
                    key.nub -> {
                        Text("The Nub. Tap sends Up; drag sends the arrow for the dominant axis, faster the further you pull. The Arrows setting below swaps it for four keys or shows both.", style = BerthType.body, color = c.text2)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BerthButton("Replace with a key", onClick = { gesture = DeckGesture.TAP })
                            BerthButton("Remove", onClick = { edit(draft.removeKey(layerIndex, slotIndex!!)); slot = null }, kind = ButtonKind.DESTRUCTIVE)
                        }
                    }
                    key.snippets -> Text(
                        if (pinned.isEmpty()) "Expands to one key per snippet pinned to the Deck. None are pinned yet; the Snippets screen pins them."
                        else "Expands to one key per pinned snippet: ${pinned.joinToString { it.name }}.",
                        style = BerthType.body,
                        color = c.text2,
                    )
                    else -> {
                        for (g in DeckGesture.entries) {
                            PickerRow(g.title, key.action(g)?.describeWith(snippets) ?: "None", onClick = { gesture = g })
                        }
                        PickerRow("Label", key.display ?: "From the tap action", onClick = { labelling = true })
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canAddKey) BerthButton("Add key", onClick = ::addKey)
                            if (keys.none { it.nub }) BerthButton("Make it the Nub", onClick = { edit(draft.setKey(layerIndex, slotIndex!!, DeckKey(nub = true))) })
                            BerthButton("Remove key", onClick = { edit(draft.removeKey(layerIndex, slotIndex!!)); slot = null }, kind = ButtonKind.DESTRUCTIVE)
                        }
                    }
                }
            }

            // ---- layout -------------------------------------------------------------------------
            Panel(label = "Layout") {
                Caption("Rows")
                SegmentedControl(listOf("One", "Two"), (draft.rows - 1).coerceIn(0, 1), onSelect = { edit(draft.copy(rows = it + 1)) })
                Caption("Reach", top = 14.dp)
                SegmentedControl(listOf("Left", "Right"), if (draft.reach == DeckReach.LEFT) 0 else 1, onSelect = { edit(draft.copy(reach = if (it == 0) DeckReach.LEFT else DeckReach.RIGHT)) })
                Caption("Arrows", top = 14.dp)
                SegmentedControl(listOf("Nub", "Four keys", "Both"), DeckArrows.entries.indexOf(draft.arrows), onSelect = { edit(draft.copy(arrows = DeckArrows.entries[it])) })
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Caption("Height", modifier = Modifier.weight(1f))
                    Text("${draft.heightDp} dp", style = BerthType.caption, color = c.text3)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("40", style = BerthType.caption, color = c.text3)
                    // Snaps to the four spec heights; each step the knob lands on is one edit, so Undo
                    // walks back a drag a step at a time and the Stage's Deck follows the knob.
                    BerthSlider(
                        value = draft.heightDp.toFloat(),
                        onValueChange = { v -> edit(draft.copy(heightDp = v.roundToInt())) },
                        valueRange = HEIGHTS,
                        steps = HEIGHT_STOPS,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = "Deck height" },
                    )
                    Text("52", style = BerthType.caption, color = c.text3)
                }
            }

            // ---- actions ------------------------------------------------------------------------
            // What each accepts is said where it is asked for: the import sheet's caption and the
            // export menu's entries, not in a paragraph under the buttons.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Presets", onClick = { presets = true })
                BerthButton("Import", onClick = { importError = null; importing = true })
                Box {
                    BerthButton("Export", onClick = { exportMenu = true })
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                        MenuItem("Share Berth JSON") { exportMenu = false; shareText(context, "berth-deck.json", draft.toJson()) }
                        MenuItem("Save JSON to file") { exportMenu = false; saver.save("berth-deck.json", draft.toJson()) }
                        MenuItem("Share as Termux extra-keys") { exportMenu = false; shareText(context, "termux.properties", TermuxExtraKeys.export(draft), mime = "text/plain") }
                    }
                }
                if (current != null) BerthButton("Reset layer", onClick = { edit(draft.resetLayer(layerIndex)); slot = null })
            }
        }
    }

    // ---- sheets ---------------------------------------------------------------------------------
    val g = gesture
    if (g != null && key != null && slotIndex != null) {
        ActionCatalogueSheet(
            gesture = g,
            slotTitle = "Slot ${slotIndex + 1}",
            current = key.action(g),
            allowNub = g == DeckGesture.TAP && keys.none { it.nub && it !== key },
            onPick = { a -> edit(draft.setKey(layerIndex, slotIndex, key.withAction(g, a))); gesture = null },
            onNub = { edit(draft.setKey(layerIndex, slotIndex, DeckKey(nub = true))); gesture = null },
            onDismiss = { gesture = null },
            snippets = snippets,
        )
    }
    if (presets) {
        PresetsSheet(
            previewInput = previewInput,
            snippets = pinned,
            onPick = { p -> edit(p.layout); layer = 0; slot = null; presets = false; note = "${p.name} preset applied." },
            onDismiss = { presets = false },
        )
    }
    if (importing) {
        PasteTextSheet(
            title = "Import a Deck",
            caption = "Berth Deck JSON, or Termux extra-keys from termux.properties",
            action = "Import",
            onDismiss = { importing = false; importError = null },
            onSubmit = ::importText,
            error = importError,
            secondary = "Open file" to openFile,
        )
    }
    if (renaming && current != null) {
        RenameSheet(current = current.name, onDismiss = { renaming = false }) { name ->
            edit(draft.renameLayer(layerIndex, name))
            renaming = false
        }
    }
    if (prefixing && current != null) {
        RenameSheet(
            current = current.prefix ?: "",
            title = "tmux prefix",
            label = "Prefix",
            caption = "The combo that PREFIX expands to in this layer's macros; blank uses CTRL b",
            action = "Set",
            allowBlank = true,
            mono = true,
            onDismiss = { prefixing = false },
        ) { prefix ->
            edit(draft.setLayerPrefix(layerIndex, prefix.ifBlank { null }))
            prefixing = false
        }
    }
    if (labelling && key != null && slotIndex != null) {
        RenameSheet(
            current = key.display ?: "",
            title = "Key label",
            label = "Label",
            caption = "Blank derives the label from the tap action. One or two characters use the terminal font.",
            action = "Set",
            allowBlank = true,
            onDismiss = { labelling = false },
        ) { label ->
            edit(draft.setKey(layerIndex, slotIndex, key.copy(display = label.ifBlank { null })))
            labelling = false
        }
    }
    if (addingLayer) {
        RenameSheet(current = "", title = "New layer", label = "Name", action = "Add", onDismiss = { addingLayer = false }) { name ->
            edit(draft.addLayer(name))
            layer = draft.layers.lastIndex
            slot = null
            addingLayer = false
        }
    }
}

/**
 * A text action in text.2 for the header: the same 44 dp footprint and press fill as a text
 * [BerthButton], without its accent, so the one accent action beside it stays the one to look at.
 */
@Composable
private fun QuietAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = enabled && interaction.showsFocus()
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp, minWidth = 44.dp)
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(if ((pressed && enabled) || focused) c.surface2 else Color.Transparent)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BerthType.label, color = if (focused) c.accent else c.text2.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
    }
}

/** A chip that also takes a long-press, for the layer row's rename, reorder and delete menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerChip(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Berth.colors
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(BerthRadius.swatch))
            .background(if (selected) c.surface4 else c.surface2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // The selection is a state, not a word in the name, so a reader says it once in its own voice.
            .semantics {
                contentDescription = "Layer $text"
                this.selected = selected
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BerthType.label, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Caption(text: String, top: androidx.compose.ui.unit.Dp = 0.dp, modifier: Modifier = Modifier) {
    Text(text, style = BerthType.caption, color = Berth.colors.text2, modifier = modifier.padding(start = 4.dp, top = top, bottom = 6.dp))
}

/** The `+` at the end of the layer chips: a chip-sized target with the drawn add glyph. */
@Composable
private fun AddLayerChip(onClick: () -> Unit) {
    val c = Berth.colors
    Box(
        Modifier
            .size(width = 36.dp, height = 28.dp)
            .clip(RoundedCornerShape(BerthRadius.swatch))
            .background(c.surface2)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Add layer" },
        contentAlignment = Alignment.Center,
    ) {
        BerthIcon(BerthIcons.add, tint = c.text1, size = 18.dp)
    }
}

/**
 * The four presets, each rendered by the real Deck so the choice is made on what it will look like.
 * Nothing is boxed: name and caption are plain text over the Deck, which sits on the sheet's own
 * surface and lifts to surface.2 while pressed; empty space keeps the presets apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetsSheet(previewInput: StageInput, snippets: List<Snippet>, onPick: (DeckPreset) -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    BerthSheet(onDismiss = onDismiss, scrimColor = c.scrim) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.sectionGap),
        ) {
            SheetTitle("Presets", "Each replaces every layer; Undo brings the previous Deck back")
            for (preset in DeckPresets.all) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val focused = interaction.showsFocus()
                val lift by animateColorAsState(if (pressed || focused) c.surface2 else c.surface1, tween(120), label = "preset")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = interaction, indication = null) { onPick(preset) }
                        // One button, said as its name and caption: the Deck drawn inside is for
                        // looking at (the overlay below takes every touch on it), so neither its keys
                        // nor the overlay are controls of their own to a reader.
                        .clearAndSetSemantics {
                            contentDescription = "Preset ${preset.name}, ${preset.caption}"
                            role = Role.Button
                            onClick { onPick(preset); true }
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(preset.name, style = BerthType.bodyMedium, color = c.text1)
                        Text(preset.caption, style = BerthType.caption, color = c.text2)
                    }
                    Box {
                        Deck(
                            layout = preset.layout,
                            layerIndex = 0,
                            onLayerIndexChange = {},
                            input = previewInput,
                            modifier = Modifier.clip(RoundedCornerShape(BerthRadius.row)),
                            surface = lift,
                            snippets = snippets,
                        )
                        // The preview is for looking at; a tap anywhere on it picks the preset, and
                        // it shares the interaction so the strip lifts whichever part is pressed.
                        Box(Modifier.matchParentSize().clickable(interactionSource = interaction, indication = null) { onPick(preset) })
                    }
                }
            }
        }
    }
}

/** [describe], with a snippet binding named after the snippet when it is one of [snippets]. */
private fun DeckAction.describeWith(snippets: List<Snippet>): String {
    val id = (this as? DeckAction.Snippet)?.snippetId ?: return describe()
    return snippets.firstOrNull { it.id == id }?.let { "Snippet \u00B7 ${it.name}" } ?: describe()
}
