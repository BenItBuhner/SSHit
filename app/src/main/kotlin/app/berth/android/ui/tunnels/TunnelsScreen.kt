package app.berth.android.ui.tunnels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.berth.android.session.TunnelStatus
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.Glyph
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import app.berth.domain.model.SessionState
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import java.util.UUID

/**
 * Tunnels for one host (C14), or every host's tunnels grouped by host when [hostId] is null.
 * Rows carry the live status from the session that runs them; the switch enables or disables.
 */
@Composable
fun TunnelsScreen(vm: AppViewModel, hostId: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val hosts by vm.hosts.collectAsState()
    val all by vm.tunnels.collectAsState()
    val statuses by vm.tunnelStatuses.collectAsState()
    val records by vm.records.collectAsState(initial = emptyList())
    val host = hosts.firstOrNull { it.id == hostId }
    val shown = if (hostId == null) all else all.filter { it.hostId == hostId }
    var editor by remember { mutableStateOf<TunnelEditorTarget?>(null) }

    fun hostActive(id: String) = records.any { it.hostId == id && it.state.isActive }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(
            title = if (host != null) "Tunnels \u00B7 ${host.name}" else "Tunnels",
            onBack = onBack,
            actions = {
                if (hostId != null || hosts.isNotEmpty()) {
                    IconAction(onClick = { editor = TunnelEditorTarget(hostId = hostId, tunnel = null) }, description = "Add tunnel") { Glyph("+", size = 24) }
                }
            },
        )
        if (shown.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState(
                title = "No tunnels yet.",
                body = if (host != null) "Forward a port from this device to something ${host.name} can reach, expose a local service on the server, or run a SOCKS proxy through it."
                else "Tunnels belong to hosts. Add one here or from a host's settings; they run while that host has a session.",
            ) {
                if (hostId != null || hosts.isNotEmpty()) BerthButton("Add tunnel", kind = ButtonKind.PRIMARY, onClick = { editor = TunnelEditorTarget(hostId, null) })
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hostId == null) {
                    val grouped = shown.groupBy { it.hostId }
                    val order = hosts.map { it.id }.filter { it in grouped } + grouped.keys.filter { id -> hosts.none { it.id == id } }
                    for (id in order) {
                        val tunnels = grouped[id] ?: continue
                        val h = hosts.firstOrNull { it.id == id }
                        item(key = "host-$id") {
                            Row(
                                Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (h != null) Swatch(h.color, h.monogram, 20.dp)
                                SectionLabel(h?.name ?: "Removed host")
                            }
                        }
                        items(tunnels, key = { it.id }) { t ->
                            TunnelRow(vm, t, statuses[t.id], hostActive(t.hostId), onEdit = { editor = TunnelEditorTarget(t.hostId, t) })
                        }
                    }
                } else {
                    items(shown, key = { it.id }) { t ->
                        TunnelRow(vm, t, statuses[t.id], hostActive(t.hostId), onEdit = { editor = TunnelEditorTarget(t.hostId, t) })
                    }
                }
                if (hostId != null) {
                    item {
                        Text(
                            "Tunnels run while this host has a session.",
                            style = BerthType.caption,
                            color = c.text3,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    editor?.let { target ->
        TunnelEditorSheet(vm, hostId = target.hostId, existing = target.tunnel, onDismiss = { editor = null })
    }
}

class TunnelEditorTarget(val hostId: String?, val tunnel: Tunnel?)

/**
 * One tunnel as a [ListRow]: state dot leading, the spec in Mono, type and status in one Caption
 * line, then Open for local web ports, Retry after a failure, and the switch.
 */
@Composable
fun TunnelRow(
    vm: AppViewModel,
    tunnel: Tunnel,
    status: TunnelStatus?,
    hostActive: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    surface: Color = Berth.colors.surface2,
) {
    val c = Berth.colors
    val uris = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    val dot = when (status) {
        is TunnelStatus.Up -> SessionState.LIVE
        TunnelStatus.Starting -> SessionState.CONNECTING
        is TunnelStatus.Failed -> SessionState.FAILED
        null -> SessionState.DETACHED
    }
    val stateText = when (status) {
        is TunnelStatus.Up -> if (tunnel.bindPort == 0 || status.localPort != tunnel.bindPort) "Up on port ${status.localPort}" else "Up"
        TunnelStatus.Starting -> "Starting"
        is TunnelStatus.Failed -> status.reason
        null -> when {
            !tunnel.enabled -> "Off"
            hostActive -> "Waiting for the session"
            else -> "Runs when a session is open"
        }
    }
    val failed = status is TunnelStatus.Failed
    Box(modifier) {
        ListRow(
            title = tunnel.spec,
            titleStyle = BerthType.mono.copy(fontSize = BerthType.body.fontSize, lineHeight = BerthType.body.lineHeight),
            titleColor = if (tunnel.enabled) c.text1 else c.text2,
            subtitle = buildAnnotatedString {
                append(tunnel.type.label)
                append(" \u00B7 ")
                if (failed) withStyle(SpanStyle(color = c.danger)) { append(stateText) } else append(stateText)
                if (tunnel.exposed) append(" \u00B7 all interfaces")
            },
            surface = surface,
            onClick = onEdit,
            onLongClick = { menu = true },
            leading = { Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { StatusDot(dot) } },
            trailing = {
                val url = tunnel.openUrl
                if (status is TunnelStatus.Up && url != null) {
                    BerthButton("Open", kind = ButtonKind.TEXT, onClick = { uris.openUri(url) }, modifier = Modifier.height(36.dp))
                }
                if (failed) {
                    BerthButton("Retry", kind = ButtonKind.TEXT, onClick = { vm.retryTunnel(tunnel.id) }, modifier = Modifier.height(36.dp))
                }
                TunnelSwitch(checked = tunnel.enabled, onCheckedChange = { vm.setTunnelEnabled(tunnel.id, it) })
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
            DropdownMenuItem(text = { Text("Edit", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(
                text = { Text("Duplicate", style = BerthType.body, color = c.text1) },
                onClick = {
                    menu = false
                    vm.saveTunnel(tunnel.copy(id = UUID.randomUUID().toString(), enabled = false, bindPort = if (tunnel.bindPort in 1..65534) tunnel.bindPort + 1 else tunnel.bindPort))
                },
            )
            DropdownMenuItem(text = { Text("Delete", style = BerthType.body, color = c.danger) }, onClick = { menu = false; vm.deleteTunnel(tunnel.id) })
        }
    }
}

/**
 * A forward an `ssh://` link asks for, not yet saved (spec C10): the same row as a saved tunnel,
 * so the two read alike, with the dot grey since nothing runs yet, `from the link` where a saved
 * row has its state (or the [problem] the tunnel editor would raise, in danger), `all interfaces`
 * in the attention colour the tunnel editor warns in, and the switch deciding whether Save keeps
 * it. Nothing here is saved or started until the editor's Save.
 */
@Composable
fun PendingTunnelRow(
    tunnel: Tunnel,
    kept: Boolean,
    onKeptChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    problem: String? = null,
    surface: Color = Berth.colors.surface2,
) {
    val c = Berth.colors
    ListRow(
        title = tunnel.spec,
        modifier = modifier,
        titleStyle = BerthType.mono.copy(fontSize = BerthType.body.fontSize, lineHeight = BerthType.body.lineHeight),
        titleColor = if (kept) c.text1 else c.text2,
        subtitle = buildAnnotatedString {
            append(tunnel.type.label)
            append(" \u00B7 ")
            when {
                !kept -> append("left out")
                problem != null -> withStyle(SpanStyle(color = c.danger)) { append(problem) }
                else -> append("from the link")
            }
            if (kept && tunnel.exposed) withStyle(SpanStyle(color = c.attention)) { append(" \u00B7 all interfaces") }
        },
        surface = surface,
        onClick = { onKeptChange(!kept) },
        leading = { Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { StatusDot(SessionState.DETACHED) } },
        trailing = { TunnelSwitch(checked = kept, onCheckedChange = onKeptChange) },
    )
}

/** The switch at the end of a tunnel row, in the app's colours. */
@Composable
private fun TunnelSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = Berth.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = c.text2,
            uncheckedTrackColor = c.surface4,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * Add or edit a tunnel. Type first, then what to bind and where to send it; the port and
 * conflict checks from the model gate Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelEditorSheet(vm: AppViewModel, hostId: String?, existing: Tunnel?, onDismiss: () -> Unit) {
    val c = Berth.colors
    val hosts by vm.hosts.collectAsState()
    val all by vm.tunnels.collectAsState()
    var chosenHost by remember { mutableStateOf(existing?.hostId ?: hostId ?: hosts.firstOrNull()?.id) }
    var type by remember { mutableStateOf(existing?.type ?: TunnelType.LOCAL) }
    var allInterfaces by remember { mutableStateOf(existing?.exposed ?: false) }
    var bindPort by remember { mutableStateOf(existing?.bindPort?.toString() ?: "") }
    var destinationHost by remember { mutableStateOf(existing?.destinationHost ?: "localhost") }
    var destinationPort by remember { mutableStateOf(existing?.destinationPort?.takeIf { it > 0 }?.toString() ?: "") }

    val draft = Tunnel(
        id = existing?.id ?: "new",
        hostId = chosenHost ?: "",
        type = type,
        bindAddress = if (allInterfaces) (if (type == TunnelType.REMOTE) "" else "0.0.0.0") else "127.0.0.1",
        bindPort = bindPort.toIntOrNull() ?: 0,
        destinationHost = if (type == TunnelType.DYNAMIC) "" else destinationHost.trim(),
        destinationPort = if (type == TunnelType.DYNAMIC) 0 else destinationPort.toIntOrNull() ?: 0,
        enabled = existing?.enabled ?: true,
    )
    val touched = bindPort.isNotEmpty() && (type == TunnelType.DYNAMIC || destinationPort.isNotEmpty())
    val problem = if (chosenHost == null) "Add a host first." else if (touched) draft.validate(all) else null
    val canSave = touched && problem == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(if (existing == null) "New tunnel" else "Edit tunnel", hosts.firstOrNull { it.id == chosenHost }?.let { "${it.name} \u00B7 ${it.userAtHost}" })
            if (hostId == null && existing == null) {
                CyclePicker("Host", hosts.map { it.id }, chosenHost, { id -> hosts.firstOrNull { it.id == id }?.name ?: "Choose" }) { chosenHost = it }
            }
            Text("Type", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp))
            SegmentedControl(TunnelType.entries.map { it.label }, type.ordinal, { type = TunnelType.entries[it] })
            Text(
                when (type) {
                    TunnelType.LOCAL -> "A port on this device reaches something the server can see, like a database or a web console."
                    TunnelType.REMOTE -> "A port on the server reaches something this device can see."
                    TunnelType.DYNAMIC -> "A SOCKS5 proxy on this device sends every connection out through the server."
                },
                style = BerthType.caption,
                color = c.text3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(if (type == TunnelType.REMOTE) "Listen on the server" else "Listen on this device", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp))
            SegmentedControl(listOf("Loopback only", "All interfaces"), if (allInterfaces) 1 else 0, { allInterfaces = it == 1 })
            if (allInterfaces) {
                Text(
                    if (type == TunnelType.REMOTE) "Anyone who can reach the server's port can use this tunnel, if the server allows it (GatewayPorts)."
                    else "Other devices on your network can use this tunnel while it is up.",
                    style = BerthType.caption,
                    color = c.attention,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthField(
                    bindPort,
                    { bindPort = it.filter(Char::isDigit).take(5) },
                    label = if (type == TunnelType.REMOTE) "Remote port" else "Local port",
                    placeholder = if (type == TunnelType.DYNAMIC) "1080" else "8080",
                    mono = true,
                    modifier = Modifier.width(140.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (type != TunnelType.DYNAMIC) {
                    BerthField(destinationHost, { destinationHost = it }, label = "Destination host", placeholder = "localhost", mono = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    BerthField(destinationPort, { destinationPort = it.filter(Char::isDigit).take(5) }, label = "Port", placeholder = "80", mono = true, modifier = Modifier.width(88.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            if (touched) {
                Text(problem ?: draft.spec, style = if (problem != null) BerthType.caption else BerthType.mono, color = if (problem != null) c.danger else c.text2, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(
                    if (existing == null) "Add" else "Save",
                    kind = ButtonKind.PRIMARY,
                    enabled = canSave,
                    onClick = {
                        vm.saveTunnel(draft.copy(id = existing?.id ?: UUID.randomUUID().toString()))
                        onDismiss()
                    },
                )
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                if (existing != null) {
                    Spacer(Modifier.weight(1f))
                    BerthButton("Delete", kind = ButtonKind.DESTRUCTIVE, onClick = { vm.deleteTunnel(existing.id); onDismiss() })
                }
            }
        }
    }
}

/**
 * The host editor's Tunnels panel body (C10): the host's tunnels as rows on the panel surface,
 * then [pending] (a link's forwards awaiting Save, so they sit with their peers), then `+ Add tunnel`.
 */
@Composable
fun TunnelsPanelContent(vm: AppViewModel, host: Host, pending: @Composable () -> Unit = {}) {
    val c = Berth.colors
    val all by vm.tunnels.collectAsState()
    val statuses by vm.tunnelStatuses.collectAsState()
    val records by vm.records.collectAsState(initial = emptyList())
    val mine = all.filter { it.hostId == host.id }
    val active = records.any { it.hostId == host.id && it.state.isActive }
    var editor by remember { mutableStateOf<TunnelEditorTarget?>(null) }
    for (t in mine) {
        TunnelRow(vm, t, statuses[t.id], hostActive = active, onEdit = { editor = TunnelEditorTarget(host.id, t) }, surface = Color.Transparent)
    }
    pending()
    ListRow(
        title = "Add tunnel",
        surface = Color.Transparent,
        minHeight = 44.dp,
        onClick = { editor = TunnelEditorTarget(host.id, null) },
        titleColor = c.text2,
        leading = { Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Text("+", style = BerthType.title, color = c.text2) } },
    )
    editor?.let { target -> TunnelEditorSheet(vm, hostId = target.hostId, existing = target.tunnel, onDismiss = { editor = null }) }
}
