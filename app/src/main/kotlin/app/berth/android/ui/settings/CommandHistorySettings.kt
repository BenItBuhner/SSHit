package app.berth.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.ToggleRow

/**
 * The History panel of Settings (spec C16): whether sessions keep the commands they run, and the
 * row that forgets every host's. Its own composable so the Settings screen registers it in one line.
 */
@Composable
fun CommandHistorySettings(vm: AppViewModel, onNotice: (String) -> Unit = {}) {
    val enabled by vm.commandHistoryEnabled.collectAsState()
    Panel(label = "History") {
        // Two lines beside the switch at 1× (A11); the note under it carries the rest.
        ToggleRow(
            "Keep command history",
            enabled,
            { vm.setCommandHistoryEnabled(it) },
            caption = "Each host's commands, from prompt marks or typed and echoed input",
        )
        ListRow(
            title = "Clear command history",
            subtitle = "Every host's, on this device",
            onClick = {
                vm.clearCommandHistory(null)
                onNotice("Command history cleared")
            },
        )
        PanelNote("Kept on this device per host, shared by every tab on it, up to 2,000 a host. Never captured while echo is off, so passwords stay out. Turning it off keeps what was already recorded.")
    }
}
