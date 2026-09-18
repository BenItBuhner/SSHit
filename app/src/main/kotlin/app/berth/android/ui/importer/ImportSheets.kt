package app.berth.android.ui.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.ssh.SshConfigHost
import app.berth.ssh.SshKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 1 shl 20

/** Reads a picked document as text; configs and keys are small, so anything over 1 MB is refused. */
suspend fun readDocument(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_IMPORT_BYTES) null else bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()
}

/** One config alias as the import sheet sees it, with everything resolved against the library. */
class ConfigCandidate(
    val entry: SshConfigHost,
    /** Identity whose name matches an `IdentityFile` basename, when one does. */
    val identity: Identity?,
    /** A saved host with the same name or the same user@address:port. */
    val existing: Host?,
) {
    val alias get() = entry.alias
    val target get() = "${entry.user ?: "root"}@${entry.hostName}" + if (entry.port != 22) ":${entry.port}" else ""
}

fun candidatesFor(entries: List<SshConfigHost>, identities: List<Identity>, hosts: List<Host>): List<ConfigCandidate> = entries.map { entry ->
    val basenames = entry.identityFiles.map { it.substringAfterLast('/').substringAfterLast('\\') }
    val identity = identities.firstOrNull { id -> basenames.any { it.equals(id.name, ignoreCase = true) || it.equals(id.name.replace(' ', '_'), ignoreCase = true) } }
    val user = entry.user ?: "root"
    val existing = hosts.firstOrNull { it.name.equals(entry.alias, ignoreCase = true) } ?: hosts.firstOrNull { it.address.equals(entry.hostName, ignoreCase = true) && it.port == entry.port && it.user == user }
    ConfigCandidate(entry, identity, existing)
}

