package app.berth.android.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType

/**
 * The History panel of Settings (spec C16): whether sessions keep the commands they run. Its own
 * composable so the Settings screen registers it in one line.
 */
@Composable
fun CommandHistorySettings(vm: AppViewModel) {
    val c = Berth.colors
    val enabled by vm.commandHistoryEnabled.collectAsState()
    Panel(label = "History") {
        ToggleRow(
            "Keep command history",
            enabled,
            { vm.setCommandHistoryEnabled(it) },
            caption = "Each session's commands, from the shell's prompt marks or from what was typed and echoed",
        )
        Text(
            "Kept on this device with the session, up to 2,000 per session. Never captured while echo is off, so passwords stay out. Turning it off keeps what was already recorded.",
            style = BerthType.caption,
            color = c.text3,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        )
    }
}
