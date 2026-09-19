package app.berth.android.ui.stage

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.byId
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.snippets.PendingSnippet
import app.berth.android.ui.snippets.SnippetRunSheet
import app.berth.android.ui.terminal.TerminalCanvas
import app.berth.android.ui.terminal.TerminalViewport
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.SessionState
import app.berth.domain.model.TerminalFont
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The terminal on stage: ribbon, cell-accurate terminal, state pill and the Deck. The ribbon owns
 * the status-bar inset and the bottom chrome owns the keyboard and navigation-bar insets, so both
 * surfaces run edge to edge (A4) and the Deck rides the keyboard's top edge without jumping.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun StageScreen(
    vm: AppViewModel,
    session: TerminalSession,
    onOpenRail: () -> Unit,
    onOpenSessionSheet: () -> Unit,
    onEditHost: (String) -> Unit,
    onNextSession: () -> Unit,
    onPreviousSession: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeckEditor: () -> Unit = {},
) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val retryIn by session.retryIn.collectAsState()
    val failure by session.failure.collectAsState()
    val attention by vm.attentionCount.collectAsState()
    val deckLayout by vm.deckLayout.collectAsState()
    val fontSetting by vm.terminalFont.collectAsState()
    val defaultTheme by vm.defaultTerminalTheme.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val host = record.hostSnapshot
    // Host override, then the workspace's theme, then the app default; all three flows are live, so a theme edit lands here at once.
    val theme = AppViewModel.resolveTerminalTheme(themes, defaultTheme, host, workspaces.byId(record.workspaceId))
    val font: TerminalFont = host.appearance.fontSizeSp?.let { fontSetting.copy(sizeSp = it) } ?: fontSetting
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val imeVisible = WindowInsets.isImeVisible
    val snippets by vm.snippets.collectAsState()
    val pinnedSnippets = remember(snippets, record.hostId, record.workspaceId) {
        snippets.filter { it.pinnedToDeck && it.visibleFor(record.hostId, record.workspaceId) }.sortedBy { it.name.lowercase() }
    }
    var pendingSnippet by remember { mutableStateOf<PendingSnippet?>(null) }
    val configuration = LocalConfiguration.current
    val hardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val patterns = rememberDeckHaptics()
    val now = ageTicker()

    // A hardware keyboard collapses the Deck to its strip (C4); attaching or removing one flips it once,
    // and the user's own choice survives otherwise.
    var deckVisible by rememberSaveable { mutableStateOf(!hardwareKeyboard) }
    var seenHardwareKeyboard by rememberSaveable { mutableStateOf(hardwareKeyboard) }
    LaunchedEffect(hardwareKeyboard) {
        if (hardwareKeyboard != seenHardwareKeyboard) {
            seenHardwareKeyboard = hardwareKeyboard
            deckVisible = !hardwareKeyboard
        }
    }
    var layerIndex by rememberSaveable { mutableIntStateOf(0) }
    val latch = remember(session.id) { ModifierLatch() }
    val viewport = remember(session.id) { TerminalViewport() }
    val focusRequester = remember { FocusRequester() }
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
            onAppAction = { action ->
                when (action) {
                    DeckAppAction.HIDE_KEYBOARD -> keyboard?.hide()
                    DeckAppAction.PASTE -> clipboard.getText()?.text?.let {
                        session.paste(it)
                        patterns.paste()
                    }
                    DeckAppAction.NEXT_SESSION -> onNextSession()
                    DeckAppAction.PREVIOUS_SESSION -> onPreviousSession()
                    DeckAppAction.DETACH -> vm.detach(session.id)
                    DeckAppAction.OPEN_SESSION_SHEET -> onOpenSessionSheet()
                    DeckAppAction.NEXT_LAYER -> layerIndex += 1
                    DeckAppAction.PREVIOUS_LAYER -> layerIndex -= 1
                    DeckAppAction.OPEN_DECK_EDITOR -> onOpenDeckEditor()
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
    // Bell while on stage is haptic only (C2), unless the host mutes it. Keyed on the patterns too,
    // so a haptic level change restarts the collector on the new instance.
    LaunchedEffect(session.id, host.muteBell, patterns) {
        if (host.muteBell) return@LaunchedEffect
        session.bell.collect { patterns.bell() }
    }

    BackHandler(enabled = imeVisible) { keyboard?.hide() }

    val live = record.state == SessionState.LIVE
    val frameAlpha = when (record.state) {
        SessionState.LIVE -> 1f
        SessionState.CONNECTING, SessionState.IDLE -> 0.6f
        else -> 0.8f
    }
    val deckStateOk = record.state != SessionState.DETACHED && record.state != SessionState.FAILED && record.state != SessionState.CLOSED
    val deckAllowed = deckVisible && deckStateOk

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            // The ribbon absorbs the status bar and the bottom chrome the navigation bar and IME; in
            // landscape the navigation bar and a cutout sit on a side, which nothing below takes.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Ribbon(
            session = session,
            attention = attention,
            now = now,
            onOpenRail = onOpenRail,
            onTitleTap = onOpenSessionSheet,
            onSwipe = { forward -> if (forward) onNextSession() else onPreviousSession() },
            onReconnect = { vm.reconnect(session.id) },
            onDetach = { vm.detach(session.id) },
            onClose = { vm.close(session.id) },
            onEditHost = { record.hostId?.let(onEditHost) },
            onToggleDeck = { deckVisible = !deckVisible },
            deckVisible = deckVisible,
            scrolled = viewport.scrollOffset > 0,
            onReturnToBottom = { viewport.scrollOffset = 0 },
        )

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
                    vm.setFontSize(font.sizeSp + step)
                },
            )
            if (record.state == SessionState.FAILED) {
                FailedPanel(
                    plain = failure?.first ?: "Couldn't connect.",
                    raw = failure?.second,
                    onRetry = { vm.reconnect(session.id) },
                    onEditHost = record.hostId?.let { id -> { onEditHost(id) } },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                )
            }
        }

        StatePill(
            state = record.state,
            retryIn = retryIn,
            lastLiveAt = record.lastLiveAt,
            now = now,
            onReconnect = { vm.reconnect(session.id) },
            onDetach = { vm.detach(session.id) },
            onClose = { vm.close(session.id) },
        )

        // The bottom chrome takes the larger of the keyboard and navigation-bar insets, so the Deck
        // sits on the keyboard when it is up and its surface runs under the bar when it is not.
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (deckStateOk) c.surface1 else c.surface0)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        ) {
            AnimatedVisibility(visible = deckAllowed) {
                Deck(
                    layout = deckLayout,
                    layerIndex = layerIndex,
                    onLayerIndexChange = { layerIndex = it },
                    input = input,
                    enabled = live,
                    onGripTap = onOpenSessionSheet,
                    onGripSwipeDown = {
                        keyboard?.hide()
                        deckVisible = false
                    },
                    snippets = pinnedSnippets,
                    onOpenDeckEditor = onOpenDeckEditor,
                )
            }
            if (!deckVisible && deckStateOk) {
                DeckStrip(
                    layerName = deckLayout.usableLayers(pinnedSnippets.isNotEmpty()).getOrNull(layerIndex)?.name ?: "Base",
                    latch = latch,
                    onExpand = { deckVisible = true },
                )
            }
        }
    }

    pendingSnippet?.let { p -> SnippetRunSheet(vm, session, p, onDismiss = { pendingSnippet = null }) }
}

