package app.berth.android.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.data.bundle.BundleException
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundleImportReport
import app.berth.domain.model.Identity
import app.berth.domain.model.RecreateNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** A bundle file larger than this is not one Berth wrote; the sheet says so instead of reading it in. */
private const val MAX_BUNDLE_BYTES = 32 shl 20

/** The shortest passphrase the export accepts; the sheet says longer is better. */
const val MIN_BUNDLE_PASSPHRASE = 8

/**
 * Settings › Data › Export encrypted bundle (spec C20). The sheet says what goes in, takes the
 * passphrase twice, seals the bundle off the main thread and then asks where to put the file, so
 * a cancelled picker costs nothing but the seal. A hardware-backed key is named here, before the
 * export, since it stays on this phone by construction and the other phone will ask for a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBundleSheet(vm: AppViewModel, onDismiss: () -> Unit, onNotice: (String) -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by vm.hosts.collectAsState()
    val identities by vm.identities.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val snippets by vm.snippets.collectAsState()
    val tunnels by vm.tunnels.collectAsState()
    val knownHosts by vm.knownHosts.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The sealed file waits here for the picker's answer; the passphrase is gone by then.
    var sealed by remember { mutableStateOf<ByteArray?>(null) }
    val hardware = identities.filter { it.isHardwareBacked }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = sealed
        sealed = null
        if (uri == null || bytes == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null }.getOrDefault(false)
            }
            busy = false
            if (written) {
                onNotice("Exported to ${documentName(context, uri) ?: "the file"}")
                onDismiss()
            } else {
                failure = "Couldn't write the file there."
            }
        }
    }

    val contents = remember(hosts, identities, workspaces, snippets, tunnels, knownHosts, themes) {
        buildList {
            add(BundleImportReport.count(hosts.size, "host"))
            add(BundleImportReport.count(identities.size, "key"))
            add(BundleImportReport.count(workspaces.size, "workspace"))
            add(BundleImportReport.count(snippets.size, "snippet"))
            add(BundleImportReport.count(tunnels.size, "tunnel"))
            val custom = themes.count { !it.builtIn }
            add(if (custom == 0) "the stock themes" else BundleImportReport.count(custom, "theme"))
            add("the Deck")
            add(BundleImportReport.count(knownHosts.size, "known host"))
        }.joinToString(" \u00B7 ")
    }
    val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_BUNDLE_PASSPHRASE
    val mismatch = confirm.isNotEmpty() && confirm != passphrase
    val ready = passphrase.length >= MIN_BUNDLE_PASSPHRASE && confirm == passphrase && !busy

    fun export() {
        busy = true
        failure = null
        scope.launch {
            val bytes = runCatching { vm.exportBundle(passphrase.toCharArray(), version) }
                .onFailure { failure = it.message ?: "Couldn't seal the bundle." }
                .getOrNull()
            if (bytes == null) {
                busy = false
                return@launch
            }
            sealed = bytes
            runCatching { saver.launch("berth-${LocalDate.now()}.${BerthBundle.EXTENSION}") }
                .onFailure { sealed = null; busy = false; failure = "No app on this phone can save a file." }
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
            SheetTitle("Export encrypted bundle", "One .berth file for another phone, or for keeping")
            Text(contents, style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                "Saved passwords and software keys go in, sealed. The file opens only with this passphrase; there is no other way in, so keep it somewhere that is not this phone.",
                style = BerthType.caption,
                color = c.text3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (hardware.isNotEmpty()) Text(hardwareStaysNote(hardware), style = BerthType.caption, color = c.attention, modifier = Modifier.padding(horizontal = 4.dp))
            BerthField(
                passphrase,
                { passphrase = it; failure = null },
                label = "Passphrase",
                password = true,
                enabled = !busy,
                helper = if (tooShort) "At least $MIN_BUNDLE_PASSPHRASE characters; longer is better." else null,
                isError = tooShort,
            )
            BerthField(
                confirm,
                { confirm = it; failure = null },
                label = "Once more",
                password = true,
                enabled = !busy,
                helper = if (mismatch) "The two don't match yet." else null,
                isError = mismatch,
            )
            if (failure != null) Text(failure!!, style = BerthType.caption, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(if (busy) "Sealing\u2026" else "Export", kind = ButtonKind.PRIMARY, enabled = ready, onClick = ::export)
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

/**
 * Settings › Data › Import bundle (spec C20): choose the file, open it with its passphrase, see
 * what is in it, import. A hardware-backed key the bundle names comes without its private half
 * by construction; the sheet says which keys those are and which hosts used them, before the
 * import and again after it, so the user knows what to make again in Keys. A file that arrived
 * already read, [initialFile], skips the picker and waits on its passphrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBundleSheet(vm: AppViewModel, onDismiss: () -> Unit, onNotice: (String) -> Unit, initialFile: PickedFile? = null) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(initialFile) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<BerthBundle?>(null) }
    var report by remember { mutableStateOf<BundleImportReport?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val picked = readBundleFile(context, uri)
            if (picked == null) {
                error = "Couldn't read that file, or it is too large to be a bundle."
            } else {
                file = picked
                bundle = null
                error = null
            }
        }
    }

    fun unlock() {
        val picked = file ?: return
        busy = true
        error = null
        scope.launch {
            try {
                bundle = vm.openBundle(picked.bytes, passphrase.toCharArray())
            } catch (e: BundleException) {
                error = e.message
            } catch (e: BundleFormatException) {
                error = e.message
            }
            busy = false
        }
    }

    fun import() {
        val opened = bundle ?: return
        busy = true
        scope.launch {
            val done = runCatching { vm.importBundle(opened) }.getOrElse { failure ->
                error = failure.message ?: "Couldn't import the bundle."
                busy = false
                return@launch
            }
            busy = false
            if (done.needsRecreation.isEmpty()) {
                onNotice(done.summary)
                onDismiss()
            } else {
                // The list is the point: it stays until the user has read it.
                report = done
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
            val finished = report
            val opened = bundle
            when {
                finished != null -> {
                    SheetTitle("Imported", finished.summary)
                    RecreateList(finished.needsRecreation)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BerthButton("Done", kind = ButtonKind.PRIMARY, onClick = onDismiss)
                    }
                }
                opened != null -> {
                    SheetTitle("Import bundle", file?.let { "${it.name} \u00B7 made ${exportedOn(opened.exportedAt)}" })
                    BundleContents(opened)
                    Text(
                        "Anything already here with the same id is replaced by the bundle's copy; nothing here is removed.",
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (error != null) Text(error!!, style = BerthType.caption, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BerthButton(if (busy) "Importing\u2026" else "Import", kind = ButtonKind.PRIMARY, enabled = !busy && !opened.isEmpty, onClick = ::import)
                        BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                    }
                }
                else -> {
                    SheetTitle("Import bundle", "A .berth file Berth exported, here or on another phone")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BerthButton(if (file == null) "Choose file" else "Choose another", enabled = !busy, onClick = { runCatching { picker.launch(arrayOf("*/*")) } })
                        val picked = file
                        if (picked != null) Text("${picked.name} \u00B7 ${sizeLabel(picked.bytes.size)}", style = BerthType.caption, color = c.text2, modifier = Modifier.weight(1f))
                    }
                    if (file != null) {
                        BerthField(
                            passphrase,
                            { passphrase = it; error = null },
                            label = "Passphrase",
                            password = true,
                            enabled = !busy,
                            helper = "The one the bundle was exported with.",
                        )
                    }
                    if (error != null) Text(error!!, style = BerthType.caption, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BerthButton(if (busy) "Opening\u2026" else "Open", kind = ButtonKind.PRIMARY, enabled = file != null && passphrase.isNotEmpty() && !busy, onClick = ::unlock)
                        BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/** What an opened bundle holds, one row a kind, with the names where they fit in a caption. */
@Composable
private fun BundleContents(bundle: BerthBundle) {
    val c = Berth.colors
    SectionLabel("In this bundle", Modifier.padding(start = 4.dp))
    if (bundle.isEmpty) {
        Text("Nothing: the phone it came from had no hosts, keys or settings of its own.", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    @Composable
    fun row(count: Int, noun: String, names: List<String>) {
        if (count == 0) return
        ListRow(BundleImportReport.count(count, noun), subtitle = names.take(6).joinToString(", ") + if (names.size > 6) " and ${names.size - 6} more" else "", minHeight = 44.dp)
    }
    row(bundle.hosts.size, "host", bundle.hosts.map { it.name })
    val hardware = bundle.hardwareIdentities
    val software = bundle.identities.size - hardware.size
    if (bundle.identities.isNotEmpty()) {
        ListRow(
            BundleImportReport.count(software, "key") + if (hardware.isNotEmpty()) " and ${BundleImportReport.count(hardware.size, "hardware key")} to make again" else "",
            subtitle = bundle.identities.joinToString(", ") { it.identity.name },
            minHeight = 44.dp,
        )
    }
    row(bundle.workspaces.size, "workspace", bundle.workspaces.map { it.name })
    row(bundle.snippets.size, "snippet", bundle.snippets.map { it.name })
    row(bundle.tunnels.size, "tunnel", bundle.tunnels.map { it.spec })
    row(bundle.terminalThemes.size, "theme", bundle.terminalThemes.map { it.name })
    if (bundle.deck != null) ListRow("The Deck", subtitle = bundle.deck!!.layers.joinToString(", ") { it.name }, minHeight = 44.dp)
    row(bundle.knownHosts.size, "known host", bundle.knownHosts.map { it.endpoint })
    if (hardware.isNotEmpty()) {
        val used = bundle.hosts.filter { host -> hardware.any { (host.auth as? AuthMethod.Key)?.identityId == it.id } }
        Text(
            recreateNote(hardware.map { it.name }, used.map { it.name }),
            style = BerthType.caption,
            color = c.attention,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** After the import: each key to make again and the hosts waiting on it, one row a key. */
@Composable
private fun RecreateList(notices: List<RecreateNotice>) {
    val c = Berth.colors
    SectionLabel("Make again in Keys", Modifier.padding(start = 4.dp))
    for (notice in notices) {
        ListRow(
            notice.identityName,
            subtitle = notice.algorithm.displayName + " \u00B7 " + when (notice.hostNames.size) {
                0 -> "no host used it"
                1 -> "${notice.hostNames[0]} asks each time until you pick a key"
                else -> notice.hostNames.joinToString(", ") + " ask each time until you pick a key"
            },
            minHeight = 44.dp,
        )
    }
    Text(
        "These were hardware-backed on the phone that made the bundle, and a hardware key never leaves its phone. Make a new key here, install it on each host, then pick it in the host's settings.",
        style = BerthType.caption,
        color = c.text3,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** The export sheet's line for keys that stay: names, then what the other phone will do. */
internal fun hardwareStaysNote(hardware: List<Identity>): String {
    val names = hardware.joinToString(", ") { it.name }
    return (if (hardware.size == 1) "$names is hardware-backed and stays on this phone" else "$names are hardware-backed and stay on this phone") +
        "; the bundle carries the public half, and the other phone will ask for a new key."
}

/** The import sheet's line for keys the bundle could not carry. */
internal fun recreateNote(keys: List<String>, hosts: List<String>): String {
    val head = if (keys.size == 1) "${keys[0]} was hardware-backed on the other phone and is not in the file" else "${keys.joinToString(", ")} were hardware-backed on the other phone and are not in the file"
    val tail = when (hosts.size) {
        0 -> "."
        1 -> "; ${hosts[0]} will ask each time until you make a key here and pick it."
        else -> "; ${hosts.joinToString(", ")} will ask each time until you make a key here and pick it."
    }
    return head + tail
}

private fun exportedOn(at: Long): String = runCatching { java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.getOrDefault("")

private fun sizeLabel(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** A picked bundle file: its bytes and the name the picker shows for it. */
class PickedFile(val name: String, val bytes: ByteArray)

private suspend fun readBundleFile(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BUNDLE_BYTES) null else PickedFile(documentName(context, uri) ?: "bundle.${BerthBundle.EXTENSION}", bytes)
        }
    }.getOrNull()
}

/** The display name the provider gives [uri], or its last path segment. */
private fun documentName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
