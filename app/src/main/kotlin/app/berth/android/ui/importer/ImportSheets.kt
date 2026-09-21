package app.berth.android.ui.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.KnownHostsCandidate
import app.berth.android.ui.KnownHostsImport
import app.berth.android.ui.KnownHostsImported
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.prompts.formatDate
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KnownHostKey
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
    fun hopKnown(hop: String) = hop in aliasesHere || hostForHop(hop, hosts) != null
    val jumpsNew = picked.any { cand -> cand.entry.proxyJump.any { !hopKnown(it) } }
    val keysMissing = picked.any { it.identity == null && it.entry.identityFiles.isNotEmpty() }

    /** Everything about one candidate in a single Caption line; the leading dot carries the tick. */
    fun captionFor(cand: ConfigCandidate): String = buildList {
        add(cand.target)
        if (cand.existing != null) add("saved")
        if (cand.entry.proxyJump.isNotEmpty()) add("via " + cand.entry.proxyJump.joinToString(", ") { hop -> if (hopKnown(hop)) hop else "$hop (new)" })
        val forwards = cand.entry.forwards.size
        if (forwards > 0) add(if (forwards == 1) "1 forward" else "$forwards forwards")
        when {
            cand.identity != null -> add("key ${cand.identity.name}")
            cand.entry.identityFiles.isNotEmpty() -> add("no matching key")
        }
    }.joinToString(" \u00B7 ")

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
                        TickRow(
                            title = cand.alias,
                            subtitle = captionFor(cand),
                            ticked = selected,
                            onTicked = { selection = if (it) selection + cand.alias else selection - cand.alias },
                        )
                    }
                    if (jumpsNew) {
                        Text("Jumps that are not hosts here are saved as hosts of their own, with no key, so the chain stays whole.", style = BerthType.caption, color = c.attention, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    if (keysMissing) {
                        Text("Hosts with no matching key ask on connect.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(horizontal = 4.dp))
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

/**
 * Server keys from a pasted or picked `known_hosts` (spec A16), beside the config import. Every
 * address the file names becomes a row, `host:port · TYPE · SHA256:…`; rows whose key Berth already
 * trusts for that address start unticked and say so. A key that differs from the saved key of its
 * type for an address is the changed-key case (C13) in a file: the row starts unticked with the
 * saved key and its date under it in the danger tint, and ticking it is the Replace decision, which
 * the button then names. An address with a pinned key takes no other key, so such a row is not
 * offered: a lock stands where its tick would, and it says where the pin is undone. A hashed name
 * (`ssh-keygen -H`, the default on many
 * systems) is read for a saved or known host whose address hashes the same and is marked as matched;
 * the ones no host matches are counted in one line, since the name itself is not in the file.
 * Wildcards, negations, CA and revoked lines are skipped and said, each with its line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportKnownHostsSheet(vm: AppViewModel, onDismiss: () -> Unit, onImported: (KnownHostsImported) -> Unit = {}) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var fileNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<KnownHostsImport?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = readDocument(context, uri)
            if (read == null) fileNote = "Couldn't read that file." else { text = read; fileNote = null }
        }
    }
    // The read consults the saved and known hosts for hashed names, so it runs where they are read, not in composition.
    LaunchedEffect(text) { parsed = if (text.isBlank()) null else vm.parseKnownHosts(text) }
    val candidates = parsed?.candidates.orEmpty()
    var selection by remember(parsed) { mutableStateOf(candidates.filter { it.tickedByDefault }.map { it.key }.toSet()) }
    val picked = candidates.filter { it.key in selection }
    val replacing = picked.count { it.conflicting }

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
            SheetTitle("Import known hosts", "Paste your ~/.ssh/known_hosts, or choose the file")
            BerthField(
                text,
                { text = it },
                placeholder = "[10.0.0.12]:2222 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5\u2026",
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

            val read = parsed
            if (read != null) {
                if (candidates.isEmpty()) {
                    Text(
                        if (read.hashedUnresolved > 0) "No key here names a host Berth can hold: the names are hashed, and none hashes to a saved host's address." else "No server keys found.",
                        style = BerthType.caption,
                        color = c.text2,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    SectionLabel("${candidates.size} " + if (candidates.size == 1) "key" else "keys", Modifier.padding(start = 4.dp))
                    for (cand in candidates) {
                        val selected = cand.key in selection
                        val facts = buildList {
                            add(KnownHostKey.algorithmLabelFor(cand.entry.keyType))
                            add(shortFingerprint(cand.entry.fingerprintSha256))
                            if (cand.existing) add("trusted already")
                            if (cand.entry.hashed) add("matched by hash")
                        }.joinToString(" \u00B7 ")
                        when (val standing = cand.standing) {
                            // A pin means no other key for the address: the row is read, not offered, and says where the pin is undone.
                            is KnownHostsCandidate.Standing.Pinned -> ListRow(
                                title = cand.entry.address,
                                subtitle = "$facts\nPinned to ${standing.saved.algorithmLabel} ${shortFingerprint(standing.saved.fingerprintSha256)}; unpin it under Known hosts first.",
                                subtitleMaxLines = 4,
                                minHeight = 52.dp,
                                leading = { PinLock() },
                            )
                            // A different key of a type already trusted: C13's changed-key decision, unticked until it is made.
                            is KnownHostsCandidate.Standing.Conflicting -> TickRow(
                                title = cand.entry.address,
                                subtitle = facts,
                                ticked = selected,
                                onTicked = { selection = if (it) selection + cand.key else selection - cand.key },
                                warning = "Differs from the saved ${standing.saved.algorithmLabel} key ${shortFingerprint(standing.saved.fingerprintSha256)} (trusted ${formatDate(standing.saved.firstSeenAt)}). Ticked, it replaces that key.",
                            )
                            else -> TickRow(
                                title = cand.entry.address,
                                subtitle = facts,
                                ticked = selected,
                                onTicked = { selection = if (it) selection + cand.key else selection - cand.key },
                            )
                        }
                    }
                }
                if (read.hashedUnresolved > 0) {
                    Text(
                        (if (read.hashedUnresolved == 1) "1 hashed name matches no saved host and is left out; " else "${read.hashedUnresolved} hashed names match no saved host and are left out; ") +
                            "save the host first, then import again.",
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                val otherSkips = read.skipped.filter { !it.reason.startsWith("a hashed host name") }
                if (otherSkips.isNotEmpty()) {
                    Text(
                        otherSkips.take(4).joinToString("\n") { "Line ${it.line}: ${it.reason}." } + if (otherSkips.size > 4) "\nAnd ${otherSkips.size - 4} more." else "",
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(
                    if (busy) "Importing\u2026" else importKnownHostsLabel(picked.size, replacing),
                    kind = ButtonKind.PRIMARY,
                    enabled = picked.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = vm.importKnownHosts(picked.map { it.entry })
                            busy = false
                            onImported(result)
                            onDismiss()
                        }
                    },
                )
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

/**
 * The import button's word for [picked] keys, of which [replacing] take a saved key's place:
 * `Import 3 keys`, `Import 2 keys, replace 1`, `Replace 1 key` when that is all it does.
 */
internal fun importKnownHostsLabel(picked: Int, replacing: Int): String {
    fun keys(n: Int) = if (n == 1) "1 key" else "$n keys"
    val adding = picked - replacing
    return when {
        replacing == 0 -> "Import ${keys(picked)}"
        adding == 0 -> "Replace ${keys(replacing)}"
        else -> "Import ${keys(adding)}, replace $replacing"
    }
}

/** `SHA256:` and the first four groups of the hash, enough to tell keys apart on a row. */
private fun shortFingerprint(fingerprint: String): String =
    "SHA256:" + fingerprint.removePrefix("SHA256:").chunked(4).take(4).joinToString(" ") + "\u2026"

/**
 * A candidate row that is ticked or not: a [ListRow] that toggles as a checkbox, so a screen reader
 * hears `checked, git.example.com` rather than a button whose description changes, with the dot
 * as its only mark. A [warning] under the facts, in the danger tint, is what ticking the row would
 * undo: the saved key a conflicting one replaces.
 */
@Composable
private fun TickRow(title: String, subtitle: String, ticked: Boolean, onTicked: (Boolean) -> Unit, warning: String? = null) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    ListRow(
        title = title,
        subtitle = if (warning == null) subtitle else buildAnnotatedString {
            append(subtitle)
            append("\n")
            withStyle(SpanStyle(color = c.danger)) { append(warning) }
        },
        subtitleMaxLines = if (warning == null) 2 else 4,
        minHeight = 52.dp,
        modifier = Modifier.toggleable(value = ticked, role = Role.Checkbox, interactionSource = interaction, indication = null, onValueChange = onTicked),
        interactionSource = interaction,
        leading = { TickDot(ticked) },
    )
}

/**
 * The mark on a pinned endpoint's row, in the tick's place: a lock, in the subtitle's tone, since
 * the row is read and not offered, and an unticked dot there would read as a box that will not
 * tick. Decorative; the row's second line says what the lock means.
 */
@Composable
private fun PinLock() {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        BerthIcon(BerthIcons.lock, tint = Berth.colors.text2, size = 16.dp)
    }
}

/** The tick on a candidate row: an 8 dp dot, accent when the key or host will import, text.3 when it will not. The row's own state says which; the dot is the picture. */
@Composable
private fun TickDot(ticked: Boolean) {
    val c = Berth.colors
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ticked) c.accent else c.text3),
        )
    }
}

