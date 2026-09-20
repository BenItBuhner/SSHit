package app.berth.android.ui.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.files.FilesBrowser
import app.berth.android.files.FilesState
import app.berth.android.files.Notice
import app.berth.android.files.Transfer
import app.berth.android.files.TransferManager
import app.berth.android.files.TransferState
import app.berth.android.session.FilesTab
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.BerthMotion
import app.berth.android.ui.a11y.TouchTargetSize
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.a11y.touchTarget
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.stage.LocalHapticLevel
import app.berth.android.ui.stage.OverflowRows
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.FilesSort
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.SessionState
import app.berth.sftp.ConflictChoice
import app.berth.sftp.SftpEntry
import app.berth.sftp.SftpError
import app.berth.sftp.SftpPaths
import app.berth.sftp.TextRead

/**
 * The ways files leave and reach the device. The screen owns these because they go through the
 * Activity result flows (SAF create-document, open-tree, open-documents) and the share sheet;
 * the pane below only calls them.
 */
class FilesActions(
    /** Files and folders among [entries]: one file goes to a document, anything else into a picked folder. */
    val download: (entries: List<SftpEntry>) -> Unit,
    val share: (entry: SftpEntry) -> Unit,
    /** Picks documents and uploads them into the folder being shown. */
    val upload: () -> Unit,
    /** Picks a folder on the device and uploads it, as a folder of its name, into the folder being shown. */
    val uploadFolder: () -> Unit = {},
) {
    companion object {
        val None = FilesActions({}, {}, {}, {})
    }
}

/**
 * A Files tab's body under the tab strip (spec C3, tab kinds): the tab's browser from
 * `FilesCenter`, the document pickers wired to the transfer queue over the terminal the tab rides,
 * and [FilesPane] without a header of its own. The pane's folder rows go to the Stage's overflow
 * through [onLendOverflow], so the screen has one ⋮; the pane keeps its own navigation-bar inset,
 * so the Stage adds none below it. Nothing while the tab is gone.
 */
@Composable
fun FilesTabBody(vm: AppViewModel, tab: FilesTab, onLendOverflow: (OverflowRows?) -> Unit = {}, modifier: Modifier = Modifier) {
    val browser = remember(tab.id) { vm.files.browser(tab.id) } ?: return
    val record by tab.record.collectAsState()
    val ride by tab.ride.collectAsState()
    val rideRecord = ride?.record?.collectAsState()?.value
    val prefs by vm.files.prefs.collectAsState()
    val transfers by vm.files.transfers.transfers.collectAsState()
    val queue = vm.files.transfers

    // A transfer runs over a login. The pane disables uploads while there is none; the viewer's
    // download and share still reach here, and say so instead of doing nothing.
    fun login(): TerminalSession? = tab.ride.value ?: run {
        browser.post(Notice("Not connected.", isError = true))
        null
    }

    var pendingDocument by remember { mutableStateOf<SftpEntry?>(null) }
    var pendingBatch by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    val createDocument = rememberLauncherForActivityResult(CreateNamedDocument()) { uri ->
        val entry = pendingDocument
        pendingDocument = null
        val session = tab.ride.value
        if (uri != null && entry != null && session != null) queue.download(session, entry, uri)
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val batch = pendingBatch
        pendingBatch = emptyList()
        val session = tab.ride.value
        if (uri != null && batch.isNotEmpty() && session != null) queue.downloadInto(session, batch, uri)
    }
    val openDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val session = tab.ride.value
        if (uris.isNotEmpty() && session != null) queue.upload(session, uris, browser.state.value.path)
    }
    // The folder being shown when the picker opened, so the upload lands there even if the listing moved on.
    var pendingUploadDir by remember { mutableStateOf<String?>(null) }
    val openTreeToUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val dir = pendingUploadDir
        pendingUploadDir = null
        val session = tab.ride.value
        if (uri != null && dir != null && session != null) queue.uploadFolder(session, uri, dir)
    }
    val actions = FilesActions(
        download = { entries ->
            val wanted = entries.filter { it.isRegularFile || it.isDirectory }
            when {
                wanted.isEmpty() -> browser.post(Notice("Only files and folders can be downloaded.", isError = true))
                login() == null -> Unit
                wanted.size == 1 && wanted.single().isRegularFile -> {
                    val file = wanted.single()
                    pendingDocument = file
                    createDocument.launch(file.name to TransferManager.mimeFor(file.name))
                }
                else -> {
                    pendingBatch = wanted
                    openTree.launch(null)
                }
            }
        },
        share = { entry -> login()?.let { queue.share(it, entry) } },
        upload = { if (login() != null) openDocuments.launch(arrayOf("*/*")) },
        uploadFolder = {
            if (login() != null) {
                pendingUploadDir = browser.state.value.path
                openTreeToUpload.launch(null)
            }
        },
    )

    FilesPane(
        hostName = record.hostSnapshot.name,
        browser = browser,
        prefs = prefs,
        onSort = vm.files::setSort,
        onShowHidden = vm.files::setShowHidden,
        transfers = transfers,
        onCancelTransfer = queue::cancel,
        onClearFinished = queue::clearFinished,
        onResolveConflict = queue::resolveConflict,
        onRetryFailed = { id -> if (queue.retryFailed(id) == null) browser.post(Notice("That session is gone.", isError = true)) },
        terminalCwd = rideRecord?.cwd,
        recent = prefs.recentFor(tab.host.id),
        actions = actions,
        onBack = {},
        modifier = modifier,
        connected = record.state == SessionState.LIVE,
        onReconnect = { vm.reconnect(tab.id) },
        header = false,
        noSession = ride == null,
        folderMenuHost = onLendOverflow,
    )
}

