package app.berth.android.ui.stage

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.FailedHop
import app.berth.android.session.FilesTab
import app.berth.android.session.ManagedTab
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.BerthMotion
import app.berth.android.ui.a11y.TerminalAccessibility
import app.berth.android.ui.a11y.TerminalAnnouncer
import app.berth.android.ui.a11y.reachingClickable
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.byId
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.Pill
import app.berth.android.ui.files.FilesTabBody
import app.berth.android.ui.keyboard.FoldDeckOnHardwareKeyboard
import app.berth.android.ui.keyboard.HardwareShortcuts
import app.berth.android.ui.keyboard.KeepStageFocus
import app.berth.android.ui.keyboard.LocalWindowFocus
import app.berth.android.ui.keyboard.ShortcutSheet
import app.berth.android.ui.keyboard.LocalPaneActions
import app.berth.android.ui.keyboard.StageFocus
import app.berth.android.ui.keyboard.StageRegion
import app.berth.android.ui.keyboard.StageShortcutActions
import app.berth.android.ui.keyboard.WindowFocus
import app.berth.android.ui.keyboard.compactForHardwareKeyboard
import app.berth.android.ui.keyboard.rememberHardwareKeyboardAttached
import app.berth.android.ui.keyboard.rememberStageFocus
import app.berth.android.ui.keyboard.stageRegion
import app.berth.android.ui.keyboard.windowFocus
import app.berth.android.ui.snippets.PendingSnippet
import app.berth.android.ui.snippets.SnippetRunSheet
import app.berth.android.ui.tabs.CountTile
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabHeader
import app.berth.android.ui.tabs.TabShortcuts
import app.berth.android.ui.tabs.rememberTabStripState
import app.berth.android.ui.terminal.TerminalCanvas
import app.berth.android.ui.terminal.TerminalViewport
import app.berth.android.ui.terminal.cursorShapeOf
import app.berth.android.ui.tunnels.TunnelsTabBody
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.SessionState
import app.berth.domain.model.TabKind
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalSettings
import app.berth.terminal.CursorStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * The Stage (spec C3): the tab header, then the active tab's body (a terminal with its state pill
 * and Deck, or a Files browser), or the empty state when no tab is open. The header owns the
 * status-bar inset and the bottom chrome owns the keyboard and navigation-bar insets, so both
 * surfaces run edge to edge (A4) and the Deck rides the keyboard's top edge without jumping.
 * Hardware tab shortcuts are taken here, before the terminal. Each tab's body keeps its own
 * saveable state across switches (a Files tab's selection, sheet, viewer and per-folder scroll),
 * dropped when the tab closes; the Deck's visibility and layer are the Stage's, shared by every tab.
 * A [layer] lays the bodies out itself on a window that fits two (spec C23), composing each tab
 * through [StageBodies.TabBody]; without one the active tab's body fills the Stage.
 */
@Composable
fun StageScreen(
    vm: AppViewModel,
    tab: ManagedTab?,
    actions: TabActions,
    /** Opens the drawer; null when the drawer stands as a rail (spec C7) and the overflow has no Library to offer. */
    onOpenDrawer: (() -> Unit)?,
    onOpenSessionSheet: () -> Unit,
    onEditHost: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeckEditor: () -> Unit = {},
    /** The terminal tab's selection, search and paste state (spec C16 to C18); a test hands in its own to drive them. */
    tools: StageTools = rememberStageTools((tab as? TerminalSession)?.id),
    /** Overflow › Split and Unsplit (spec C3, C23), offered when the window fits two panes; one at a time. */
    onSplit: (() -> Unit)? = null,
    onUnsplit: (() -> Unit)? = null,
    /** Overflow › Groups: the groups overview (spec C8, "from the rail ... and from the Overflow"), the door a window without the drawer's GROUPS label has to it. */
    onGroups: () -> Unit = {},
    /** Lays out the body area for the active [tab] under the header, with the modifier that fills it. */
    layer: (@Composable StageBodies.(tab: ManagedTab, modifier: Modifier) -> Unit)? = null,
    /**
     * Where the keyboard's focus is, strip, bar, body or Deck, and the chords that move it (spec A11).
     * A [layer] hands in its own, since it marks the body's region on the pane the focus belongs to.
     */
    focus: StageFocus = rememberStageFocus(),
) {
    val c = Berth.colors
    val slots by vm.stripSlots.collectAsState()
    val groups by vm.workspaces.collectAsState()
    val activeId by vm.activeTabId.collectAsState()
    val restored by vm.restored.collectAsState()
    val ctrlTabKeysReachTerminal by vm.ctrlTabKeysReachTerminal.collectAsState()
    val strip = rememberTabStripState()
    var deckVisible by rememberSaveable { mutableStateOf(true) }
    var layerIndex by rememberSaveable { mutableIntStateOf(0) }
    var shortcutSheet by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val haptics = rememberDeckHaptics()
    // The pane layer's split and focus move, when one is over this Stage; the empty value on a phone.
    val panes = LocalPaneActions.current
    val shortcuts = remember(vm, actions, tab, tools, panes, focus) {
        val tabs = TabShortcuts(
            step = vm::stepTab,
            jump = { index ->
                val list = vm.stripSlots.value
                (if (index < 0) list.lastOrNull() else list.getOrNull(index))?.let { actions.activate(it.id) }
            },
            newTab = actions::newTab,
            closeActive = { vm.activeTabId.value?.let(actions::close) },
            switcher = actions::openSwitcher,
            jumpToUnread = { vm.jumpToUnread() },
        )
        // The chords beyond the strip's act on the terminal tab on stage (spec C22), and only on one
        // whose kind is a shell: keyed off TabKind like the overflow, so a Files tab, a tab kind with
        // no terminal behind it, or an empty stage takes the chord and does nothing with it, since
        // none of them means anything typed anywhere else.
        val session = (tab as? TerminalSession)?.takeIf { it.kind == TabKind.Ssh }
        val stage = object : StageShortcutActions {
            override fun find() { if (session != null) tools.openSearch() }
            override fun copy() {
                session ?: return
                val text = tools.selection.text(session.emulator)
                if (text.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(text))
                    haptics.copy()
                    tools.notice = "Copied"
                }
                tools.selection.clear()
            }
            override fun paste() { if (session != null) clipboard.getText()?.text?.let { tools.paste(session, it, haptics) } }
            override fun toggleDeck() { if (session != null) deckVisible = !deckVisible }
            override fun fontStep(step: Int) {
                session ?: return
                haptics.fontStep()
                // The chord and the pinch are one function (review #15): a host with its own size keeps the change to itself.
                vm.stepFontSize(session.record.value.hostId, step)
            }
            override fun shortcutSheet() { shortcutSheet = true }
            override fun split() { panes.split?.invoke() }
            override fun focusOtherPane() { panes.focusOtherPane?.invoke() }
            override fun focusStrip() { focus.focus(StageRegion.Strip) }
            // The Deck on screen, or the strip standing in for it, whichever terminal's it is (under two
            // panes it may be the other pane's, spec C23); with neither the chord finds no region and does nothing.
            override fun focusDeck() { focus.focus(StageRegion.Deck) }
            override fun returnToBody(): Boolean = when (focus.region) {
                // From the terminal itself Escape is the host's; with nothing focused there is nothing to return from.
                StageRegion.Body, null -> false
                StageRegion.Bar -> {
                    // Escape in the search closes it, the way its × does; the terminal then has the focus.
                    if (session != null && tools.search.open) tools.closeSearch()
                    focus.focus(StageRegion.Body)
                    true
                }
                StageRegion.Strip, StageRegion.Deck -> {
                    focus.focus(StageRegion.Body)
                    true
                }
            }
        }
        HardwareShortcuts(tabs, stage)
    }
    if (shortcutSheet) ShortcutSheet(ctrlTabKeysReachTerminal, panes = panes.available, onDismiss = { shortcutSheet = false })
    // A hardware keyboard folds the Deck to its strip (spec C4), once on attach and back on removal;
    // the Stage's visibility, so every tab's Deck folds and the user's own choice holds between.
    val hardwareKeyboardAttached = rememberHardwareKeyboardAttached()
    FoldDeckOnHardwareKeyboard(hardwareKeyboardAttached) { deckVisible = it }
    // With a keyboard attached the focus stays on the Stage across tab switches and the Deck's coming
    // and going, and a body coming on stage takes it. The keeper tells a loss from a move by the
    // window's root; a Stage composed alone marks its own root and stands in for the window.
    val shellWindow = LocalWindowFocus.current
    val ownWindow = if (shellWindow == null) remember { WindowFocus() } else null
    KeepStageFocus(focus, enabled = hardwareKeyboardAttached, window = shellWindow ?: ownWindow)

    // Each tab's saveable state lives under its id; a closed tab's is dropped so nothing accumulates.
    val holder = rememberSaveableStateHolder()
    val known = remember { HashSet<String>() }
    LaunchedEffect(slots) {
        val ids = slots.mapTo(HashSet()) { it.id }
        for (id in known) if (id !in ids) holder.removeState(id)
        known.retainAll(ids)
        known.addAll(ids)
    }
    // A Files tab's body lends the overflow its folder rows so the screen has one ⋮ (spec C3, tab
    // kinds); the body writes them, the header reads them, and only while a Files tab is on stage.
    var lentRows by remember { mutableStateOf<OverflowRows?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            // The header absorbs the status bar and the bottom chrome the navigation bar and IME; in
            // landscape the navigation bar and a cutout sit on a side, which nothing below takes.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .then(if (ownWindow != null) Modifier.windowFocus(ownWindow) else Modifier)
            .onPreviewKeyEvent { shortcuts.handle(it, ctrlTabKeysReachTerminal) },
    ) {
        StageToolbar(tools, tab as? TerminalSession, focus) {
            TabHeader(
                slots = slots,
                groups = groups,
                activeId = activeId,
                actions = actions,
                modifier = Modifier.stageRegion(focus, StageRegion.Strip),
                state = strip,
                // Ctrl+Shift+S lands on the active tab, or the first in view, in or out of touch mode.
                entry = focus.entry(StageRegion.Strip),
                trailing = {
                    if (slots.isNotEmpty()) {
                        // The ring says a tab the user cannot see needs them (spec C3): lit, not active, and not wholly in the strip's view.
                        val lit by vm.attentionTabIds.collectAsState()
                        val offScreen = lit.any { it != activeId && it !in strip.visibleTabIds }
                        CountTile(count = slots.size, attention = offScreen, onClick = actions::openSwitcher, onLongClick = { vm.jumpToUnread() })
                    }
                    StageOverflow(
                        tab = tab,
                        deckVisible = deckVisible,
                        onToggleDeck = { deckVisible = !deckVisible },
                        onOpenSessionSheet = onOpenSessionSheet,
                        onEditHost = onEditHost,
                        onOpenDrawer = onOpenDrawer,
                        actions = actions,
                        extra = if (tab is FilesTab) lentRows else null,
                        onFind = { tools.openSearch() },
                        onHistory = { tools.historyOpen = true },
                        onSplit = onSplit,
                        onUnsplit = onUnsplit,
                        onGroups = onGroups,
                    )
                },
            )
        }
        val body = Modifier.weight(1f).fillMaxWidth()
        // The one body is the keyboard's body region; a layer marks the region itself, on each pane (spec C23).
        val oneBody = body.stageRegion(focus, StageRegion.Body)
        val bodies = StageBodies(
            vm = vm,
            holder = holder,
            focus = focus,
            deckVisible = deckVisible,
            onDeckVisibleChange = { deckVisible = it },
            layerIndex = layerIndex,
            onLayerIndexChange = { layerIndex = it },
            onLendOverflow = { lentRows = it },
            onOpenSessionSheet = onOpenSessionSheet,
            onEditHost = onEditHost,
            onOpenDeckEditor = onOpenDeckEditor,
            actions = actions,
        )
        when {
            tab != null -> if (layer != null) layer(bodies, tab, body) else bodies.TabBody(tab, tools, oneBody)
            // The tab flows run a frame behind the manager: while an active id is set but its tab has not
            // arrived, or nothing has been restored yet, compose nothing rather than "No tabs" over a strip
            // that has them (spec C3, Persistence: restore is instant).
            activeId != null || !restored -> Spacer(body)
            slots.isNotEmpty() -> {
                // Tabs but no active id: never the empty state; the first tab goes on stage (the manager
                // ignores an id that no longer resolves, so a strip a frame old cannot re-stage a closed tab).
                Spacer(body)
                LaunchedEffect(slots) { vm.setActive(slots.first().id) }
            }
            else -> EmptyStage(onNewTab = actions::newTab, modifier = oneBody)
        }
    }
}

