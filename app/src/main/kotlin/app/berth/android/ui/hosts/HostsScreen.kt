package app.berth.android.ui.hosts

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.LinkOutcome
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.importer.ImportHostsSheet
import app.berth.android.ui.importer.ImportKnownHostsSheet
import app.berth.android.ui.keys.GenerateKeySheet
import app.berth.android.ui.keys.NewKeyPrefill
import app.berth.android.ui.settings.ImportBundleSheet
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.tabs.NoticeBar
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Host
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Hosts screen's order (spec C9, Overflow › Sort). */
enum class HostSort(val label: String) {
    /** The three last connected under Recent, then the rest under All in the same order, the never-connected last by name ([byRecency]). */
    RECENT("Recent"),
    NAME("Name"),

    /** A section per tag, a host under each of its tags, the untagged last. */
    TAG("Tag"),
}

/**
 * The host library (spec C9). A search field under the header reads name, address, user and tags;
 * the tags in use are chips under it, one chosen at a time, `All` to clear. Tap opens a tab on the
 * host in the current group (spec C3); the row's second line is `user@address:port`, then `via
 * bastion` for a host that jumps, the chain as `bastion › edge`. Long-press offers Edit, Connect in
 * new group (when [onConnectInNewGroup] is given), Connect as tunnel only (a Tunnels tab on the
 * host, whatever its toggle says, when [onTunnels] is given), Files (when [onFiles] is given),
 * Duplicate (a copy, opened in the editor), Share as `ssh://` link and Delete. Overflow holds
 * Sort, Quick connect, the two imports and Known hosts. [picker] mode titles the screen "New tab";
 * back returns to the Stage.
 *
 * With no host saved the screen is the empty state (spec C1): the stance, then Add host with
 * Import a bundle beside it, since a new install's first screen is where a `.berth` file from the
 * last phone is wanted and the import otherwise lives under Settings › Data; the button opens that
 * import ([ImportBundleSheet]) as it is there, under its own title, the Make a key hand-off
 * included, and the import's one-line summary shows at the foot of this screen as it does at
 * Settings'. One act, one word across the tap: the button says import, as the sheet and C1's own
 * line do. Quick connect and Import ssh config follow as text actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostsScreen(
    vm: AppViewModel,
    onConnect: (Host) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (String) -> Unit,
    onBack: (() -> Unit)?,
    onOpenDrawer: (() -> Unit)?,
    onKnownHosts: () -> Unit,
    picker: Boolean = false,
    onFiles: ((Host) -> Unit)? = null,
    onTunnels: ((Host) -> Unit)? = null,
    onConnectInNewGroup: ((Host) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by vm.hosts.collectAsState()
    val byId = remember(hosts) { hosts.associateBy { it.id } }
    var quickConnect by remember { mutableStateOf(false) }
    var importConfig by remember { mutableStateOf(false) }
    var importKnownHosts by remember { mutableStateOf(false) }
    var importBundle by remember { mutableStateOf(false) }
    // The New key sheet the bundle import's report opens, on the key it names to make again (spec C20).
    var makeKey by remember { mutableStateOf<NewKeyPrefill?>(null) }
    // One line at the foot for what the bundle import did; the text stays for the exit animation.
    var notice by remember { mutableStateOf<String?>(null) }
    val shownNotice = remember { mutableStateOf<String?>(null) }
    if (notice != null) shownNotice.value = notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(HostSort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val now = ageTicker()

    val tags = remember(hosts) { hosts.allTags() }
    // A chip for a tag no host has any more would filter to nothing for good.
    if (tag != null && tag !in tags) tag = null
    val shown = remember(hosts, query, tag) { hosts.matching(query, tag) }

    Box(modifier.fillMaxSize().background(c.surface0)) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(
            title = if (picker) "New tab" else "Hosts",
            onBack = onBack,
            actions = {
                if (onOpenDrawer != null && onBack == null) {
                    IconAction(onClick = onOpenDrawer, description = "Open the drawer") { BerthIcon(BerthIcons.workspace) }
                }
                IconAction(onClick = onAddHost, description = "Add host") { BerthIcon(BerthIcons.add) }
                Box {
                    IconAction(onClick = { menu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                    BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                        BerthMenuItem("Sort \u00B7 ${sort.label}", onClick = { menu = false; sortMenu = true })
                        BerthMenuItem("Quick connect", onClick = { menu = false; quickConnect = true })
                        BerthMenuItem("Import ssh config", onClick = { menu = false; importConfig = true })
                        BerthMenuItem("Import known_hosts", onClick = { menu = false; importKnownHosts = true })
                        BerthMenuItem("Known hosts", onClick = { menu = false; onKnownHosts() })
                    }
                    BerthMenu(expanded = sortMenu, onDismiss = { sortMenu = false }) {
                        for (option in HostSort.entries) {
                            BerthMenuItem("Sort by ${option.label.lowercase()}", selected = sort == option, onClick = { sort = option; sortMenu = false })
                        }
                    }
                }
            },
        )

        if (hosts.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState(
                title = "Nothing here yet.",
                body = "Hosts, keys and history stay on this device. No account. No telemetry.",
            ) {
                // The pair on one line where it fits (411 dp at the font cap does), the secondary wrapping under the
                // primary where it will not (a narrower phone at the cap, a longer translation), never cut at the margin.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Add host", onClick = onAddHost, kind = ButtonKind.PRIMARY)
                    BerthButton("Import a bundle", onClick = { importBundle = true }, kind = ButtonKind.SECONDARY)
                }
                BerthButton("Quick connect", onClick = { quickConnect = true }, kind = ButtonKind.TEXT)
                BerthButton("Import ssh config", onClick = { importConfig = true }, kind = ButtonKind.TEXT)
            }
        } else {
            // The 8 dp between the search and the first row is split: 4 of it end this column, 4 head
            // the list. The field is 44 dp and Compose reaches its hit area out to 48 only into space no
            // neighbour claims (see touchTarget); the list claims everything from its own top edge, so
            // with no chips between them a list starting flush with the field cut its reach to 46.
            Column(Modifier.padding(start = BerthSpace.screenMargin, end = BerthSpace.screenMargin, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BerthField(
                    query,
                    { query = it },
                    placeholder = "Search hosts and tags",
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                )
                if (tags.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("All", selected = tag == null) { tag = null }
                        for (t in tags) Chip(t, selected = tag == t) { tag = if (tag == t) null else t }
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = BerthSpace.screenMargin, end = BerthSpace.screenMargin, top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val row: @Composable (Host) -> Unit = { host ->
                    HostRow(
                        host,
                        subtitle = host.rowSubtitle(byId),
                        now = now,
                        onTap = { onConnect(host) },
                        onEdit = { onEditHost(host.id) },
                        onConnectInNewGroup = onConnectInNewGroup?.let { open -> { open(host) } },
                        onTunnels = onTunnels?.let { open -> { open(host) } },
                        onFiles = onFiles?.let { open -> { open(host) } },
                        onDuplicate = { vm.duplicateHost(host.id) { copy -> onEditHost(copy.id) } },
                        onShare = { scope.launch { shareHostLink(context, host, vm.shareLink(host)) } },
                        onDelete = { vm.deleteHost(host.id) },
                    )
                }
                val label: (String, Boolean) -> Unit = { text, first ->
                    item(key = "label-$text") { SectionLabel(text, Modifier.padding(start = 4.dp, top = if (first) 8.dp else 20.dp, bottom = 6.dp)) }
                }
                when (sort) {
                    HostSort.RECENT -> {
                        // Recent heads the whole library (C9) and All is the rest of it, in the same order;
                        // a search or a chip narrows it to one list of matches, since a shortcut over a list
                        // of two would only say each of them twice.
                        val narrowed = query.isNotBlank() || tag != null
                        val ordered = shown.byRecency()
                        val recent = if (narrowed) emptyList() else ordered.filter { it.lastConnectedAt != null }.take(3)
                        val rest = ordered.drop(recent.size)
                        if (recent.isNotEmpty()) {
                            label("Recent", true)
                            items(recent, key = { it.id }) { host -> row(host) }
                            if (rest.isNotEmpty()) label("All", false)
                        }
                        items(rest, key = { it.id }) { host -> row(host) }
                    }
                    HostSort.NAME -> items(shown.sortedBy { it.name.lowercase() }, key = { it.id }) { host -> row(host) }
                    HostSort.TAG -> {
                        val sections = shown.byTag()
                        sections.forEachIndexed { index, (name, group) ->
                            label(name, index == 0)
                            items(group, key = { "$name-" + it.id }) { host -> row(host) }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (tag != null) "No host is tagged $tag and matches." else "No host matches.",
                            style = BerthType.body,
                            color = c.text2,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                        )
                    }
                }
            }
        }
    }
    NoticeBar(
        visible = notice != null,
        text = shownNotice.value ?: "",
        action = "OK",
        onAction = { notice = null },
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    }

    if (quickConnect) {
        QuickConnectSheet(
            vm = vm,
            onDismiss = { quickConnect = false },
            onConnected = { quickConnect = false },
            onSaved = { host -> quickConnect = false; onEditHost(host.id) },
        )
    }
    if (importConfig) {
        ImportHostsSheet(vm = vm, onDismiss = { importConfig = false })
    }
    if (importKnownHosts) {
        ImportKnownHostsSheet(vm = vm, onDismiss = { importKnownHosts = false })
    }
    if (importBundle) {
        ImportBundleSheet(vm, onDismiss = { importBundle = false }, onNotice = { notice = it }, onMakeKey = { makeKey = it })
    }
    makeKey?.let { GenerateKeySheet(vm, onDismiss = { makeKey = null }, prefill = it) }
}

/** How long the foot's notice stands before it sinks on its own; Settings' figure. */
private const val NOTICE_MS = 3_500L