/** `ACTION_CREATE_DOCUMENT` with the file's own name and type, since the contract's type is fixed at construction. */
private class CreateNamedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/**
 * Which sheet is open. Targets are paths, not entries, so the state survives a tab switch or a
 * rotation through [SheetSaver] and the sheet reads the entry fresh from the listing when it draws.
 */
private sealed interface FilesSheetKind {
    data object Path : FilesSheetKind
    data object NewFolder : FilesSheetKind
    data class Rename(val path: String) : FilesSheetKind
    data class Delete(val paths: List<String>) : FilesSheetKind
    data class Chmod(val paths: List<String>) : FilesSheetKind
    data object Transfers : FilesSheetKind
}

private val SheetSaver = listSaver<FilesSheetKind?, String>(
    save = { s ->
        when (s) {
            null -> emptyList()
            FilesSheetKind.Path -> listOf("path")
            FilesSheetKind.NewFolder -> listOf("new-folder")
            is FilesSheetKind.Rename -> listOf("rename", s.path)
            is FilesSheetKind.Delete -> listOf("delete") + s.paths
            is FilesSheetKind.Chmod -> listOf("chmod") + s.paths
            FilesSheetKind.Transfers -> listOf("transfers")
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "path" -> FilesSheetKind.Path
            "new-folder" -> FilesSheetKind.NewFolder
            "rename" -> saved.getOrNull(1)?.let { FilesSheetKind.Rename(it) }
            "delete" -> FilesSheetKind.Delete(saved.drop(1))
            "chmod" -> FilesSheetKind.Chmod(saved.drop(1))
            "transfers" -> FilesSheetKind.Transfers
            else -> null
        }
    },
)

private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** The two things the foot slot shows; both are bands of [FootHeight] so the swap is a crossfade with no size change. */
private enum class Foot { TRANSFER, ACTIONS }

/**
 * The single-pane browser: header with upload, transfers and more; the breadcrumb; sort and hidden
 * chips; the listing under pull to refresh; and, when rows are selected, a contextual action band in
 * place of the transfer band. Everything the pane needs comes in, so a fake file system renders it
 * as faithfully as a live one. With [header] false the pane draws neither the screen header nor the
 * status-bar inset, for a host that owns the top (the tab strip); the header's actions then sit at
 * the end of the breadcrumb row, and while rows are selected the breadcrumb row itself becomes the
 * selection bar (same height), so the host's top row stays alone and nothing below moves. A host
 * with an overflow of its own passes [folderMenuHost]: the pane then draws no ⋮ at all and hands
 * the host its six folder rows every composition (withdrawn when the pane leaves), so the screen
 * has one ⋮ and the breadcrumb row keeps Upload and Transfers; without it the headerless pane
 * keeps a Folder options ⋮ of its own. The navigation-bar inset is the pane's own either way; a
 * host adds none. [noSession] says the tab has no terminal at all to ride, so the disconnected
 * state offers Connect rather than Reconnect. A copy waiting on a file that exists already, inside
 * a folder or a single file of a selection, asks through [onResolveConflict]; what failed in a
 * finished folder goes again through [onRetryFailed].
 */
