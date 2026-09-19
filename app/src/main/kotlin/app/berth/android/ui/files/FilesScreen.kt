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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.files.FilesBrowser
import app.berth.android.files.FilesState
import app.berth.android.files.Notice
import app.berth.android.files.Transfer
import app.berth.android.files.TransferManager
import app.berth.android.files.TransferState
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.stage.LocalHapticLevel
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.FilesSort
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.SessionState
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
    /** Regular files among [entries] go to a document (one) or a picked folder (several). */
    val download: (entries: List<SftpEntry>) -> Unit,
    val share: (entry: SftpEntry) -> Unit,
    /** Picks documents and uploads them into the folder being shown. */
    val upload: () -> Unit,
) {
    companion object {
        val None = FilesActions({}, {}, {})
    }
}

/**
 * Files for one session (the isolated entry point that becomes the Files tab kind): resolves the
 * session and its browser, wires the document pickers to the transfer queue, and shows [FilesPane].
 * [sessionId] null means the active session.
 */
@Composable
fun FilesScreen(vm: AppViewModel, sessionId: String?, onBack: () -> Unit, onNewSession: () -> Unit, modifier: Modifier = Modifier) {
    val active by vm.activeSession.collectAsState()
    val session = sessionId?.let { vm.sessions.get(it) } ?: active
    val browser = session?.let { s -> remember(s.id) { vm.files.browser(s.id) } }
    if (session == null || browser == null) {
        Column(modifier.fillMaxSize().background(Berth.colors.surface0).statusBarsPadding().navigationBarsPadding()) {
            ScreenHeader("Files", onBack = onBack)
            Spacer(Modifier.height(48.dp))
            EmptyState("Files rides a session.", "Open a host and its terminal; the browser uses that login, so there is no second sign-in.") {
                BerthButton("New session", kind = ButtonKind.PRIMARY, onClick = onNewSession)
            }
        }
        return
    }
    val record by session.record.collectAsState()
    val prefs by vm.files.prefs.collectAsState()
    val transfers by vm.files.transfers.transfers.collectAsState()
    val state by browser.state.collectAsState()
    val queue = vm.files.transfers

    // The browser opened its channel on a connection that may since have dropped; a reconnect re-lists.
    LaunchedEffect(record.state) {
        val error = state.error
        if (record.state == SessionState.LIVE && (error is SftpError.NotConnected || error is SftpError.Io)) browser.refresh()
    }

    var pendingDocument by remember { mutableStateOf<SftpEntry?>(null) }
    var pendingBatch by remember { mutableStateOf<List<SftpEntry>>(emptyList()) }
    val createDocument = rememberLauncherForActivityResult(CreateNamedDocument()) { uri ->
        val entry = pendingDocument
        pendingDocument = null
        if (uri != null && entry != null) queue.download(session, entry, uri)
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val batch = pendingBatch
        pendingBatch = emptyList()
        if (uri != null && batch.isNotEmpty()) queue.downloadInto(session, batch, uri)
    }
    val openDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) queue.upload(session, uris, browser.state.value.path)
    }
    val actions = FilesActions(
        download = { entries ->
            val files = entries.filter { it.isRegularFile }
            when {
                files.isEmpty() -> browser.post(Notice("Only files can be downloaded.", isError = true))
                files.size == 1 -> {
                    val file = files.single()
                    pendingDocument = file
                    createDocument.launch(file.name to TransferManager.mimeFor(file.name))
                }
                else -> {
                    pendingBatch = files
                    openTree.launch(null)
                }
            }
        },
        share = { entry -> queue.share(session, entry) },
        upload = { openDocuments.launch(arrayOf("*/*")) },
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
        terminalCwd = record.cwd,
        recent = prefs.recentFor(session.host.id),
        actions = actions,
        onBack = onBack,
        modifier = modifier,
        connected = record.state == SessionState.LIVE,
        onReconnect = { vm.reconnect(session.id) },
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

private sealed interface FilesSheetKind {
    data object Path : FilesSheetKind
    data object NewFolder : FilesSheetKind
    data class Rename(val entry: SftpEntry) : FilesSheetKind
    data class Delete(val entries: List<SftpEntry>) : FilesSheetKind
    data class Chmod(val entries: List<SftpEntry>) : FilesSheetKind
    data object Transfers : FilesSheetKind
}

/**
 * The single-pane browser: header with upload, transfers and more; the breadcrumb; sort and hidden
 * chips; the listing under pull to refresh; and, when rows are selected by a long press, a
 * contextual action row in place of the transfer strip. Everything the pane needs comes in, so a
 * fake file system renders it as faithfully as a live one.
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

    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selected = remember(selection, state.entries) { state.entries.filter { it.path in selection } }
    LaunchedEffect(state.path, state.entries) {
        // A re-list drops rows that are gone; a new folder drops the selection outright.
        if (selection.isNotEmpty()) {
            val present = state.entries.mapTo(HashSet()) { it.path }
            selection = selection.filterTo(HashSet()) { it in present }
        }
    }
    LaunchedEffect(state.path) { listState.scrollToItem(0) }

    var sheet by remember { mutableStateOf<FilesSheetKind?>(null) }
    var viewer by remember { mutableStateOf<SftpEntry?>(null) }
    var viewerContent by remember { mutableStateOf<ViewerContent>(ViewerContent.Loading) }
    var menu by remember { mutableStateOf(false) }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(enabled = selection.isEmpty() && state.canGoBack) { browser.back() }

    fun copyPath(path: String) {
        clipboard.setText(AnnotatedString(path))
        browser.post(Notice("Copied $path", isError = false))
    }
    fun open(entry: SftpEntry) {
        when {
            entry.isDirectory -> browser.navigate(entry.path)
            entry.isRegularFile -> viewer = entry
            entry.isSymlink -> browser.post(Notice("${entry.name} points at nothing.", isError = true))
            else -> browser.post(Notice("${entry.name} is a special file; there is nothing to open.", isError = true))
        }
    }
    fun toggle(entry: SftpEntry) {
        selection = if (entry.path in selection) selection - entry.path else selection + entry.path
    }
    fun select(entry: SftpEntry) {
        if (hapticLevel != HapticLevel.OFF) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        toggle(entry)
    }
    fun selectAll() {
        selection = entries.mapTo(HashSet()) { it.path }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (selection.isEmpty()) {
            ScreenHeader(
                title = "Files \u00B7 $hostName",
                onBack = onBack,
                actions = {
                    IconAction(onClick = actions.upload, description = "Upload", enabled = connected) {
                        BerthIcon(BerthIcons.upload, tint = if (connected) c.text2 else c.text3)
                    }
                    TransfersAction(active = transfers.count { it.state.isActive }) { sheet = FilesSheetKind.Transfers }
                    Box {
                        IconAction(onClick = { menu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                            MenuItem("New folder", enabled = connected && state.error == null) { menu = false; sheet = FilesSheetKind.NewFolder }
                            if (terminalCwd != null) {
                                MenuItem("Terminal directory", enabled = terminalCwd != state.path) { menu = false; browser.navigate(terminalCwd) }
                            }
                            MenuItem("Copy path") { menu = false; copyPath(state.path) }
                            MenuItem("Select all", enabled = entries.isNotEmpty()) { menu = false; selectAll() }
                            MenuItem("Refresh") { menu = false; browser.refresh() }
                        }
                    }
                },
            )
        } else {
            SelectionHeader(
                count = selection.size,
                allSelected = selection.size >= entries.size,
                onClose = { selection = emptySet() },
                onSelectAll = { if (selection.size >= entries.size) selection = emptySet() else selectAll() },
            )
        }
        Breadcrumb(
            path = state.path,
            onJump = { browser.navigate(it) },
            onEdit = { sheet = FilesSheetKind.Path },
            onCopy = { copyPath(state.path) },
        )
        FilterRow(prefs, onSort, onShowHidden, hiddenCount = state.entries.count { it.isHidden })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = state.error
            when {
                error != null -> ListingError(
                    error = error,
                    state = state,
                    onRetry = browser::refresh,
                    onUp = browser::up,
                    onHome = browser::home,
                    onReconnect = onReconnect,
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
                    onOpen = { entry -> if (selection.isEmpty()) open(entry) else toggle(entry) },
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
        AnimatedVisibility(visible = selection.isEmpty() && moving != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            lastMoving.value?.let { t ->
                TransferStrip(t, others = transfers.count { it.state.isActive } - 1, onCancel = { onCancelTransfer(t.id) }, onClick = { sheet = FilesSheetKind.Transfers })
            }
        }
        AnimatedVisibility(visible = selection.isNotEmpty(), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            SelectionActions(
                selected = selected,
                canWrite = connected,
                onDownload = { actions.download(selected) },
                onShare = { selected.singleOrNull()?.let(actions.share) },
                onRename = { selected.singleOrNull()?.let { sheet = FilesSheetKind.Rename(it) } },
                onChmod = { sheet = FilesSheetKind.Chmod(selected) },
                onCopyPath = { selected.singleOrNull()?.let { copyPath(it.path) } },
                onDelete = { sheet = FilesSheetKind.Delete(selected) },
            )
        }
    }

    val names = remember(state.entries) { state.entries.mapTo(HashSet()) { it.name } }
    when (val s = sheet) {
        null -> Unit
        FilesSheetKind.Path -> PathEditorSheet(
            current = state.path,
            home = state.home,
            terminalCwd = terminalCwd,
            recent = recent,
            onGo = { browser.navigate(it) },
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
        is FilesSheetKind.Rename -> NameSheet(
            title = "Rename",
            folder = SftpPaths.parent(s.entry.path),
            initial = s.entry.name,
            confirm = "Rename",
            existing = names,
            onConfirm = { browser.rename(s.entry, it); selection = emptySet() },
            onDismiss = { sheet = null },
        )
        is FilesSheetKind.Delete -> DeleteSheet(
            entries = s.entries,
            onConfirm = { browser.delete(s.entries); selection = emptySet() },
            onDismiss = { sheet = null },
        )
        is FilesSheetKind.Chmod -> ChmodSheet(
            entries = s.entries,
            onApply = { browser.chmod(s.entries, it); selection = emptySet() },
            onDismiss = { sheet = null },
        )
        FilesSheetKind.Transfers -> TransferSheet(
            transfers = transfers,
            onCancel = onCancelTransfer,
            onClearFinished = onClearFinished,
            onDismiss = { sheet = null },
        )
    }
    viewer?.let { entry ->
        LaunchedEffect(entry.path) {
            viewerContent = ViewerContent.Loading
            viewerContent = if (entry.size > FilesBrowser.VIEWER_SKIP_BYTES) {
                ViewerContent.TooLarge(entry.size)
            } else {
                browser.readText(entry).fold(
                    onSuccess = { read ->
                        when (read) {
                            is TextRead.Text -> ViewerContent.Text(read)
                            is TextRead.Binary -> ViewerContent.Binary(read.size)
                        }
                    },
                    onFailure = { ViewerContent.Failed(it.message ?: "Couldn't read the file.") },
                )
            }
        }
        FileViewerSheet(
            entry = entry,
            content = viewerContent,
            onDownload = { actions.download(listOf(entry)) },
            onShare = { actions.share(entry) },
            onCopyPath = { copyPath(entry.path) },
            onDismiss = { viewer = null },
        )
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

/** The header while rows are selected: close at the leading edge, the count as the title, All or None trailing. */
@Composable
private fun SelectionHeader(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    val c = Berth.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = BerthSpace.screenMargin - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(onClick = onClose, description = "Clear selection") { BerthIcon(BerthIcons.close) }
        Spacer(Modifier.width(4.dp))
        Text(if (count == 1) "1 selected" else "$count selected", style = BerthType.title, color = c.text1, modifier = Modifier.weight(1f), maxLines = 1)
        BerthButton(if (allSelected) "None" else "All", kind = ButtonKind.TEXT, onClick = onSelectAll)
    }
}

