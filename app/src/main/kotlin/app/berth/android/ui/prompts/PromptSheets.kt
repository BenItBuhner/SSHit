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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import app.berth.android.session.LinkFingerprint
import app.berth.android.session.Prompt
import app.berth.android.session.PromptCenter
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.HoldButton
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import app.berth.ssh.FingerprintCheck
import app.berth.ssh.Randomart
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

/**
 * The one bottom sheet every prompt uses: surface.1, the handle, 20 dp margins, 12 dp between rows;
 * the OSC 52 notice (not a transport prompt) and the crash report borrow it too. The column scrolls:
 * the sheet itself does not scroll its content, and at the interface's font cap (A11) the changed-key
 * sheet with a link's row, or a report with its box, stands taller than a phone's window, where an
 * unscrolled column would lay its last rows, the answers, out at no height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Berth.colors
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
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
 * address, so the endpoint stands alone. Name and endpoint share the line while both fit it whole;
 * when they do not, as at the interface's font cap on a phone (A11), the name takes the line and
 * the endpoint the one under it, so the name the user knows the host by is not what gives way.
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
            NameAndEndpoint(
                name = { Text(host.name, style = BerthType.body, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                separator = { Text(" \u00B7 ", style = BerthType.body, color = c.text3) },
                endpoint = { Text(endpoint, style = BerthType.monoBody, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        } else {
            Text(endpoint, style = BerthType.monoBody, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * [name], [separator] and [endpoint] on one line when the three fit it whole, each centred on the
 * line and the endpoint whole first, as a row would have them; when the name's own width does not
 * fit what the endpoint leaves, two lines, the name on the first and the endpoint under it, with
 * no separator. Either way a name wider than a whole line is the one thing cut.
 */