/**
 * The one place a tab's kind picks its body (spec C3, tab kinds): a terminal's Stage body with the
 * Deck, a Tunnels tab's forwards (a login with no shell, over the same pill, with no Deck), a Files
 * tab's browser, or the empty Stage for a kind with no body. The Stage composes the active tab
 * through it and a pane layer (spec C23) composes each pane through it, so a new kind is added here
 * once and every layout has it. Each body keeps its saveable state under the tab's id in the
 * Stage's [holder]; the Deck's visibility and layer are the Stage's and reach every terminal body.
 */
@Stable
class StageBodies internal constructor(
    private val vm: AppViewModel,
    private val holder: SaveableStateHolder,
    /** Where the keyboard's focus is on the Stage (spec A11); a layer marks the pane the body's region is on. */
    val focus: StageFocus,
    internal val deckVisible: Boolean,
    internal val onDeckVisibleChange: (Boolean) -> Unit,
    internal val layerIndex: Int,
    internal val onLayerIndexChange: (Int) -> Unit,
    private val onLendOverflow: (OverflowRows?) -> Unit,
    private val onOpenSessionSheet: () -> Unit,
    private val onEditHost: (String) -> Unit,
    private val onOpenDeckEditor: () -> Unit,
    private val actions: TabActions,
) {
    /**
     * [tab]'s body filling [modifier], with [tools] for a terminal's selection and search. A body
     * in a pane hands its bottom chrome (the Deck and its strip) to [chrome] instead of laying it
     * out itself, so one Deck can span both panes and the pane, not the body, pays the window's
     * bottom insets; [focusRequester] is the terminal's, so the pane can hand it the keys. Only the
     * body that [lendsOverflow] hands its rows to the strip's ⋮: the active tab's, the one the menu
     * is about. Nothing reaches the tab's kind but this dispatch.
     */
    @Composable
    fun TabBody(
        tab: ManagedTab,
        tools: StageTools,
        modifier: Modifier,
        chrome: StageChromeHost? = null,
        focusRequester: FocusRequester? = null,
        lendsOverflow: Boolean = true,
    ) {
        val onLendOverflow = if (lendsOverflow) onLendOverflow else NoLend
        holder.SaveableStateProvider(tab.id) {
            when {
                // A Tunnels tab is a login with no shell (spec C14): its body has no Deck to hand to a pane and no keys to take.
                tab is TerminalSession && tab.tunnelsOnly -> TunnelsTabBody(vm = vm, session = tab, onEditHost = onEditHost, modifier = modifier)
                tab is TerminalSession -> StageBody(
                    vm = vm,
                    session = tab,
                    tools = tools,
                    focus = focus,
                    deckVisible = deckVisible,
                    onDeckVisibleChange = onDeckVisibleChange,
                    layerIndex = layerIndex,
                    onLayerIndexChange = onLayerIndexChange,
                    onOpenSessionSheet = onOpenSessionSheet,
                    onEditHost = onEditHost,
                    onOpenDeckEditor = onOpenDeckEditor,
                    modifier = modifier,
                    chrome = chrome,
                    focusRequester = focusRequester,
                )
                tab is FilesTab -> FilesTabBody(vm = vm, tab = tab, onLendOverflow = onLendOverflow, modifier = modifier)
                else -> EmptyStage(onNewTab = actions::newTab, modifier = modifier)
            }
        }
    }

    private companion object {
        /** One instance, so a body that stops lending sees its host change once and withdraws. */
        val NoLend: (OverflowRows?) -> Unit = {}
    }
}

