package app.berth.android.ui.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.ClipboardClear
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.RemoteClipboardPolicy
import kotlinx.coroutines.launch

/**
 * Settings › Security (spec C20): the app lock and its timeout, screenshot blocking, clipboard
 * auto-clear, the remote clipboard switch, and the way to Known hosts. One panel, registered
 * with one line in [SettingsScreen].
 */
@Composable
fun SecurityPanel(vm: AppViewModel, onKnownHosts: () -> Unit) {
    val c = Berth.colors
    val settings by vm.security.settings.collectAsState()
    val known by vm.knownHosts.collectAsState()
    val scope = rememberCoroutineScope()
    val s = settings ?: return
    val deviceSecure = vm.security.deviceSecure
    val chevron: @Composable RowScope.() -> Unit = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) }

    Panel(label = "Security") {
        ToggleRow(
            "App lock",
            s.appLock,
            { on -> scope.launch { vm.security.setAppLock(on) } },
            caption = if (deviceSecure) "Fingerprint, face or screen lock to open Berth" else "Set a screen lock on this device first",
        )
        if (s.appLock) {
            CyclePicker("Lock after leaving", LockTimeout.entries, s.lockTimeout, ::lockTimeoutLabel) { vm.security.setLockTimeout(it) }
            Text("Opening Berth always asks. \u201CNever\u201D means leaving and coming back does not.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
        ToggleRow("Block screenshots", s.blockScreenshots, { vm.security.setBlockScreenshots(it) }, caption = "No screenshots or screen recording, and no preview in Recents")
        CyclePicker("Clear clipboard", ClipboardClear.entries, s.clipboardClear, ::clipboardClearLabel) { vm.security.setClipboardClear(it) }
        Text("Only what Berth copied is cleared; anything another app put there since is left alone.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        ToggleRow("Remote clipboard", s.remoteClipboard, { vm.security.setRemoteClipboard(it) }, caption = "Programs on a server may write this phone's clipboard (OSC 52)")
        Text("Each host can allow or refuse this on its own, under Advanced in the host's settings. The first blocked write from a host shows a notice once.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        ListRow(
            "Known hosts",
            subtitle = if (known.isEmpty()) "Saved server keys" else "${known.size} saved server ${if (known.size == 1) "key" else "keys"}" + known.count { it.pinned }.let { if (it > 0) " \u00B7 $it pinned" else "" },
            surface = Color.Transparent,
            minHeight = 44.dp,
            onClick = onKnownHosts,
            trailing = chevron,
        )
    }
}

/** The host editor's row: this host's answer to remote clipboard writes, over the app-wide switch. */
@Composable
fun HostRemoteClipboardPicker(vm: AppViewModel, hostId: String) {
    val settings by vm.security.settings.collectAsState()
    val s = settings ?: return
    CyclePicker("Remote clipboard", RemoteClipboardPolicy.entries, s.remoteClipboardPolicy(hostId), { remoteClipboardLabel(it, s.remoteClipboard) }) {
        vm.security.setHostRemoteClipboard(hostId, it)
    }
}

fun lockTimeoutLabel(timeout: LockTimeout): String = when (timeout) {
    LockTimeout.IMMEDIATELY -> "Immediately"
    LockTimeout.ONE_MINUTE -> "1 minute"
    LockTimeout.FIVE_MINUTES -> "5 minutes"
    LockTimeout.NEVER -> "Never"
}

fun clipboardClearLabel(clear: ClipboardClear): String = when (clear) {
    ClipboardClear.OFF -> "Off"
    ClipboardClear.THIRTY_SECONDS -> "After 30 seconds"
    ClipboardClear.SIXTY_SECONDS -> "After 60 seconds"
}

fun remoteClipboardLabel(policy: RemoteClipboardPolicy, appWide: Boolean): String = when (policy) {
    RemoteClipboardPolicy.INHERIT -> if (appWide) "Inherit (allowed)" else "Inherit (blocked)"
    RemoteClipboardPolicy.ALLOW -> "Allow"
    RemoteClipboardPolicy.DENY -> "Block"
}