@Composable
fun FilesPane(
    hostName: String,
    browser: FilesBrowser,
    prefs: FilesPrefs,
    onSort: (FilesSort) -> Unit,
    onShowHidden: (Boolean) -> Unit,
    transfers: List<Transfer>,
    onCancelTransfer: (String) -> Unit,
    onClearFinished: () -> Unit,
    terminalCwd: String?,
    recent: List<String>,
    actions: FilesActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
    onReconnect: (() -> Unit)? = null,
    header: Boolean = true,
    noSession: Boolean = false,
    folderMenuHost: ((OverflowRows?) -> Unit)? = null,
    onResolveConflict: (id: String, choice: ConflictChoice, applyToAll: Boolean) -> Unit = { _, _, _ -> },
    onRetryFailed: (id: String) -> Unit = {},
) {
    val c = Berth.colors
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val hapticLevel = LocalHapticLevel.current
    val state by browser.state.collectAsState()
    val notice by browser.notice.collectAsState()
    val now = ageTicker()
    val entries = remember(state.entries, prefs.sort, prefs.ascending, prefs.showHidden) { sortEntries(state.entries, prefs) }
    val listState = rememberLazyListState()

    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet()) }
    val selected = remember(selection, state.entries) { state.entries.filter { it.path in selection } }
    LaunchedEffect(state.path, state.entries) {
        // A re-list drops rows that are gone; a new folder drops the selection outright.
        if (selection.isNotEmpty()) {
            val present = state.entries.mapTo(HashSet()) { it.path }
            selection = selection.filterTo(HashSet()) { it in present }
        }
    }

    // The browser keeps where each folder was scrolled to, so coming back lands where you left; the
    // pane hands it the position on every navigation and when it goes away (tab switch, rotation).
    fun leaving() {
        browser.rememberScroll(state.path, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }
    fun go(path: String) {
        leaving()
        browser.navigate(path)
    }
    fun goBack() {
        leaving()
        browser.back()
    }
    DisposableEffect(browser) { onDispose { leaving() } }
    LaunchedEffect(state.path, state.loading) {
        if (!state.loading) {
            val (index, offset) = browser.scrollFor(state.path)
            listState.scrollToItem(index, offset)
        }
    }

    var sheet by rememberSaveable(stateSaver = SheetSaver) { mutableStateOf(null) }
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    // A copy waiting on a file that exists already, a folder's or a single file's, asks through its
    // own sheet once whatever sheet is open closes. Dismissed, that question is put aside and the copy
    // keeps waiting; with several waiting, the first not put aside is asked, so one put aside never
    // keeps another unasked. A tap on the strip or on a row brings that transfer's question back first.
    var conflictsPutAside by remember { mutableStateOf(emptySet<Pair<String, String>>()) }
    var askFirst by remember { mutableStateOf<String?>(null) }
    val waiting = transfers.filter { it.waiting && (it.id to it.pendingConflict!!.relativePath) !in conflictsPutAside }
        .let { open -> open.firstOrNull { it.id == askFirst } ?: open.firstOrNull() }
    val conflictKey = waiting?.let { it.id to it.pendingConflict!!.relativePath }
    val asking = waiting != null && sheet == null && viewerPath == null
    fun askAgain(id: String) {
        conflictsPutAside = conflictsPutAside.filterTo(HashSet()) { it.first != id }
        askFirst = id
        sheet = null
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = selection.isEmpty() && state.canGoBack) { goBack() }

    fun copyPath(path: String) {
        clipboard.setText(AnnotatedString(path))
        browser.post(Notice("Copied $path", isError = false))
    }
    fun open(entry: SftpEntry) {
        when {
            entry.isDirectory -> go(entry.path)
            entry.isRegularFile -> viewerPath = entry.path
            entry.isSymlink -> browser.post(Notice("${entry.name} points at nothing.", isError = true))
            else -> browser.post(Notice("${entry.name} is a special file; there is nothing to open.", isError = true))
        }
    }
    fun select(entry: SftpEntry) {
        if (hapticLevel != HapticLevel.OFF) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        selection = if (entry.path in selection) selection - entry.path else selection + entry.path
    }
    fun selectAll() {
        selection = entries.mapTo(HashSet()) { it.path }
    }

    // The six folder rows, wherever the menu that holds them lives; each closes that menu, then acts.
    val folderRows: OverflowRows = { dismiss ->
        MenuItem("New folder", enabled = connected && state.error == null) { dismiss(); sheet = FilesSheetKind.NewFolder }
        MenuItem("Upload folder", enabled = connected && state.error == null) { dismiss(); actions.uploadFolder() }
        if (terminalCwd != null) {
            MenuItem("Terminal directory", enabled = terminalCwd != state.path) { dismiss(); go(terminalCwd) }
        }
        MenuItem("Copy path") { dismiss(); copyPath(state.path) }
        MenuItem("Select all", enabled = entries.isNotEmpty()) { dismiss(); selectAll() }
        MenuItem("Refresh") { dismiss(); browser.refresh() }
    }
    // Under a host with its own overflow the rows go there: handed over after every composition so
    // their enabled states track the listing, and withdrawn when the pane leaves.
    val lendTo = folderMenuHost?.takeIf { !header }
    if (lendTo != null) {
        SideEffect { lendTo(folderRows) }
        DisposableEffect(lendTo) { onDispose { lendTo(null) } }
    }

    val headerActions: @Composable RowScope.() -> Unit = {
        IconAction(onClick = actions.upload, description = "Upload", enabled = connected) {
            BerthIcon(BerthIcons.upload, tint = if (connected) c.text2 else c.text3)
        }
        TransfersAction(active = transfers.count { it.state.isActive }) { sheet = FilesSheetKind.Transfers }
        if (lendTo == null) {
            Box {
                // Named for the folder it acts on, apart from a host's own overflow, the plain "More".
                IconAction(onClick = { menu = true }, description = "Folder options") { BerthIcon(BerthIcons.moreVert) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                    folderRows { menu = false }
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .then(if (header) Modifier.statusBarsPadding() else Modifier)
            .navigationBarsPadding(),
    ) {
        val selecting = selection.isNotEmpty()
        val allSelected = selection.size >= entries.size
        val clearSelection = { selection = emptySet() }
        val toggleAll = { if (allSelected) selection = emptySet() else selectAll() }
        if (header) {
            if (selecting) SelectionHeader(count = selection.size, allSelected = allSelected, onClose = clearSelection, onSelectAll = toggleAll)
            else ScreenHeader(title = "Files \u00B7 $hostName", onBack = onBack, actions = headerActions)
        }
        if (!header && selecting) {
            SelectionBar(count = selection.size, allSelected = allSelected, onClose = clearSelection, onSelectAll = toggleAll)
        } else {
            Breadcrumb(
                path = state.path,
                onJump = ::go,
                onEdit = { sheet = FilesSheetKind.Path },
                onCopy = { copyPath(state.path) },
                trailing = if (header) null else headerActions,
            )
        }
        // Nothing to sort over an error or an empty folder; the chips stay when hidden files are the reason.
        if (state.error == null && (state.entries.isNotEmpty() || state.loading)) {
            FilterRow(prefs, onSort, onShowHidden, hiddenCount = state.entries.count { it.isHidden })
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = state.error
            when {
                error != null -> ListingError(
                    error = error,
                    state = state,
                    onRetry = browser::refresh,
                    onUp = { go(SftpPaths.parent(state.path)) },
                    onHome = { go(state.home ?: SftpPaths.ROOT) },
                    onReconnect = onReconnect,
                    noSession = noSession,
                )
                state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StatusDot(SessionState.CONNECTING, size = 10.dp)
                }
                else -> EntryList(
                    entries = entries,
                    hiddenOnly = entries.isEmpty() && state.entries.isNotEmpty(),
                    refreshing = state.refreshing,
                    selection = selection,
                    listState = listState,
                    now = now,
                    onRefresh = browser::refresh,
                    onOpen = ::open,
                    onSelect = ::select,
                    onUpload = actions.upload,
                    onNewFolder = { sheet = FilesSheetKind.NewFolder },
                    onShowHidden = { onShowHidden(true) },
                    canWrite = connected,
                )
            }
            NoticeLine(notice, onDismiss = browser::dismissNotice, modifier = Modifier.align(Alignment.BottomCenter))
        }
        val moving = transfers.firstOrNull { it.state == TransferState.RUNNING } ?: transfers.firstOrNull { it.state == TransferState.QUEUED }
        val lastMoving = remember { mutableStateOf(moving) }
        if (moving != null) lastMoving.value = moving
        val foot = when {
            selection.isNotEmpty() -> Foot.ACTIONS
            moving != null -> Foot.TRANSFER
            else -> null
        }
        val lastFoot = remember { mutableStateOf(foot) }
        if (foot != null) lastFoot.value = foot
        AnimatedVisibility(visible = foot != null, enter = BerthMotion.riseIn(), exit = BerthMotion.sinkOut()) {
            Crossfade(targetState = lastFoot.value, animationSpec = tween(150), label = "foot") { which ->
                when (which) {
                    Foot.ACTIONS -> SelectionActions(
                        selected = selected,
                        canWrite = connected,
                        onDownload = { actions.download(selected) },
                        onShare = { selected.singleOrNull()?.let(actions.share) },
                        onRename = { selected.singleOrNull()?.let { sheet = FilesSheetKind.Rename(it.path) } },
                        onChmod = { sheet = FilesSheetKind.Chmod(selected.map { it.path }) },
                        onCopyPath = { selected.singleOrNull()?.let { copyPath(it.path) } },
                        onDelete = { sheet = FilesSheetKind.Delete(selected.map { it.path }) },
                        onHint = { browser.post(Notice(it, isError = false)) },
                    )
                    Foot.TRANSFER, null -> lastMoving.value?.let { t ->
                        TransferStrip(
                            t,
                            others = transfers.count { it.state.isActive } - 1,
                            onCancel = { onCancelTransfer(t.id) },
                            onClick = { if (t.waiting) askAgain(t.id) else sheet = FilesSheetKind.Transfers },
                        )
                    }
                }
            }
        }
    }

    val names = remember(state.entries) { state.entries.mapTo(HashSet()) { it.name } }
    fun entriesAt(paths: List<String>): List<SftpEntry> = state.entries.filter { it.path in paths }
    when (val s = sheet) {
        null -> Unit
        FilesSheetKind.Path -> PathEditorSheet(
            current = state.path,
            home = state.home,
            terminalCwd = terminalCwd,
            recent = recent,
            onGo = ::go,
            onDismiss = { sheet = null },
        )
        FilesSheetKind.NewFolder -> NameSheet(
            title = "New folder",
            folder = state.path,
            initial = "",
            confirm = "Create",
            existing = names,
            onConfirm = { browser.mkdir(it) },
            onDismiss = { sheet = null },
        )
        is FilesSheetKind.Rename -> WithTargets(entriesAt(listOf(s.path)), onGone = { sheet = null }) { targets ->
            val entry = targets.single()
            NameSheet(
                title = "Rename",
                folder = SftpPaths.parent(entry.path),
                initial = entry.name,
                confirm = "Rename",
                existing = names,
                onConfirm = { browser.rename(entry, it); selection = emptySet() },
                onDismiss = { sheet = null },
            )
        }
        is FilesSheetKind.Delete -> WithTargets(entriesAt(s.paths), onGone = { sheet = null }) { targets ->
            DeleteSheet(
                entries = targets,
                onConfirm = { browser.delete(targets); selection = emptySet() },
                onDismiss = { sheet = null },
            )
        }
        is FilesSheetKind.Chmod -> WithTargets(entriesAt(s.paths), onGone = { sheet = null }) { targets ->
            ChmodSheet(
                entries = targets,
                onApply = { browser.chmod(targets, it); selection = emptySet() },
                onDismiss = { sheet = null },
            )
        }
        FilesSheetKind.Transfers -> TransferSheet(
            transfers = transfers,
            onCancel = onCancelTransfer,
            onClearFinished = onClearFinished,
            onDismiss = { sheet = null },
            onRetryFailed = onRetryFailed,
            onAnswer = ::askAgain,
        )
    }
    if (asking && waiting != null && conflictKey != null) {
        FolderConflictSheet(
            transfer = waiting,
            now = now,
            onChoose = { choice, applyToAll -> onResolveConflict(waiting.id, choice, applyToAll) },
            onDismiss = { conflictsPutAside = conflictsPutAside + conflictKey },
        )
    }
    viewerPath?.let { path ->
        WithTargets(entriesAt(listOf(path)), onGone = { viewerPath = null }) { targets ->
            val entry = targets.single()
            // The name or the size can settle what the sheet is before a byte is read.
            val verdict = remember(entry.path) {
                when {
                    entry.size > FilesBrowser.VIEWER_SKIP_BYTES -> ViewerContent.TooLarge(entry.size)
                    looksBinary(entry.name) -> ViewerContent.Binary(entry.size)
                    else -> ViewerContent.Loading
                }
            }
            var content by remember(entry.path) { mutableStateOf(verdict) }
            if (verdict == ViewerContent.Loading) {
                LaunchedEffect(entry.path) {
                    content = browser.readText(entry).fold(
                        onSuccess = { read ->
                            when (read) {
                                is TextRead.Text -> ViewerContent.Text(read)
                                is TextRead.Binary -> ViewerContent.Binary(read.size)
                            }
                        },
                        onFailure = { ViewerContent.Failed(it.message ?: "The read did not finish.") },
                    )
                }
            }
            FileViewerSheet(
                entry = entry,
                content = content,
                tall = verdict == ViewerContent.Loading,
                onDownload = { actions.download(listOf(entry)) },
                onShare = { actions.share(entry) },
                onCopyPath = { copyPath(entry.path) },
                onDismiss = { viewerPath = null },
            )
        }
    }
}

/** Shows [content] for the entries a sheet targets; when the listing no longer has them the sheet has nothing to act on and closes. */
@Composable
private fun WithTargets(targets: List<SftpEntry>, onGone: () -> Unit, content: @Composable (List<SftpEntry>) -> Unit) {
    if (targets.isEmpty()) {
        LaunchedEffect(Unit) { onGone() }
    } else {
        content(targets)
    }
}

// ---- header pieces --------------------------------------------------------------------------------

/** The transfers glyph with a 7 dp accent dot while anything is moving. */
@Composable
private fun TransfersAction(active: Int, onClick: () -> Unit) {
    val c = Berth.colors
    IconAction(onClick = onClick, description = if (active > 0) "Transfers, $active running" else "Transfers") {
        BerthIcon(BerthIcons.transfers)
        if (active > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
        }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Berth.colors
    DropdownMenuItem(
        text = { Text(label, style = BerthType.body, color = if (enabled) c.text1 else c.text3) },
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * The header while rows are selected: the shared [ScreenHeader] with close in its navigation slot,
 * the count as the title, All or None trailing, so a restyle of the header covers this state too.
 */
@Composable
private fun SelectionHeader(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    ScreenHeader(
        title = selectionTitle(count),
        navigation = { IconAction(onClick = onClose, description = "Clear selection") { BerthIcon(BerthIcons.close) } },
        actions = { BerthButton(if (allSelected) "None" else "All", kind = ButtonKind.TEXT, onClick = onSelectAll) },
    )
}

private fun selectionTitle(count: Int) = if (count == 1) "1 selected" else "$count selected"

/**
 * Selection under a host's header: the breadcrumb row's height with Clear selection leading, the
 * count where the crumbs were, and All or None trailing. The same row swaps for the crumbs and
 * back, so a tab strip above keeps the top to itself and the listing never moves.
 */
@Composable
private fun SelectionBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val c = Berth.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(PathRowHeight)
            .padding(horizontal = BerthSpace.screenMargin - 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(onClick = onClose, description = "Clear selection") { BerthIcon(BerthIcons.close) }
        Spacer(Modifier.width(4.dp))
        Text(selectionTitle(count), style = BerthType.bodyMedium, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        BerthButton(if (allSelected) "None" else "All", kind = ButtonKind.TEXT, onClick = onSelectAll)
    }
}

/**
 * The path as crumbs: ancestors in text.2, the current folder in text.1, a chevron between. A tap
 * jumps; the current crumb and the pencil open the editor; a long press copies the path. Long
 * paths scroll and keep their tail in view, and a fade at the leading edge says there is more
 * behind. [trailing] carries the header's actions when the pane has no header of its own.
 */
@Composable
private fun Breadcrumb(
    path: String,
    onJump: (String) -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Berth.colors
    val crumbs = remember(path) { SftpPaths.crumbs(path) }
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    // The row is one target tall, and the icon actions at its end are 48 dp boxes around 44 dp
    // circles, so the end margin gives back the 2 dp of box beside the circle.
    Row(
        Modifier
            .fillMaxWidth()
            .height(PathRowHeight)
            .padding(start = BerthSpace.screenMargin - 6.dp, end = BerthSpace.screenMargin - 10.dp)
            .semantics { contentDescription = "Path $path" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fade = 16.dp
        Row(
            Modifier
                .weight(1f)
                .drawWithContent {
                    drawContent()
                    if (scroll.value > 0) {
                        val w = fade.toPx().coerceAtMost(size.width)
                        drawRect(Brush.horizontalGradient(0f to c.surface0, 1f to Color.Transparent, startX = 0f, endX = w), size = Size(w, size.height))
                    }
                }
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, (label, full) ->
                val last = i == crumbs.lastIndex
                if (i > 0) BerthIcon(BerthIcons.chevronRight, size = 14.dp, tint = c.text3)
                Crumb(label, last = last, onClick = { if (last) onEdit() else onJump(full) }, onLongClick = onCopy)
            }
        }
        IconAction(onClick = onEdit, description = "Edit path") { BerthIcon(BerthIcons.edit, size = 20.dp) }
        if (trailing != null) trailing()
    }
}

/** The breadcrumb row and the selection bar that swaps in for it: one touch target tall. */
private val PathRowHeight = TouchTargetSize

/**
 * One crumb: the pressed step Berth's pressables share, no ripple. A short crumb (`var`, `~`) is
 * widened to a full target; the text stays where it was and the pressed step stays the text's size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Crumb(label: String, last: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    Box(
        Modifier
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (last) BerthType.bodyMedium else BerthType.body,
            color = if (focused) c.accent else if (last) c.text1 else c.text2,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(BerthRadius.swatch))
                .background(if (pressed || focused) c.surface3 else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
}

/**
 * Sort chips and the hidden-files chip at the trailing edge. Every sort chip is as wide as its label
 * with the direction arrow, so the arrow moving to the active one does not reflow the row. The
 * chips' 48 dp targets give the row its height and its clearance from the crumbs and the listing.
 */
@Composable
private fun FilterRow(prefs: FilesPrefs, onSort: (FilesSort) -> Unit, onShowHidden: (Boolean) -> Unit, hiddenCount: Int) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BerthSpace.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (sort in FilesSort.entries) {
            val active = prefs.sort == sort
            val arrow = if (prefs.ascending) " \u2191" else " \u2193"
            val width = remember(sort, density) {
                with(density) { measurer.measure(sort.label + " \u2191", BerthType.label).size.width.toDp() } + 20.dp
            }
            Chip(
                text = if (active) sort.label + arrow else sort.label,
                selected = active,
                onClick = { onSort(sort) },
                modifier = Modifier.width(width),
            )
        }
        Spacer(Modifier.weight(1f))
        Chip(
            text = if (!prefs.showHidden && hiddenCount > 0) "Hidden $hiddenCount" else "Hidden",
            selected = prefs.showHidden,
            onClick = { onShowHidden(!prefs.showHidden) },
        )
    }
}

// ---- listing --------------------------------------------------------------------------------------

/** The rows under pull to refresh; the indicator is Berth's state dot descending, spinning once released. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryList(
    entries: List<SftpEntry>,
    hiddenOnly: Boolean,
    refreshing: Boolean,
    selection: Set<String>,
    listState: LazyListState,
    now: Long,
    onRefresh: () -> Unit,
    onOpen: (SftpEntry) -> Unit,
    onSelect: (SftpEntry) -> Unit,
    onUpload: () -> Unit,
    onNewFolder: () -> Unit,
    onShowHidden: () -> Unit,
    canWrite: Boolean,
) {
    val pull = rememberPullToRefreshState()
    // The pull opens a slot above the first row for the disc, so it never sits over a row; the slot
    // stays open while the listing is on its way and closes when it lands.
    val slot by animateDpAsState(
        if (refreshing) RefreshSlot else RefreshSlot * pull.distanceFraction.coerceIn(0f, 1f),
        if (refreshing || pull.distanceFraction > 0f) snap() else BerthMotion.transform(tween(200)),
        label = "refresh",
    )
    Column(
        Modifier
            .fillMaxSize()
            .pullToRefresh(isRefreshing = refreshing, state = pull, onRefresh = onRefresh),
    ) {
        Box(Modifier.fillMaxWidth().height(slot), contentAlignment = Alignment.BottomCenter) {
            RefreshIndicator(pull, refreshing)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.rowGap),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Column {
                        Spacer(Modifier.height(40.dp))
                        if (hiddenOnly) {
                            EmptyState("Only hidden files here.", "Everything in this folder starts with a dot.") {
                                BerthButton("Show hidden", onClick = onShowHidden)
                            }
                        } else {
                            EmptyState("Empty folder.", if (canWrite) "Upload something here or start a folder." else "Nothing to show.") {
                                if (canWrite) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BerthButton("Upload", onClick = onUpload)
                                        BerthButton("New folder", onClick = onNewFolder)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            items(entries, key = { it.path }) { entry ->
                EntryRow(
                    entry = entry,
                    selected = entry.path in selection,
                    now = now,
                    onClick = { onOpen(entry) },
                    onSelect = { onSelect(entry) },
                    modifier = Modifier.padding(horizontal = BerthSpace.screenMargin),
                )
            }
        }
    }
}

private val RefreshSlot = 44.dp

/**
 * One entry: the leading slot, name, and the Caption line with size or kind, modified time and mode.
 * The leading 36 dp slot is the selection control: the type glyph at rest, a check in a surface.4 disc
 * when selected, the same box either way so nothing shifts. It has its own tap, so tapping the glyph
 * selects and tapping the row opens; a long press on the row is the other way into selecting. The
 * selected row also steps up one surface, without the accent dot the Rail uses for the live session.
 */
@Composable
private fun EntryRow(entry: SftpEntry, selected: Boolean, now: Long, onClick: () -> Unit, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val (icon, tint) = when {
        entry.isSymlink -> BerthIcons.link to (if (entry.linkTarget == null) c.text3 else c.text2)
        entry.isDirectory -> BerthIcons.folder to c.text1
        else -> BerthIcons.file to c.text2
    }
    ListRow(
        title = entry.name,
        modifier = modifier,
        subtitle = entryCaption(entry, now),
        surface = if (selected) c.surface3 else c.surface2,
        onClick = onClick,
        onLongClick = onSelect,
        titleColor = if (entry.isHidden) c.text2 else c.text1,
        leading = {
            val interaction = remember { MutableInteractionSource() }
            val disc by animateColorAsState(if (selected) c.surface4 else Color.Transparent, tween(120), label = "disc")
            // A 36 dp slot in the layout; the touch target is 44 dp centred on it, the way IconAction overflows a short row.
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .requiredSize(44.dp)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Checkbox, onClick = onSelect)
                        .semantics { contentDescription = if (selected) "Deselect ${entry.name}" else "Select ${entry.name}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
                        if (selected) BerthIcon(BerthIcons.check, size = 18.dp, tint = c.text1)
                        else BerthIcon(icon, size = 22.dp, tint = tint)
                    }
                }
            }
        },
    )
}