/**
 * Where a terminal body in a pane puts its bottom chrome (spec C23): the body sets [content] to
 * the Deck (or its strip) it would have laid out, and the pane layer lays it out under both panes.
 * Null content means the body has nothing to put there right now (a detached or failed frame, a
 * body that has left), and the layer may show another terminal's chrome in its place.
 */
@Stable
class StageChromeHost {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        internal set
}

/** "No tabs" with the primary way forward (spec C3, Closing); focusable so Ctrl+T works with nothing on stage. */
@Composable
private fun EmptyStage(onNewTab: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .focusRequester(focus)
            .focusable()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No tabs", style = BerthType.body, color = c.text2)
        Spacer(Modifier.height(16.dp))
        BerthButton("New tab", kind = ButtonKind.PRIMARY, onClick = onNewTab)
    }
}

/**
 * Rows a tab's body lends the Stage overflow as its first section, so a screen has one ⋮ (spec C3,
 * tab kinds); a row calls [dismiss] before it acts, since the menu is the Stage's.
 */
typealias OverflowRows = @Composable ColumnScope.(dismiss: () -> Unit) -> Unit

/**
 * Overflow (spec C3): Reconnect or Detach, Show or Hide Deck, Session, Host settings, Tabs, Groups
 * (the overview, spec C8), Library, Close. A Files tab has no Deck and no connection of its own, so
 * it offers Connect (no terminal on the host) or Reconnect (its terminal is down) and Terminal in
 * their place, and its body lends the folder rows as a leading section over an 8 dp break,
 * [extra]. Without a tab it offers New tab, Groups and Library.
 */