/** Every tag any host carries, once each, in order of the alphabet. */
internal fun List<Host>.allTags(): List<String> = flatMap { it.tags }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }

/**
 * The hosts the search field and the chosen chip leave (spec C9): [query] is looked for in the
 * name, the address, the user and the tags, case aside, each word of it in any of those; [tag],
 * when chosen, has to be one of the host's.
 */
internal fun List<Host>.matching(query: String, tag: String?): List<Host> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return filter { host ->
        (tag == null || host.tags.any { it.equals(tag, ignoreCase = true) }) &&
            words.all { word ->
                host.name.lowercase().contains(word) || host.address.lowercase().contains(word) ||
                    host.user.lowercase().contains(word) || host.tags.any { it.lowercase().contains(word) }
            }
    }
}

/**
 * Sort › Recent's order (spec C9, the drawing's `4m, 1h` over the line and `2d, 2w, 3w, 3w` under
 * it): the last connected first, back through time, then the hosts that have never connected, by
 * name; two connected in the same instant stand by name too.
 */
internal fun List<Host>.byRecency(): List<Host> =
    sortedWith(compareByDescending<Host> { it.lastConnectedAt ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })

/** Sort by tag (spec C9): a section per tag in alphabetical order, its hosts by name; the untagged last, when there are any. */
internal fun List<Host>.byTag(): List<Pair<String, List<Host>>> {
    val sections = allTags().map { t -> t to filter { h -> h.tags.any { it.equals(t, ignoreCase = true) } }.sortedBy { it.name.lowercase() } }
    val untagged = filter { it.tags.none { t -> t.isNotBlank() } }.sortedBy { it.name.lowercase() }
    return if (untagged.isEmpty()) sections else sections + ("Untagged" to untagged)
}

