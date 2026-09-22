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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
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
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Identity
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.RecreateNotice
import app.berth.ssh.SshKeys
import kotlinx.coroutines.launch

/**
 * Identities (spec C12): name, algorithm, fingerprint and protection. A tap opens the key's detail
 * sheet (fingerprint, randomart, the `ssh-keygen -lf` line, Show QR and Install on host); a
 * long-press opens the row's menu in the spec's order, with Save public key beside Share. Change
 * protection re-encodes a software key under a new passphrase or none; a hardware-backed key's is
 * fixed at generation, and its sheet offers a new hardware key instead. [onOpenTab] puts a tab on
 * the Stage, for the install sheet's way to the shell it typed into.
 */
@Composable
fun KeysScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, onOpenTab: (String) -> Unit = {}) {
    val c = Berth.colors
    val identities by vm.identities.collectAsState()
    var generate by remember { mutableStateOf(false) }
    var generatePrefill by remember { mutableStateOf<NewKeyPrefill?>(null) }
    var importKey by remember { mutableStateOf(false) }
    var headerMenu by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<Pair<Identity, List<String>>?>(null) }
    var detail by remember { mutableStateOf<Identity?>(null) }
    var qr by remember { mutableStateOf<Identity?>(null) }
    var install by remember { mutableStateOf<Identity?>(null) }
    var rename by remember { mutableStateOf<Identity?>(null) }
    var protect by remember { mutableStateOf<Identity?>(null) }
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
                BerthMenu(expanded = headerMenu, onDismiss = { headerMenu = false }) {
                    BerthMenuItem("Import key", onClick = { headerMenu = false; importKey = true })
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
                            onClick = { detail = identity },
                            onLongClick = { menu = true },
                        )
                        BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                            BerthMenuItem("Copy public key", onClick = { menu = false; clipboard.setText(AnnotatedString(identity.publicKeyOpenSsh)) })
                            BerthMenuItem("Share public key", onClick = { menu = false; sharePublicKey(context, identity) })
                            BerthMenuItem("Save public key\u2026", onClick = { menu = false; savePublicKey(identity) })
                            BerthMenuItem("Show QR", onClick = { menu = false; qr = identity })
                            BerthMenuItem("Install on host", onClick = { menu = false; install = identity })
                            BerthMenuItem("Rename", onClick = { menu = false; rename = identity })
                            BerthMenuItem("Change protection", onClick = { menu = false; protect = identity })
                            BerthMenuItem(
                                "Delete",
                                destructive = true,
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

    if (generate) GenerateKeySheet(vm, onDismiss = { generate = false; generatePrefill = null }, prefill = generatePrefill)
    if (importKey) ImportKeySheet(vm, onDismiss = { importKey = false })
    rename?.let { identity -> RenameKeySheet(vm, identity, onDismiss = { rename = null }) }
    protect?.let { identity ->
        ChangeProtectionSheet(
            vm,
            identity,
            onDismiss = { protect = null },
            onNewHardwareKey = {
                protect = null
                generatePrefill = NewKeyPrefill(name = "", algorithm = KeyAlgorithm.ECDSA_P256, hardware = true)
                generate = true
            },
        )
    }
    detail?.let { identity ->
        KeyDetailSheet(
            vm,
            identity,
            onDismiss = { detail = null },
            onShowQr = { detail = null; qr = identity },
            onInstall = { detail = null; install = identity },
        )
    }
    qr?.let { identity -> PublicKeyQrSheet(identity, onDismiss = { qr = null }) }
    install?.let { identity -> InstallKeySheet(vm, identity, onDismiss = { install = null }, onOpenTab = { install = null; onOpenTab(it) }) }
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

/**
 * What the New key sheet opens set to when another surface asks for a key it can name: the
 * bundle import's Make a key (spec C20), for a hardware-backed key that stayed on the phone the
 * bundle came from, opens on that key's [name] and its type, [hardware] on this phone too, since
 * that is the key it stands in for. Everything else on the sheet is the user's to choose.
 */
data class NewKeyPrefill(val name: String, val algorithm: KeyAlgorithm, val hardware: Boolean) {
    /** The bundle import's recreate notice as a prefill: the key's name, hardware-backed as the one it replaces was. */
    constructor(notice: RecreateNotice) : this(notice.identityName, notice.algorithm, hardware = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateKeySheet(vm: AppViewModel, onDismiss: () -> Unit, prefill: NewKeyPrefill? = null) {
    val c = Berth.colors
    var kind by remember {
        mutableStateOf(
            when {
                prefill == null -> KeyKind.ED25519
                prefill.hardware -> KeyKind.HARDWARE
                prefill.algorithm == KeyAlgorithm.RSA_4096 -> KeyKind.RSA
                else -> KeyKind.ED25519
            },
        )
    }
    var name by remember { mutableStateOf(prefill?.name ?: "") }
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
                .verticalScroll(rememberScrollState())
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