@Composable
private fun StageOverflow(
    tab: ManagedTab?,
    deckVisible: Boolean,
    onToggleDeck: () -> Unit,
    onOpenSessionSheet: () -> Unit,
    onEditHost: (String) -> Unit,
    onOpenDrawer: (() -> Unit)?,
    actions: TabActions,
    extra: OverflowRows? = null,
    onFind: () -> Unit = {},
    onHistory: () -> Unit = {},
    onSplit: (() -> Unit)? = null,
    onUnsplit: (() -> Unit)? = null,
    onGroups: () -> Unit = {},
) {
    val c = Berth.colors
    var menu by remember { mutableStateOf(false) }
    val record = tab?.record?.collectAsState()?.value
    val ride = (tab as? FilesTab)?.ride?.collectAsState()?.value
    Box {
        IconAction(onClick = { menu = true }, description = "More") {
            BerthIcon(BerthIcons.moreVert)
        }
        BerthMenu(expanded = menu, onDismiss = { menu = false }) {
            @Composable fun item(text: String, destructive: Boolean = false, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(text, style = BerthType.body, color = if (destructive) c.danger else c.text1) },
                    onClick = { menu = false; action() },
                )
            }
            if (extra != null) {
                extra { menu = false }
                // The break between the lent section and the tab's rows is room, not a rule.
                Spacer(Modifier.height(8.dp))
            }
            if (tab != null && record != null) {
                if (tab.kind == TabKind.Files) {
                    when {
                        ride == null -> item("Connect") { actions.reconnect(tab.id) }
                        record.state != SessionState.LIVE && record.state != SessionState.CONNECTING -> item("Reconnect") { actions.reconnect(tab.id) }
                    }
                    item("Terminal") { actions.openTerminal(tab.id) }
                } else {
                    if (record.state != SessionState.LIVE && record.state != SessionState.CONNECTING) item("Reconnect") { actions.reconnect(tab.id) }
                    if (record.state.isActive) item("Detach") { actions.detach(tab.id) }
                    if (tab.kind == TabKind.Tunnels) {
                        // No shell behind a Tunnels tab: no Deck, nothing to find or to have run; a terminal or Files on the host is one row away.
                        item("Terminal") { actions.openTerminal(tab.id) }
                        item("Files") { actions.openFiles(tab.id) }
                    } else {
                        item(if (deckVisible) "Hide Deck" else "Show Deck", action = onToggleDeck)
                        item("Find", action = onFind)
                        item("History", action = onHistory)
                    }
                }
                // Split (spec C3 overflow, landscape and larger): a second tab on this host beside this one; Unsplit while two are up.
                if (onSplit != null) item("Split", action = onSplit)
                if (onUnsplit != null) item("Unsplit", action = onUnsplit)
                item("Session", action = onOpenSessionSheet)
                record.hostId?.let { hostId -> item("Host settings") { onEditHost(hostId) } }
                item("Tabs", action = actions::openSwitcher)
                // The groups overview (spec C8) is reached from here as from the drawer's label, which a
                // window whose drawer stands as a column without labels (spec C7, C23) does not have.
                item("Groups", action = onGroups)
                // With the drawer standing as a rail (spec C7) the library is already in view.
                if (onOpenDrawer != null) item("Library", action = onOpenDrawer)
                item("Close", destructive = true) { actions.close(tab.id) }
            } else {
                item("New tab", action = actions::newTab)
                item("Groups", action = onGroups)
                if (onOpenDrawer != null) item("Library", action = onOpenDrawer)
            }
        }
    }
}