@Composable
private fun NameAndEndpoint(name: @Composable () -> Unit, separator: @Composable () -> Unit, endpoint: @Composable () -> Unit) {
    Layout(content = { name(); separator(); endpoint() }) { measurables, constraints ->
        val (nameM, separatorM, endpointM) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val separatorP = separatorM.measure(loose)
        val endpointP = endpointM.measure(loose)
        val room = constraints.maxWidth - separatorP.width - endpointP.width
        if (nameM.maxIntrinsicWidth(constraints.maxHeight) <= room) {
            val nameP = nameM.measure(loose.copy(maxWidth = room))
            val height = maxOf(nameP.height, separatorP.height, endpointP.height)
            layout(nameP.width + separatorP.width + endpointP.width, height) {
                nameP.placeRelative(0, (height - nameP.height) / 2)
                separatorP.placeRelative(nameP.width, (height - separatorP.height) / 2)
                endpointP.placeRelative(nameP.width + separatorP.width, (height - endpointP.height) / 2)
            }
        } else {
            val nameP = nameM.measure(loose)
            layout(maxOf(nameP.width, endpointP.width), nameP.height + endpointP.height) {
                nameP.placeRelative(0, 0)
                endpointP.placeRelative(0, nameP.height)
            }
        }
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

/**
 * First contact: fingerprint in mono groups, one primary action, cancel. A hop's sheet says so,
 * and where the chain is going. When the login was opened by a link that carried a fingerprint
 * ([Prompt.TrustHostKey.link]), the sheet says how the two compare: a match is one line under the
 * fingerprint; a mismatch leads the sheet in danger, said once in the caption, with the offered
 * key and the link's fingerprint as two labelled rows of one size (C13's `SAVED` / `OFFERED`
 * treatment, never a box beside a row) and no primary action, the way the changed-key sheet has none.
 * Under the fingerprint, C13's two ways of checking it that need no one else: the key's randomart
 * behind `Show visual fingerprint`, to hold next to what the server prints at login, and under
 * COMPARE ON THE SERVER the `ssh-keygen -lf` command that prints this key type's fingerprint
 * there, with a copy.
 */
@Composable
private fun TrustHostKeySheet(p: Prompt.TrustHostKey) {
    val c = Berth.colors
    val link = p.link
    val mismatched = link?.takeIf { it.check == FingerprintCheck.MISMATCH }
    val mismatch = mismatched != null
    val art = remember(p.request.fingerprintSha256) { Randomart.of(p.request.publicKey) }
    PromptSheet(onDismiss = p::cancel) {
        if (mismatch) {
            SheetTitle(
                "Key does not match the link",
                "The link that opened this connection carried a different fingerprint for this ${if (p.via != null) "jump host" else "server"}.",
                color = c.danger,
            )
        } else {
            SheetTitle(
                "First connection",
                p.via?.let { "Berth has not seen this jump host before. ${it.sentence}" } ?: "Berth has not seen this server before.",
            )
        }
        HostLine(p)
        if (mismatched != null) {
            Fingerprint(p.request.keyType, p.request.fingerprintSha256, label = "Offered")
            LinkFingerprintRow(mismatched)
            VisualFingerprint(art, label = "offered")
        } else {
            Fingerprint(p.request.keyType, p.request.fingerprintSha256, boxed = true)
            VisualFingerprint(art)
            if (link != null) {
                when (link.check) {
                    FingerprintCheck.MATCH -> Text("The link that opened this connection carried the same fingerprint as this key's.", style = BerthType.body, color = c.text2)
                    FingerprintCheck.UNREADABLE -> LinkUnreadableLine(link)
                    FingerprintCheck.MISMATCH -> Unit
                }
            }
        }
        if (p.otherKnown.isNotEmpty()) {
            Text(
                "A ${p.otherKnown.joinToString(", ") { it.keyType }} key is already saved for this server. Offering a different key type is normal when a server adds algorithms.",
                style = BerthType.caption,
                color = c.text2,
            )
        }
        CopyableLine("Compare on the server", SshKeys.serverFingerprintCommand(p.request.keyType))
        if (mismatch) {
            Text("Either the link or the server is not what it says it is. Do not trust this key on the link's word; check the fingerprint with the server's administrator.", style = BerthType.body, color = c.text2)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BerthButton("Cancel", onClick = p::cancel, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                BerthButton("Trust and connect anyway", onClick = p::trust, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text("Compare it with the fingerprint the server's administrator gave you. Trusting saves the key on this device.", style = BerthType.body, color = c.text2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Trust and connect", onClick = p::trust, kind = ButtonKind.PRIMARY)
                BerthButton("Cancel", onClick = p::cancel, kind = ButtonKind.TEXT)
            }
        }
    }
}

/**
 * The fingerprint the link that opened the connection carried, as a labelled row beside the key's
 * and of the same size ([Fingerprint]'s row: `LINK   SHA256`, or `LINK   MD5` for a link written
 * that way, over the value in Mono grouped as the key rows are). A link names no key type, so the
 * row claims none. Shown for a readable fingerprint that matched neither key on the sheet; the
 * value is [LinkFingerprint.shown], the normalized form, never the link's own text.
 */
@Composable
private fun LinkFingerprintRow(link: LinkFingerprint) {
    val shown = link.shown
    val (hash, value) = when {
        shown.startsWith("SHA256:") -> "SHA256" to SshKeys.groupedFingerprint(shown)
        shown.startsWith("MD5:") -> "MD5" to shown.removePrefix("MD5:")
        else -> "" to link.quoted
    }
    FingerprintRow(fingerprintCaption(label = "Link", hash), value)
}

/**
 * A link's fingerprint in no form Berth reads: said so, with the value itself quoted as
 * [LinkFingerprint.quoted] (short, no control or format characters), since it came in from
 * outside and this is the one place the link's text is shown. In body, as the match line is:
 * the lesser event is not the smaller text, and the quoted value is not the smallest on the sheet.
 */
@Composable
private fun LinkUnreadableLine(link: LinkFingerprint) {
    Text(
        "The link that opened this connection carried a fingerprint Berth cannot read (\u201C${link.quoted}\u201D), so there is nothing to compare here.",
        style = BerthType.body,
        color = Berth.colors.text2,
    )
}

/**
 * The one alarming sheet in the app: the saved key changed. Danger colour on the title, no primary.
 * A link's fingerprint is compared with both keys ([LinkFingerprint.check] with the offered,
 * [LinkFingerprint.savedCheck] with the saved): the offered key's, the saved key's (what a link
 * written before a rotation carries, said in text.2 like the rest), or neither, which is the one
 * that alarms, with the link's fingerprint as a third row so the three can be compared by eye.
 */
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
        // Two fingerprints as labelled text rows (C13), never two boxes; the link's, when it is neither, a third.
        Fingerprint(p.saved.keyType, p.saved.fingerprintSha256, label = "Saved")
        Fingerprint(p.request.keyType, p.request.fingerprintSha256, label = "Offered")
        p.link?.let { link ->
            when {
                link.check == FingerprintCheck.UNREADABLE -> LinkUnreadableLine(link)
                link.check == FingerprintCheck.MATCH -> Text("The link that opened this connection carried the offered key's fingerprint.", style = BerthType.body, color = c.text2)
                link.matchesSaved -> Text("The link that opened this connection carried the saved key's fingerprint.", style = BerthType.body, color = c.text2)
                else -> {
                    LinkFingerprintRow(link)
                    Text(
                        if (link.savedCheck == null) "The link that opened this connection carried a fingerprint that is not the offered key's."
                        else "The link that opened this connection carried a fingerprint that is neither key's.",
                        style = BerthType.body,
                        color = c.danger,
                    )
                }
            }
        }
        Text(
            "This happens when a server is reinstalled or its keys rotate. It also happens when something is between you and the server. Do not continue unless you know which.",
            style = BerthType.body,
            color = c.text2,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BerthButton("Disconnect", onClick = { p.decide(HostKeyChangedDecision.DISCONNECT) }, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            BerthButton("Connect once without saving", onClick = { p.decide(HostKeyChangedDecision.TRUST_ONCE) }, kind = ButtonKind.TEXT, modifier = Modifier.fillMaxWidth())
            // C13's `Replace saved key — hold to confirm`: the one answer that rewrites what Berth trusts is held, not tapped.
            HoldButton("Replace saved key \u2014 hold to confirm", onConfirm = { p.decide(HostKeyChangedDecision.REPLACE_SAVED) }, modifier = Modifier.fillMaxWidth())
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
 * stacked fingerprints stay as plain rows, every one the same size ([FingerprintRow]).
 */
@Composable
fun Fingerprint(keyType: String, fingerprint: String, modifier: Modifier = Modifier, label: String? = null, boxed: Boolean = false) {
    FingerprintRow(fingerprintCaption(label, "$keyType \u00B7 SHA256"), SshKeys.groupedFingerprint(fingerprint), modifier, boxed)
}

/** `LABEL   ssh-ed25519 · SHA256`: the label in text.2 when there is one, then what the value is. */
@Composable
private fun fingerprintCaption(label: String?, what: String): AnnotatedString {
    val c = Berth.colors
    return buildAnnotatedString {
        if (label != null) {
            withStyle(SpanStyle(color = c.text2)) { append(label.uppercase()) }
            append("   ")
        }
        append(what)
    }
}

/**
 * The one row every fingerprint on a sheet is set in: the caption over the value in Mono at 14 sp.
 * One node to a reader: the caption and the value merge, so a row is heard as `OFFERED ssh-ed25519 ·
 * SHA256` and then its key in one item rather than a label and, a swipe later, a value with no name.
 * Since the `LINK` row is set here as well, it reads the same way.
 */
@Composable
private fun FingerprintRow(caption: AnnotatedString, value: String, modifier: Modifier = Modifier, boxed: Boolean = false) {
    val c = Berth.colors
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .then(
                if (boxed) Modifier.clip(RoundedCornerShape(BerthRadius.row)).background(c.surface2).padding(12.dp)
                else Modifier.padding(horizontal = 4.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(caption, style = BerthType.caption, color = c.text3)
        Text(value, style = BerthType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.text1)
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
