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
import app.berth.android.ui.keyboard.label
import app.berth.android.ui.theme.Berth
import app.berth.domain.model.AltKeyMode
import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.LeaderKey
import app.berth.domain.model.VolumeButtons

/**
 * Settings › Hardware keyboard (spec C22): what Alt does, what every app chord starts with and
 * which key is the Leader when that is the prefix, whether the Deck a keyboard's user opens from
 * its strip is the compact row, whether readline's Ctrl+T and Ctrl+W belong to the shell, and the
 * shortcut sheet, which is the remap table (A44). One panel, registered with one line in
 * [SettingsScreen]; the sheet it opens is the same one the sheet's chord opens on the Stage, so the
 * two never drift, and every chord the panel names is read from the same [ChordTable] the Stage
 * dispatches from, so a prefix or a remap changes the words here the moment it lands.
 */
@Composable
fun HardwareKeyboardPanel(vm: AppViewModel) {
    val c = Berth.colors
    val settings by vm.hardwareKeyboard.collectAsState()
    val ctrlTabKeys by vm.ctrlTabKeysReachTerminal.collectAsState()
    val table = remember(settings, ctrlTabKeys) { ChordTable(settings, ctrlTabKeys) }
    var sheet by remember { mutableStateOf(false) }
    if (sheet) ShortcutSheet(table, onDismiss = { sheet = false }, onRemap = { action, chord -> vm.updateHardwareKeyboard { it.withRemap(action, chord) } })

    Panel(label = "Hardware keyboard") {
        CyclePicker("Alt key", AltKeyMode.entries, settings.altKey, ::altKeyLabel, caption = "What Alt does to a character; a host may say otherwise", captionLines = 2) { mode ->
            vm.updateHardwareKeyboard { it.copy(altKey = mode) }
        }
        PanelNote("Escape then the key is what every shell and editor reads as Meta. The eighth bit is for the few programs that want a Meta byte; keys outside ASCII still take the Escape prefix.")
        // The prefix is one choice for every chord; a plain Ctrl key is never offered, since those are the shell's (C22).
        CyclePicker("Chord prefix", ChordPrefix.entries, settings.chordPrefix, ::chordPrefixLabel, caption = "What every app chord starts with; plain Ctrl is the shell\u2019s", captionLines = 2) { prefix ->
            vm.updateHardwareKeyboard { it.withChordPrefix(prefix) }
        }
        if (settings.chordPrefix == ChordPrefix.LEADER) {
            CyclePicker("Leader key", LeaderKey.entries, settings.leaderKey, { it.label() }, caption = "Held with the key, or tapped once before it", captionLines = 2) { key ->
                vm.updateHardwareKeyboard { it.copy(leaderKey = key) }
            }
        }
        PanelNote(prefixNote(table))
        // A keyboard folds the Deck to its strip whatever this says (spec C4); the toggle is what the strip opens
        // to. The caption fits its two lines at 1× beside the switch; the panel's label says "keyboard" already.
        ToggleRow("Compact Deck when expanded", settings.compactDeck, { on -> vm.updateHardwareKeyboard { it.copy(compactDeck = on) } }, caption = "Opened from its strip, one row of modifiers and actions")
        // A title short enough to read whole beside its switch; the caption names the keys.
        ToggleRow("Readline keys go to the shell", ctrlTabKeys, { vm.setCtrlTabKeysReachTerminal(it) }, caption = "Ctrl+T and Ctrl+W: transpose and delete word")
        PanelNote("${table.chord(ChordAction.NEW_TAB).label()} and ${table.chord(ChordAction.CLOSE_TAB).label()} still open and close tabs; Ctrl+Tab and Ctrl+1\u20269 always switch.")
        val rebound = settings.remaps.size
        ListRow(
            "Keyboard shortcuts",
            subtitle = buildString {
                append("${table.chord(ChordAction.SHORTCUT_SHEET).label()} opens this from a session")
                if (rebound > 0) append(" \u00B7 $rebound rebound")
            },
            surface = Color.Transparent,
            minHeight = 44.dp,
            onClick = { sheet = true },
            trailing = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) },
        )
    }
}

/**
 * The line under the prefix rows, for what the prefix asks of the hands: nothing under Ctrl+Shift
 * and Meta beyond the way out of pass-through, and under the Leader how a tap and a hold differ and
 * that the key is no longer the shell's. The chords are the table's, so a remap reads true here.
 */
private fun prefixNote(table: ChordTable): String {
    val settings = table.settings
    val passThrough = table.chord(ChordAction.PASS_THROUGH).label()
    return when (settings.chordPrefix) {
        ChordPrefix.LEADER ->
            "${settings.leaderKey.label()} is the app\u2019s and never reaches the shell: hold it with a key, or tap it and the next key is the chord; a second tap or Esc lets a tap go. $passThrough sends every key to the shell until it is pressed again."
        else -> "$passThrough sends every key to the shell, the chords included, until it is pressed again or its pill is tapped."
    }
}

/**
 * Settings › Volume buttons (spec A43, C20): what the phone's two buttons do while a shell tab is on
 * stage, off by default. Its own panel beside the keyboard's, the way C20 lists it, since a phone
 * has these buttons with no keyboard attached and the panel's label is what a user scanning for
 * them reads.
 */
@Composable
fun VolumeButtonsPanel(vm: AppViewModel) {
    val settings by vm.hardwareKeyboard.collectAsState()
    Panel(label = "Volume buttons") {
        // A title short enough to stand beside the longest value; the caption says when.
        CyclePicker("On a shell tab", VolumeButtons.entries, settings.volumeButtons, ::volumeButtonsLabel, caption = "Up then down; every other screen keeps the volume", captionLines = 2) { choice ->
            vm.updateHardwareKeyboard { it.copy(volumeButtons = choice) }
        }
        PanelNote("A held button repeats the way a held key does. A Files tab leaves them to the volume as well.")
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

/**
 * Two lines beside the row's value, which is the widest of the editor's ("Inherit (escape prefix)")
 * and leaves the caption less room than the clipboard row's has; the row sits in the host's editor,
 * so "this host" is said by where it is, and the Settings row carries the rest of the explanation.
 */
private const val HOST_ALT_KEY_CAPTION = "For a hardware keyboard"

fun altKeyLabel(mode: AltKeyMode): String = when (mode) {
    AltKeyMode.ESC_PREFIX -> "Escape prefix"
    AltKeyMode.META -> "Eighth bit"
}

fun hostAltKeyLabel(mode: AltKeyMode?, appWide: AltKeyMode): String =
    if (mode == null) "Inherit (${altKeyLabel(appWide).lowercase()})" else altKeyLabel(mode)

/** The prefix as the picker's value: the keys, or the Leader by name. */
fun chordPrefixLabel(prefix: ChordPrefix): String = when (prefix) {
    ChordPrefix.CTRL_SHIFT -> "Ctrl+Shift"
    ChordPrefix.META -> "Meta"
    ChordPrefix.LEADER -> "Leader key"
}

/** The volume setting as the picker's value: what the two buttons send, up then down. */
fun volumeButtonsLabel(setting: VolumeButtons): String = when (setting) {
    VolumeButtons.OFF -> "Off"
    VolumeButtons.ARROWS -> "Up and Down arrows"
    VolumeButtons.PAGES -> "Page Up and Down"
    VolumeButtons.FONT_SIZE -> "Font size"
    VolumeButtons.INTERRUPT_AND_ENTER -> "Ctrl+C and Enter"
}