/**
 * 40 dp strip over the Stage on surface.1, drawn under the status bar: rail glyph, swatch, title,
 * state, "needs you" pill, overflow. Swipe switches sessions.
 */
@Composable
private fun Ribbon(
    session: TerminalSession,
    attention: Int,
    now: Long,
    onOpenRail: () -> Unit,
    onTitleTap: () -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    onEditHost: () -> Unit,
    onToggleDeck: () -> Unit,
    deckVisible: Boolean,
    scrolled: Boolean,
    onReturnToBottom: () -> Unit,
) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val retryIn by session.retryIn.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val host = record.hostSnapshot
    val title = record.title.ifBlank { host.name }
    val subtitle = if (record.title.isNotBlank() && record.title != host.name) host.name else null
    val stateText = when (record.state) {
        SessionState.LIVE -> null
        SessionState.IDLE, SessionState.CONNECTING -> "Connecting\u2026"
        SessionState.RECONNECTING -> if (retryIn != null) "Reconnecting \u00B7 retry in ${retryIn}s" else "Reconnecting\u2026"
        SessionState.DETACHED -> "Detached \u00B7 ${ageText(record.lastLiveAt, now)}"
        SessionState.FAILED -> "Couldn't connect"
        SessionState.CLOSED -> "Closed"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface1)
            .statusBarsPadding()
            .height(40.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dx = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (abs(dx) > 56.dp.toPx()) onSwipe(dx < 0)
                            break
                        }
                        dx = change.position.x - down.position.x
                        if (abs(dx) > viewConfiguration.touchSlop) change.consume()
                    }
                }
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(onClick = onOpenRail, description = "Open the rail") {
            BerthIcon(BerthIcons.workspace)
        }
        Spacer(Modifier.width(2.dp))
        Swatch(host.color, host.monogram, 24.dp, state = record.state.takeIf { it != SessionState.LIVE }, attention = record.needsAttention)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onTitleTap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = BerthType.label, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (stateText != null) Text(stateText, style = BerthType.caption, color = if (record.state == SessionState.FAILED) c.danger else c.text2, maxLines = 1)
                if (attention > 0) Pill("$attention needs you", color = c.attention.copy(alpha = 0.18f), textColor = c.attention)
            }
            if (subtitle != null) Text(subtitle, style = BerthType.caption, color = c.text3, maxLines = 1)
        }
        if (scrolled) {
            // The return-to-bottom action (C2): accent text so the pill reads as tappable, 40 dp target,
            // one surface step when pressed. The label names the state; the description names the action.
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .clickable(interactionSource = interaction, indication = null, onClick = onReturnToBottom)
                    .clearAndSetSemantics {
                        contentDescription = "Scrolled up, return to the bottom"
                        role = Role.Button
                    }
                    .padding(horizontal = 4.dp, vertical = 9.dp),
            ) {
                Pill("scrolled", color = if (pressed) c.surface4 else c.surface3, textColor = c.accent)
            }
        }
        Box {
            IconAction(onClick = { menu = true }, description = "More") {
                BerthIcon(BerthIcons.moreVert)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
                @Composable fun item(text: String, destructive: Boolean = false, action: () -> Unit) {
                    DropdownMenuItem(
                        text = { Text(text, style = BerthType.body, color = if (destructive) c.danger else c.text1) },
                        onClick = { menu = false; action() },
                    )
                }
                if (record.state != SessionState.LIVE && record.state != SessionState.CONNECTING) item("Reconnect", action = onReconnect)
                if (record.state.isActive) item("Detach", action = onDetach)
                item(if (deckVisible) "Hide Deck" else "Show Deck", action = onToggleDeck)
                if (record.hostId != null) item("Host settings", action = onEditHost)
                item("Close", destructive = true, action = onClose)
            }
        }
    }
}

