package app.berth.android.ui.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.AppViewModel.Companion.toTunnel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ColorOption
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.components.spokenName
import app.berth.android.ui.components.TrailingMenuAnchor
import app.berth.android.ui.settings.HostAltKeyPicker
import app.berth.android.ui.settings.HostRemoteClipboardPicker
import app.berth.android.ui.stage.rememberTerminalFontFamilies
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.android.ui.tunnels.PendingTunnelRow
import app.berth.android.ui.tunnels.TunnelsPanelContent
import app.berth.domain.model.AddressFamily
import app.berth.domain.model.AltKeyMode
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.RemoteClipboardPolicy
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TmuxMode
import app.berth.ssh.SshConfigForward
import app.berth.ssh.SshLink
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Add or edit a host. Panels stacked with 12 dp gaps; Save waits for Address and User. Opened for
 * an `ssh://` or `sftp://` [link] no saved host answered to, the fields start from the link (its
 * name, address, port and user, and Tunnels only when it asks for forwards alone). Opened for a
 * saved host with a link that carries forwards the host does not have, the fields are the host's.
 * Either way the link's forwards are pending rows in the Tunnels panel, each with a switch, and
 * nothing is saved or started until Save, which keeps the ones switched on and connects the way the
 * link asked ([AppViewModel.saveHostFromLink]).
 */
