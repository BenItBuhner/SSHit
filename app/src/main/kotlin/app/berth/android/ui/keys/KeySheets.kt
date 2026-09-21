package app.berth.android.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.qr.QrCode
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.QrImage
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.prompts.CopyableLine
import app.berth.android.ui.prompts.Fingerprint
import app.berth.android.ui.prompts.HostLine
import app.berth.android.ui.prompts.VisualFingerprint
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.SessionState
import app.berth.domain.model.TmuxMode
import app.berth.ssh.Randomart
import app.berth.ssh.SshKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.security.PublicKey

/**
 * The sheet every key sheet is: surface.1, the handle, 20 dp margins, 12 dp between rows, and a
 * column that scrolls, since the QR sheet stands taller than a phone on its side and the detail
 * sheet with its randomart shown does at the interface's font cap (A11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BerthSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

/** The key behind an identity's public line, or null for a line that does not parse (nothing in the app writes one). */
@Composable
private fun rememberPublicKey(identity: Identity): PublicKey? =
    remember(identity.publicKeyOpenSsh) { runCatching { SshKeys.parseOpenSshPublic(identity.publicKeyOpenSsh) }.getOrNull() }

/**
 * A key's detail (spec C12, C13): a tap on its row. What the key is in the caption, its fingerprint
 * as the focal row, the visual fingerprint behind the spec's reveal, and the line `ssh-keygen -lf`
 * prints for it with a copy, so it can be matched against a server's `authorized_keys` or a
 * teammate's list by the tool's own output. The two actions this slice adds sit under it; the rest
 * of the row's menu (copy, share, save, delete) stays the menu's.
 */
@Composable
fun KeyDetailSheet(vm: AppViewModel, identity: Identity, onDismiss: () -> Unit, onShowQr: () -> Unit, onInstall: () -> Unit) {
    val c = Berth.colors
    val key = rememberPublicKey(identity)
    var users by remember { mutableStateOf<List<Host>?>(null) }
    LaunchedEffect(identity.id) { users = vm.hostsUsing(identity.id) }
    KeySheet(onDismiss) {
        SheetTitle(identity.name, keyCaption(identity, users))
        Fingerprint(identity.algorithm.sshName, identity.fingerprintSha256, boxed = true)
        if (key != null) {
            VisualFingerprint(Randomart.of(key))
            CopyableLine("As ssh-keygen prints it", SshKeys.keygenLine(key, identity.comment))
        }
        Text(
            "The private key stays on this phone. What leaves it is the public line: copy or share it from the row's menu, show it as a QR code, or install it on a host over a session.",
            style = BerthType.caption,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Show QR", onClick = onShowQr, kind = ButtonKind.SECONDARY)
            BerthButton("Install on host", onClick = onInstall, kind = ButtonKind.PRIMARY)
        }
    }
}

/** `Ed25519 · passphrase · used by 2 hosts`: the row's facts in one line, with how many hosts log in with the key once that is known. */
internal fun keyCaption(identity: Identity, users: List<Host>?): String {
    val parts = ArrayList<String>()
    parts += identity.algorithm.displayName
    if (identity.isHardwareBacked) parts += "hardware-backed"
    parts += when (identity.protection) {
        KeyProtection.BIOMETRIC -> "biometric"
        KeyProtection.PASSPHRASE -> "passphrase"
        KeyProtection.NONE -> "no passphrase"
    }
    if (users != null) {
        parts += when (users.size) {
            0 -> "used by no host yet"
            1 -> "used by ${users[0].name}"
            else -> "used by ${users.size} hosts"
        }
    }
    return parts.joinToString(" \u00B7 ")
}

/**
 * Show QR (spec C12): the public key line as a QR code on paper-light ground, sized for a camera
 * across a desk, with the line itself under it and a copy. The private key is not in it and the
 * sheet says so, since a QR of a key is the one thing here a person might mistake for an export.
 */
@Composable
fun PublicKeyQrSheet(identity: Identity, onDismiss: () -> Unit) {
    val c = Berth.colors
    val line = identity.publicKeyOpenSsh.trim()
    val code = remember(line) { QrCode.encodeFitting(line) }
    KeySheet(onDismiss) {
        SheetTitle("Public key as QR", "${identity.name} \u00B7 scan it into another device's authorized_keys or key list. The private key is not in it.")
        if (code == null) {
            Text("This key's public line is too long for a QR code; copy it instead.", style = BerthType.body, color = c.text2)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrImage(
                    code,
                    contentDescription = "QR code of ${identity.name}'s public key",
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(BerthRadius.row)),
                )
            }
        }
        CopyableLine("Public key", line, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Done", onClick = onDismiss, kind = ButtonKind.SECONDARY)
        }
    }
}