/**
 * Hosts from a pasted or picked `~/.ssh/config`. Every concrete alias becomes a row; rows that
 * match a saved host start unticked. `ProxyJump` hops are linked when the hop is another alias in
 * the same import or a saved host; otherwise the sheet says so and imports the host without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportHostsSheet(vm: AppViewModel, onDismiss: () -> Unit, onImported: (Int) -> Unit = {}) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identities by vm.identities.collectAsState()
    val hosts by vm.hosts.collectAsState()
    var text by remember { mutableStateOf("") }
    var fileNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = readDocument(context, uri)
            if (read == null) fileNote = "Couldn't read that file." else { text = read; fileNote = null }
        }
    }

    val parsed = remember(text) { if (text.isBlank()) null else vm.parseSshConfig(text) }
    val candidates = remember(parsed, identities, hosts) { candidatesFor(parsed?.hosts.orEmpty(), identities, hosts) }
    // Ticked aliases; hosts already saved start unticked. Editing the text starts the choice over.
    var selection by remember(candidates) { mutableStateOf(candidates.filter { it.existing == null }.map { it.alias }.toSet()) }
    val picked = candidates.filter { it.alias in selection }
    val aliasesHere = candidates.map { it.alias }.toSet()
    val unresolvedJumps = picked.flatMap { cand -> cand.entry.proxyJump.filterNot { hop -> hop in aliasesHere || hosts.any { it.name.equals(hop, true) || it.address.equals(hop.substringAfter('@').substringBefore(':'), true) } } }.distinct()

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Import hosts", "Paste your ~/.ssh/config, or choose the file")
            BerthField(
                text,
                { text = it },
                placeholder = "Host prod\n  HostName 203.0.113.10\n  User deploy\n  IdentityFile ~/.ssh/id_ed25519",
                mono = true,
                singleLine = false,
                minLines = 4,
                modifier = Modifier.heightIn(max = 220.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                helper = fileNote,
                isError = fileNote != null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton("Choose file", onClick = { picker.launch(arrayOf("*/*")) })
                if (text.isNotEmpty()) BerthButton("Clear", kind = ButtonKind.TEXT, onClick = { text = "" })
            }

            if (parsed != null) {
                if (candidates.isEmpty()) {
                    Text("No concrete Host entries found. Wildcard blocks like `Host *` supply defaults but are not hosts themselves.", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    SectionLabel("${candidates.size} " + if (candidates.size == 1) "host" else "hosts", Modifier.padding(start = 4.dp))
                    for (cand in candidates) {
                        val selected = cand.alias in selection
                        ListRow(
                            title = cand.alias,
                            subtitle = cand.target,
                            subtitleStyle = BerthType.mono.copy(fontSize = BerthType.caption.fontSize),
                            selected = selected,
                            minHeight = 52.dp,
                            onClick = { selection = if (selected) selection - cand.alias else selection + cand.alias },
                            trailing = {
                                if (cand.existing != null) Pill("saved")
                                if (cand.entry.proxyJump.isNotEmpty()) Pill("via " + cand.entry.proxyJump.joinToString(", "), mono = true)
                                if (cand.identity != null) Pill(cand.identity.name) else if (cand.entry.identityFiles.isNotEmpty()) Pill("no key match", textColor = c.text3)
                                if (cand.entry.forwards.isNotEmpty()) Pill("${cand.entry.forwards.size} fwd", mono = true)
                            },
                        )
                    }
                    for (hop in unresolvedJumps) {
                        Text("ProxyJump $hop is not a host here; the jump is left off. Add it and set the chain in the host editor.", style = BerthType.caption, color = c.attention, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    if (candidates.any { it.identity == null && it.entry.identityFiles.isNotEmpty() }) {
                        Text("Hosts whose IdentityFile does not match a key here will ask on connect. Import the key under Keys, named after the file, and pick it in the host editor.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                for (note in parsed.notes) {
                    Text(note, style = BerthType.caption, color = c.text3, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(
                    when {
                        busy -> "Importing\u2026"
                        picked.size == 1 -> "Import 1 host"
                        else -> "Import ${picked.size} hosts"
                    },
                    kind = ButtonKind.PRIMARY,
                    enabled = picked.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val count = importCandidates(vm, picked, hosts)
                            busy = false
                            onImported(count)
                            onDismiss()
                        }
                    },
                )
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

/** Saves [picked] as hosts, linking ProxyJump hops to hosts in the same batch or already saved. */
suspend fun importCandidates(vm: AppViewModel, picked: List<ConfigCandidate>, saved: List<Host>): Int {
    val fresh = picked.associate { cand -> cand.alias to vm.hostFromConfig(cand.entry, cand.identity?.id, emptyList()) }
    fun resolve(hop: String): String? {
        fresh[hop]?.let { return it.id }
        val address = hop.substringAfter('@').substringBefore(':')
        return saved.firstOrNull { it.name.equals(hop, true) }?.id ?: saved.firstOrNull { it.address.equals(address, true) }?.id
    }
    val linked = picked.map { cand ->
        val host = fresh.getValue(cand.alias)
        host.copy(jumpHostIds = cand.entry.proxyJump.mapNotNull(::resolve)) to cand.entry
    }
    return vm.importHosts(linked)
}

/**
 * A private key from pasted text or a picked file. Encrypted keys ask for their passphrase and keep
 * it: the stored copy stays protected and the passphrase is asked for on each connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportKeySheet(vm: AppViewModel, onDismiss: () -> Unit, onImported: (Identity) -> Unit = {}) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var needsPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = readDocument(context, uri)
            if (read == null) error = "Couldn't read that file." else {
                text = read
                error = null
                if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.removeSuffix(".pem")?.removeSuffix(".key").orEmpty()
            }
        }
    }
    val looksLikeKey = remember(text) { SshKeys.looksLikePrivateKey(text) }
    val encrypted = remember(text) { looksLikeKey && runCatching { SshKeys.isEncrypted(text.trim()) }.getOrDefault(false) }
    val askPassphrase = encrypted || needsPassphrase
    val canImport = looksLikeKey && !busy && (!askPassphrase || passphrase.isNotEmpty())

    fun import() {
        busy = true
        error = null
        scope.launch {
            val result = vm.importIdentity(name.trim(), text, passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
            busy = false
            when (result) {
                is AppViewModel.KeyImportResult.Done -> { onImported(result.identity); onDismiss() }
                AppViewModel.KeyImportResult.PassphraseNeeded -> { needsPassphrase = true; error = if (passphrase.isEmpty()) null else "That passphrase didn't unlock the key." }
                is AppViewModel.KeyImportResult.Failed -> error = result.reason
            }
        }
    }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Import key", "OpenSSH, PEM, PKCS#8 or PuTTY private key")
            BerthField(
                text,
                { text = it; error = null; needsPassphrase = false },
                placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                mono = true,
                singleLine = false,
                minLines = 4,
                modifier = Modifier.heightIn(max = 200.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                helper = if (text.isNotBlank() && !looksLikeKey) "This doesn't look like a private key yet." else null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton("Choose file", onClick = { picker.launch(arrayOf("*/*")) })
                if (text.isNotEmpty()) BerthButton("Clear", kind = ButtonKind.TEXT, onClick = { text = ""; passphrase = ""; error = null; needsPassphrase = false })
            }
            BerthField(name, { name = it }, label = "Name", placeholder = "From the key's comment when left empty")
            if (askPassphrase) {
                BerthField(
                    passphrase,
                    { passphrase = it; error = null },
                    label = "Passphrase",
                    password = true,
                    helper = "This key is protected. It stays protected here and is asked for on each connection.",
                )
            }
            if (error != null) Text(error!!, style = BerthType.caption, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp))
            Text("The key is re-encoded as OpenSSH and encrypted at rest with a Keystore-wrapped key. Nothing is uploaded anywhere.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(horizontal = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(if (busy) "Importing\u2026" else "Import", kind = ButtonKind.PRIMARY, enabled = canImport, onClick = ::import)
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

// ---- public key export -----------------------------------------------------------------------------

/** Opens the system share sheet with the identity's one-line OpenSSH public key. */
fun sharePublicKey(context: Context, identity: Identity) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "${identity.name}.pub")
        .putExtra(Intent.EXTRA_TEXT, identity.publicKeyOpenSsh)
    val chooser = Intent.createChooser(send, "Share public key").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/** Returns an action that asks where to save `<name>.pub` and writes the public key there. */
@Composable
fun rememberPublicKeySaver(): (Identity) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Identity?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val identity = pending
        pending = null
        if (uri != null && identity != null) scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write((identity.publicKeyOpenSsh + "\n").toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    return { identity ->
        pending = identity
        launcher.launch(identity.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "id" } + ".pub")
    }
}