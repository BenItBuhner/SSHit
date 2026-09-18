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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.hosts.QuickConnectSheet
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.rail.Library
import app.berth.android.ui.rail.Rail
import app.berth.android.ui.settings.KnownHostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.SessionSheet
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable data object Stage : Screen
    @Serializable data class Hosts(val picker: Boolean = false) : Screen
    @Serializable data class HostEditor(val hostId: String?) : Screen
    @Serializable data object Keys : Screen
    @Serializable data object Settings : Screen
    @Serializable data object KnownHosts : Screen
}

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val theme by vm.interfaceTheme.collectAsState()
    BerthTheme(theme) {
        Box(Modifier.fillMaxSize().background(Berth.colors.surface0)) {
            Shell(vm)
            PromptHost(vm.prompts)
        }
    }
}

@Composable
private fun Shell(vm: AppViewModel) {
    val backStack = rememberNavBackStack(Screen.Stage)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val active by vm.activeSession.collectAsState()
    val workspaceSessions by vm.workspaceSessions.collectAsState()
    var sessionSheet by remember { mutableStateOf(false) }
    var quickConnect by remember { mutableStateOf(false) }

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
    fun openRail() = scope.launch { drawer.open() }
    fun closeRail() = scope.launch { drawer.close() }
    fun step(forward: Boolean) {
        val list = workspaceSessions
        if (list.size < 2) return
        val i = list.indexOfFirst { it.id == active?.id }
        val next = if (i < 0) 0 else (i + (if (forward) 1 else -1) + list.size) % list.size
        vm.setActive(list[next].id)
    }

    val onStage = backStack.lastOrNull() == Screen.Stage
    BackHandler(enabled = drawer.isOpen) { closeRail() }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = onStage || drawer.isOpen,
        scrimColor = Berth.colors.scrim,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color.Transparent, drawerShape = androidx.compose.ui.graphics.RectangleShape, drawerTonalElevation = 0.dp) {
                Rail(
                    vm = vm,
                    onSessionTap = { id ->
                        vm.setActive(id)
                        toStage()
                        closeRail()
                    },
                    onNewSession = {
                        closeRail()
                        go(Screen.Hosts(picker = true))
                    },
                    onLibrary = { lib ->
                        closeRail()
                        go(
                            when (lib) {
                                Library.HOSTS -> Screen.Hosts()
                                Library.KEYS -> Screen.Keys
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
                        val session = active
                        if (session != null) {
                            StageScreen(
                                vm = vm,
                                session = session,
                                onOpenRail = { openRail() },
                                onOpenSessionSheet = { sessionSheet = true },
                                onEditHost = { go(Screen.HostEditor(it)) },
                                onNextSession = { step(true) },
                                onPreviousSession = { step(false) },
                            )
                        } else {
                            HostsScreen(
                                vm = vm,
                                onConnect = { host -> vm.open(host) },
                                onAddHost = { go(Screen.HostEditor(null)) },
                                onEditHost = { go(Screen.HostEditor(it)) },
                                onBack = null,
                                onOpenRail = { openRail() },
                                onKnownHosts = { go(Screen.KnownHosts) },
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
                            onAddHost = { go(Screen.HostEditor(null)) },
                            onEditHost = { go(Screen.HostEditor(it)) },
                            onBack = { back() },
                            onOpenRail = null,
                            onKnownHosts = { go(Screen.KnownHosts) },
                            picker = key.picker,
                        )
                    }
                    is Screen.HostEditor -> NavEntry(key) {
                        HostEditorScreen(vm = vm, hostId = key.hostId, onDone = { back() })
                    }
                    is Screen.Keys -> NavEntry(key) { KeysScreen(vm, onBack = { back() }) }
                    is Screen.Settings -> NavEntry(key) { SettingsScreen(vm, onBack = { back() }, onKnownHosts = { go(Screen.KnownHosts) }) }
                    is Screen.KnownHosts -> NavEntry(key) { KnownHostsScreen(vm, onBack = { back() }) }
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
            onNewSession = { go(Screen.Hosts(picker = true)) },
        )
    }
    if (quickConnect) {
        QuickConnectSheet(vm, onDismiss = { quickConnect = false }, onConnected = { quickConnect = false; toStage() })
    }
}
