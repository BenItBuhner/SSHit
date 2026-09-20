package app.berth.android.ui.hosts

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.importer.ImportHostsSheet
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host

/**
 * The host library (spec C9). Tap opens a tab on the host in the current group (spec C3); the row's
 * second line is `user@address:port`, then `via bastion` for a host that jumps, the chain as
 * `bastion › edge`. Long-press offers Files (the host's Files tab, opened or brought on stage, when
 * [onFiles] is given), Connect as tunnel only (a Tunnels tab on the host, whatever its toggle says,
 * when [onTunnels] is given), Edit and Delete. [picker] mode titles the screen "New tab"; back
 * returns to the Stage.
 */
@Composable
fun HostsScreen(
    vm: AppViewModel,
    onConnect: (Host) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onBack: (() -> Unit)?,
    onOpenDrawer: (() -> Unit)?,
    onKnownHosts: () -> Unit,
    picker: Boolean = false,
    onFiles: ((Host) -> Unit)? = null,
    onTunnels: ((Host) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val hosts by vm.hosts.collectAsState()
    val byId = remember(hosts) { hosts.associateBy { it.id } }
    var quickConnect by remember { mutableStateOf(false) }
    var importConfig by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val now = ageTicker()

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(
            title = if (picker) "New tab" else "Hosts",
            onBack = onBack,
            actions = {
                if (onOpenDrawer != null && onBack == null) {
                    IconAction(onClick = onOpenDrawer, description = "Open the drawer") { BerthIcon(BerthIcons.workspace) }
                }
                IconAction(onClick = onAddHost, description = "Add host") { BerthIcon(BerthIcons.add) }
                Box {
                    IconAction(onClick = { menu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                        DropdownMenuItem(text = { Text("Quick connect", style = BerthType.body, color = c.text1) }, onClick = { menu = false; quickConnect = true })
                        DropdownMenuItem(text = { Text("Import ssh config", style = BerthType.body, color = c.text1) }, onClick = { menu = false; importConfig = true })
                        DropdownMenuItem(text = { Text("Known hosts", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onKnownHosts() })
                    }
                }
            },
        )

        if (hosts.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState(
                title = "Nothing here yet.",
                body = "Hosts, keys and history stay on this device. No account. No telemetry.",
            ) {
                BerthButton("Add host", onClick = onAddHost, kind = ButtonKind.PRIMARY)
                BerthButton("Quick connect", onClick = { quickConnect = true }, kind = ButtonKind.TEXT)
                BerthButton("Import ssh config", onClick = { importConfig = true }, kind = ButtonKind.TEXT)
            }
        } else {
            val recent = hosts.filter { it.lastConnectedAt != null }.sortedByDescending { it.lastConnectedAt }.take(3)
            val all = hosts.sortedBy { it.name.lowercase() }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val row: @Composable (Host) -> Unit = { host ->
                    HostRow(
                        host,
                        subtitle = host.rowSubtitle(byId),
                        now = now,
                        onTap = { onConnect(host) },
                        onFiles = onFiles?.let { open -> { open(host) } },
                        onTunnels = onTunnels?.let { open -> { open(host) } },
                        onEdit = { onEditHost(host.id) },
                        onDelete = { vm.deleteHost(host.id) },
                    )
                }
                if (recent.isNotEmpty()) {
                    item { SectionLabel("Recent", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp)) }
                    items(recent, key = { "recent-" + it.id }) { host -> row(host) }
                    item { SectionLabel("All", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp)) }
                }
                items(all, key = { it.id }) { host -> row(host) }
            }
        }
    }

    if (quickConnect) {
        QuickConnectSheet(vm = vm, onDismiss = { quickConnect = false }, onConnected = { quickConnect = false })
    }
    if (importConfig) {
        ImportHostsSheet(vm = vm, onDismiss = { importConfig = false })
    }
}

/**
 * A host row's second line (spec C9): `ben@10.0.0.15`, the port when it is not 22, then
 * `· via bastion` for a host that logs in through a chain, the hops in order as `bastion › edge`.
 * A hop whose host has been deleted is named as such, since the login will stop there.
 */
internal fun Host.rowSubtitle(byId: Map<String, Host>): String = buildString {
    append(userAtHost)
    if (port != 22) append(':').append(port)
    if (jumpHostIds.isNotEmpty()) append(" \u00B7 via ").append(jumpHostIds.joinToString(" \u203A ") { byId[it]?.name ?: "a deleted host" })
}

@Composable
private fun HostRow(
    host: Host,
    subtitle: String,
    now: Long,
    onTap: () -> Unit,
    onFiles: (() -> Unit)?,
    onTunnels: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Berth.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = host.name,
            subtitle = subtitle,
            onClick = onTap,
            onLongClick = { menu = true },
            leading = { Swatch(host.color, host.monogram, 36.dp) },
            trailing = {
                if (host.lastConnectedAt != null) Text(ageText(host.lastConnectedAt, now), style = BerthType.caption, color = c.text3)
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
            if (onFiles != null) DropdownMenuItem(text = { Text("Files", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onFiles() })
            if (onTunnels != null) DropdownMenuItem(text = { Text("Connect as tunnel only", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onTunnels() })
            DropdownMenuItem(text = { Text("Edit", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("Delete", style = BerthType.body, color = c.danger) }, onClick = { menu = false; onDelete() })
        }
    }
}

/**
 * Quick connect (spec C11): a `user@host:port` field in Mono, the identity to log in with, Connect;
 * the login opens as an unsaved host. What stops a spec is said under the field in the parser's
 * words, the same ones a link's notice uses. With [initialSpec] the sheet is where a plain `ssh://`
 * link no saved host answers to lands (spec, Deep links): the field holds the link's address and
 * the title says why the sheet is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectSheet(vm: AppViewModel, onDismiss: () -> Unit, onConnected: () -> Unit, initialSpec: String? = null) {
    val c = Berth.colors
    val identities by vm.identities.collectAsState()
    var spec by remember { mutableStateOf(initialSpec ?: "") }
    var identityId by remember { mutableStateOf<String?>(null) }
    var pickIdentity by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun connect() {
        if (spec.isBlank()) return
        val problem = vm.quickConnect(spec, identityId)
        if (problem == null) onConnected() else error = problem
    }
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Quick connect", if (initialSpec != null) "No saved host matches the link." else null)
            BerthField(
                value = spec,
                onValueChange = { spec = it; error = null },
                placeholder = "user@host:port",
                mono = true,
                isError = error != null,
                helper = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { connect() }),
            )
            Box {
                PickerRow(
                    title = "Identity",
                    value = identities.firstOrNull { it.id == identityId }?.name ?: "Ask on connect",
                    onClick = { pickIdentity = true },
                )
                DropdownMenu(expanded = pickIdentity, onDismissRequest = { pickIdentity = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                    DropdownMenuItem(text = { Text("Ask on connect", style = BerthType.body, color = c.text1) }, onClick = { identityId = null; pickIdentity = false })
                    for (identity in identities) {
                        DropdownMenuItem(text = { Text(identity.name, style = BerthType.body, color = c.text1) }, onClick = { identityId = identity.id; pickIdentity = false })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Connect", onClick = ::connect, kind = ButtonKind.PRIMARY, enabled = spec.isNotBlank())
            }
        }
    }
}