/** Where the install stands: picking a host, connecting to one, the command typed and waiting, its answer, or the tab that could not be typed into. */
private sealed interface InstallPhase {
    data object Picking : InstallPhase
    data class Connecting(val host: Host) : InstallPhase
    data class Running(val host: Host, val session: TerminalSession) : InstallPhase
    data class Answered(val host: Host, val session: TerminalSession, val outcome: KeyInstall.Outcome?) : InstallPhase
    data class ProgramRunning(val host: Host, val session: TerminalSession) : InstallPhase
    data class NotConnected(val host: Host, val reason: String, val session: TerminalSession?) : InstallPhase
}

/** A shell to type into: a Live terminal session on the host, whether saved or quick-connected, never a Tunnels tab. */
private fun List<TerminalSession>.shellOn(hostId: String): TerminalSession? =
    firstOrNull { it.state == SessionState.LIVE && !it.tunnelsOnly && it.host.id == hostId }

/**
 * Install on host (spec C12): pick a connected or saved host, see the command, run it through the
 * host's session and read the result. A host with a Live shell is under CONNECTED and the command
 * goes straight into it, as a snippet would; a saved host without one is under SAVED, and the
 * button connects first, with the login's prompts (its key, a password) coming up over this sheet
 * as they would over any screen. A connected tab that is in a program rather than at a shell
 * ([KeyInstall.runningProgram]) says so on its row and is not typed into: its button opens the
 * tab, where the program can be quit, or the command is pasted from the copy button. The answer
 * is what the shell printed, read off the screen; when it prints neither answer in twenty seconds
 * the sheet says so and offers the tab, where whatever is waiting there can be seen.
 */
