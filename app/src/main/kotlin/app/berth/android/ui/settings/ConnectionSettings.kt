package app.berth.android.ui.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.theme.Berth
import app.berth.domain.model.IdleDetach

/**
 * The Connection panel of Settings (spec C20): how long an idle session is kept, and the Background
 * row that opens the battery-optimisation explainer. Its own composable, like the History panel, so
 * the Settings screen registers it in one line.
 */
@Composable
fun ConnectionSettingsPanel(vm: AppViewModel) {
    val c = Berth.colors
    val settings by vm.connectionSettings.collectAsState()
    var background by remember { mutableStateOf(false) }
    if (background) BackgroundSheet(vm, onDismiss = { background = false })

    Panel(label = "Connection") {
        CyclePicker(
            "Detach idle sessions",
            IdleDetach.entries,
            settings.idleDetach,
            ::idleDetachLabel,
            caption = "Keep a session that has done nothing for this long as a frozen frame; tunnels and the tab on screen are left alone",
        ) { vm.setIdleDetach(it) }
        val chevron: @Composable RowScope.() -> Unit = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) }
        ListRow(
            "Background",
            subtitle = "The service that keeps sessions alive, the battery exemption, and the steps some phones need",
            surface = Color.Transparent,
            minHeight = 44.dp,
            onClick = {
                // Seen from here is seen: the app does not raise the same sheet on its own later.
                vm.batteryExplainerRaised()
                background = true
            },
            trailing = chevron,
        )
        PanelNote("Sessions keep running while the app is away; detach them all from the notification. Idle-detach is off unless you turn it on.")
    }
}

/** How the idle-detach policy reads in the picker (spec C20): Never, 15 min, 1 hour, 4 hours. */
internal fun idleDetachLabel(policy: IdleDetach): String = when (policy) {
    IdleDetach.NEVER -> "Never"
    IdleDetach.FIFTEEN_MINUTES -> "15 min"
    IdleDetach.ONE_HOUR -> "1 hour"
    IdleDetach.FOUR_HOURS -> "4 hours"
}