/**
 * Everything below the header for one terminal tab; the caller keys it on the session's id. In a
 * pane (spec C23) the bottom chrome goes to [chrome] for the pane to lay out under both panes,
 * and the terminal takes the pane's [focusRequester] so the pane can hand it the keys.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StageBody(
    vm: AppViewModel,
    session: TerminalSession,
    tools: StageTools,
    focus: StageFocus,
    deckVisible: Boolean,
    onDeckVisibleChange: (Boolean) -> Unit,
    layerIndex: Int,
    onLayerIndexChange: (Int) -> Unit,
    onOpenSessionSheet: () -> Unit,
    onEditHost: (String) -> Unit,
    onOpenDeckEditor: () -> Unit,
    modifier: Modifier = Modifier,
    chrome: StageChromeHost? = null,
    focusRequester: FocusRequester? = null,
) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val failure by session.failure.collectAsState()
    val swipeGesture by vm.tabSwipeGesture.collectAsState()
    // The window's fit of the saved layout (spec C23, C4): a phone on its side gives the Deck one 40 dp row, a tablet its second row.
    val fittedDeckLayout = LocalDeckFit.current.fit(vm.deckLayout.collectAsState().value)
    // The hand's gestures on the Deck (spec D2): the swipe down, the swipe across for the layer.
    val deckSettings by vm.deckSettings.collectAsState()
    // With a hardware keyboard attached the Deck stands folded to its strip (spec C4, the Stage's
    // FoldDeckOnHardwareKeyboard); what the strip expands to is one row of modifiers and actions
    // (Settings › Hardware keyboard › Compact Deck when expanded), or the whole Deck with that off.
    val hardwareKeyboard by vm.hardwareKeyboard.collectAsState()
    val compactDeck = rememberHardwareKeyboardAttached() && hardwareKeyboard.compactDeck
    val deckLayout = remember(fittedDeckLayout, compactDeck) { if (compactDeck) fittedDeckLayout.compactForHardwareKeyboard() else fittedDeckLayout }
    val fontSetting by vm.terminalFont.collectAsState()
    val defaultTheme by vm.defaultTerminalTheme.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    // The saved host as it is now, not as it was when the tab opened: a pinch writes the host's own
    // size (review #15) and the Look sheet's picks land on the saved host (spec C6), and the terminal
    // under the fingers or under the sheet has to follow them. A quick-connect tab has no saved host
    // and keeps the snapshot the record carries.
    val hosts by vm.hosts.collectAsState()
    val host = record.hostId?.let { id -> hosts.firstOrNull { it.id == id } } ?: record.hostSnapshot
    // Host override, then the workspace's theme, then the app default, and the host's font size and
    // family over the app's font; every flow is live, so a theme edit, a pinch or a Look pick lands here at once.
    val look = resolveLook(themes, defaultTheme, fontSetting, host, workspaces.byId(record.workspaceId))
    val theme = look.theme
    val font: TerminalFont = look.font
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val imeVisible = WindowInsets.isImeVisible
    val snippets by vm.snippets.collectAsState()
    val pinnedSnippets = remember(snippets, record.hostId, record.workspaceId) {
        snippets.filter { it.pinnedToDeck && it.visibleFor(record.hostId, record.workspaceId) }.sortedBy { it.name.lowercase() }
    }
    var pendingSnippet by remember { mutableStateOf<PendingSnippet?>(null) }
    val patterns = rememberDeckHaptics()
    val latch = remember(session.id) { ModifierLatch() }
    val viewport = tools.viewport
    val focusRequester = focusRequester ?: remember { FocusRequester() }
    // The screen reader's terminal: the canvas' text and the live region beside it share it.
    val accessibility = remember(session.id) { TerminalAccessibility() }
    // Every paste (Deck key, keyboard menu, two-finger tap, selection bar) goes through the preview (spec C18).
    val paste: (String) -> Unit = { tools.paste(session, it, patterns) }
    val input = remember(session.id) {
        StageInput(
            session = { session },
            latch = latch,
            // A Deck key bound to a snippet lands here: run it, or ask for its placeholders first.
            onSnippet = { id ->
                vm.snippets.value.firstOrNull { it.id == id }?.let { s ->
                    if (s.hasPlaceholders) pendingSnippet = PendingSnippet(s, s.defaultAction) else vm.runSnippet(session, s)
                }
            },
            pasteHook = paste,
            onAppAction = { action ->
                when (action) {
                    DeckAppAction.HIDE_KEYBOARD -> keyboard?.hide()
                    DeckAppAction.PASTE -> clipboard.getText()?.text?.let(paste)
                    DeckAppAction.NEXT_SESSION -> vm.stepTab(1)
                    DeckAppAction.PREVIOUS_SESSION -> vm.stepTab(-1)
                    DeckAppAction.DETACH -> vm.detach(session.id)
                    DeckAppAction.OPEN_SESSION_SHEET -> onOpenSessionSheet()
                    DeckAppAction.NEXT_LAYER -> onLayerIndexChange(layerIndex + 1)
                    DeckAppAction.PREVIOUS_LAYER -> onLayerIndexChange(layerIndex - 1)
                    DeckAppAction.OPEN_DECK_EDITOR -> onOpenDeckEditor()
                    DeckAppAction.JUMP_TO_UNREAD -> vm.jumpToUnread()
                    // The Deck key for C5's "Toggle predictive text" flips the same flag the Session sheet's row does.
                    DeckAppAction.TOGGLE_PREDICTIVE_TEXT -> vm.setPredictiveText(session.id, session.id !in vm.predictiveTextTabIds.value)
                    else -> Unit
                }
            },
        )
    }

    LaunchedEffect(session.id) {
        session.onStage = true
        session.markSeen()
        viewport.scrollOffset = 0
    }
    // Settings › Terminal reaches the emulator here: the history it keeps, and the cursor the user
    // chose, which stands until the program on the other end asks for its own (DECSCUSR).
    val terminalSettings by vm.terminalSettings.collectAsState()
    LaunchedEffect(session.id, terminalSettings.scrollbackLines) {
        session.emulator.maxScrollback = terminalSettings.scrollbackLines.coerceIn(TerminalSettings.MIN_SCROLLBACK, TerminalSettings.MAX_SCROLLBACK)
    }
    LaunchedEffect(session.id, fontSetting.cursorShape, fontSetting.cursorBlink) {
        session.emulator.defaultCursorStyle = CursorStyle(cursorShapeOf(fontSetting.cursorShape), fontSetting.cursorBlink)
    }
    // Bell while on stage is haptic only (C2), unless the host mutes it. Keyed on the patterns too,
    // so a haptic level change restarts the collector on the new instance.
    LaunchedEffect(session.id, host.muteBell, patterns) {
        if (host.muteBell) return@LaunchedEffect
        session.bell.collect { patterns.bell() }
    }
    // A key typed into a detached tab reconnects it (review #15); the pill shows the reconnect, the
    // notice says the key itself went nowhere, so a sentence is not typed into the gap.
    LaunchedEffect(session.id) {
        session.keyReconnected.collect { tools.notice = "Reconnecting, the key was not sent" }
    }

    BackHandler(enabled = imeVisible) { keyboard?.hide() }

    // Predictive text is the tab's own (spec C6, the Session sheet's row): the keyboard is told, and the grip lit, from the one flag.
    val predictiveTabIds by vm.predictiveTextTabIds.collectAsState()
    val predictiveText = session.id in predictiveTabIds

    val live = record.state == SessionState.LIVE
    val frameAlpha = when (record.state) {
        SessionState.LIVE -> 1f
        SessionState.CONNECTING, SessionState.IDLE -> 0.6f
        else -> 0.8f
    }
    val deckStateOk = record.state != SessionState.DETACHED && record.state != SessionState.FAILED && record.state != SessionState.CLOSED
    val deckAllowed = deckVisible && deckStateOk

    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            TerminalCanvas(
                session = session,
                theme = theme,
                font = font,
                sink = input,
                viewport = viewport,
                focusRequester = focusRequester,
                showCursor = live,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, top = 4.dp, end = 4.dp)
                    .alpha(frameAlpha),
                onFontSizeStep = { step ->
                    patterns.fontStep()
                    vm.stepFontSize(record.hostId, step)
                },
                onTwoFingerSwipe = if (swipeGesture == TabSwipeGesture.TWO_FINGER) { forward -> vm.stepTab(if (forward) 1 else -1) } else null,
                onTwoFingerTap = { clipboard.getText()?.text?.let(paste) },
                // The D1 gestures the canvas reports and the Stage owns: the font back to the host's default,
                // the Deck shown or hidden (its own state lives here, not in Deck.kt), arrows for a drag when asked.
                onTwoFingerDoubleTap = {
                    patterns.fontStep()
                    vm.resetFontSize(record.hostId)
                },
                onTwoFingerTapArmed = { patterns.twoFingerTapArmed() },
                onThreeFingerTap = { if (deckStateOk) onDeckVisibleChange(!deckVisible) },
                horizontalDragArrows = terminalSettings.horizontalDragArrows,
                // An OSC 8 link goes through its sheet (spec A60): the address is the remote's and is looked at first.
                onLinkTap = { tools.pendingLink = it },
                selection = tools.selection,
                search = tools.search,
                onSelectionStarted = { patterns.selectionStarted() },
                accessibility = accessibility,
                predictiveText = predictiveText,
            )
            TerminalAnnouncer(accessibility, session, Modifier.align(Alignment.TopStart))
            if (swipeGesture == TabSwipeGesture.RIGHT_EDGE) {
                EdgeSwipeZone(onSwipe = { forward -> vm.stepTab(if (forward) 1 else -1) }, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            ScrolledPill(viewport, Modifier.align(Alignment.TopEnd))
            NoticePill(tools, Modifier.align(Alignment.TopCenter))
            if (record.state == SessionState.FAILED) {
                FailedPanel(
                    plain = failure?.plain ?: "Couldn't connect.",
                    raw = failure?.raw,
                    onRetry = { vm.reconnect(session.id) },
                    onEditHost = record.hostId?.let { id -> { onEditHost(id) } },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                    hop = failure?.hop,
                    onEditHop = { onEditHost(it.hostId) },
                )
            }
        }

        StatePill(
            state = record.state,
            retryIn = session.retryIn,
            lastLiveAt = record.lastLiveAt,
            onReconnect = { vm.reconnect(session.id) },
            onDetach = { vm.detach(session.id) },
            onClose = { vm.close(session.id) },
            via = session.via,
        )

        // The bottom chrome takes the larger of the keyboard and navigation-bar insets, so the Deck
        // sits on the keyboard when it is up and its surface runs under the bar when it is not.
        val bottomChrome: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (deckStateOk) c.surface1 else c.surface0)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
            ) {
                AnimatedVisibility(visible = deckAllowed, enter = BerthMotion.unfoldIn(), exit = BerthMotion.foldOut()) {
                    Deck(
                        layout = deckLayout,
                        // The Deck holds the index to its layers; the one-layer compact Deck shows its
                        // one, and the layer the user was on comes back with the whole Deck.
                        layerIndex = layerIndex,
                        onLayerIndexChange = onLayerIndexChange,
                        input = input,
                        // The Deck and the strip that stands in for it are the one region Ctrl+Shift+K enters.
                        modifier = Modifier.stageRegion(focus, StageRegion.Deck),
                        enabled = live,
                        settings = deckSettings,
                        // The grip's tap and its drag up (spec C4) open the one Session sheet, which opens whole.
                        onGripTap = onOpenSessionSheet,
                        onGripSwipeDown = {
                            keyboard?.hide()
                            onDeckVisibleChange(false)
                        },
                        onGripLongPress = { if (vm.jumpToUnread()) patterns.hold() },
                        snippets = pinnedSnippets,
                        onOpenDeckEditor = onOpenDeckEditor,
                        predictiveText = predictiveText,
                    )
                }
                if (!deckVisible && deckStateOk) {
                    DeckStrip(
                        layerName = deckLayout.shownLayer(layerIndex, pinnedSnippets.isNotEmpty())?.name ?: "Base",
                        latch = latch,
                        onExpand = { onDeckVisibleChange(true) },
                        modifier = Modifier.stageRegion(focus, StageRegion.Deck),
                    )
                }
            }
        }
        if (chrome == null) {
            bottomChrome()
        } else {
            // In a pane the chrome is the pane's to place: handed over after every composition while
            // there is something in it (the Deck, or its strip), withheld while there is not (a detached
            // or failed frame has neither), and withdrawn when the body leaves. What this body has nothing
            // to put there, the pane may fill with another terminal's Deck (spec C23) rather than with nothing.
            val handed = if (deckStateOk) bottomChrome else null
            SideEffect { chrome.content = handed }
            DisposableEffect(chrome) { onDispose { chrome.content = null } }
        }
    }

    pendingSnippet?.let { p -> SnippetRunSheet(vm, session, p, onDismiss = { pendingSnippet = null }) }
    tools.pendingPaste?.let { p -> PastePreviewSheet(p, session, patterns, onDismiss = { tools.pendingPaste = null }) }
    tools.pendingLink?.let { l -> LinkOpenSheet(l, session, tools, patterns, onDismiss = { tools.pendingLink = null }) }
    if (tools.historyOpen) CommandHistorySheet(vm, session, onDismiss = { tools.historyOpen = false }, onNotice = { tools.notice = it })
}

/**
 * The right-edge alternative to the two-finger swipe (spec C3, Switching): a 24 dp zone, kept out
 * of the system back gesture, where a one-finger horizontal drag past 56 dp steps tabs. Vertical
 * movement is left to the terminal.
 */
