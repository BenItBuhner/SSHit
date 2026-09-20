package app.berth.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.berth.android.security.LockState
import app.berth.android.security.WindowSecurity
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.LocalWindowSecure
import app.berth.android.ui.deck.DeckEditorScreen
import app.berth.android.ui.diagnostics.CrashReportHost
import app.berth.android.ui.diagnostics.DiagnosticsScreen
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.hosts.QuickConnectSheet
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.layout.LocalWindowLayout
import app.berth.android.ui.layout.windowLayout
import app.berth.android.ui.prompts.NotificationPermissionHost
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.rail.Drawer
import app.berth.android.ui.rail.Library
import app.berth.android.ui.security.BerthClipboardLocals
import app.berth.android.ui.security.LockCover
import app.berth.android.ui.security.RemoteClipboardNoticeSheet
import app.berth.android.ui.settings.KnownHostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.snippets.SnippetsScreen
import app.berth.android.ui.stage.LocalDeckFit
import app.berth.android.ui.stage.LocalHapticLevel
import app.berth.android.ui.stage.PaneStageScreen
import app.berth.android.ui.stage.SessionSheet
import app.berth.android.ui.stage.ShortDeckFit
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.GroupEditorRequest
import app.berth.android.ui.tabs.LocalTabStripStyle
import app.berth.android.ui.tabs.NOTICE_BAR_MS
import app.berth.android.ui.tabs.NoticeBar
import app.berth.android.ui.tabs.ReopenBar
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabSheets
import app.berth.android.ui.tabs.TabStripStyle
import app.berth.android.ui.tabs.rememberTabUiState
import app.berth.android.ui.tunnels.TunnelsScreen
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.android.ui.themes.TerminalThemeEditorScreen
import app.berth.android.ui.themes.ThemeScope
import app.berth.android.ui.themes.ThemesScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable data object Stage : Screen
    @Serializable data class Hosts(val picker: Boolean = false) : Screen
    /** The editor for [hostId], or for a new host; [link] is the `ssh://` or `sftp://` link a new host starts from, or one whose forwards a saved host has to confirm. */
    @Serializable data class HostEditor(val hostId: String?, val link: String? = null) : Screen
    @Serializable data object Keys : Screen
    @Serializable data object Settings : Screen
    @Serializable data object KnownHosts : Screen
    /** One host's tunnels, or every host's when [hostId] is null. */
    @Serializable data class Tunnels(val hostId: String? = null) : Screen
    @Serializable data object Snippets : Screen
    @Serializable data object Themes : Screen
    @Serializable data class ThemeEditor(val themeId: String, val scope: ThemeScope = ThemeScope.AppDefault) : Screen
    @Serializable data object Appearance : Screen
    @Serializable data object DeckEditor : Screen
    @Serializable data object Diagnostics : Screen
}

/** The drawer's width as a sheet over the Stage (spec C7). */
private val DrawerWidth = 304.dp

/** The drawer's width standing as the rail on an expanded window (spec A12). */
private val RailWidth = 280.dp

/** The Stage's gutter on its rail side (spec A, the gap between panels): the terminal's first column is not against the rail's tonal edge. */
private val RailGutter = 12.dp

/**
 * The strip on a phone lying on its side (spec C23): 32 dp with 28 dp tabs, the swatch at 20 in 4 dp
 * of padding, so the terminal keeps the rows the row would have taken. Only the sizes change; the
 * style they change on is the one in force (the direction's skin, through [LocalTabStripStyle]),
 * so a phone turning does not turn the strip back to the default skin.
 */
