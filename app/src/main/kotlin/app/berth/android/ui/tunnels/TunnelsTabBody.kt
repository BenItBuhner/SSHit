package app.berth.android.ui.tunnels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.session.TunnelStatus
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.files.formatSize
import app.berth.android.ui.stage.FailedPanel
import app.berth.android.ui.stage.StatePill
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionState
import app.berth.domain.model.Tunnel
import app.berth.ssh.ForwardTraffic
import kotlinx.coroutines.delay

/**
 * A Tunnels tab's body under the tab strip (spec C3, tab kinds; C14): the host's forwards as rows
 * carrying their live state and traffic, over the same state pill a terminal has, with no Deck. The
 * login behind the tab opens no shell, so this is the whole stage; Terminal and Files are a menu
 * away. The counters move with every packet rather than through state, so while the tab is Live
 * they are read once a second ([trafficClock]) and the rows redraw from that clock alone.
 */
@Composable
fun TunnelsTabBody(vm: AppViewModel, session: TerminalSession, onEditHost: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val statuses by session.tunnels.collectAsState()
    val failure by session.failure.collectAsState()
    val all by vm.tunnels.collectAsState()
    val mine = remember(all, record.hostId) { all.filter { it.hostId == record.hostId } }
    val live = record.state == SessionState.LIVE
    val clock = trafficClock(running = live)
    var editor by remember { mutableStateOf<TunnelEditorTarget?>(null) }

    LaunchedEffect(session.id) {
        session.onStage = true
        session.markSeen()
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (mine.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(48.dp))
                    EmptyState(
                        title = "No tunnels on ${record.hostSnapshot.name}.",
                        body = "This tab is a login that carries the host's port forwards and opens no shell. Add a forward and it starts here the moment the login is up.",
                    ) {
                        record.hostId?.let { hostId -> BerthButton("Add tunnel", kind = ButtonKind.PRIMARY, onClick = { editor = TunnelEditorTarget(hostId, null) }) }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(mine, key = { it.id }) { tunnel ->
                        TunnelStateRow(
                            tunnel = tunnel,
                            status = statuses[tunnel.id],
                            loginActive = record.state.isActive,
                            clock = clock,
                            onRetry = { vm.retryTunnel(tunnel.id) },
                            onEdit = { editor = TunnelEditorTarget(tunnel.hostId, tunnel) },
                        )
                    }
                    item(key = "summary") {
                        Text(
                            tunnelsSummary(mine, statuses, record.state),
                            style = BerthType.caption,
                            color = c.text3,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                        )
                    }
                }
            }
            if (record.state == SessionState.FAILED) {
                FailedPanel(
                    plain = failure?.first ?: "Couldn't connect.",
                    raw = failure?.second,
                    onRetry = { vm.reconnect(session.id) },
                    onEditHost = record.hostId?.let { id -> { onEditHost(id) } },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                )
            }
        }
        StatePill(
            state = record.state,
            retryIn = session.retryIn,
            lastLiveAt = record.lastLiveAt,
            onReconnect = { vm.reconnect(session.id) },
            onDetach = { vm.detach(session.id) },
            onClose = { vm.close(session.id) },
            via = session.via,
        )
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
    }

    editor?.let { target -> TunnelEditorSheet(vm, hostId = target.hostId, existing = target.tunnel, onDismiss = { editor = null }) }
}

/**
 * One forward on the Tunnels stage: state dot leading, the spec in Mono, then the type and state
 * on one Caption line and, while it is up, its traffic on a second (`2 open · 14 served · 1.2 MB up
 * · 48 KB down`). Open for a local web port, Retry after a failure; tapping the row edits it.
 */
@Composable
internal fun TunnelStateRow(
    tunnel: Tunnel,
    status: TunnelStatus?,
    loginActive: Boolean,
    clock: Long,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val uris = LocalUriHandler.current
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
            loginActive -> "Waiting for the login"
            else -> "Not connected"
        }
    }
    val failed = status is TunnelStatus.Failed
    // The counters are plain atomics, not state: [clock] changing is what brings this row back here
    // to read them again, so it is remembered against the tick rather than the traffic object.
    val traffic = (status as? TunnelStatus.Up)?.traffic?.let { t -> remember(t, clock) { trafficLine(t) } }
    ListRow(
        title = tunnel.spec,
        modifier = modifier,
        titleStyle = BerthType.mono.copy(fontSize = BerthType.body.fontSize, lineHeight = BerthType.body.lineHeight),
        titleColor = if (tunnel.enabled) c.text1 else c.text2,
        subtitle = buildAnnotatedString {
            append(tunnel.type.label)
            append(" \u00B7 ")
            if (failed) withStyle(SpanStyle(color = c.danger)) { append(stateText) } else append(stateText)
            if (tunnel.exposed) append(" \u00B7 all interfaces")
            if (traffic != null) {
                append("\n")
                append(traffic)
            }
        },
        subtitleMaxLines = 2,
        onClick = onEdit,
        leading = { Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { StatusDot(dot) } },
        trailing = {
            val url = tunnel.openUrl
            if (status is TunnelStatus.Up && url != null) {
                BerthButton("Open", kind = ButtonKind.TEXT, onClick = { uris.openUri(url) }, modifier = Modifier.height(36.dp))
            }
            if (failed) BerthButton("Retry", kind = ButtonKind.TEXT, onClick = onRetry, modifier = Modifier.height(36.dp))
        },
    )
}

/** `2 open · 14 served · 1.2 MB up · 48 KB down`; `no connections yet` before the first. */
internal fun trafficLine(traffic: ForwardTraffic): String {
    val served = traffic.connections
    if (served == 0) return "no connections yet"
    val open = traffic.openConnections
    return listOfNotNull(
        if (open > 0) "$open open" else null,
        if (served == 1) "1 served" else "$served served",
        "${formatSize(traffic.bytesUp)} up",
        "${formatSize(traffic.bytesDown)} down",
    ).joinToString(" \u00B7 ")
}

/** The caption under the rows: `3 of 4 up · this login opens no shell`, or what the forwards wait for. */
internal fun tunnelsSummary(tunnels: List<Tunnel>, statuses: Map<String, TunnelStatus>, state: SessionState): String {
    val enabled = tunnels.count { it.enabled }
    val up = tunnels.count { statuses[it.id] is TunnelStatus.Up }
    val head = when {
        state == SessionState.LIVE && enabled == 0 -> "Every tunnel is off"
        state == SessionState.LIVE -> "$up of $enabled up"
        state.isActive -> "Tunnels start when the login is up"
        else -> "Tunnels run while this login is up"
    }
    return "$head \u00B7 this login opens no shell; Terminal and Files are in the menu."
}

/** A 1 Hz tick while [running], so byte counters redraw without the state changing; frozen otherwise. */
@Composable
private fun trafficClock(running: Boolean): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1_000)
            tick++
        }
    }
    return tick
}