/** Berth's pending dot in a surface.3 disc, rising into its slot with the pull and spinning once the listing is on its way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val fraction = state.distanceFraction.coerceIn(0f, 1f)
    if (fraction <= 0f && !refreshing) return
    Box(
        modifier
            .padding(bottom = 4.dp)
            .graphicsLayer { alpha = if (refreshing) 1f else fraction }
            .size(36.dp)
            .clip(CircleShape)
            .background(c.surface3),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            StatusDot(SessionState.CONNECTING, size = 10.dp)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(c.pending))
        }
    }
}

/** The listing failed: what went wrong in Berth's words, then the ways out. */
@Composable
private fun ListingError(
    error: SftpError,
    state: FilesState,
    onRetry: () -> Unit,
    onUp: () -> Unit,
    onHome: () -> Unit,
    onReconnect: (() -> Unit)?,
    noSession: Boolean = false,
) {
    val disconnected = error is SftpError.NotConnected || error is SftpError.Io
    val (title, body) = when (error) {
        is SftpError.NotFound -> "Nothing here." to "There is no folder at ${state.path}."
        is SftpError.PermissionDenied -> "No access." to "The server refused to list ${state.path} for this login."
        is SftpError.NotADirectory -> "That is a file." to "${SftpPaths.name(state.path)} is a file, not a folder."
        is SftpError.NotConnected ->
            if (noSession) "Not connected." to "Files rides a terminal's login. Connect one on this host and the browser uses it; there is no second sign-in."
            else "Not connected." to "Files rides the terminal's connection. Reconnect the session to browse."
        is SftpError.Io -> "The connection dropped." to "Reconnect the session and the listing comes back."
        is SftpError.Unsupported -> "Not available here." to (error.message ?: "The server does not offer sftp.")
        else -> "Couldn't list this folder." to (error.message ?: "")
    }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(40.dp))
        EmptyState(title, body) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (disconnected && onReconnect != null) BerthButton(if (noSession) "Connect" else "Reconnect", kind = ButtonKind.PRIMARY, onClick = onReconnect)
                else BerthButton("Try again", kind = ButtonKind.PRIMARY, onClick = onRetry)
                if (state.path != SftpPaths.ROOT) BerthButton("Up", onClick = onUp)
                if (state.home != null && state.home != state.path) BerthButton("Home", onClick = onHome)
            }
        }
    }
}

