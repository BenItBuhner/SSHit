package app.berth.android.ui.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.security.KeyUnlocker
import app.berth.android.session.HostKeyChangedDecision
import app.berth.android.session.Prompt
import app.berth.android.session.PromptCenter
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.domain.model.Host
import app.berth.ssh.SshKeys

/**
 * Shows whatever the transport is waiting on; one sheet at a time, from any screen.
 * [onOpenKnownHosts] is the refused-pinned-key sheet's way out to the key that did the refusing.
 */
@Composable
fun PromptHost(prompts: PromptCenter, onOpenKnownHosts: () -> Unit = {}) {
    val current by prompts.current.collectAsState()
    when (val p = current) {
        null -> Unit
        is Prompt.TrustHostKey -> TrustHostKeySheet(p)
        is Prompt.HostKeyChanged -> HostKeyChangedSheet(p)
        is Prompt.PinnedKeyRefused -> PinnedKeyRefusedSheet(p, onOpenKnownHosts)
        is Prompt.Password -> SecretSheet(
            title = "Password for ${p.host.userAtHost}",
            caption = p.instruction ?: p.serverPrompt,
            fieldLabel = p.serverPrompt?.trimEnd(':', ' ') ?: "Password",
            onSubmit = { p.submit(it.toCharArray()) },
            onCancel = p::cancel,
            host = p,
        )
        is Prompt.Passphrase -> SecretSheet(
            title = "Unlock ${p.identityName}",
            caption = "The key's passphrase is not stored; it is asked for on each connection.",
            fieldLabel = "Passphrase",
            onSubmit = { p.submit(it.toCharArray()) },
            onCancel = p::cancel,
            host = p,
        )
        is Prompt.UnlockKey -> UnlockKeySheet(p)
        is Prompt.KeyInvalidated -> KeyInvalidatedSheet(p)
    }
}

/** The one bottom sheet every prompt uses: surface.1, the handle, 20 dp margins, 12 dp between rows; the OSC 52 notice (not a transport prompt) borrows it too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Berth.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BerthSheet(onDismiss = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun HostLine(prompt: Prompt) = HostLine(prompt.host)

/**
 * The host a sheet is about: its swatch, its saved name in text.1 and `user@address:port` in Mono
 * text.2 (`[BA] bastion · demo@127.0.0.1:2223`), so two hosts at one endpoint, a bastion and the
 * target behind it, read apart by more than a monogram. A quick-connect host is named by its
 * address, so the endpoint stands alone. The endpoint is whole first; a long name gives way.
 */