@Composable
fun InstallKeySheet(vm: AppViewModel, identity: Identity, onDismiss: () -> Unit, onOpenTab: (String) -> Unit) {
    val c = Berth.colors
    val hosts by vm.hosts.collectAsState()
    val sessions by vm.sessions.sessions.collectAsState()
    var picked by remember { mutableStateOf<Host?>(null) }
    var phase by remember { mutableStateOf<InstallPhase>(InstallPhase.Picking) }
    val command = remember(identity.publicKeyOpenSsh) { KeyInstall.command(identity.publicKeyOpenSsh) }
    val preview = remember(identity.publicKeyOpenSsh) { KeyInstall.command(shortKeyLine(identity.publicKeyOpenSsh)) }

    // Live shells first, the quick-connected among them, then every saved host without one; a tunnels-only host has no shell to type into.
    val connected = sessions.filter { it.state == SessionState.LIVE && !it.tunnelsOnly }.map { it.host }.distinctBy { it.id }
    val saved = hosts.filter { h -> !h.tunnelsOnly && connected.none { it.id == h.id } }

    // The install itself, off the composition: connect when there is no shell, then type and wait for the answer.
    LaunchedEffect(phase) {
        when (val p = phase) {
            is InstallPhase.Connecting -> {
                val session = runCatching { vm.sessions.connect(p.host) }.getOrElse {
                    phase = InstallPhase.NotConnected(p.host, it.message ?: "The connection could not be opened.", null)
                    return@LaunchedEffect
                }
                val settled = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    session.record.first { it.state == SessionState.LIVE || it.state == SessionState.FAILED || it.state == SessionState.CLOSED || it.state == SessionState.DETACHED }.state
                }
                phase = when (settled) {
                    SessionState.LIVE -> {
                        // The shell's first prompt, so the command lands on it rather than in the login's banner.
                        delay(SHELL_SETTLE_MS)
                        InstallPhase.Running(p.host, session)
                    }
                    SessionState.FAILED -> InstallPhase.NotConnected(p.host, session.failure.value?.plain ?: "The login failed.", session)
                    null -> InstallPhase.NotConnected(p.host, "Still connecting after ${CONNECT_TIMEOUT_MS / 1000} s.", session)
                    else -> InstallPhase.NotConnected(p.host, "The login did not complete.", session)
                }
            }
            is InstallPhase.Running -> phase = when (val result = KeyInstall.run(p.session, command)) {
                is KeyInstall.Result.Answered -> InstallPhase.Answered(p.host, p.session, result.outcome)
                KeyInstall.Result.ProgramRunning -> InstallPhase.ProgramRunning(p.host, p.session)
            }
            else -> Unit
        }
    }

    KeySheet(onDismiss) {
        when (val p = phase) {
            InstallPhase.Picking -> {
                SheetTitle("Install on host", "Adds ${identity.name}'s public key to ~/.ssh/authorized_keys on the host you pick, typed into its shell.")
                if (connected.isEmpty() && saved.isEmpty()) {
                    Text("No hosts yet. Save a host under Hosts, or connect to one, and it is offered here.", style = BerthType.body, color = c.text2)
                }
                if (connected.isNotEmpty()) {
                    SectionLabel("Connected", Modifier.padding(start = 4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (host in connected) {
                            val program = sessions.shellOn(host.id)?.let { runningProgram(it) } == true
                            HostPickRow(host, live = true, program = program, selected = picked?.id == host.id) { picked = host }
                        }
                    }
                }
                if (saved.isNotEmpty()) {
                    SectionLabel("Saved", Modifier.padding(start = 4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (host in saved) HostPickRow(host, live = false, program = false, selected = picked?.id == host.id) { picked = host }
                    }
                }
                CopyableLine("Runs in the shell", preview, copyText = command)
                val target = picked
                val shell = target?.let { sessions.shellOn(it.id) }
                val program = shell?.let { runningProgram(it) } == true
                if (program) {
                    Text(
                        "${target?.name}'s tab is in a program, not at a shell, so the command is not typed there. Quit what is running there, or paste the command from the copy button.",
                        style = BerthType.caption,
                        color = c.text2,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton(
                        when {
                            target == null || (shell != null && !program) -> "Install"
                            program -> "Open tab"
                            else -> "Connect and install"
                        },
                        kind = ButtonKind.PRIMARY,
                        enabled = target != null,
                        onClick = {
                            val host = target ?: return@BerthButton
                            when {
                                shell == null -> phase = InstallPhase.Connecting(host)
                                program -> onOpenTab(shell.id)
                                else -> phase = InstallPhase.Running(host, shell)
                            }
                        },
                    )
                    BerthButton("Cancel", onClick = onDismiss, kind = ButtonKind.TEXT)
                }
            }
            is InstallPhase.Connecting -> {
                SheetTitle("Connecting", "The key is installed once ${p.host.name} is up. A prompt for its host key or your password comes up over this sheet.")
                HostLine(p.host)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // The connection is a tab of its own and goes on without the sheet; only the waiting stops here.
                    BerthButton("Stop waiting", onClick = onDismiss, kind = ButtonKind.TEXT)
                }
            }
            is InstallPhase.Running -> {
                SheetTitle("Installing", "Typed into ${p.host.name}'s shell; waiting for its answer.")
                HostLine(p.host)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // The command is typed and cannot be taken back; closing the sheet only stops the wait for its answer.
                    BerthButton("Stop waiting", onClick = onDismiss, kind = ButtonKind.TEXT)
                }
            }
            is InstallPhase.ProgramRunning -> {
                SheetTitle("Running a program", "${p.host.name}'s tab is in a program, not at a shell, so nothing was typed. Quit what is running there, or paste the command from the copy button.", color = c.danger)
                HostLine(p.host)
                CopyableLine("Runs in the shell", preview, copyText = command)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Open tab", onClick = { onOpenTab(p.session.id) }, kind = ButtonKind.PRIMARY)
                    BerthButton("Close", onClick = onDismiss, kind = ButtonKind.TEXT)
                }
            }
            is InstallPhase.Answered -> {
                when (p.outcome) {
                    KeyInstall.Outcome.INSTALLED -> {
                        SheetTitle("Installed", "${identity.name} is in ~/.ssh/authorized_keys on ${p.host.name}. Logging in there as ${p.host.user} with this key works from the next connection.")
                        HostLine(p.host)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BerthButton("Done", onClick = onDismiss, kind = ButtonKind.PRIMARY)
                            BerthButton("Open tab", onClick = { onOpenTab(p.session.id) }, kind = ButtonKind.TEXT)
                        }
                    }
                    KeyInstall.Outcome.NOT_INSTALLED -> {
                        SheetTitle("Not installed", "${p.host.name}'s shell could not write ~/.ssh/authorized_keys. Its output says why; the tab has it.", color = c.danger)
                        HostLine(p.host)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BerthButton("Open tab", onClick = { onOpenTab(p.session.id) }, kind = ButtonKind.PRIMARY)
                            BerthButton("Close", onClick = onDismiss, kind = ButtonKind.TEXT)
                        }
                    }
                    null -> {
                        val seconds = KeyInstall.ANSWER_TIMEOUT_MS / 1000
                        SheetTitle(
                            "No answer",
                            if (p.host.persistence.tmux == TmuxMode.OFF) {
                                "${p.host.name}'s shell said nothing in $seconds s; it may be busy or waiting on a prompt. Open the tab; if a program is running there, quit it and paste the command from the copy button."
                            } else {
                                "${p.host.name} said nothing in $seconds s. The command went to the tmux pane that was up; open the tab and look at the pane. If a program is running there, quit it and paste the command from the copy button."
                            },
                        )
                        HostLine(p.host)
                        CopyableLine("Runs in the shell", preview, copyText = command)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BerthButton("Open tab", onClick = { onOpenTab(p.session.id) }, kind = ButtonKind.PRIMARY)
                            BerthButton("Close", onClick = onDismiss, kind = ButtonKind.TEXT)
                        }
                    }
                }
            }
            is InstallPhase.NotConnected -> {
                SheetTitle("Not connected", p.reason, color = c.danger)
                HostLine(p.host)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.session != null) BerthButton("Open tab", onClick = { onOpenTab(p.session.id) }, kind = ButtonKind.PRIMARY)
                    BerthButton("Close", onClick = onDismiss, kind = ButtonKind.TEXT)
                }
            }
        }
    }
}

