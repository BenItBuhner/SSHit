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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PinLock
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.TickRow
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.importer.keyFacts
import app.berth.android.ui.importer.pinnedToLine
import app.berth.android.ui.importer.replacesSavedKeyLine
import app.berth.android.ui.importer.shortFingerprint
import app.berth.android.ui.keys.NewKeyPrefill
import app.berth.android.ui.prompts.formatDate
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.data.bundle.BundleException
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundleImportOptions
import app.berth.domain.model.BundleImportPlan
import app.berth.domain.model.BundleImportReport
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.KnownHostStanding
import app.berth.domain.model.RecreateNotice
import app.berth.domain.model.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 *
 * [hostsOnly] is Hosts › Export (spec C9): the same sheet and the same file, holding the hosts,
 * their saved passwords and their tunnels alone ([app.berth.data.bundle.BerthBundles.collectHosts]),
 * every key staying here; the sheet says what a key-login host does on the other phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBundleSheet(vm: AppViewModel, onDismiss: () -> Unit, onNotice: (String) -> Unit, hostsOnly: Boolean = false) {
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

    val contents = remember(hosts, identities, workspaces, snippets, tunnels, knownHosts, themes, hostsOnly) {
        if (hostsOnly) return@remember hostsExportContents(hosts, tunnels)
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
            val bytes = runCatching { if (hostsOnly) vm.exportHosts(passphrase.toCharArray(), version) else vm.exportBundle(passphrase.toCharArray(), version) }
                .onFailure { failure = it.message ?: "Couldn't seal the bundle." }
                .getOrNull()
            if (bytes == null) {
                busy = false
                return@launch
            }
            sealed = bytes
            runCatching { saver.launch((if (hostsOnly) "berth-hosts-" else "berth-") + "${LocalDate.now()}.${BerthBundle.EXTENSION}") }
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
            if (hostsOnly) SheetTitle("Export hosts", "The host list alone, in one .berth file") else SheetTitle("Export encrypted bundle", "One .berth file for another phone, or for keeping")
            Text(contents, style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                if (hostsOnly) HOSTS_EXPORT_NOTE else "Saved passwords and software keys go in, sealed. The file opens only with this passphrase; there is no other way in, so keep it somewhere that is not this phone.",
                style = BerthType.caption,
                color = c.text2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (hardware.isNotEmpty() && !hostsOnly) Text(hardwareStaysNote(hardware), style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
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
 * import and again after it, so the user knows what to make again in Keys, and [onMakeKey], where
 * the caller has a New key sheet to open, puts a Make a key beside Done that opens it on the first
 * of them ([NewKeyPrefill]). A file that arrived already read, [initialFile], skips the picker
 * and waits on its passphrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBundleSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
    initialFile: PickedFile? = null,
    onMakeKey: ((NewKeyPrefill) -> Unit)? = null,
) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf(initialFile) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<BerthBundle?>(null) }
    // What the import would do beyond writing records, read against this phone once the bundle is open.
    var plan by remember { mutableStateOf<BundleImportPlan?>(null) }
    var options by remember { mutableStateOf(BundleImportOptions()) }
    var report by remember { mutableStateOf<BundleImportReport?>(null) }
    val defaultTheme by vm.defaultTerminalTheme.collectAsState()
    // What the ticks do, which the button names: a key of a held type takes the saved key's place, one of a type this
    // phone holds none of is added beside it; the disclosure's last clause holds for the first.
    val ticked = plan?.knownHostsConflicting?.filter { it.id in options.replaceKnownHosts }.orEmpty()
    val replacing = ticked.count { !it.addsBeside }
    val adding = ticked.count { it.addsBeside }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val picked = readBundleFile(context, uri)
            if (picked == null) {
                error = "Couldn't read that file, or it is too large to be a bundle."
            } else {
                file = picked
                bundle = null
                plan = null
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
                val opened = vm.openBundle(picked.bytes, passphrase.toCharArray())
                plan = vm.planBundle(opened)
                options = BundleImportOptions()
                bundle = opened
            } catch (e: BundleException) {
                error = e.message
            } catch (e: BundleFormatException) {
                error = e.message
            } catch (e: OutOfMemoryError) {
                // The codec turns its own allocation into a line; this is for anything past it, so the sheet stays up either way.
                error = BundleException.TooCostly().message
            }
            busy = false
        }
    }

    fun import() {
        val opened = bundle ?: return
        busy = true
        scope.launch {
            val done = runCatching { vm.importBundle(opened, options) }.getOrElse { failure ->
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
                        // The list says to make a key: the way to do it, opening on the first key named, hardware-backed as it was.
                        if (onMakeKey != null) {
                            BerthButton("Make a key", kind = ButtonKind.SECONDARY, onClick = { onMakeKey(NewKeyPrefill(finished.needsRecreation.first())); onDismiss() })
                        }
                    }
                }
                opened != null -> {
                    SheetTitle("Import bundle", file?.let { "${it.name} \u00B7 made ${exportedOn(opened.exportedAt)}" })
                    BundleContents(opened, plan, options, defaultThemeHere = defaultTheme.name, onOptions = { options = it })
                    Text(
                        importDisclosure(replacing),
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (error != null) Text(error!!, style = BerthType.caption, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BerthButton(if (busy) "Importing\u2026" else importBundleLabel(replacing, adding), kind = ButtonKind.PRIMARY, enabled = !busy && !opened.isEmpty, onClick = ::import)
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

/**
 * What an opened bundle holds, one row a kind inside one panel (A1: grouped by the tonal step,
 * not by boxes), with the names where they fit in a caption. The three things that are this
 * phone's one copy, the default terminal theme, the Deck and the interface theme, are switches,
 * the first only where the bundle's would change what new terminals open in ([defaultThemeHere]
 * names what they open in now); a tunnel that would listen on every interface has its own row
 * under the tunnels, saying it comes in switched off. A key a hosts-only bundle names without
 * carrying it has a row of its own, saying whether this phone has it ([BundleImportPlan.identitiesHere])
 * and so whether its hosts log in with it or ask each time; so has a hardware key this phone
 * holds, which is no key to make again. The known hosts take the `known_hosts`
 * import's shape (spec A16, C13) once [plan] has been read: one count row for the keys that need
 * no decision, a bundle being a restore and a new key routine; then the decisions, a row each, a
 * key that differs from one this phone trusts for its endpoint as an unticked [TickRow], its line
 * in the danger tint naming the saved key when the tick is the Replace, and in the subtitle's own
 * tone when the key is of a type this phone holds none of and the tick adds it beside the saved
 * key ([BundledKnownHost.addsBeside]); and a pinned endpoint as a lock row, read and not offered,
 * since a pin takes no other key.
 */
@Composable
private fun BundleContents(bundle: BerthBundle, plan: BundleImportPlan?, options: BundleImportOptions, defaultThemeHere: String, onOptions: (BundleImportOptions) -> Unit) {
    val c = Berth.colors
    if (bundle.isEmpty) {
        SectionLabel("In this bundle", Modifier.padding(start = 4.dp))
        Text("Nothing: the phone it came from had no hosts, keys or settings of its own.", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    val here = plan?.identitiesHere.orEmpty()
    // A hardware key this phone holds (a bundle made on this phone) is not one to make again: it is named like a hosts-only file's key.
    val (hardwareHere, hardware) = bundle.hardwareIdentities.partition { it.id in here }
    val leftBehind = bundle.leftBehindIdentities
    val named = bundle.identities.map { it.identity }.filter { it in leftBehind || it in hardwareHere }
    val carried = bundle.identities.size - hardware.size - named.size
    Panel(label = "In this bundle") {
        @Composable
        fun row(title: String, subtitle: String) {
            ListRow(title, subtitle = subtitle, surface = Color.Transparent, minHeight = 44.dp)
        }

        @Composable
        fun row(title: String, names: List<String>) = row(title, namesLine(names))

        @Composable
        fun row(count: Int, noun: String, names: List<String>) {
            if (count > 0) row(BundleImportReport.count(count, noun), names)
        }
        row(bundle.hosts.size, "host", bundle.hosts.map { it.name })
        if (carried + hardware.size > 0) {
            row(keysLine(carried, hardware.size), bundle.identities.map { it.identity }.filter { it !in named }.map { it.name })
        }
        // Keys named and not carried, a hosts-only bundle's or a hardware key this phone holds: a row each, saying whether this phone has the key and so what its hosts do.
        for (identity in named) {
            ListRow(
                identity.name,
                subtitle = namedKeyLine(
                    identity.name,
                    identity.algorithm.displayName,
                    hereAs = here[identity.id],
                    hosts = bundle.hosts.filter { (it.auth as? AuthMethod.Key)?.identityId == identity.id }.map { it.name },
                ),
                subtitleMaxLines = 3,
                surface = Color.Transparent,
                minHeight = 52.dp,
            )
        }
        row(bundle.workspaces.size, "workspace", bundle.workspaces.map { it.name })
        row(bundle.snippets.size, "snippet", bundle.snippets.map { it.name })
        row(bundle.tunnels.size, "tunnel", bundle.tunnels.map { it.spec })
        val everywhere = plan?.tunnelsOnEveryInterface.orEmpty()
        if (everywhere.isNotEmpty()) row(tunnelsHeldOffLine(everywhere.size), tunnelsHeldOffCaption(everywhere.map { it.spec }))
        row(bundle.terminalThemes.size, "theme", bundle.terminalThemes.map { it.name })
        plan?.defaultTerminalTheme?.let { name ->
            ToggleRow(
                "Default terminal theme",
                checked = options.defaultTerminalTheme,
                onCheckedChange = { onOptions(options.copy(defaultTerminalTheme = it)) },
                caption = defaultTerminalThemeCaption(name, defaultThemeHere),
            )
        }
        bundle.deck?.let { deck ->
            ToggleRow(
                "The Deck",
                checked = options.deck,
                onCheckedChange = { onOptions(options.copy(deck = it)) },
                caption = "Replaces this phone's Deck with " + deck.layers.joinToString(", ") { it.name },
            )
        }
        if (bundle.interfaceTheme != null) {
            ToggleRow(
                "Interface theme",
                checked = options.interfaceTheme,
                onCheckedChange = { onOptions(options.copy(interfaceTheme = it)) },
                caption = "Replaces this phone's look with the bundle's",
            )
        }
        if (plan == null) {
            row(bundle.knownHosts.size, "known host", bundle.knownHosts.map { it.endpoint })
        } else {
            val routine = plan.knownHostsRoutine
            if (routine.isNotEmpty()) row(BundleImportReport.count(routine.size, "known host"), knownHostsCaption(routine.map { it.key.endpoint }, plan.knownHostsExisting))
            for (bundled in plan.knownHosts) {
                val facts = keyFacts(bundled.key.keyType, bundled.key.fingerprintSha256)
                when (val standing = bundled.standing) {
                    // Written or nothing to do: the count row above has them.
                    KnownHostStanding.NEW, KnownHostStanding.EXISTING -> Unit
                    // C13's decision, unticked until it is made: the Replace for a key of a held type, said in the danger
                    // tint; the add beside for a key of a type this phone holds none of, which takes nothing away.
                    is KnownHostStanding.Conflicting -> TickRow(
                        title = bundled.key.endpoint,
                        subtitle = facts,
                        ticked = bundled.id in options.replaceKnownHosts,
                        onTicked = { onOptions(options.copy(replaceKnownHosts = if (it) options.replaceKnownHosts + bundled.id else options.replaceKnownHosts - bundled.id)) },
                        warning = if (bundled.addsBeside) null else replacesSavedKeyLine(standing.saved),
                        note = if (bundled.addsBeside) addedBesideSavedKeyLine(standing.saved) else null,
                        surface = Color.Transparent,
                    )
                    // A pin means no other key for the endpoint: the row is read, not offered, and says where the pin is undone.
                    is KnownHostStanding.Pinned -> ListRow(
                        title = bundled.key.endpoint,
                        subtitle = "$facts\n" + pinnedToLine(standing.saved),
                        subtitleMaxLines = 4,
                        minHeight = 52.dp,
                        surface = Color.Transparent,
                        leading = { PinLock() },
                    )
                }
            }
        }
    }
    if (hardware.isNotEmpty()) {
        val used = bundle.hosts.filter { host -> hardware.any { (host.auth as? AuthMethod.Key)?.identityId == it.id } }
        Text(
            recreateNote(hardware.map { it.name }, used.map { it.name }),
            style = BerthType.caption,
            color = c.text2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private const val DISCLOSURE =
    "Hosts, keys, workspaces, snippets, tunnels and themes already here with the same id are replaced by the bundle's copies; nothing is removed"

/** The line under the contents for what an import does to what is already here, with no tick set to take a saved key's place. */
internal const val IMPORT_DISCLOSURE = "$DISCLOSURE."

/**
 * [IMPORT_DISCLOSURE], true to the ticks: with [replacing] keys ticked to take a saved key's place
 * the last clause names them, `nothing is removed but the host key you ticked to replace`, since a
 * saved key does go then, as the row and the button say. A tick that adds a key beside the saved
 * one removes nothing and leaves the line as it is.
 */
internal fun importDisclosure(replacing: Int): String = when (replacing) {
    0 -> IMPORT_DISCLOSURE
    1 -> "$DISCLOSURE but the host key you ticked to replace."
    else -> "$DISCLOSURE but the $replacing host keys you ticked to replace."
}

/** A row's caption from the names it stands for: the first six, and how many more. */
internal fun namesLine(names: List<String>): String =
    names.take(6).joinToString(", ") + if (names.size > 6) " and ${names.size - 6} more" else ""

/**
 * The count row's caption for the bundled known hosts that need no decision: their [endpoints],
 * and how many of them this phone trusts already, which the import leaves as they are.
 */
internal fun knownHostsCaption(endpoints: List<String>, existing: Int): String = namesLine(endpoints) + when {
    existing == 0 -> ""
    existing == endpoints.size -> " \u00B7 " + if (existing == 1) "trusted already" else "all trusted already"
    else -> " \u00B7 $existing trusted already"
}

/**
 * The import button's word for what the sheet's ticks do, the way the `known_hosts` import's
 * button names what it replaces: `Import`; `Import, replace 1 key` when [replacing] ticks take a
 * saved key's place; `Import, add 1 key` when [adding] ticks add a key beside the saved one; and
 * `Import, replace 1 key, add 1` for both.
 */
internal fun importBundleLabel(replacing: Int, adding: Int = 0): String {
    fun keys(n: Int) = if (n == 1) "1 key" else "$n keys"
    return when {
        replacing == 0 && adding == 0 -> "Import"
        adding == 0 -> "Import, replace ${keys(replacing)}"
        replacing == 0 -> "Import, add ${keys(adding)}"
        else -> "Import, replace ${keys(replacing)}, add $adding"
    }
}

/**
 * The line under the row of a bundled key of a type this phone holds none of for its endpoint, in
 * the subtitle's own tone since the tick takes nothing away: the [saved] key of another type it
 * would be added beside, since when, and that the saved key stays. The live first-connection
 * sheet's accept saves such a key the same way (spec C13's additional key).
 */
internal fun addedBesideSavedKeyLine(saved: KnownHostKey): String =
    "Ticked, it is added beside the saved ${saved.algorithmLabel} key ${shortFingerprint(saved.fingerprintSha256)} (trusted ${formatDate(saved.firstSeenAt)}), which stays."

/** The default terminal theme switch's caption: what new terminals would open in, and what they open in now. */
internal fun defaultTerminalThemeCaption(bundled: String, here: String): String = "New terminals open in $bundled instead of $here"

/** The row for bundled tunnels that would listen on every interface, imported switched off: its title, and the caption that names them and says why. */
internal fun tunnelsHeldOffLine(n: Int): String = if (n == 1) "1 tunnel comes in switched off" else "$n tunnels come in switched off"

internal fun tunnelsHeldOffCaption(specs: List<String>): String =
    namesLine(specs) + " \u00B7 " + if (specs.size == 1) "it listens on every interface; it stays off until you turn it on" else "they listen on every interface; they stay off until you turn them on"

/**
 * After the import: each key this phone lacks and the hosts waiting on it, one row a key, a panel
 * for the hardware-backed keys to make again and one for the software keys a hosts-only export
 * left on the other phone, each with what to do about them.
 */
@Composable
private fun RecreateList(notices: List<RecreateNotice>) {
    val (hardware, leftBehind) = notices.partition { it.hardware }
    if (hardware.isNotEmpty()) RecreatePanel("Make again in Keys", hardware, hardwareNote(hardware.size))
    if (leftBehind.isNotEmpty()) RecreatePanel("Not on this phone", leftBehind, leftBehindNote(leftBehind.size))
}

/** The report's line for the [n] hardware-backed keys the bundle named. */
internal fun hardwareNote(n: Int): String =
    (if (n == 1) "This key was" else "These were") +
        " hardware-backed on the phone that made the bundle, and a hardware key never leaves its phone. Make a new key here, install it on each host, then pick it in the host's settings."

/** The report's line for the [n] software keys the file named and did not carry. */
internal fun leftBehindNote(n: Int): String =
    if (n == 1) {
        "This key stayed on the phone that made the file. Bring it here with Keys \u203A Import key, or make a new key and install it on each host, then pick it in the host's settings."
    } else {
        "These keys stayed on the phone that made the file. Bring each here with Keys \u203A Import key, or make a new key and install it on each host, then pick it in the host's settings."
    }

@Composable
private fun RecreatePanel(label: String, notices: List<RecreateNotice>, note: String) {
    val c = Berth.colors
    Panel(label = label) {
        for (notice in notices) {
            ListRow(
                notice.identityName,
                subtitle = notice.algorithm.displayName + " \u00B7 " + when (notice.hostNames.size) {
                    0 -> "no host used it"
                    1 -> "${notice.hostNames[0]} asks each time until you pick a key"
                    else -> notice.hostNames.joinToString(", ") + " ask each time until you pick a key"
                },
                surface = Color.Transparent,
                minHeight = 44.dp,
            )
        }
    }
    Text(note, style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
}

/** The export sheet's contents line in its hosts-only mode: the hosts, the saved passwords they log in with, and the tunnels defined on them. */
internal fun hostsExportContents(hosts: List<Host>, tunnels: List<Tunnel>): String {
    val passwords = hosts.mapNotNull { (it.auth as? AuthMethod.Password)?.secretId }.distinct().size
    val ids = hosts.map { it.id }.toSet()
    val carried = tunnels.count { it.hostId in ids }
    return listOfNotNull(
        BundleImportReport.count(hosts.size, "host"),
        if (passwords > 0) BundleImportReport.count(passwords, "saved password") else null,
        if (carried > 0) BundleImportReport.count(carried, "tunnel") else null,
    ).joinToString(" \u00B7 ")
}

/** The export sheet's line in its hosts-only mode: what goes in, what stays, and what a key-login host does on the other phone. */
internal const val HOSTS_EXPORT_NOTE =
    "Saved passwords go in, sealed, with the hosts' tunnels. Keys stay on this phone: the file names each host's key by its public half, and a phone that has the same key logs in with it. Snippets and known hosts stay too. The file opens only with this passphrase; there is no other way in."

/** The contents row for the keys a bundle carries: `2 keys`, `1 key and 1 hardware key to make again`, or the hardware keys alone. */
internal fun keysLine(carried: Int, hardware: Int): String {
    val toMake = "${BundleImportReport.count(hardware, "hardware key")} to make again"
    return when {
        hardware == 0 -> BundleImportReport.count(carried, "key")
        carried == 0 -> toMake
        else -> BundleImportReport.count(carried, "key") + " and " + toMake
    }
}

/**
 * The row of a key a hosts-only bundle names without carrying it: its [algorithm], that it is not
 * in the file, and where it stands here, [hereAs] the name of the key this phone holds for it or
 * null when it holds none, and so what the [hosts] on it do after the import.
 */
internal fun namedKeyLine(name: String, algorithm: String, hereAs: String?, hosts: List<String>): String {
    val many = hosts.size > 1
    val who = hosts.joinToString(", ")
    return "$algorithm, not in the file \u00B7 " + when {
        hereAs != null -> (if (hereAs == name) "this phone has it" else "this phone has it as $hereAs") +
            if (hosts.isEmpty()) "" else ", so $who log${if (many) "" else "s"} in with it"
        else -> "this phone does not have it" +
            if (hosts.isEmpty()) "" else ", so $who ask${if (many) "" else "s"} each time until you pick a key"
    }
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

/**
 * The picked document, or null when it cannot be read or is larger than any bundle Berth wrote.
 * The picker is `*∕*`, so the pick may be anything on the phone: the provider's own size refuses
 * a wrong one before a byte is read where the provider gives a size, and the read itself stops at
 * the limit where it does not, so no pick is ever held whole in memory to be measured.
 */
private suspend fun readBundleFile(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    runCatching {
        val declared = documentSize(context, uri)
        if (declared != null && declared > MAX_BUNDLE_BYTES) return@runCatching null
        context.contentResolver.openInputStream(uri)?.use { input ->
            readAtMost(input, MAX_BUNDLE_BYTES)?.let { bytes -> PickedFile(documentName(context, uri) ?: "bundle.${BerthBundle.EXTENSION}", bytes) }
        }
    }.getOrNull()
}

/** Up to [limit] bytes of [input], or null as soon as the stream proves to hold more than that. */
internal fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > limit) return null
        out.write(buffer, 0, n)
    }
}

/** The size the provider declares for [uri], or null when it declares none. */
private fun documentSize(context: Context, uri: Uri): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
    }
}.getOrNull()

/** The display name the provider gives [uri], or its last path segment. */
private fun documentName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