/**
 * The path as crumbs: ancestors in text.2, the current folder in text.1, a chevron between. A tap
 * jumps; the current crumb and the pencil open the editor; a long press copies the path. Long
 * paths scroll and keep their tail in view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Breadcrumb(path: String, onJump: (String) -> Unit, onEdit: () -> Unit, onCopy: () -> Unit) {
    val c = Berth.colors
    val crumbs = remember(path) { SftpPaths.crumbs(path) }
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = BerthSpace.screenMargin - 6.dp, end = BerthSpace.screenMargin - 8.dp)
            .semantics { contentDescription = "Path $path" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, (label, full) ->
                val last = i == crumbs.lastIndex
                if (i > 0) BerthIcon(BerthIcons.chevronRight, size = 14.dp, tint = c.text3)
                Text(
                    label,
                    style = if (last) BerthType.bodyMedium else BerthType.body,
                    color = if (last) c.text1 else c.text2,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(BerthRadius.swatch))
                        .combinedClickable(onClick = { if (last) onEdit() else onJump(full) }, onLongClick = onCopy)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
        IconAction(onClick = onEdit, description = "Edit path") { BerthIcon(BerthIcons.edit, size = 20.dp) }
    }
}

/** Sort chips, the active one carrying its direction, and the hidden-files chip at the trailing edge. */
@Composable
private fun FilterRow(prefs: FilesPrefs, onSort: (FilesSort) -> Unit, onShowHidden: (Boolean) -> Unit, hiddenCount: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BerthSpace.screenMargin)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (sort in FilesSort.entries) {
            val active = prefs.sort == sort
            Chip(
                text = if (active) sort.label + (if (prefs.ascending) " \u2191" else " \u2193") else sort.label,
                selected = active,
                onClick = { onSort(sort) },
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
    Box(
        Modifier
            .fillMaxSize()
            .pullToRefresh(isRefreshing = refreshing, state = pull, onRefresh = onRefresh),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
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
                    onLongClick = { onSelect(entry) },
                    modifier = Modifier.padding(horizontal = BerthSpace.screenMargin),
                )
            }
        }
        RefreshIndicator(pull, refreshing, Modifier.align(Alignment.TopCenter))
    }
}