private fun TabStripStyle.short() = copy(height = 32.dp, tabHeight = 28.dp, tabPadding = 4.dp, topReach = 2.dp, chipHeight = 20.dp)

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val theme by vm.interfaceTheme.collectAsState()
    val hapticLevel by vm.hapticLevel.collectAsState()
    val lock by vm.security.lock.state.collectAsState()
    BerthTheme(theme) {
        CompositionLocalProvider(LocalHapticLevel provides hapticLevel) {
            Box(Modifier.fillMaxSize().background(Berth.colors.surface0)) {
                // Nothing is composed until the lock is decided (the splash holds meanwhile). From
                // then on the shell stays composed, locked or not, so an edit in progress, an open
                // sheet and a running Stage are where they were when the lock lifts. While locked,
                // the cover hides it in this window and the lock window (LockActivity) lies over
                // both, since sheets, menus and prompts are windows of their own.
                if (lock != LockState.UNKNOWN) BerthClipboardLocals(vm.security.clipboard) { Shell(vm) }
                if (lock == LockState.LOCKED) LockCover()
            }
        }
    }
}

@Composable
private fun Shell(vm: AppViewModel) {
    val backStack = rememberNavBackStack(Screen.Stage)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val active by vm.activeTab.collectAsState()
    var sessionSheet by remember { mutableStateOf(false) }
    val tabUi = rememberTabUiState()

    LaunchedEffect(Unit) { vm.sessions.restore() }

    fun go(screen: Screen) {
        if (backStack.lastOrNull() != screen) backStack.add(screen)
    }
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    fun toStage() {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    fun openDrawer() = scope.launch { drawer.open() }
    fun closeDrawer() = scope.launch { drawer.close() }
    val tabActions = remember(vm, tabUi) { ShellTabActions(vm, tabUi, onActivated = { toStage() }) }

    // A notification opened a tab (spec C21): whatever screen or sheet was up gives way to the Stage.
    LaunchedEffect(vm) {
        vm.stageRequests.collect {
            sessionSheet = false
            toStage()
            if (drawer.isOpen) drawer.close()
        }
    }

    // An ssh:// or sftp:// link (AppViewModel.openLink): a tab opened, so the Stage; no host and a
    // plain link, so Quick connect prefilled; no host and more than a shell asked for, so the editor
    // prefilled from the link; a host but forwards it does not have, so that host's editor with them
    // pending; unreadable, so a notice saying what was wrong.
    val linkOutcome by vm.linkOutcome.collectAsState()
    var linkNotice by remember { mutableStateOf<String?>(null) }
    var quickConnectSpec by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(linkOutcome) {
        when (val outcome = linkOutcome) {
            null -> return@LaunchedEffect
            LinkOutcome.Staged -> {
                sessionSheet = false
                toStage()
                if (drawer.isOpen) drawer.close()
            }
            is LinkOutcome.QuickConnect -> quickConnectSpec = outcome.spec
            is LinkOutcome.NewHost -> go(Screen.HostEditor(null, link = outcome.raw))
            is LinkOutcome.ConfirmForwards -> go(Screen.HostEditor(outcome.hostId, link = outcome.raw))
            is LinkOutcome.Malformed -> linkNotice = outcome.reason
        }
        vm.clearLinkOutcome()
    }
    LaunchedEffect(linkNotice) {
        if (linkNotice == null) return@LaunchedEffect
        delay(NOTICE_BAR_MS)
        linkNotice = null
    }

    val onStage = backStack.lastOrNull() == Screen.Stage
    // What the window's size allows (spec A12, C23): decided here once, read below and by every sheet.
    val layout = windowLayout()
    val rail = layout.rail
    val securitySettings by vm.security.settings.collectAsState()
    BackHandler(enabled = drawer.isOpen) { closeDrawer() }

    val drawerContent: @Composable (width: Dp) -> Unit = { width ->
        Drawer(
            vm = vm,
            actions = tabActions,
            width = width,
            onGroupTap = { id ->
                vm.setWorkspace(id)
                toStage()
                closeDrawer()
            },
            onNewGroup = {
                closeDrawer()
                tabUi.groupEditor = GroupEditorRequest.Create(thenNewTab = true)
            },
            onLibrary = { lib ->
                closeDrawer()
                go(
                    when (lib) {
                        Library.HOSTS -> Screen.Hosts()
                        Library.KEYS -> Screen.Keys
                        Library.TUNNELS -> Screen.Tunnels()
                        Library.SNIPPETS -> Screen.Snippets
                        Library.SETTINGS -> Screen.Settings
                    },
                )
            },
        )
    }
    val screens: @Composable () -> Unit = {
        NavDisplay(
            backStack = backStack,
            onBack = { back() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            transitionSpec = { slideInHorizontally(tween(220)) { it / 6 } + fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            popTransitionSpec = { fadeIn(tween(160)) togetherWith slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(160)) },
            predictivePopTransitionSpec = { fadeIn(tween(160)) togetherWith slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(160)) },
            entryProvider = { key ->
                when (key) {
                    is Screen.Stage -> NavEntry(key) {
                        // With the drawer standing as a rail there is nothing to open; with two panes' width the pane layer hosts the Stage.
                        val onOpenDrawer: (() -> Unit)? = if (rail) null else { { openDrawer() } }
                        if (layout.panes) {
                            PaneStageScreen(
                                vm = vm,
                                actions = tabActions,
                                onOpenDrawer = onOpenDrawer,
                                onOpenSessionSheet = { sessionSheet = true },
                                onEditHost = { go(Screen.HostEditor(it)) },
                                onOpenDeckEditor = { go(Screen.DeckEditor) },
                            )
                        } else {
                            StageScreen(
                                vm = vm,
                                tab = active,
                                actions = tabActions,
                                onOpenDrawer = onOpenDrawer,
                                onOpenSessionSheet = { sessionSheet = true },
                                onEditHost = { go(Screen.HostEditor(it)) },
                                onOpenDeckEditor = { go(Screen.DeckEditor) },
                            )
                        }
                    }
                    is Screen.Hosts -> NavEntry(key) {
                        HostsScreen(
                            vm = vm,
                            onConnect = { host ->
                                vm.open(host)
                                toStage()
                            },
                            onFiles = { host ->
                                vm.openFilesForHost(host)
                                toStage()
                            },
                            onTunnels = { host ->
                                vm.openTunnels(host)
                                toStage()
                            },
                            onAddHost = { go(Screen.HostEditor(null)) },
                            onEditHost = { go(Screen.HostEditor(it)) },
                            onBack = { back() },
                            onOpenDrawer = null,
                            onKnownHosts = { go(Screen.KnownHosts) },
                            picker = key.picker,
                        )
                    }
                    is Screen.HostEditor -> NavEntry(key) {
                        HostEditorScreen(vm = vm, hostId = key.hostId, onDone = { back() }, link = key.link)
                    }
                    is Screen.Keys -> NavEntry(key) { KeysScreen(vm, onBack = { back() }) }
                    is Screen.Settings -> NavEntry(key) {
                        SettingsScreen(
                            vm,
                            onBack = { back() },
                            onKnownHosts = { go(Screen.KnownHosts) },
                            onThemes = { go(Screen.Themes) },
                            onAppearance = { go(Screen.Appearance) },
                            onDeckEditor = { go(Screen.DeckEditor) },
                            onDiagnostics = { go(Screen.Diagnostics) },
                        )
                    }
                    is Screen.Diagnostics -> NavEntry(key) { DiagnosticsScreen(vm.reports, onBack = { back() }) }
                    is Screen.KnownHosts -> NavEntry(key) { KnownHostsScreen(vm, onBack = { back() }) }
                    is Screen.Tunnels -> NavEntry(key) { TunnelsScreen(vm, hostId = key.hostId, onBack = { back() }) }
                    is Screen.Snippets -> NavEntry(key) { SnippetsScreen(vm, onBack = { back() }) }
                    is Screen.Themes -> NavEntry(key) { ThemesScreen(vm, onBack = { back() }, onOpen = { go(Screen.ThemeEditor(it)) }) }
                    is Screen.ThemeEditor -> NavEntry(key) {
                        TerminalThemeEditorScreen(
                            vm,
                            themeId = key.themeId,
                            scope = key.scope,
                            onDone = { back() },
                            // Duplicating or saving a copy of a stock theme continues in the new theme without growing the stack.
                            onOpenTheme = { id -> backStack[backStack.lastIndex] = Screen.ThemeEditor(id, key.scope) },
                        )
                    }
                    is Screen.Appearance -> NavEntry(key) { AppearanceScreen(vm, onBack = { back() }) }
                    is Screen.DeckEditor -> NavEntry(key) { DeckEditorScreen(vm, onBack = { back() }) }
                    else -> NavEntry(key) {
                        Spacer(Modifier.statusBarsPadding().height(48.dp))
                        EmptyState("Nothing here.", "This screen has not been built yet.") {
                            BerthButton("Back", onClick = { back() }, kind = ButtonKind.PRIMARY)
                        }
                    }
                }
            },
        )
    }

    // A phone on its side (spec C23): the strip and the Deck give height back to the terminal.
    val stripStyle = LocalTabStripStyle.current.let { if (layout.shortLandscape) it.short() else it }
    val deckFit = if (layout.shortLandscape) ShortDeckFit else LocalDeckFit.current
    // Every window Berth opens over this one (a sheet as a dialog) blocks capture when this one does.
    val secure = securitySettings?.let(WindowSecurity::secure) ?: false
    CompositionLocalProvider(
        LocalWindowLayout provides layout,
        LocalTabStripStyle provides stripStyle,
        LocalDeckFit provides deckFit,
        LocalWindowSecure provides secure,
    ) {
        if (rail) {
            // Expanded width (spec C7, A12): the drawer stands as a 280 dp rail beside the screens, always
            // in view, with the Stage's gutter between its tonal edge and the terminal's first column.
            Row(Modifier.fillMaxSize()) {
                drawerContent(RailWidth)
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = RailGutter)) { screens() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = onStage || drawer.isOpen,
                scrimColor = Berth.colors.scrim,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = Color.Transparent, drawerShape = androidx.compose.ui.graphics.RectangleShape, drawerTonalElevation = 0.dp) {
                        drawerContent(DrawerWidth)
                    }
                },
                content = screens,
            )
        }
    }

    val sheetTab = active
    if (sessionSheet && sheetTab != null) {
        SessionSheet(
            vm = vm,
            tab = sheetTab,
            onDismiss = { sessionSheet = false },
            onSwitch = { vm.setActive(it) },
            onEditHost = { go(Screen.HostEditor(it)) },
            onNewSession = { tabActions.newTab() },
            onOpenTunnels = { hostId ->
                sessionSheet = false
                go(Screen.Tunnels(hostId))
            },
            // The host's Files tab, opened or brought on stage; and back to the terminal it rides.
            onOpenFiles = tabActions::openFiles,
            onOpenTerminal = tabActions::openTerminal,
        )
    }
    TabSheets(vm = vm, ui = tabUi, actions = tabActions, onAddHost = { go(Screen.HostEditor(null)) })
    // A plain link's landing (spec, Deep links): Quick connect over whatever is up, the link's address in its field.
    quickConnectSpec?.let { spec ->
        QuickConnectSheet(
            vm = vm,
            initialSpec = spec,
            onDismiss = { quickConnectSpec = null },
            onConnected = {
                quickConnectSpec = null
                sessionSheet = false
                toStage()
                if (drawer.isOpen) closeDrawer()
            },
        )
    }
    Box(Modifier.fillMaxSize()) {
        ReopenBar(ui = tabUi, vm = vm, modifier = Modifier.align(Alignment.BottomCenter))
        // The link notice is kept through the bar's exit, so the text does not blank as it slides away.
        // It is the parser's reason alone (`The IPv6 address is missing its closing bracket.`): the
        // bar has one line, and the reason says it was the link.
        val shownNotice = remember { mutableStateOf(linkNotice) }
        if (linkNotice != null) shownNotice.value = linkNotice
        NoticeBar(
            visible = linkNotice != null,
            text = shownNotice.value ?: "",
            action = "OK",
            onAction = { linkNotice = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    PromptHost(vm.prompts, onOpenKnownHosts = { sessionSheet = false; go(Screen.KnownHosts) })
    NotificationPermissionHost(vm.notifier)
    RemoteClipboardNoticeSheet(vm.security.remoteClipboard)
    CrashReportHost(vm.reports)
}
