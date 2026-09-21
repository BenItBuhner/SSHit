package app.berth.android.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ColorOption
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.components.spokenName
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace

/**
 * Rename a tab (spec C3, Long-press menu › Rename). The field starts on the current title; an empty
 * field or Reset returns the tab to its automatic title (the host name, then the terminal's own title).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameTabSheet(
    record: SessionRecord,
    onRename: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    var title by remember { mutableStateOf(record.customTitle ?: record.displayTitle) }
    fun save() {
        onRename(title.trim().takeIf { it.isNotEmpty() })
        onDismiss()
    }
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Rename tab", record.hostSnapshot.userAtHost)
            BerthField(
                value = title,
                onValueChange = { title = it },
                placeholder = record.title.ifBlank { record.hostSnapshot.name },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Save", kind = ButtonKind.PRIMARY, onClick = ::save)
                if (record.customTitle != null) {
                    BerthButton("Reset", onClick = { onRename(null); onDismiss() })
                }
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

/**
 * Create or edit a group (spec C3, Groups; C8, the editor): name and colour, the colour picked
 * from the twelve swatches, and for an existing [group] the spec's Reconnect at launch switch,
 * which is off by default so a launch restores the group's frames and reconnects on a tap.
 * Editing an existing group applies as you go; creating one commits on Create. The sheet is a
 * fixed set of controls, so it opens at its content height rather than at half the window, where
 * Done and Cancel stood below the fold at the interface's font cap (A11), and its column scrolls
 * for a window shorter than the controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditorSheet(
    group: Workspace?,
    onCreate: (name: String, color: SwatchColor) -> Unit,
    onRename: (String) -> Unit,
    onRecolor: (SwatchColor) -> Unit,
    onDismiss: () -> Unit,
    onReconnectAtLaunch: (Boolean) -> Unit = {},
) {
    val c = Berth.colors
    var name by remember { mutableStateOf(group?.name ?: "") }
    var color by remember { mutableStateOf(group?.color ?: SwatchColor.SLATE) }
    var touchedColor by remember { mutableStateOf(group != null) }
    var reconnect by remember { mutableStateOf(group?.reconnectAtLaunch ?: false) }
    val trimmed = name.trim()
    val previewColor = if (touchedColor || group != null) color else SwatchColor.forName(trimmed.ifBlank { "Group" })
    fun commit() {
        if (trimmed.isEmpty()) return
        if (group == null) onCreate(trimmed, previewColor) else if (trimmed != group.name) onRename(trimmed)
        onDismiss()
    }
    BerthSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatch(previewColor, Host.monogramFor(trimmed.ifBlank { "Group" }), 40.dp)
                SheetTitle(if (group == null) "New group" else "Edit group", if (group == null) "A run of tabs with its own chip" else null)
            }
            BerthField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                placeholder = "Homelab",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
            )
            Text("Colour".uppercase(), style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp))
            // The options' 48 dp targets set the pitch; the swatches inside them sit 12 dp apart.
            Row(Modifier.fillMaxWidth()) {
                for (swatch in SwatchColor.entries.take(6)) SwatchOption(swatch, previewColor == swatch) { color = swatch; touchedColor = true; if (group != null) onRecolor(swatch) }
            }
            Row(Modifier.fillMaxWidth()) {
                for (swatch in SwatchColor.entries.drop(6)) SwatchOption(swatch, previewColor == swatch) { color = swatch; touchedColor = true; if (group != null) onRecolor(swatch) }
            }
            if (group != null) {
                ToggleRow(
                    "Reconnect tabs at launch",
                    reconnect,
                    { reconnect = it; onReconnectAtLaunch(it) },
                    caption = "Off, its tabs come back as saved frames and reconnect when tapped.",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton(if (group == null) "Create" else "Done", kind = ButtonKind.PRIMARY, enabled = trimmed.isNotEmpty(), onClick = ::commit)
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

/** The group's colour as a [ColorOption]: named for a screen reader, in a 48 dp target. */
@Composable
private fun SwatchOption(swatch: SwatchColor, selected: Boolean, onClick: () -> Unit) {
    ColorOption(color = swatch.rgb.toColor(), name = swatch.spokenName(), selected = selected, onClick = onClick)
}
