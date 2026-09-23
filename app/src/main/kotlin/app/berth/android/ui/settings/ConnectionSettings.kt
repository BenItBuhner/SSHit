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
import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.IdleDetach

/**
 * The Connection panel of Settings (spec C20): the Keepalive and Reconnect every host inherits
 * unless its editor sets its own, how long an idle session is kept, and the Background row that
 * opens the battery-optimisation explainer. Its own composable, like the History panel, so the
 * Settings screen registers it in one line.
 */
@Composable
fun ConnectionSettingsPanel(vm: AppViewModel) {
    val c = Berth.colors
    val settings by vm.connectionSettings.collectAsState()
    var background by remember { mutableStateOf(false) }
    if (background) BackgroundSheet(vm, onDismiss = { background = false })

    Panel(label = "Connection") {
        // Plain rows as C20 draws them: "default" in the title says a host's editor may set its own, and the editor names what it inherits.
        CyclePicker("Keepalive default", ConnectionSettings.KEEPALIVE_CHOICES, settings.keepaliveSeconds, ::keepaliveLabel) { vm.setKeepaliveDefault(it) }
        CyclePicker("Reconnect default", ConnectionSettings.RECONNECT_CHOICES, settings.reconnectMinutes, ::reconnectLabel) { vm.setReconnectDefault(it) }
        CyclePicker(
            "Detach idle sessions",
            IdleDetach.entries,
            settings.idleDetach,
            ::idleDetachLabel,
            // Two lines beside the row's value at 1× (A11): the condition, what happens, and the one exemption.
            caption = "A session idle off screen for this long is detached; tunnels stay up",
        ) { vm.setIdleDetach(it) }
        val chevron: @Composable RowScope.() -> Unit = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) }
        ListRow(
            "Background",
            subtitle = "The service, the battery exemption and the step some phones add",
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

/** A keepalive span as the pickers read it (spec C20, C10): `15 s`, or Off for 0. */
fun keepaliveLabel(seconds: Int): String = if (seconds == 0) "Off" else "$seconds s"

/** A reconnect window as the pickers read it (spec C20, C10): `15 min`, whole hours as idle-detach says them beside it (`1 hour`), or Forever for 0. */
fun reconnectLabel(minutes: Int): String = when {
    minutes == 0 -> "Forever"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "$minutes min"
}

/** The host editor's Keepalive: the host's own span, or `Inherit (15 s)` naming what Settings gives it. */
fun hostKeepaliveLabel(seconds: Int?, appWide: Int): String =
    seconds?.let(::keepaliveLabel) ?: "Inherit (${keepaliveLabel(appWide).lowercase()})"

/** The host editor's Reconnect: the host's own window, or `Inherit (15 min)` naming what Settings gives it. */
fun hostReconnectLabel(minutes: Int?, appWide: Int): String =
    minutes?.let(::reconnectLabel) ?: "Inherit (${reconnectLabel(appWide).lowercase()})"

/** How the idle-detach policy reads in the picker (spec C20): Never, 15 min, 1 hour, 4 hours. */
internal fun idleDetachLabel(policy: IdleDetach): String = when (policy) {
    IdleDetach.NEVER -> "Never"
    IdleDetach.FIFTEEN_MINUTES -> "15 min"
    IdleDetach.ONE_HOUR -> "1 hour"
    IdleDetach.FOUR_HOURS -> "4 hours"
}
