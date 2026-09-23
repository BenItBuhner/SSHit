package app.berth.android.ui.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.security.WindowSecurity
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.theme.Berth
import app.berth.domain.model.ClipboardClear
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.RemoteClipboardPolicy
import kotlinx.coroutines.launch

/**
 * Settings › Security (spec C20): the app lock and its timeout, screenshot blocking, clipboard
 * auto-clear, the remote clipboard switch, and the way to Known hosts. One panel, registered
 * with one line in [SettingsScreen]. A switch's caption fits its one line; what needs a sentence
 * goes in the [PanelNote] under the row.
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
            caption = if (deviceSecure) "Ask for your fingerprint, face or PIN" else "Set a screen lock on this device first",
            enabled = deviceSecure,
        )
        if (s.appLock) {
            CyclePicker("Lock after leaving", LockTimeout.entries, s.lockTimeout, ::lockTimeoutLabel) { vm.security.setLockTimeout(it) }
            PanelNote("Opening Berth always asks. \u201CNever\u201D means leaving and coming back does not.")
        }
        val lockForcesSecure = WindowSecurity.lockForcesSecure && s.appLock && !s.blockScreenshots
        ToggleRow(
            "Block screenshots",
            s.blockScreenshots,
            { vm.security.setBlockScreenshots(it) },
            caption = if (lockForcesSecure) "On while the app lock is on" else "No screenshots or screen recording",
        )
        PanelNote("The preview in Recents is hidden too; the app lock hides it on its own.")
        CyclePicker("Clear clipboard", ClipboardClear.entries, s.clipboardClear, ::clipboardClearLabel) { vm.security.setClipboardClear(it) }
        PanelNote("Only what Berth copied is cleared; anything another app put there since is left alone.")
        ToggleRow("Remote clipboard", s.remoteClipboard, { vm.security.setRemoteClipboard(it) }, caption = "Servers may write this phone\u2019s clipboard")
        PanelNote("Programs on a server write it with OSC 52. Each host can allow or refuse this on its own, under Advanced in the host\u2019s settings; the first blocked write from a host shows a notice once.")
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

/**
 * The host editor's row: this host's answer to remote clipboard writes, over the app-wide switch.
 * Editor state like its siblings: the editor holds [value], Save commits it. With no [hostId] yet
 * (a host not saved) the row is disabled and its caption says so, with no value beside it to
 * narrow the caption at the font cap; the override keys on the host's id.
 */
@Composable
fun HostRemoteClipboardPicker(vm: AppViewModel, hostId: String?, value: RemoteClipboardPolicy, onSelect: (RemoteClipboardPolicy) -> Unit) {
    val settings by vm.security.settings.collectAsState()
    val s = settings ?: return
    if (hostId == null) {
        PickerRow("Remote clipboard", "", onClick = {}, caption = UNSAVED_HOST_REMOTE_CLIPBOARD_CAPTION, captionLines = 3, enabled = false)
        return
    }
    CyclePicker("Remote clipboard", RemoteClipboardPolicy.entries, value, { remoteClipboardLabel(it, s.remoteClipboard) }, caption = HOST_REMOTE_CLIPBOARD_CAPTION, captionLines = 2, onSelect = onSelect)
}

/** Two lines beside the row's value; the Settings row carries the rest of the explanation. */
private const val HOST_REMOTE_CLIPBOARD_CAPTION = "Clipboard writes from this server (OSC 52)"

internal const val UNSAVED_HOST_REMOTE_CLIPBOARD_CAPTION = "Save the host first; then set clipboard writes from this server (OSC 52)"

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