/**
 * A host row's second line (spec C9): `ben@10.0.0.15`, the port when it is not 22, then
 * `· via bastion` for a host that logs in through a chain, the hops in order as `bastion › edge`.
 * A hop whose host has been deleted is named as such, since the login will stop there.
 */
internal fun Host.rowSubtitle(byId: Map<String, Host>): String = buildString {
    append(userAtHost)
    if (port != 22) append(':').append(port)
    if (jumpHostIds.isNotEmpty()) append(" \u00B7 via ").append(jumpHostIds.joinToString(" \u203A ") { byId[it]?.name ?: "a deleted host" })
}

/** Share as `ssh://` link (spec C9): the link as text through the system share sheet, titled with the host's name. */
internal fun shareHostLink(context: Context, host: Host, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, host.name)
        .putExtra(Intent.EXTRA_TEXT, link)
    runCatching { context.startActivity(Intent.createChooser(send, "Share ${host.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun HostRow(
    host: Host,
    subtitle: String,
    now: Long,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onConnectInNewGroup: (() -> Unit)?,
    onTunnels: (() -> Unit)?,
    onFiles: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Berth.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = host.name,
            subtitle = subtitle,
            onClick = onTap,
            onLongClick = { menu = true },
            leading = { Swatch(host.color, host.monogram, 36.dp) },
            trailing = {
                if (host.lastConnectedAt != null) Text(ageText(host.lastConnectedAt, now), style = BerthType.caption, color = c.text3)
            },
        )
        BerthMenu(expanded = menu, onDismiss = { menu = false }) {
            BerthMenuItem("Edit", onClick = { menu = false; onEdit() })
            if (onConnectInNewGroup != null) BerthMenuItem("Connect in new group", onClick = { menu = false; onConnectInNewGroup() })
            if (onTunnels != null) BerthMenuItem("Connect as tunnel only", onClick = { menu = false; onTunnels() })
            if (onFiles != null) BerthMenuItem("Files", onClick = { menu = false; onFiles() })
            BerthMenuItem("Duplicate", onClick = { menu = false; onDuplicate() })
            BerthMenuItem("Share as ssh:// link", onClick = { menu = false; onShare() })
            BerthMenuItem("Delete", destructive = true, onClick = { menu = false; onDelete() })
        }
    }
}