@Composable
private fun EdgeSwipeZone(onSwipe: (forward: Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(24.dp)
            .systemGestureExclusion()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    var dx = 0f
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (claimed && abs(dx) > 56.dp.toPx()) onSwipe(dx < 0)
                            break
                        }
                        dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!claimed && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                        if (abs(dx) > viewConfiguration.touchSlop) claimed = true
                        if (claimed) change.consume()
                    }
                }
            },
    )
}

/**
 * The return-to-bottom action (C2): accent text so the pill reads as tappable, 40 dp target, one
 * surface step when pressed. The label names the state; the description names the action. Reads
 * the viewport itself, so a scroll through history recomposes this and nothing around it.
 */
@Composable
private fun ScrolledPill(viewport: TerminalViewport, modifier: Modifier = Modifier) {
    if (viewport.scrollOffset <= 0) return
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    Box(
        modifier
            .padding(end = 8.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = { viewport.scrollOffset = 0 })
            .clearAndSetSemantics {
                contentDescription = "Scrolled up, return to the bottom"
                role = Role.Button
            }
            .padding(horizontal = 4.dp, vertical = 9.dp),
    ) {
        Pill("scrolled", color = if (pressed || focused) c.surface4 else c.surface3, textColor = c.accent)
    }
}

/** Visible height of the state pill; its touch target is the full 44 dp row around it. */
private val StatePillHeight = 32.dp

