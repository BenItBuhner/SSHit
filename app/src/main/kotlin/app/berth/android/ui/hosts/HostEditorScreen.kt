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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.android.ui.tunnels.TunnelsPanelContent
import app.berth.domain.model.AddressFamily
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TmuxMode
import java.util.UUID

/** Add or edit a host. Panels stacked with 12 dp gaps; Save waits for Address and User. */
@Composable
fun HostEditorScreen(
    vm: AppViewModel,
    hostId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val identities by vm.identities.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    var loaded by remember { mutableStateOf(hostId == null) }
    var original by remember { mutableStateOf<Host?>(null) }

    var name by remember { mutableStateOf("") }
    var monogramEdited by remember { mutableStateOf(false) }
    var monogram by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(SwatchColor.COPPER) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf<AuthMethod>(AuthMethod.AskEachTime) }
    var password by remember { mutableStateOf("") }
    var keepalive by remember { mutableStateOf(15) }
    var reconnectMinutes by remember { mutableStateOf(15) }
    var tmux by remember { mutableStateOf(TmuxMode.OFF) }
    var tmuxPrefix by remember { mutableStateOf("C-b") }
    var themeId by remember { mutableStateOf<String?>(null) }
    var fontSize by remember { mutableStateOf<Int?>(null) }
    var startupCommand by remember { mutableStateOf("") }
    var terminalType by remember { mutableStateOf("xterm-256color") }
    var compression by remember { mutableStateOf(false) }
    var addressFamily by remember { mutableStateOf(AddressFamily.AUTO) }
    var colorPicker by remember { mutableStateOf(false) }

    LaunchedEffect(hostId) {
        if (hostId != null) {
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
                keepalive = h.persistence.keepaliveSeconds
                reconnectMinutes = h.persistence.reconnectMinutes
                tmux = h.persistence.tmux
                tmuxPrefix = h.persistence.tmuxPrefix
                themeId = h.appearance.terminalThemeId
                fontSize = h.appearance.fontSizeSp
                startupCommand = h.startupCommand ?: ""
                terminalType = h.terminalType
                compression = h.compression
                addressFamily = h.addressFamily
            }
        }
        loaded = true
    }
    if (!loaded) return

    val portValue = port.toIntOrNull()
    val portError = port.isNotBlank() && (portValue == null || portValue !in 1..65535)
    val canSave = address.isNotBlank() && user.isNotBlank() && !portError

    fun save() {
        val base = original
        val finalName = name.ifBlank { address }
        val host = Host(
            id = base?.id ?: UUID.randomUUID().toString(),
            name = finalName,
            color = color,
            monogram = (if (monogramEdited) monogram else Host.monogramFor(finalName)).ifBlank { Host.monogramFor(finalName) },
            address = address.trim(),
            port = portValue ?: 22,
            user = user.trim(),
            auth = auth,
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
            appearance = (base?.appearance ?: app.berth.domain.model.AppearanceOverride()).copy(terminalThemeId = themeId, fontSizeSp = fontSize),
            tags = base?.tags ?: emptyList(),
            lastConnectedAt = base?.lastConnectedAt,
            createdAt = base?.createdAt ?: System.currentTimeMillis(),
        )
        vm.saveHost(host, password.takeIf { it.isNotEmpty() })
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
                BerthButton("Save", onClick = ::save, kind = ButtonKind.TEXT, enabled = canSave)
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box {
                    Swatch(color, (if (monogramEdited) monogram else Host.monogramFor(name.ifBlank { address })).ifBlank { "??" }, 56.dp, modifier = Modifier.clickable { colorPicker = !colorPicker })
                }
                BerthField(name, { name = it }, label = "Name", placeholder = address.ifBlank { "prod-web" }, modifier = Modifier.weight(1f))
            }
            if (colorPicker) {
                Panel(label = "Colour and monogram") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (swatch in SwatchColor.entries.take(6)) SwatchOption(swatch, color == swatch) { color = swatch }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            Panel(label = "Identity") {
                var pick by remember { mutableStateOf(false) }
                val label = when (val a = auth) {
                    is AuthMethod.Key -> identities.firstOrNull { it.id == a.identityId }?.name ?: "Missing key"
                    is AuthMethod.Password -> "Password"
                    AuthMethod.AskEachTime -> "Ask each time"
                }
                Box {
                    PickerRow("Key", label, onClick = { pick = true })
                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                        DropdownMenuItem(text = { Text("Ask each time", style = BerthType.body, color = c.text1) }, onClick = { auth = AuthMethod.AskEachTime; pick = false })
                        DropdownMenuItem(text = { Text("Password", style = BerthType.body, color = c.text1) }, onClick = { auth = AuthMethod.Password((auth as? AuthMethod.Password)?.secretId); pick = false })
                        for (identity in identities) {
                            DropdownMenuItem(text = { Text(identity.name, style = BerthType.body, color = c.text1) }, onClick = { auth = AuthMethod.Key(identity.id); pick = false })
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
                    Text("No keys yet. Create one under Keys in the rail.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
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

            original?.let { saved ->
                Panel(label = "Tunnels") {
                    TunnelsPanelContent(vm, saved)
                }
            }

            Panel(label = "Look") {
                CyclePicker("Theme", listOf<String?>(null) + themes.map { it.id }, themeId, { id -> id?.let { i -> themes.firstOrNull { it.id == i }?.name } ?: "Inherit" }) { themeId = it }
                CyclePicker("Font size", listOf<Int?>(null) + (9..24).toList(), fontSize, { it?.let { s -> "$s sp" } ?: "Inherit" }) { fontSize = it }
            }

            Panel(label = "Advanced") {
                BerthField(startupCommand, { startupCommand = it }, label = "Startup command", placeholder = "none", mono = true)
                BerthField(terminalType, { terminalType = it }, label = "Terminal type", mono = true)
                ToggleRow("Compression", compression, { compression = it })
                Text("Address family", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
                SegmentedControl(listOf("Auto", "IPv4", "IPv6"), addressFamily.ordinal, { addressFamily = AddressFamily.entries[it] })
            }

            if (original != null) {
                Spacer(Modifier.height(8.dp))
                BerthButton("Delete host", onClick = { vm.deleteHost(original!!.id); onDone() }, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SwatchOption(swatch: SwatchColor, selected: Boolean, onClick: () -> Unit) {
    val c = Berth.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(BerthRadius.swatch))
            .background(if (selected) c.surface4 else c.surface2)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(swatch.rgb.toColor()),
        )
    }
}

/** A picker row that shows the current value and opens the choices as a menu. */
@Composable
fun <T> CyclePicker(title: String, options: List<T>, value: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = Berth.colors
    var open by remember { mutableStateOf(false) }
    Box {
        PickerRow(title, label(value), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
            for (option in options) {
                DropdownMenuItem(text = { Text(label(option), style = BerthType.body, color = if (option == value) c.accent else c.text1) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

fun TmuxMode.label(): String = when (this) {
    TmuxMode.OFF -> "Off"
    TmuxMode.ATTACH_OR_CREATE -> "Attach or create"
    TmuxMode.ATTACH_ONLY -> "Attach only"
}