@Composable
internal fun HostLine(host: Host) {
    val c = Berth.colors
    val endpoint = host.userAtHost + if (host.port != 22) ":${host.port}" else ""
    val named = host.name.isNotBlank() && host.name != host.address
    Row(verticalAlignment = Alignment.CenterVertically) {
        Swatch(host.color, host.monogram, 24.dp)
        Spacer(Modifier.width(10.dp))
        if (named) {
            Text(host.name, style = BerthType.body, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(" \u00B7 ", style = BerthType.body, color = c.text3)
        }
        Text(endpoint, style = BerthType.body.copy(fontFamily = JetBrainsMono), color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The connect flow paused on the key: the system prompt is up over this sheet, which names the key
 * and the server and offers the one way out. Nothing to type here; the answer is the fingerprint.
 */
@Composable
private fun UnlockKeySheet(p: Prompt.UnlockKey) {
    val c = Berth.colors
    PromptSheet(onDismiss = p::cancel) {
        SheetTitle(KeyUnlocker.promptTitle(p.host), KeyUnlocker.promptSubtitle(p.identityName))
        HostLine(p)
        Text("Confirm with your fingerprint, face or screen lock when the system asks. Cancelling leaves ${p.host.name} unconnected.", style = BerthType.body, color = c.text2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            BerthButton("Cancel", onClick = p::cancel, kind = ButtonKind.TEXT)
        }
    }
}

/** Android destroyed the key after a biometric change; the fix is a new pair, and the server has to learn it. */
@Composable
private fun KeyInvalidatedSheet(p: Prompt.KeyInvalidated) {
    val c = Berth.colors
    PromptSheet(onDismiss = p::cancel) {
        SheetTitle("Key no longer usable", "${KeyUnlocker.keyName(p.identity)} cannot sign any more.", color = c.danger)
        HostLine(p)
        Fingerprint(p.identity.algorithm.sshName, p.identity.fingerprintSha256, label = "Key")
        Text(
            "A key that needs your fingerprint or face is tied to the biometrics enrolled when it was made. Those changed, so Android destroyed it. A new key pair can take its place under the same name; ${p.host.name} needs its new public key before the next connection.",
            style = BerthType.body,
            color = c.text2,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BerthButton("Regenerate key", onClick = p::regenerate, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
            BerthButton("Not now", onClick = p::cancel, kind = ButtonKind.TEXT, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** First contact: fingerprint in mono groups, one primary action, cancel. A hop's sheet says so, and where the chain is going. */
@Composable
private fun TrustHostKeySheet(p: Prompt.TrustHostKey) {
    val c = Berth.colors
    PromptSheet(onDismiss = p::cancel) {
        SheetTitle(
            "First connection",
            p.via?.let { "Berth has not seen this jump host before. ${it.sentence}" } ?: "Berth has not seen this server before.",
        )
        HostLine(p)
        Fingerprint(p.request.keyType, p.request.fingerprintSha256, boxed = true)
        if (p.otherKnown.isNotEmpty()) {
            Text(
                "A ${p.otherKnown.joinToString(", ") { it.keyType }} key is already saved for this server. Offering a different key type is normal when a server adds algorithms.",
                style = BerthType.caption,
                color = c.text2,
            )
        }
        Text("Compare it with the fingerprint the server's administrator gave you. Trusting saves the key on this device.", style = BerthType.body, color = c.text2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Trust and connect", onClick = p::trust, kind = ButtonKind.PRIMARY)
            BerthButton("Cancel", onClick = p::cancel, kind = ButtonKind.TEXT)
        }
    }
}

/** The one alarming sheet in the app: the saved key changed. Danger colour on the title, no primary. */
@Composable
private fun HostKeyChangedSheet(p: Prompt.HostKeyChanged) {
    val c = Berth.colors
    PromptSheet(onDismiss = { p.decide(HostKeyChangedDecision.DISCONNECT) }) {
        SheetTitle(
            "Host key changed",
            "This ${if (p.via != null) "jump host's" else "server's"} key does not match the one saved on ${formatDate(p.saved.firstSeenAt)}." +
                (p.via?.let { " ${it.sentence}" } ?: ""),
            color = c.danger,
        )
        HostLine(p)
        // Two fingerprints as labelled text rows (C13), never two boxes.
        Fingerprint(p.saved.keyType, p.saved.fingerprintSha256, label = "Saved")
        Fingerprint(p.request.keyType, p.request.fingerprintSha256, label = "Offered")
        Text(
            "This happens when a server is reinstalled or its keys rotate. It also happens when something is between you and the server. Do not continue unless you know which.",
            style = BerthType.body,
            color = c.text2,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BerthButton("Disconnect", onClick = { p.decide(HostKeyChangedDecision.DISCONNECT) }, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            BerthButton("Connect once without saving", onClick = { p.decide(HostKeyChangedDecision.TRUST_ONCE) }, kind = ButtonKind.TEXT, modifier = Modifier.fillMaxWidth())
            BerthButton("Replace the saved key", onClick = { p.decide(HostKeyChangedDecision.REPLACE_SAVED) }, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * No decision here: a pinned key rules the offered one out, so the connection was refused. The two
 * fingerprints are plain labelled rows (C13); the way out is the Known hosts screen, where the pin is.
 */
@Composable
private fun PinnedKeyRefusedSheet(p: Prompt.PinnedKeyRefused, onOpenKnownHosts: () -> Unit) {
    val c = Berth.colors
    PromptSheet(onDismiss = p::acknowledge) {
        SheetTitle(
            "Connection refused",
            "This ${if (p.via != null) "jump host" else "server"} has a pinned key and offered a different one." + (p.via?.let { " ${it.sentence}" } ?: ""),
            color = c.danger,
        )
        HostLine(p)
        Fingerprint(p.pinned.keyType, p.pinned.fingerprintSha256, label = "Pinned")
        Fingerprint(p.request.keyType, p.request.fingerprintSha256, label = "Offered")
        Text(
            "A pinned key is never replaced. Unpin or forget it under Known hosts to accept a new one.",
            style = BerthType.body,
            color = c.text2,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            BerthButton("Open Known hosts", onClick = { p.acknowledge(); onOpenKnownHosts() }, kind = ButtonKind.TEXT)
            BerthButton("OK", onClick = p::acknowledge, kind = ButtonKind.PRIMARY)
        }
    }
}

/**
 * A host key fingerprint as text rows: a Caption naming the algorithm and the hash
 * (`ssh-ed25519 · SHA256`, with an optional leading label such as `PINNED`), then the base64 body
 * grouped in fours in Mono. [boxed] puts the one focal fingerprint of the trust sheet on surface.2;
 * stacked fingerprints stay as plain rows.
 */
@Composable
fun Fingerprint(keyType: String, fingerprint: String, modifier: Modifier = Modifier, label: String? = null, boxed: Boolean = false) {
    val c = Berth.colors
    val caption = buildAnnotatedString {
        if (label != null) {
            withStyle(SpanStyle(color = c.text2)) { append(label.uppercase()) }
            append("   ")
        }
        append("$keyType \u00B7 SHA256")
    }
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (boxed) Modifier.clip(RoundedCornerShape(BerthRadius.row)).background(c.surface2).padding(12.dp)
                else Modifier.padding(horizontal = 4.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(caption, style = BerthType.caption, color = c.text3)
        Text(SshKeys.groupedFingerprint(fingerprint), style = BerthType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.text1)
    }
}

@Composable
private fun SecretSheet(
    title: String,
    caption: String?,
    fieldLabel: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    host: Prompt,
) {
    var value by remember { mutableStateOf("") }
    PromptSheet(onDismiss = onCancel) {
        SheetTitle(title, caption)
        HostLine(host)
        BerthField(
            value = value,
            onValueChange = { value = it },
            label = fieldLabel,
            password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit(value) }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Connect", onClick = { onSubmit(value) }, kind = ButtonKind.PRIMARY)
            BerthButton("Cancel", onClick = onCancel, kind = ButtonKind.TEXT)
        }
        Spacer(Modifier.height(4.dp))
    }
}

fun formatDate(epochMillis: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMillis))