/**
 * One floating pill for the non-live states (C2, A9): `Detached · 4 min ago · Reconnect · Close`,
 * full radius on surface.3 with Caption text, the actions in accent. Nothing when Live. The age
 * follows its own clock unless a [now] is given, and the reconnect countdown is collected here
 * from [retryIn], so the minute tick and the 1 Hz backoff tick recompose the pill alone and the
 * Stage around it never re-runs for either. While a jump chain is being made, [via] names the hop
 * (`Connecting via bastion (1 of 2)…`), so a slow or failing hop is seen as that hop.
 */
@Composable
internal fun StatePill(
    state: SessionState,
    retryIn: StateFlow<Int?>?,
    lastLiveAt: Long?,
    onReconnect: () -> Unit,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    now: Long? = null,
    via: StateFlow<String?>? = null,
) {
    val c = Berth.colors
    if (state != SessionState.RECONNECTING && state != SessionState.DETACHED && state != SessionState.CONNECTING && state != SessionState.IDLE) return
    val clock = now ?: ageTicker()
    val seconds = retryIn?.collectAsState()?.value
    val hop = via?.collectAsState()?.value?.let { " $it" } ?: ""
    val (text, actions) = when (state) {
        // Spec D4: "Connecting…" over the dimmed previous frame, no actions until the connect resolves.
        SessionState.CONNECTING, SessionState.IDLE -> "Connecting$hop\u2026" to emptyList()
        SessionState.RECONNECTING -> (if (seconds != null) "Reconnecting \u00B7 retry in ${seconds}s" else "Reconnecting$hop\u2026") to listOf("Detach" to onDetach)
        else -> "Detached \u00B7 ${ageText(lastLiveAt, clock)}" to listOf("Reconnect" to onReconnect, "Close" to onClose)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(44.dp)
                .drawBehind {
                    val h = StatePillHeight.toPx()
                    drawRoundRect(
                        color = c.surface3,
                        topLeft = Offset(0f, (size.height - h) / 2),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // In a pane too narrow for all of it (spec C23) the age is what gives, never an action.
            Text(text, style = BerthType.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp))
            for ((label, action) in actions) {
                // The neighbours' own 8 dp insets space the dot; it carries none itself.
                Text("\u00B7", style = BerthType.caption, color = c.text3)
                PillAction(label, onClick = action)
            }
        }
    }
}