/** One entry: type glyph, name, and the Caption line with size or kind, modified time and mode. */
@Composable
private fun EntryRow(entry: SftpEntry, selected: Boolean, now: Long, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
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
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        titleColor = if (entry.isHidden) c.text2 else c.text1,
        leading = {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                BerthIcon(icon, size = 22.dp, tint = tint)
            }
        },
    )
}

/** Berth's pending dot rides down with the pull inside a surface.3 disc and spins once the listing is on its way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIndicator(state: PullToRefreshState, refreshing: Boolean, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val fraction = state.distanceFraction.coerceIn(0f, 1f)
    if (fraction <= 0f && !refreshing) return
    Box(
        modifier
            .padding(top = 4.dp)
            .graphicsLayer {
                translationY = (fraction - 1f) * 44.dp.toPx()
                alpha = fraction
            }
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
) {
    val disconnected = error is SftpError.NotConnected || error is SftpError.Io
    val (title, body) = when (error) {
        is SftpError.NotFound -> "Nothing here." to "There is no folder at ${state.path}."
        is SftpError.PermissionDenied -> "No access." to "The server refused to list ${state.path} for this login."
        is SftpError.NotADirectory -> "That is a file." to "${SftpPaths.name(state.path)} is a file, not a folder."
        is SftpError.NotConnected -> "Not connected." to "Files rides the terminal's connection. Reconnect the session to browse."
        is SftpError.Io -> "The connection dropped." to "Reconnect the session and the listing comes back."
        is SftpError.Unsupported -> "Not available here." to (error.message ?: "The server does not offer sftp.")
        else -> "Couldn't list this folder." to (error.message ?: "")
    }
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(40.dp))
        EmptyState(title, body) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (disconnected && onReconnect != null) BerthButton("Reconnect", kind = ButtonKind.PRIMARY, onClick = onReconnect)
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
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
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

/** The transfer that is moving, as its row from the sheet; a tap opens the sheet, the trailing pill counts the rest. */
@Composable
private fun TransferStrip(transfer: Transfer, others: Int, onCancel: () -> Unit, onClick: () -> Unit) {
    val c = Berth.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BerthSpace.screenMargin)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TransferRow(
            transfer,
            onCancel = onCancel,
            modifier = Modifier
                .weight(1f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            surface = c.surface2,
        )
        if (others > 0) Pill("+$others", color = c.surface3)
    }
}

/** The contextual actions for the selected rows, one glyph over one Caption each; Delete in danger. */
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
) {
    val c = Berth.colors
    val single = selected.size == 1
    val files = selected.count { it.isRegularFile }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface1)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionItem(BerthIcons.download, "Download", enabled = files > 0, onClick = onDownload, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.link, "Share", enabled = single && files == 1, onClick = onShare, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.edit, "Rename", enabled = single && canWrite, onClick = onRename, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.lock, "chmod", enabled = selected.isNotEmpty() && canWrite, onClick = onChmod, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.copy, "Copy path", enabled = single, onClick = onCopyPath, modifier = Modifier.weight(1f))
        ActionItem(BerthIcons.trash, "Delete", enabled = selected.isNotEmpty() && canWrite, danger = true, onClick = onDelete, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionItem(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    val c = Berth.colors
    val tint = when {
        !enabled -> c.text3.copy(alpha = 0.6f)
        danger -> c.danger
        else -> c.text1
    }
    Column(
        modifier
            .clip(RoundedCornerShape(BerthRadius.row))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BerthIcon(icon, size = 22.dp, tint = tint)
        Text(label, style = BerthType.caption, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