/** Visible height of the state pill; its touch target is the full 44 dp row around it. */
private val StatePillHeight = 32.dp

/**
 * One floating pill for the non-live states (C2, A9): `Detached · 4 min ago · Reconnect · Close`,
 * full radius on surface.3 with Caption text, the actions in accent. Nothing when Live.
 */
@Composable
internal fun StatePill(
    state: SessionState,
    retryIn: Int?,
    lastLiveAt: Long?,
    now: Long,
    onReconnect: () -> Unit,
    onDetach: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Berth.colors
    val (text, actions) = when (state) {
        SessionState.RECONNECTING -> (if (retryIn != null) "Reconnecting \u00B7 retry in ${retryIn}s" else "Reconnecting\u2026") to listOf("Detach" to onDetach)
        SessionState.DETACHED -> "Detached \u00B7 ${ageText(lastLiveAt, now)}" to listOf("Reconnect" to onReconnect, "Close" to onClose)
        else -> return
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
            Text(text, style = BerthType.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
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
    Box(
        Modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .drawBehind {
                if (pressed) {
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

/** Radius 20 panel over the terminal with the plain reason and the raw error behind Details. */
@Composable
private fun FailedPanel(plain: String, raw: String?, onRetry: () -> Unit, onEditHost: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Berth.colors
    var details by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.panel))
            .background(c.surface2)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Couldn't connect", style = BerthType.headline, color = c.text1)
        Text(plain, style = BerthType.body, color = c.text2)
        if (raw != null) {
            Text(
                if (details) raw else "Details",
                style = if (details) BerthType.mono else BerthType.label,
                color = if (details) c.text3 else c.accent,
                modifier = Modifier.clickable { details = !details },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Retry", onClick = onRetry, kind = ButtonKind.PRIMARY)
            if (onEditHost != null) BerthButton("Edit host", onClick = onEditHost)
        }
    }
}

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
val MonoBody get() = BerthType.body.copy(fontFamily = JetBrainsMono)