/**
 * A host to install on: its swatch, name and `user@address:port`, the live dot when a shell is up,
 * the selected tonal step when picked. A live tab that is in a [program] says so on the line under
 * its address, in the caption face, the way the import sheet's rows carry their second line: the
 * address keeps its own line whole rather than breaking inside an octet to make room, and the
 * trailing slot keeps the dot, since the shell is up. That tab is offered to open, not to type into.
 */
@Composable
private fun HostPickRow(host: Host, live: Boolean, program: Boolean, selected: Boolean, onClick: () -> Unit) {
    val endpoint = host.userAtHost + if (host.port != 22) ":${host.port}" else ""
    val subtitle = if (!program) endpoint else buildAnnotatedString {
        append(endpoint)
        append("\n")
        withStyle(SpanStyle(fontFamily = BerthType.caption.fontFamily, fontWeight = BerthType.caption.fontWeight, letterSpacing = BerthType.caption.letterSpacing)) {
            append("running a program")
        }
    }
    ListRow(
        title = host.name,
        subtitle = subtitle,
        subtitleStyle = BerthType.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
        subtitleMaxLines = if (program) 2 else 1,
        selected = selected,
        onClick = onClick,
        leading = { Swatch(host.color, host.monogram, 32.dp) },
        trailing = { if (live) StatusDot(SessionState.LIVE) },
    )
}

/** Whether [session]'s tab is in a program rather than at a shell, read again on every change of its screen, so a quit program frees the row while the sheet is up. */
@Composable
private fun runningProgram(session: TerminalSession): Boolean {
    val version by session.screenVersion.collectAsState()
    return remember(session, version) { KeyInstall.runningProgram(session) }
}

/** The key line with the middle of its base64 body elided (`ssh-ed25519 AAAAC3NzaC1l…dKb4 ben@pixel`), for the preview's width; what is copied and typed is the whole line. */
internal fun shortKeyLine(publicKeyLine: String): String {
    val parts = publicKeyLine.trim().split(Regex("\\s+"), limit = 3)
    if (parts.size < 2 || parts[1].length <= 22) return publicKeyLine.trim()
    val body = parts[1].take(12) + "\u2026" + parts[1].takeLast(6)
    return (listOf(parts[0], body) + parts.drop(2)).joinToString(" ")
}

/** How long a login may take, prompts included, before the sheet stops waiting and offers the tab. */
private const val CONNECT_TIMEOUT_MS = 90_000L

/** After Live, the moment the shell needs to print its first prompt; typed before it, the command would still run, under the banner. */
private const val SHELL_SETTLE_MS = 800L