/** An action segment inside the state pill: accent Caption, pressed shows a concentric surface.4 pill. */
@Composable
private fun PillAction(label: String, onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    Box(
        Modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .drawBehind {
                if (pressed || focused) {
                    val h = (StatePillHeight - 8.dp).toPx()
                    drawRoundRect(
                        color = c.surface4,
                        topLeft = Offset(0f, (size.height - h) / 2),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                }
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BerthType.caption, color = c.accent, maxLines = 1)
    }
}

/**
 * Radius 20 panel over the terminal (or the Tunnels stage) with the plain reason and the raw error
 * behind Details. When the login failed at a jump host ([hop]), the credentials or key that failed
 * are that host's, so the action beside Retry opens its editor, named for it (`Edit old bastion`,
 * or `Edit jump host` when the name is long); the target's editor stays a text action behind it.
 * To a reader and a keyboard the panel is one group read top to bottom: the heading, the reason,
 * Details (a button that says whether it is open), then Retry, the hop's editor, the host's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FailedPanel(
    plain: String,
    raw: String?,
    onRetry: () -> Unit,
    onEditHost: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hop: FailedHop? = null,
    onEditHop: ((FailedHop) -> Unit)? = null,
) {
    val c = Berth.colors
    var details by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.panel))
            .background(c.surface2)
            .padding(20.dp)
            .focusGroup()
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Couldn't connect", style = BerthType.headline, color = c.text1, modifier = Modifier.semantics { heading() })
        Text(plain, style = BerthType.body, color = c.text2)
        if (raw != null) {
            Text(
                if (details) raw else "Details",
                style = if (details) BerthType.mono else BerthType.label,
                color = if (details) c.text3 else c.accent,
                modifier = Modifier
                    .reachingClickable(onClick = { details = !details }, onClickLabel = if (details) "hide the details" else "show the details")
                    .semantics { stateDescription = if (details) "Expanded" else "Collapsed" },
            )
        }
        // A flow, so a third action or a long hop name wraps under the first two rather than leaving the panel.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Retry", onClick = onRetry, kind = ButtonKind.PRIMARY)
            if (hop != null && onEditHop != null) {
                BerthButton(editHopLabel(hop.name), onClick = { onEditHop(hop) })
                if (onEditHost != null) BerthButton("Edit host", onClick = onEditHost, kind = ButtonKind.TEXT)
            } else if (onEditHost != null) {
                BerthButton("Edit host", onClick = onEditHost)
            }
        }
    }
}

/** `Edit old bastion` while the name fits a button beside Retry; `Edit jump host` for a longer one. */
internal fun editHopLabel(name: String): String = if (name.length <= MAX_HOP_LABEL_CHARS) "Edit $name" else "Edit jump host"

private const val MAX_HOP_LABEL_CHARS = 16

/** "4 min ago" style ages; re-evaluated by callers each minute through [ageTicker]. */
fun ageText(since: Long?, now: Long = System.currentTimeMillis()): String {
    if (since == null) return ""
    val s = (now - since) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** Ticks once a minute so ages re-render. */
@Composable
fun ageTicker(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Mono style for fingerprints and specs in sheets. */
val MonoBody get() = BerthType.monoBody