/**
 * A `ProxyJump` hop that is not an alias: `[user@]host[:port]`, the host in brackets when it is an
 * IPv6 address. A missing port means 22, as it does for `ssh -J`; a missing user is the caller's.
 */
internal data class HopSpec(val user: String?, val host: String, val port: Int)

internal fun parseHopSpec(hop: String): HopSpec {
    var rest = hop.trim()
    val user = if ('@' in rest) rest.substringBeforeLast('@').takeIf { it.isNotEmpty() } else null
    if ('@' in rest) rest = rest.substringAfterLast('@')
    var host = rest
    var port: Int? = null
    if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        if (end > 0) {
            host = rest.substring(1, end)
            port = rest.substring(end + 1).removePrefix(":").toIntOrNull()
        }
    } else if (rest.count { it == ':' } == 1) {
        host = rest.substringBefore(':')
        port = rest.substringAfter(':').toIntOrNull()
    }
    return HopSpec(user, host, port?.takeIf { it in 1..65535 } ?: 22)
}

/**
 * The saved host a hop names: by name when the hop is an alias, otherwise the one at the spec's
 * address and port, and the spec's user when it gives one. Null when there is no such host.
 */
internal fun hostForHop(hop: String, saved: List<Host>): Host? {
    saved.firstOrNull { it.name.equals(hop, ignoreCase = true) }?.let { return it }
    val spec = parseHopSpec(hop)
    return saved.firstOrNull { it.address.equals(spec.host, ignoreCase = true) && it.port == spec.port && (spec.user == null || it.user == spec.user) }
}

