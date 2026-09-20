package app.berth.android.ui.keys

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
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
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.importer.ImportKeySheet
import app.berth.android.ui.importer.rememberPublicKeySaver
import app.berth.android.ui.importer.sharePublicKey
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Identity
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.ssh.SshKeys
import kotlinx.coroutines.launch

/** Identities: name, algorithm, fingerprint and protection; long-press copies or deletes. */
@Composable
fun KeysScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val identities by vm.identities.collectAsState()
    var generate by remember { mutableStateOf(false) }
    var importKey by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<Pair<Identity, List<String>>?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val savePublicKey = rememberPublicKeySaver()

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(title = "Keys", onBack = onBack, actions = {
            IconAction(onClick = { generate = true }, description = "New key") { BerthIcon(BerthIcons.add) }
            Box {
                IconAction(onClick = { headerMenu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                    DropdownMenuItem(text = { Text("Import key", style = BerthType.body, color = c.text1) }, onClick = { headerMenu = false; importKey = true })
                }
            }
        })
        if (identities.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState(
                title = "No keys yet.",
                body = "Keys are generated or imported on this device, stored encrypted under a Keystore-wrapped key, and never leave it unencrypted. Hardware-backed keys never leave the secure hardware at all.",
            ) {
                BerthButton("New key", onClick = { generate = true }, kind = ButtonKind.PRIMARY)
                BerthButton("Import key", onClick = { importKey = true }, kind = ButtonKind.TEXT)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(identities, key = { it.id }) { identity ->
                    var menu by remember { mutableStateOf(false) }
                    val model = remember(identity.id, identity.protection) { vm.keyAuthModel(identity) }
                    Box {
                        ListRow(
                            title = identity.name,
                            subtitle = identity.summary(model),
                            subtitleStyle = BerthType.caption,
                            subtitleMaxLines = 2,
                            onClick = { menu = true },
                            onLongClick = { menu = true },
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                            DropdownMenuItem(
                                text = { Text("Copy public key", style = BerthType.body, color = c.text1) },
                                onClick = { menu = false; clipboard.setText(AnnotatedString(identity.publicKeyOpenSsh)) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share public key", style = BerthType.body, color = c.text1) },
                                onClick = { menu = false; sharePublicKey(context, identity) },
                            )
                            DropdownMenuItem(
                                text = { Text("Save public key\u2026", style = BerthType.body, color = c.text1) },
                                onClick = { menu = false; savePublicKey(identity) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", style = BerthType.body, color = c.danger) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val users = vm.hostsUsing(identity.id)
                                        if (users.isEmpty()) vm.deleteIdentity(identity.id) else blocked = identity to users.map { it.name }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (generate) GenerateKeySheet(vm, onDismiss = { generate = false })
    if (importKey) ImportKeySheet(vm, onDismiss = { importKey = false })
    blocked?.let { (identity, names) ->
        InfoSheet(
            title = "${identity.name} is still in use",
            body = "Change these hosts to another key first: ${names.joinToString(", ")}.",
            onDismiss = { blocked = null },
        )
    }
}

/**
 * The row's two lines: what the key is, then how it is protected. A biometric key names its
 * model ([KeyAuthModel]) so the user knows whether every sign-in asks or a window opens; [model]
 * null when the Keystore could not say.
 */
fun Identity.summary(model: KeyAuthModel? = null): String {
    val parts = ArrayList<String>()
    parts += algorithm.displayName
    if (isHardwareBacked) parts += "hardware-backed"
    // `SHA256:Qk3f 8vLm…`, the spec's row anatomy: hash name, then the first two groups.
    parts += "SHA256:" + SshKeys.groupedFingerprint(fingerprintSha256).take(9) + "\u2026"
    val first = parts.joinToString(" \u00B7 ")
    val second = when (protection) {
        KeyProtection.BIOMETRIC -> biometricModelLabel(model)
        KeyProtection.PASSPHRASE -> "Passphrase \u00B7 asked for on each connection"
        KeyProtection.NONE -> null
    }
    return if (second == null) first else "$first\n$second"
}

/** `Biometric · asks each time it signs in`, or the window a timed key opens; plain `Biometric` when the model is unknown. */
fun biometricModelLabel(model: KeyAuthModel?): String = when (model) {
    KeyAuthModel.PER_USE -> "Biometric \u00B7 asks each time it signs in"
    KeyAuthModel.TIMED_WINDOW -> "Biometric \u00B7 unlocked for ${HardwareKeys.AUTH_WINDOW_SECONDS} s after your fingerprint"
    KeyAuthModel.NONE, null -> "Biometric"
}

private enum class KeyKind(val label: String) { ED25519("Ed25519"), HARDWARE("ECDSA P-256, hardware"), RSA("RSA 4096") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateKeySheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = Berth.colors
    var kind by remember { mutableStateOf(KeyKind.ED25519) }
    var name by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var protection by remember { mutableStateOf(0) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val protections = if (kind == KeyKind.HARDWARE) listOf("None", "Biometric") else listOf("None", "Passphrase")
    val defaultName = when (kind) {
        KeyKind.ED25519 -> "id-ed25519"
        KeyKind.HARDWARE -> "phone-hw-p256"
        KeyKind.RSA -> "id-rsa"
    }

    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("New key")
            Text("Type", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp))
            SegmentedControl(KeyKind.entries.map { it.label }, kind.ordinal, { kind = KeyKind.entries[it]; protection = 0 })
            Text(
                when (kind) {
                    KeyKind.ED25519 -> "Ed25519 is the default for modern servers."
                    KeyKind.HARDWARE -> "Hardware-backed keys never leave this phone's secure hardware; they use ECDSA P-256 because Android Keystore does not support Ed25519." +
                        if (vm.strongBoxAvailable) " This device has StrongBox." else ""
                    KeyKind.RSA -> "RSA 4096 for older servers that do not accept Ed25519."
                },
                style = BerthType.caption,
                color = c.text3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            BerthField(name, { name = it }, label = "Name", placeholder = defaultName)
            Text("Protect", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp))
            SegmentedControl(protections, protection, { protection = it })
            if (kind == KeyKind.HARDWARE && protection == 1) {
                // Which model the key gets is decided here, by the Android version, and cannot change later.
                Text(
                    when (vm.newBiometricKeyModel) {
                        KeyAuthModel.TIMED_WINDOW -> "${biometricModelLabel(KeyAuthModel.TIMED_WINDOW)}. Any unlock opens the window; the key is destroyed if new biometrics are enrolled."
                        else -> "${biometricModelLabel(KeyAuthModel.PER_USE)}, a reconnect included; the key is destroyed if new biometrics are enrolled."
                    },
                    style = BerthType.caption,
                    color = c.text3,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (kind != KeyKind.HARDWARE && protection == 1) {
                BerthField(passphrase, { passphrase = it }, label = "Passphrase", password = true, helper = "Asked for on each connection; the key file is also encrypted at rest.")
            }
            BerthField(comment, { comment = it }, label = "Comment", placeholder = "ben@pixel")
            if (error != null) Text(error!!, style = BerthType.caption, color = c.danger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton(
                    if (busy) "Generating\u2026" else "Generate",
                    kind = ButtonKind.PRIMARY,
                    enabled = !busy && (protection == 0 || kind == KeyKind.HARDWARE || passphrase.isNotEmpty()),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val result = vm.generateIdentity(
                                name = name.ifBlank { defaultName },
                                algorithm = when (kind) {
                                    KeyKind.ED25519 -> KeyAlgorithm.ED25519
                                    KeyKind.HARDWARE -> KeyAlgorithm.ECDSA_P256
                                    KeyKind.RSA -> KeyAlgorithm.RSA_4096
                                },
                                hardware = kind == KeyKind.HARDWARE,
                                protection = when {
                                    protection == 0 -> KeyProtection.NONE
                                    kind == KeyKind.HARDWARE -> KeyProtection.BIOMETRIC
                                    else -> KeyProtection.PASSPHRASE
                                },
                                comment = comment.trim(),
                                passphrase = passphrase.takeIf { it.isNotEmpty() && kind != KeyKind.HARDWARE }?.toCharArray(),
                            )
                            busy = false
                            when (result) {
                                is AppViewModel.KeyGenResult.Done -> onDismiss()
                                is AppViewModel.KeyGenResult.Failed -> error = result.reason
                            }
                        }
                    },
                )
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, body: String, onDismiss: () -> Unit) {
    val c = Berth.colors
    BerthSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetTitle(title)
            Text(body, style = BerthType.body, color = c.text2, overflow = TextOverflow.Ellipsis)
            BerthButton("OK", onClick = onDismiss, kind = ButtonKind.SECONDARY)
        }
    }
}
