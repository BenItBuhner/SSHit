package app.berth.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.theme.Berth

/**
 * Settings › Notifications (spec C21): one row to the system page for this app, its subtitle
 * saying whether the shade is reachable, and a caption naming the three channels. Berth never
 * asks for the permission a second time itself; this row is the way back after a refusal.
 */
@Composable
fun NotificationsSection(notifier: SessionNotifier) {
    val c = Berth.colors
    val enabled by notifier.enabled.collectAsState()
    val context = LocalContext.current
    Panel(label = "Notifications") {
        ListRow(
            "System notifications",
            subtitle = if (enabled) "On \u00B7 Sessions, Attention and Problems" else "Off \u00B7 nothing reaches you outside the app",
            surface = Color.Transparent,
            minHeight = 44.dp,
            onClick = { context.startActivity(notifier.systemSettingsIntent()) },
            trailing = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) },
        )
        PanelNote("Sessions is silent and shows what is connected. Attention is a tab that needs you while Berth is away. Problems is a reconnect that gave up or a sign-in the server refused.")
    }
}