@Composable
fun HostEditorScreen(
    vm: AppViewModel,
    hostId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    link: String? = null,
) {
    val c = Berth.colors
    val identities by vm.identities.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    val allTunnels by vm.tunnels.collectAsState()
    val fromLink = remember(link) { link?.let { (SshLink.parse(it) as? SshLink.Result.Parsed)?.link } }
    var loaded by remember { mutableStateOf(hostId == null) }
    var original by remember { mutableStateOf<Host?>(null) }
    // The link's forwards the host does not have yet, and the ones the user switched off; Save keeps the rest.
    val pending = remember(fromLink, hostId, allTunnels) {
        fromLink?.let { AppViewModel.pendingForwards(it, allTunnels.filter { t -> t.hostId == hostId }) } ?: emptyList()
    }
    var leftOut by remember { mutableStateOf(emptySet<SshConfigForward>()) }

    var name by remember { mutableStateOf(fromLink?.name ?: "") }
    var monogramEdited by remember { mutableStateOf(false) }
    var monogram by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(fromLink?.let { SwatchColor.forName(it.name ?: it.host) } ?: SwatchColor.COPPER) }
    var address by remember { mutableStateOf(fromLink?.host ?: "") }
    var port by remember { mutableStateOf(fromLink?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(fromLink?.user ?: "") }
    var auth by remember { mutableStateOf<AuthMethod>(AuthMethod.AskEachTime) }
    var password by remember { mutableStateOf("") }
    var agentForwarding by remember { mutableStateOf(false) }
    var agentSilent by remember { mutableStateOf(false) }
    var keepalive by remember { mutableStateOf(15) }
    var reconnectMinutes by remember { mutableStateOf(15) }
    var tmux by remember { mutableStateOf(TmuxMode.OFF) }
    var tmuxPrefix by remember { mutableStateOf("C-b") }
    var themeId by remember { mutableStateOf<String?>(null) }
    var fontFamily by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableStateOf<Int?>(null) }
    var startupCommand by remember { mutableStateOf("") }
    var terminalType by remember { mutableStateOf("xterm-256color") }
    var compression by remember { mutableStateOf(false) }
    var addressFamily by remember { mutableStateOf(AddressFamily.AUTO) }
    var remoteClipboard by remember { mutableStateOf(RemoteClipboardPolicy.INHERIT) }
    var jumpHostIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var tunnelsOnly by remember { mutableStateOf(fromLink?.tunnelsOnly ?: false) }
    var altKey by remember { mutableStateOf<AltKeyMode?>(null) }
    var tags by remember { mutableStateOf("") }
    var environment by remember { mutableStateOf("") }
    var muteBell by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }

    LaunchedEffect(hostId) {
        if (hostId != null) {
            val security = vm.security.settings.filterNotNull().first()
            remoteClipboard = security.remoteClipboardPolicy(hostId)
            agentSilent = security.signsAgentSilently(hostId)
            altKey = vm.hardwareKeyboard.value.altKeyOverride(hostId)
            vm.host(hostId)?.let { h ->
                original = h
                name = h.name
                monogram = h.monogram
                monogramEdited = h.monogram != Host.monogramFor(h.name)
                color = h.color
                address = h.address
                port = h.port.toString()
                user = h.user
                auth = h.auth
                agentForwarding = h.agentForwarding
                keepalive = h.persistence.keepaliveSeconds
                reconnectMinutes = h.persistence.reconnectMinutes
                tmux = h.persistence.tmux
                tmuxPrefix = h.persistence.tmuxPrefix
                themeId = h.appearance.terminalThemeId
                fontFamily = h.appearance.fontFamily
                fontSize = h.appearance.fontSizeSp
                startupCommand = h.startupCommand ?: ""
                terminalType = h.terminalType
                compression = h.compression
                addressFamily = h.addressFamily
                jumpHostIds = h.jumpHostIds
                tunnelsOnly = h.tunnelsOnly
                tags = HostEditorFields.tagsText(h.tags)
                environment = HostEditorFields.environmentText(h.environment)
                muteBell = h.muteBell
            }
        }
        loaded = true
    }
    if (!loaded) return

    val portValue = port.toIntOrNull()
    val portError = port.isNotBlank() && (portValue == null || portValue !in 1..65535)
    // The link's forwards as the tunnels Save would make, each checked as the tunnel editor checks one: against
    // the saved tunnels and against the other rows kept from the link, so two rows on one port are said here.
    val pendingDrafts = pending.mapIndexed { index, fwd -> fwd.toTunnel(hostId = original?.id ?: "", id = "pending-$index") }
    val keptDrafts = pendingDrafts.filterIndexed { index, _ -> pending[index] !in leftOut }
    fun pendingProblem(index: Int): String? = pendingDrafts[index].validate(allTunnels + keptDrafts)
    // A kept row with a problem holds Save, as the tunnel editor's own Save is held: switching the row off lets the rest through.
    val pendingProblem = pending.indices.any { pending[it] !in leftOut && pendingProblem(it) != null }
    // A line that is not NAME=value holds Save the way a bad port does: said under the field, by its number, not found at login.
    val environmentValue = HostEditorFields.parseEnvironment(environment)
    val environmentProblem = HostEditorFields.environmentProblem(environment)
    val environmentError = environmentProblem != null
    val canSave = address.isNotBlank() && user.isNotBlank() && !portError && !pendingProblem && !environmentError

    fun save() {
        val base = original
        val finalName = name.ifBlank { address }
        // The saved host is the base, so what this screen has no field for (when it was made and last
        // connected) comes through unchanged rather than reset to the default.
        val host = (base ?: Host(id = UUID.randomUUID().toString(), name = finalName, color = color, monogram = "", address = "", user = "", createdAt = System.currentTimeMillis())).copy(
            name = finalName,
            color = color,
            monogram = (if (monogramEdited) monogram else Host.monogramFor(finalName)).ifBlank { Host.monogramFor(finalName) },
            address = address.trim(),
            port = portValue ?: 22,
            user = user.trim(),
            auth = auth,
            agentForwarding = agentForwarding,
            jumpHostIds = jumpHostIds,
            persistence = (base?.persistence ?: app.berth.domain.model.PersistencePolicy()).copy(
                keepaliveSeconds = keepalive,
                reconnectMinutes = reconnectMinutes,
                tmux = tmux,
                tmuxPrefix = tmuxPrefix.ifBlank { "C-b" },
            ),
            startupCommand = startupCommand.ifBlank { null },
            terminalType = terminalType.ifBlank { "xterm-256color" },
            compression = compression,
            addressFamily = addressFamily,
            appearance = (base?.appearance ?: app.berth.domain.model.AppearanceOverride()).copy(terminalThemeId = themeId, fontFamily = fontFamily, fontSizeSp = fontSize),
            tunnelsOnly = tunnelsOnly,
            tags = HostEditorFields.parseTags(tags),
            environment = environmentValue ?: base?.environment ?: emptyMap(),
            muteBell = muteBell,
        )
        val secret = password.takeIf { it.isNotEmpty() }
        if (fromLink != null) vm.saveHostFromLink(host, secret, fromLink, pending.filter { it !in leftOut }) else vm.saveHost(host, secret)
        // The overrides live in the settings documents, keyed by the host's id; they commit here with the rest.
        if (base != null) {
            vm.security.setHostRemoteClipboard(base.id, remoteClipboard)
            vm.updateHardwareKeyboard { it.withHostAltKey(base.id, altKey) }
        }
        // Silent signing is kept only while forwarding is on, so switching forwarding back on later asks again.
        val silent = agentForwarding && agentSilent
        if (base != null || silent) vm.security.setHostAgentSilent(host.id, silent)
        onDone()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(
            title = if (original == null) "New host" else original!!.name,
            onBack = onDone,
            actions = {
                BerthButton(
                    when {
                        fromLink == null -> "Save"
                        fromLink.scheme == SshLink.Scheme.SFTP -> "Save and open files"
                        fromLink.tunnelsOnly -> "Save and open tunnels"
                        else -> "Save and connect"
                    },
                    onClick = ::save,
                    kind = ButtonKind.TEXT,
                    enabled = canSave,
                )
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.panelGap),
        ) {
            if (fromLink != null) {
                Text(
                    when {
                        original == null -> "No saved host matches ${fromLink.target} from the link."
                        pending.size == 1 -> "The link carries a forward ${original!!.name} does not have yet. Nothing is saved or started until Save."
                        else -> "The link carries ${pending.size} forwards ${original!!.name} does not have yet. Nothing is saved or started until Save."
                    },
                    style = BerthType.caption,
                    color = c.text2,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box {
                    Swatch(color, (if (monogramEdited) monogram else Host.monogramFor(name.ifBlank { address })).ifBlank { "??" }, 56.dp, modifier = Modifier.clickable { colorPicker = !colorPicker })
                }
                BerthField(name, { name = it }, label = "Name", placeholder = address.ifBlank { "prod-web" }, modifier = Modifier.weight(1f))
            }
            // Beside the name, not in a panel: tags say what the host is in the library (the chips on Hosts, C9), as the name does.
            BerthField(tags, { tags = it }, label = "Tags", placeholder = "prod, homelab", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            if (colorPicker) {
                Panel(label = "Colour and monogram") {
                    // The options' 48 dp targets set the pitch; the swatches inside them sit 12 dp apart.
                    Row(Modifier.fillMaxWidth()) {
                        for (swatch in SwatchColor.entries.take(6)) SwatchOption(swatch, color == swatch) { color = swatch }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        for (swatch in SwatchColor.entries.drop(6)) SwatchOption(swatch, color == swatch) { color = swatch }
                    }
                    Spacer(Modifier.height(8.dp))
                    BerthField(
                        value = if (monogramEdited) monogram else Host.monogramFor(name.ifBlank { address }),
                        onValueChange = { monogram = it.take(2).uppercase(); monogramEdited = true },
                        label = "Monogram",
                        mono = true,
                    )
                }
            }

            Panel(label = "Connection") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthField(address, { address = it }, label = "Address", placeholder = "10.0.0.12", mono = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    BerthField(port, { port = it.filter(Char::isDigit).take(5) }, label = "Port", mono = true, isError = portError, modifier = Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                BerthField(user, { user = it }, label = "User", placeholder = "ben", mono = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            }

            Panel(label = "Jump hosts") {
                JumpHostsPicker(
                    hosts = hosts,
                    selfId = original?.id,
                    chain = jumpHostIds,
                    onChange = { jumpHostIds = it },
                )
            }

            Panel(label = "Identity") {
                var pick by remember { mutableStateOf(false) }
                val label = when (val a = auth) {
                    is AuthMethod.Key -> identities.firstOrNull { it.id == a.identityId }?.name ?: "Missing key"
                    is AuthMethod.Password -> "Password"
                    AuthMethod.AskEachTime -> "Ask each time"
                }
                Box {
                    PickerRow("Key", label, onClick = { pick = true })
                    TrailingMenuAnchor {
                        BerthMenu(expanded = pick, onDismiss = { pick = false }) {
                            BerthMenuItem("Ask each time", selected = auth is AuthMethod.AskEachTime, onClick = { auth = AuthMethod.AskEachTime; pick = false })
                            BerthMenuItem("Password", selected = auth is AuthMethod.Password, onClick = { auth = AuthMethod.Password((auth as? AuthMethod.Password)?.secretId); pick = false })
                            for (identity in identities) {
                                BerthMenuItem(identity.name, selected = (auth as? AuthMethod.Key)?.identityId == identity.id, onClick = { auth = AuthMethod.Key(identity.id); pick = false })
                            }
                        }
                    }
                }
                if (auth is AuthMethod.Password) {
                    BerthField(
                        password,
                        { password = it },
                        label = "Password",
                        placeholder = if ((auth as AuthMethod.Password).secretId != null) "Saved; enter to replace" else "Leave empty to be asked",
                        password = true,
                        helper = "Stored encrypted with a key in this device's Keystore.",
                    )
                }
                if (identities.isEmpty()) {
                    PanelNote("No keys yet. Create one under Keys in the rail.")
                }
                ToggleRow("Agent forwarding", agentForwarding, { agentForwarding = it }, caption = "Programs on the host may ask to sign")
                if (agentForwarding) {
                    CyclePicker("Signatures", listOf(false, true), agentSilent, { if (it) "Always allow" else "Ask each time" }) { agentSilent = it }
                    val key = identities.firstOrNull { it.id == (auth as? AuthMethod.Key)?.identityId }
                    val note = agentForwardingNote(name.ifBlank { address }, key?.name, auth is AuthMethod.Key, agentSilent, tunnelsOnly)
                    PanelNote(
                        buildAnnotatedString {
                            note.lead?.let { withStyle(SpanStyle(color = c.text1)) { append(it) }; append(" ") }
                            append(note.rest)
                        },
                    )
                }
            }

            Panel(label = "Persistence") {
                CyclePicker("Keepalive", listOf(0, 15, 30, 60), keepalive, { if (it == 0) "Off" else "$it s" }) { keepalive = it }
                CyclePicker("Reconnect", listOf(5, 15, 60, 0), reconnectMinutes, { if (it == 0) "Forever" else "$it min" }) { reconnectMinutes = it }
                CyclePicker("tmux", TmuxMode.entries, tmux, { it.label() }) { tmux = it }
                if (tmux != TmuxMode.OFF) {
                    BerthField(tmuxPrefix, { tmuxPrefix = it }, label = "Prefix", mono = true, helper = "Session name berth-${(name.ifBlank { address }).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}")
                }
            }

            Panel(label = "Tunnels") {
                // One sentence that fits its line beside the switch; the Tunnels tab's own empty state says where Terminal and Files went.
                ToggleRow("Tunnels only", tunnelsOnly, { tunnelsOnly = it }, caption = "Connect opens a Tunnels tab, no shell.")
                // The link's forwards, seen here before anything is saved: the switch is what Save reads.
                val pendingRows: @Composable () -> Unit = {
                    pending.forEachIndexed { index, fwd ->
                        PendingTunnelRow(
                            tunnel = pendingDrafts[index],
                            kept = fwd !in leftOut,
                            onKeptChange = { keep -> leftOut = if (keep) leftOut - fwd else leftOut + fwd },
                            // The same check the tunnel editor runs, so a port a saved tunnel already listens on is said here, not found at login.
                            problem = pendingProblem(index),
                            surface = Color.Transparent,
                        )
                    }
                    if (pending.isNotEmpty()) PanelNote("From the link: Save keeps what is switched on, and nothing starts before then.")
                }
                when {
                    original != null -> TunnelsPanelContent(vm, original!!, pending = pendingRows)
                    pending.isNotEmpty() -> pendingRows()
                    else -> PanelNote("Save the host to add tunnels.")
                }
            }

            // The three fields the Session sheet's Look sheet sets, so a family picked there shows and clears here.
            Panel(label = "Look") {
                CyclePicker("Theme", listOf<String?>(null) + themes.map { it.id }, themeId, { id -> id?.let { i -> themes.firstOrNull { it.id == i }?.name } ?: "Inherit" }) { themeId = it }
                CyclePicker("Font", listOf<String?>(null) + rememberTerminalFontFamilies(), fontFamily, { it ?: "Inherit" }) { fontFamily = it }
                CyclePicker("Font size", listOf<Int?>(null) + (9..24).toList(), fontSize, { it?.let { s -> "$s sp" } ?: "Inherit" }) { fontSize = it }
            }

            Panel(label = "Advanced") {
                BerthField(startupCommand, { startupCommand = it }, label = "Startup command", placeholder = "none", mono = true)
                // Sent with the shell request as SSH env (SshConnection.openShell); the server's AcceptEnv decides what lands.
                BerthField(
                    environment,
                    { environment = it },
                    label = "Environment",
                    placeholder = "LANG=C.UTF-8",
                    mono = true,
                    singleLine = false,
                    minLines = 2,
                    isError = environmentError,
                    helper = environmentProblem?.helper ?: "One NAME=value per line; the server's AcceptEnv decides which arrive.",
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                BerthField(terminalType, { terminalType = it }, label = "Terminal type", mono = true)
                ToggleRow("Compression", compression, { compression = it })
                ToggleRow("Mute bell", muteBell, { muteBell = it }, caption = "No buzz when the shell rings; off stage the tab still lights.")
                Text("Address family", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
                SegmentedControl(listOf("Auto", "IPv4", "IPv6"), addressFamily.ordinal, { addressFamily = AddressFamily.entries[it] })
                HostRemoteClipboardPicker(vm, original?.id, remoteClipboard) { remoteClipboard = it }
                HostAltKeyPicker(vm, original?.id, altKey) { altKey = it }
            }

            if (original != null) {
                Spacer(Modifier.height(8.dp))
                BerthButton("Delete host", onClick = { vm.deleteHost(original!!.id); onDone() }, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The host's swatch colour as a [ColorOption]: named for a screen reader, in a 48 dp target. */
@Composable
private fun SwatchOption(swatch: SwatchColor, selected: Boolean, onClick: () -> Unit) {
    ColorOption(color = swatch.rgb.toColor(), name = swatch.spokenName(), selected = selected, onClick = onClick)
}

/**
 * A picker row that shows the current value and opens the choices as a menu under it, hung from
 * the row's trailing edge on a surface that reads against the panel ([BerthMenu]).
 */
@Composable
fun <T> CyclePicker(title: String, options: List<T>, value: T, label: (T) -> String, caption: String? = null, captionLines: Int = 2, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PickerRow(title, label(value), onClick = { open = true }, caption = caption, captionLines = captionLines)
        TrailingMenuAnchor {
            BerthMenu(expanded = open, onDismiss = { open = false }) {
                for (option in options) {
                    BerthMenuItem(label(option), selected = option == value, onClick = { onSelect(option); open = false })
                }
            }
        }
    }
}

/**
 * The note under Agent forwarding. [lead], when there is one, is the consequence the choice has,
 * set first in the stronger tone; [rest] follows in the note's own.
 */
data class AgentForwardingNote(val lead: String?, val rest: String) {
    val text: String get() = listOfNotNull(lead, rest).joinToString(" ")
}

/**
 * What forwarding [hostName]'s agent means, under the switch: which key it offers, that only
 * signatures leave, and who can ask. The agent holds only the key that logged in, so a host that
 * logs in with a password has nothing to offer, and a Tunnels only host opens no shell to forward to.
 * Always allow leads with who can then sign.
 */
fun agentForwardingNote(hostName: String, keyName: String?, keyAuth: Boolean, silent: Boolean, tunnelsOnly: Boolean): AgentForwardingNote {
    val offered = "Only ${keyPhrase(keyName)} is offered, and only its signatures leave this phone."
    return when {
        tunnelsOnly -> AgentForwardingNote(null, "With Tunnels only on, Connect opens no shell, so no agent is forwarded.")
        !keyAuth -> AgentForwardingNote(null, "Only the key that signs in to this host is offered, and this host signs in without one, so the agent has nothing to offer.")
        silent -> AgentForwardingNote("Anything on ${hostName.ifBlank { "this host" }}, root included, can sign as you without asking while a tab is connected.", offered)
        else -> AgentForwardingNote(null, "$offered Each request asks you first, naming the host and what it is for.")
    }
}

private fun keyPhrase(keyName: String?): String = keyName?.let { "the key \u201c$it\u201d" } ?: "the key that signs in"

fun TmuxMode.label(): String = when (this) {
    TmuxMode.OFF -> "Off"
    TmuxMode.ATTACH_OR_CREATE -> "Attach or create"
    TmuxMode.ATTACH_ONLY -> "Attach only"
}