/**
 * Quick connect (spec C11): a `user@host:port` field in Mono, the identity to log in with, Connect
 * and Save as host; the login opens as an unsaved host, and Save as host keeps the address as a
 * host named after it and hands it to [onSaved] for the editor to name. What stops a spec is said
 * under the field in the parser's words, the same ones a link's notice uses. With [fromLink] the
 * sheet is where a plain `ssh://` link no saved host answers to lands (spec, Deep links): the field
 * holds the link's address with the caret after it and takes focus as the sheet opens, so a
 * keyboard adds a port or presses Enter without first reaching for the field; the title says why
 * the sheet is up, and a fingerprint the link carried goes to the trust sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onConnected: () -> Unit,
    fromLink: LinkOutcome.QuickConnect? = null,
    onSaved: ((Host) -> Unit)? = null,
) {
    val identities by vm.identities.collectAsState()
    val initialSpec = fromLink?.spec
    var spec by remember { mutableStateOf(TextFieldValue(initialSpec ?: "", TextRange(initialSpec?.length ?: 0))) }
    var identityId by remember { mutableStateOf<String?>(null) }
    var pickIdentity by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun connect() {
        if (spec.text.isBlank()) return
        val problem = vm.quickConnect(spec.text, identityId, fromLink = fromLink)
        if (problem == null) onConnected() else error = problem
    }
    fun save() {
        val done = onSaved ?: return
        if (spec.text.isBlank()) return
        val problem = vm.saveQuickConnectAsHost(spec.text, identityId, onSaved = done)
        if (problem != null) error = problem
    }
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The launcher's Quick connect shortcut lands here too (spec, App shortcuts), as an empty spec: the field takes focus, and there is no link to speak of.
            SheetTitle("Quick connect", if (!initialSpec.isNullOrEmpty()) "No saved host matches the link." else null)
            BerthField(
                value = spec,
                onValueChange = { spec = it; error = null },
                placeholder = "user@host:port",
                mono = true,
                isError = error != null,
                helper = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                focusRequester = focus,
            )
            // Inside the sheet's own composition, so the field is attached in the sheet window by the time this runs.
            if (initialSpec != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box {
                PickerRow(
                    title = "Identity",
                    value = identities.firstOrNull { it.id == identityId }?.name ?: "Ask on connect",
                    onClick = { pickIdentity = true },
                )
                BerthMenu(expanded = pickIdentity, onDismiss = { pickIdentity = false }) {
                    BerthMenuItem("Ask on connect", selected = identityId == null, onClick = { identityId = null; pickIdentity = false })
                    for (identity in identities) {
                        BerthMenuItem(identity.name, selected = identityId == identity.id, onClick = { identityId = identity.id; pickIdentity = false })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Connect", onClick = ::connect, kind = ButtonKind.PRIMARY, enabled = spec.text.isNotBlank())
                if (onSaved != null) BerthButton("Save as host", onClick = ::save, kind = ButtonKind.SECONDARY, enabled = spec.text.isNotBlank())
            }
        }
    }
}
