package app.berth.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.keyboard.ShortcutSheet
import app.berth.android.ui.theme.Berth
import app.berth.domain.model.AltKeyMode

/**
 * Settings › Hardware keyboard (spec C22): what Alt does, the compact Deck, whether readline's
 * Ctrl+T and Ctrl+W belong to the shell, and the shortcut sheet. One panel, registered
 * with one line in [SettingsScreen]; the sheet it opens is the same one Ctrl+Shift+/ opens on the
 * Stage, so the two never drift.
 */
@Composable
fun HardwareKeyboardPanel(vm: AppViewModel) {
    val c = Berth.colors
    val settings by vm.hardwareKeyboard.collectAsState()
    val ctrlTabKeys by vm.ctrlTabKeysReachTerminal.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    if (sheet) ShortcutSheet(ctrlTabKeys, onDismiss = { sheet = false })

    Panel(label = "Hardware keyboard") {
        CyclePicker("Alt key", AltKeyMode.entries, settings.altKey, ::altKeyLabel, caption = "What Alt does to a character; a host may say otherwise", captionLines = 2) { mode ->
            vm.updateHardwareKeyboard { it.copy(altKey = mode) }
        }
        PanelNote("Escape then the key is what every shell and editor reads as Meta. The eighth bit is for the few programs that want a Meta byte; keys outside ASCII still take the Escape prefix.")
        ToggleRow("Compact Deck", settings.compactDeck, { on -> vm.updateHardwareKeyboard { it.copy(compactDeck = on) } }, caption = "One row of modifiers and actions while a keyboard is attached", captionLines = 2)
        // A title short enough to read whole beside its switch; the caption names the keys.
        ToggleRow("Readline keys go to the shell", ctrlTabKeys, { vm.setCtrlTabKeysReachTerminal(it) }, caption = "Ctrl+T and Ctrl+W: transpose and delete word")
        PanelNote("Ctrl+Shift+T and Ctrl+Shift+W still open and close tabs; Ctrl+Tab and Ctrl+1\u20269 always switch.")
        ListRow(
            "Keyboard shortcuts",
            subtitle = "Ctrl+Shift+/ opens this from a session",
            surface = Color.Transparent,
            minHeight = 44.dp,
            onClick = { sheet = true },
            trailing = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) },
        )
    }
}

/**
 * The host editor's row: this host's Alt behaviour, over the app-wide choice. Editor state like its
 * siblings: the editor holds [value] (null follows the app), Save commits it through
 * [app.berth.domain.model.HardwareKeyboardSettings.withHostAltKey]. With no [hostId] yet (a host
 * not saved) the row is disabled and says so; the override keys on the host's id.
 */
@Composable
fun HostAltKeyPicker(vm: AppViewModel, hostId: String?, value: AltKeyMode?, onSelect: (AltKeyMode?) -> Unit) {
    val settings by vm.hardwareKeyboard.collectAsState()
    if (hostId == null) {
        PickerRow("Alt key", "Save the host first", onClick = {}, caption = HOST_ALT_KEY_CAPTION, captionLines = 2, enabled = false)
        return
    }
    CyclePicker("Alt key", listOf<AltKeyMode?>(null) + AltKeyMode.entries, value, { hostAltKeyLabel(it, settings.altKey) }, caption = HOST_ALT_KEY_CAPTION, captionLines = 2, onSelect = onSelect)
}

/** Two lines beside the row's value; the Settings row carries the rest of the explanation. */
private const val HOST_ALT_KEY_CAPTION = "A hardware keyboard\u2019s Alt on this host"

fun altKeyLabel(mode: AltKeyMode): String = when (mode) {
    AltKeyMode.ESC_PREFIX -> "Escape prefix"
    AltKeyMode.META -> "Eighth bit"
}

fun hostAltKeyLabel(mode: AltKeyMode?, appWide: AltKeyMode): String =
    if (mode == null) "Inherit (${altKeyLabel(appWide).lowercase()})" else altKeyLabel(mode)