/**
 * Saves [picked] as hosts with their `ProxyJump` chains intact. A hop is linked to the host in the
 * same batch or already saved that it names; a hop no host answers to is saved as a host of its
 * own (tagged `jump`, asking for its key on connect), once per address, port and user, so that a
 * bastion two aliases share becomes one host. Returns how many hosts were saved, hops included.
 */
suspend fun importCandidates(vm: AppViewModel, picked: List<ConfigCandidate>, saved: List<Host>): Int {
    val fresh = picked.associate { cand -> cand.alias to vm.hostFromConfig(cand.entry, cand.identity?.id, emptyList()) }
    val hops = LinkedHashMap<String, Pair<Host, SshConfigHost>>()
    fun resolve(hop: String, targetUser: String): String {
        fresh[hop]?.let { return it.id }
        hostForHop(hop, saved)?.let { return it.id }
        val spec = parseHopSpec(hop)
        val user = spec.user ?: targetUser
        return hops.getOrPut("$user@${spec.host.lowercase()}:${spec.port}") {
            val entry = SshConfigHost(
                alias = spec.host,
                hostName = spec.host,
                user = user,
                port = spec.port,
                identityFiles = emptyList(),
                proxyJump = emptyList(),
                forwards = emptyList(),
                compression = null,
                serverAliveInterval = null,
                addressFamily = null,
                forwardAgent = null,
                remoteCommand = null,
            )
            val host = vm.hostFromConfig(entry, null, emptyList())
            host.copy(tags = host.tags + "jump") to entry
        }.first.id
    }
    val linked = picked.map { cand ->
        val host = fresh.getValue(cand.alias)
        host.copy(jumpHostIds = cand.entry.proxyJump.map { resolve(it, host.user) }) to cand.entry
    }
    return vm.importHosts(hops.values.toList() + linked)
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
                    helper = "Stays protected; asked for on each connection.",
                )
            }
            if (error != null) Text(error!!, style = BerthType.caption, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp))
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