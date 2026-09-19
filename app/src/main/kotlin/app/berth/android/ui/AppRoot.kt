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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.deck.DeckEditorScreen
import app.berth.android.ui.files.FilesScreen
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.rail.Drawer
import app.berth.android.ui.rail.Library
import app.berth.android.ui.settings.KnownHostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.snippets.SnippetsScreen
import app.berth.android.ui.stage.LocalHapticLevel
import app.berth.android.ui.stage.SessionSheet
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.GroupEditorRequest
import app.berth.android.ui.tabs.ReopenBar
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabSheets
import app.berth.android.ui.tabs.rememberTabUiState
import app.berth.android.ui.tunnels.TunnelsScreen
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.android.ui.themes.TerminalThemeEditorScreen
import app.berth.android.ui.themes.ThemeScope
import app.berth.android.ui.themes.ThemesScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable data object Stage : Screen
    @Serializable data class Hosts(val picker: Boolean = false) : Screen
    @Serializable data class HostEditor(val hostId: String?) : Screen
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
    /** One session's SFTP browser, or the active session's when [sessionId] is null; becomes the Files tab kind. */
    @Serializable data class Files(val sessionId: String? = null) : Screen
}

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val theme by vm.interfaceTheme.collectAsState()
    val hapticLevel by vm.hapticLevel.collectAsState()
    BerthTheme(theme) {
        CompositionLocalProvider(LocalHapticLevel provides hapticLevel) {
            Box(Modifier.fillMaxSize().background(Berth.colors.surface0)) {
                Shell(vm)
            }
        }
    }
}

@Composable
private fun Shell(vm: AppViewModel) {
    val backStack = rememberNavBackStack(Screen.Stage)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val active by vm.activeSession.collectAsState()
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

    val onStage = backStack.lastOrNull() == Screen.Stage
    BackHandler(enabled = drawer.isOpen) { closeDrawer() }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = onStage || drawer.isOpen,
        scrimColor = Berth.colors.scrim,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color.Transparent, drawerShape = androidx.compose.ui.graphics.RectangleShape, drawerTonalElevation = 0.dp) {
                Drawer(
                    vm = vm,
                    actions = tabActions,
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
                                Library.FILES -> Screen.Files()
                                Library.SNIPPETS -> Screen.Snippets
                                Library.SETTINGS -> Screen.Settings
                            },
                        )
                    },
                )
            }
        },
    ) {
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
                        StageScreen(
                            vm = vm,
                            session = active,
                            actions = tabActions,
                            onOpenDrawer = { openDrawer() },
                            onOpenSessionSheet = { sessionSheet = true },
                            onEditHost = { go(Screen.HostEditor(it)) },
                            onOpenDeckEditor = { go(Screen.DeckEditor) },
                        )
                    }
                    is Screen.Hosts -> NavEntry(key) {
                        HostsScreen(
                            vm = vm,
                            onConnect = { host ->
                                vm.open(host)
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
                        HostEditorScreen(vm = vm, hostId = key.hostId, onDone = { back() })
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
                        )
                    }
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
                    is Screen.Files -> NavEntry(key) {
                        FilesScreen(vm, sessionId = key.sessionId, onBack = { back() }, onNewSession = { go(Screen.Hosts(picker = true)) })
                    }
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

    val sheetSession = active
    if (sessionSheet && sheetSession != null) {
        SessionSheet(
            vm = vm,
            session = sheetSession,
            onDismiss = { sessionSheet = false },
            onSwitch = { vm.setActive(it) },
            onEditHost = { go(Screen.HostEditor(it)) },
            onNewSession = { tabActions.newTab() },
            onOpenTunnels = { hostId ->
                sessionSheet = false
                go(Screen.Tunnels(hostId))
            },
            onOpenFiles = { sessionId ->
                sessionSheet = false
                go(Screen.Files(sessionId))
            },
        )
    }
    TabSheets(vm = vm, ui = tabUi, actions = tabActions, onAddHost = { go(Screen.HostEditor(null)) })
    Box(Modifier.fillMaxSize()) {
        ReopenBar(ui = tabUi, vm = vm, modifier = Modifier.align(Alignment.BottomCenter))
    }
    PromptHost(vm.prompts, onOpenKnownHosts = { sessionSheet = false; go(Screen.KnownHosts) })
}