/** One line over the list's foot after an action; tap to dismiss, gone on its own after a moment. */
@Composable
private fun NoticeLine(notice: Notice?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val last = remember { mutableStateOf(notice) }
    if (notice != null) last.value = notice
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = BerthMotion.riseIn(),
        exit = BerthMotion.sinkOut(),
    ) {
        last.value?.let { n ->
            Box(
                Modifier
                    .padding(horizontal = BerthSpace.screenMargin, vertical = 12.dp)
                    .clip(RoundedCornerShape(BerthRadius.row))
                    .background(c.surface4)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(n.text, style = BerthType.label, color = if (n.isError) c.danger else c.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- the foot -------------------------------------------------------------------------------------

/** Both bands in the foot slot are this tall, so selection starting or ending crossfades without a size change. */
private val FootHeight = 64.dp

/**
 * The transfer that is moving, as a full-width band like the action band, with the 2 dp progress
 * line as its top edge: direction glyph, name, one Caption line of bytes, speed and how many wait,
 * the percentage, and Cancel. A folder keeps the percentage, the line's own measure, and its Caption
 * reads files done of total and speed; bytes of total belong to the sheet, so no line here carries
 * two `X of Y` pairs. A tap anywhere else opens the sheet, or brings back the question a waiting copy
 * is stopped on.
 */
@Composable
private fun TransferStrip(transfer: Transfer, others: Int, onCancel: () -> Unit, onClick: () -> Unit) {
    val c = Berth.colors
    Column(
        Modifier
            .fillMaxWidth()
            .height(FootHeight)
            .background(c.surface1)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        ProgressLine(
            fraction = if (transfer.state == TransferState.DONE) 1f else transfer.fraction,
            active = transfer.state == TransferState.RUNNING,
            color = transfer.lineColor,
            edge = true,
        )
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                BerthIcon(transfer.kind.icon, size = 20.dp, tint = c.text2)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(transfer.name, style = BerthType.bodyMedium, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(transferCaption(transfer, showHost = false, others = others.coerceAtLeast(0), compact = true), style = BerthType.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Text(transferTrailing(transfer), style = BerthType.caption, color = c.text2)
            Spacer(Modifier.width(4.dp))
            IconAction(onClick = onCancel, description = "Cancel ${transfer.name}") { BerthIcon(BerthIcons.close, size = 20.dp) }
        }
    }
}

/**
 * The contextual actions for the selected rows, one glyph over one Caption each; Delete in danger.
 * Download takes files and folders alike. Share takes one file and nothing else: a folder has no
 * single document to hand to another app. It keeps its name when it is off, as Rename and Copy path
 * do for two rows; the reason goes where the bar already tells reasons, a [Notice] on a tap of the
 * disabled item, through [onHint].
 */
@Composable
private fun SelectionActions(
    selected: List<SftpEntry>,
    canWrite: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit,
    onHint: (String) -> Unit,
) {
    val c = Berth.colors
    val single = selected.size == 1
    val files = selected.count { it.isRegularFile }
    val folders = selected.count { it.isDirectory }
    val shareHint = when {
        folders > 0 -> "Share takes one file; a folder has no single document to hand on. Download takes folders."
        else -> "Share takes one file at a time."
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(FootHeight)
            .background(c.surface1)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionItem(BerthIcons.download, "Download", enabled = files + folders > 0, onClick = onDownload, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.link, "Share", enabled = single && files == 1, onClick = onShare, modifier = Modifier.weight(1f), onDisabledClick = { onHint(shareHint) })
        ActionItem(BerthIcons.edit, "Rename", enabled = single && canWrite, onClick = onRename, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.lock, "Mode", enabled = selected.isNotEmpty() && canWrite, onClick = onChmod, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.copy, "Copy path", enabled = single, onClick = onCopyPath, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.trash, "Delete", enabled = selected.isNotEmpty() && canWrite, danger = true, onClick = onDelete, modifier = Modifier.weight(1f))
    }
}

/**
 * One action of the bar. Off, it is drawn muted and does nothing, unless [onDisabledClick] is given:
 * then a tap on it still lands and says why it is off, while the item itself stays disabled to
 * assistive tech through its state.
 */
@Composable
private fun ActionItem(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onDisabledClick: (() -> Unit)? = null,
) {
    val c = Berth.colors
    val tint = when {
        !enabled -> c.text3.copy(alpha = 0.6f)
        danger -> c.danger
        else -> c.text1
    }
    Column(
        modifier
            .clip(RoundedCornerShape(BerthRadius.row))
            .clickable(enabled = enabled || onDisabledClick != null, onClick = { if (enabled) onClick() else onDisabledClick?.invoke() })
            .then(if (!enabled && onDisabledClick != null) Modifier.semantics { disabled() } else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BerthIcon(icon, size = 22.dp, tint = tint)
        Text(label, style = BerthType.caption, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
