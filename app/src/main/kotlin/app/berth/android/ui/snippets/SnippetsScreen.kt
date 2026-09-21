package app.berth.android.ui.snippets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.Glyph
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionState
import app.berth.domain.model.Snippet
import app.berth.domain.model.SnippetAction
import java.util.UUID

/** One chip on the Snippets screen: everything, only global ones, or one workspace's or host's. */
private sealed interface SnippetFilter {
    data object All : SnippetFilter
    data object Global : SnippetFilter
    data class InWorkspace(val id: String) : SnippetFilter
    data class ForHost(val id: String) : SnippetFilter

    fun accepts(s: Snippet): Boolean = when (this) {
        All -> true
        Global -> s.hostId == null && s.workspaceId == null
        is InWorkspace -> s.workspaceId == id
        is ForHost -> s.hostId == id
    }
}

/** A snippet about to run into a session, waiting on its placeholders or a Run/Paste choice. */
class PendingSnippet(val snippet: Snippet, val action: SnippetAction)

/**
 * The snippet library (C15): search, scope chips, rows with the first line in Mono and
 * placeholders in accent. Tap runs into the session on stage when it is live; otherwise it edits.
 */
@Composable
fun SnippetsScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val snippets by vm.snippets.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val active by vm.activeSession.collectAsState()
    val liveSession = active?.takeIf { it.record.collectAsState().value.state == SessionState.LIVE }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<SnippetFilter>(SnippetFilter.All) }
    var editor by remember { mutableStateOf<SnippetEditorTarget?>(null) }
    var pending by remember { mutableStateOf<PendingSnippet?>(null) }

    val q = query.trim().lowercase()
    val shown = snippets
        .filter { filter.accepts(it) }
        .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.body.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) } }
        .sortedBy { it.name.lowercase() }
    val workspaceChips = workspaces.filter { ws -> snippets.any { it.workspaceId == ws.id } }
    val hostChips = hosts.filter { h -> snippets.any { it.hostId == h.id } }

    fun start(snippet: Snippet, action: SnippetAction) {
        val session = liveSession ?: run { editor = SnippetEditorTarget(snippet); return }
        if (snippet.hasPlaceholders) pending = PendingSnippet(snippet, action) else vm.runSnippet(session, snippet, action = action)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(
            title = "Snippets",
            onBack = onBack,
            actions = {
                IconAction(onClick = { editor = SnippetEditorTarget(null) }, description = "New snippet") { Glyph("+", size = 24) }
            },
        )
        if (snippets.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            EmptyState(
                title = "No snippets yet.",
                body = "Commands you type often. Write {{name}} or {{name:default}} for the parts that change and Berth asks for them when you run it. Pin favourites to the Deck.",
            ) {
                BerthButton("New snippet", kind = ButtonKind.PRIMARY, onClick = { editor = SnippetEditorTarget(null) })
            }
        } else {
            Column(Modifier.padding(horizontal = BerthSpace.screenMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BerthField(query, { query = it }, placeholder = "Search snippets", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("All", selected = filter == SnippetFilter.All) { filter = SnippetFilter.All }
                    Chip("Global", selected = filter == SnippetFilter.Global) { filter = SnippetFilter.Global }
                    for (ws in workspaceChips) Chip(ws.name, selected = filter == SnippetFilter.InWorkspace(ws.id)) { filter = SnippetFilter.InWorkspace(ws.id) }
                    for (h in hostChips) Chip(h.name, selected = filter == SnippetFilter.ForHost(h.id)) { filter = SnippetFilter.ForHost(h.id) }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { s ->
                    SnippetRow(
                        snippet = s,
                        canRun = liveSession != null,
                        onTap = { start(s, s.defaultAction) },
                        onRun = { start(s, SnippetAction.RUN) },
                        onPaste = { start(s, SnippetAction.PASTE) },
                        onEdit = { editor = SnippetEditorTarget(s) },
                        onPin = { vm.saveSnippet(s.copy(pinnedToDeck = !s.pinnedToDeck)) },
                        onDuplicate = { vm.saveSnippet(s.copy(id = UUID.randomUUID().toString(), name = s.name + " copy", pinnedToDeck = false, runOnConnect = false)) },
                        onDelete = { vm.deleteSnippet(s.id) },
                    )
                }
                if (shown.isEmpty()) {
                    item { Text("Nothing matches.", style = BerthType.body, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
                }
                item {
                    Text(
                        if (liveSession != null) "Tap runs into ${liveSession.record.collectAsState().value.title.ifBlank { liveSession.host.name }}. Long-press for paste, pin and more."
                        else "Open a session and tapping a snippet runs it there. Long-press for more.",
                        style = BerthType.caption,
                        color = c.text3,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
            }
        }
    }

    editor?.let { target -> SnippetEditorSheet(vm, existing = target.snippet, onDismiss = { editor = null }) }
    LaunchedEffect(liveSession == null) { if (liveSession == null) pending = null }
    liveSession?.let { session -> pending?.let { p -> SnippetRunSheet(vm, session, p, onDismiss = { pending = null }) } }
}

class SnippetEditorTarget(val snippet: Snippet?)

/** The body with every `{{placeholder}}` in accent, for previews and the run sheet. */
@Composable
fun highlightedBody(body: String, oneLine: Boolean = true): AnnotatedString {
    val accent = Berth.colors.accent
    val text = if (oneLine) body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "" else body
    return buildAnnotatedString {
        var last = 0
        for (m in Snippet.PLACEHOLDER.findAll(text)) {
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(color = accent)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/**
 * A snippet as a [ListRow] (C15): the name and one Mono preview line with placeholders in accent.
 * Pinned to the Deck is the row's 4 dp accent dot in the leading padding; run-on-connect is a
 * Caption suffix on the preview. Scope is the filter chips' job, so the row carries no badge.
 */
@Composable
fun SnippetRow(
    snippet: Snippet,
    canRun: Boolean,
    onTap: () -> Unit,
    onRun: () -> Unit,
    onPaste: () -> Unit,
    onPin: () -> Unit,
    modifier: Modifier = Modifier,
    surface: Color = Berth.colors.surface2,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val c = Berth.colors
    var menu by remember { mutableStateOf(false) }
    val preview = buildAnnotatedString {
        append(highlightedBody(snippet.body))
        if (snippet.runOnConnect) {
            withStyle(SpanStyle(fontFamily = BerthType.caption.fontFamily, fontWeight = BerthType.caption.fontWeight, letterSpacing = BerthType.caption.letterSpacing, color = c.text3)) {
                append(" \u00B7 on connect")
            }
        }
    }
    Box(modifier) {
        ListRow(
            title = snippet.name,
            subtitle = preview,
            subtitleStyle = BerthType.mono.copy(fontSize = BerthType.caption.fontSize, lineHeight = BerthType.caption.lineHeight),
            surface = surface,
            onClick = onTap,
            onLongClick = { menu = true },
            leading = if (snippet.pinnedToDeck) ({ PinnedDot() }) else null,
        )
        BerthMenu(expanded = menu, onDismiss = { menu = false }) {
            @Composable fun item(text: String, destructive: Boolean = false, action: () -> Unit) {
                DropdownMenuItem(text = { Text(text, style = BerthType.body, color = if (destructive) c.danger else c.text1) }, onClick = { menu = false; action() })
            }
            if (canRun) {
                item("Run", action = onRun)
                item("Paste", action = onPaste)
            }
            if (onEdit != null) item("Edit", action = onEdit)
            item(if (snippet.pinnedToDeck) "Unpin from Deck" else "Pin to Deck", action = onPin)
            if (onDuplicate != null) item("Duplicate", action = onDuplicate)
            if (onDelete != null) item("Delete", destructive = true, action = onDelete)
        }
    }
}

/** The ListRow selection idiom borrowed for "on the Deck": a 4 dp accent dot in the leading padding. */
@Composable
private fun PinnedDot() {
    Box(
        Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(Berth.colors.accent)
            .semantics { contentDescription = "Pinned to the Deck" },
    )
}

/**
 * Add or edit a snippet: name, Mono body, scope (global or one host), workspace, default action,
 * run on connect for host-scoped snippets, Deck pin and tags. Placeholders are listed as typed.
 * A new snippet may start from [initialBody] (Save as snippet in the History sheet, spec C16),
 * named after its first words until the name is edited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetEditorSheet(vm: AppViewModel, existing: Snippet?, onDismiss: () -> Unit, defaultHostId: String? = null, initialBody: String? = null) {
    val c = Berth.colors
    val hosts by vm.hosts.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    var name by remember { mutableStateOf(existing?.name ?: initialBody?.lineSequence()?.firstOrNull()?.trim()?.take(40) ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: initialBody ?: "") }
    var hostId by remember { mutableStateOf(existing?.hostId ?: defaultHostId) }
    var workspaceId by remember { mutableStateOf(existing?.workspaceId) }
    var action by remember { mutableStateOf(existing?.defaultAction ?: SnippetAction.RUN) }
    var runOnConnect by remember { mutableStateOf(existing?.runOnConnect ?: false) }
    var pinned by remember { mutableStateOf(existing?.pinnedToDeck ?: false) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }

    val draft = Snippet(
        id = existing?.id ?: "new",
        name = name.trim(),
        body = body,
        hostId = hostId,
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        defaultAction = action,
        runOnConnect = runOnConnect && hostId != null,
        pinnedToDeck = pinned,
        workspaceId = workspaceId,
    )
    val placeholders = draft.placeholders()
    val canSave = draft.name.isNotEmpty() && body.isNotBlank()

    BerthSheet(
        onDismiss = onDismiss,
        // A form opens whole: half-open, its last rows and Save were cut at the screen's edge
        // (the audit saw a 31 dp Run on connect). It scrolls under the keyboard instead.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(if (existing == null) "New snippet" else "Edit snippet")
            BerthField(name, { name = it }, label = "Name", placeholder = "restart nginx")
            BerthField(
                body,
                { body = it },
                label = "Command",
                placeholder = "tail -n {{lines:200}} -f {{path}}",
                mono = true,
                singleLine = false,
                minLines = 3,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                helper = if (placeholders.isEmpty()) "Use {{name}} or {{name:default}} for the parts that change."
                else "Asks for " + placeholders.joinToString(", ") { (n, d) -> if (d != null) "$n (default $d)" else n } + " when it runs.",
            )
            Panel {
                CyclePicker("Scope", listOf<String?>(null) + hosts.map { it.id }, hostId, { id -> id?.let { i -> hosts.firstOrNull { it.id == i }?.name } ?: "Global" }) { hostId = it; if (it == null) runOnConnect = false }
                CyclePicker("Workspace", listOf<String?>(null) + workspaces.map { it.id }, workspaceId, { id -> id?.let { i -> workspaces.firstOrNull { it.id == i }?.name } ?: "Everywhere" }) { workspaceId = it }
                if (hostId != null) {
                    ToggleRow("Run on connect", runOnConnect, { runOnConnect = it }, caption = "Typed into the first shell after this host connects")
                }
                ToggleRow("Pin to Deck", pinned, { pinned = it }, caption = "A key on the Deck's Snippets layer")
            }
            Text("Tap does", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp))
            SegmentedControl(listOf("Run", "Paste"), action.ordinal, { action = SnippetAction.entries[it] })
            BerthField(tags, { tags = it }, label = "Tags", placeholder = "logs, nginx", helper = "Comma separated; searchable.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(
                    if (existing == null) "Add" else "Save",
                    kind = ButtonKind.PRIMARY,
                    enabled = canSave,
                    onClick = {
                        vm.saveSnippet(draft.copy(id = existing?.id ?: UUID.randomUUID().toString()))
                        onDismiss()
                    },
                )
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                if (existing != null) {
                    Spacer(Modifier.weight(1f))
                    BerthButton("Delete", kind = ButtonKind.DESTRUCTIVE, onClick = { vm.deleteSnippet(existing.id); onDismiss() })
                }
            }
        }
    }
}

/**
 * The small sheet before a snippet with placeholders runs: one field per placeholder, the final
 * command in Mono, then Run or Paste. Snippets without placeholders never come through here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetRunSheet(vm: AppViewModel, session: TerminalSession, pending: PendingSnippet, onDismiss: () -> Unit) {
    val c = Berth.colors
    val snippet = pending.snippet
    val record by session.record.collectAsState()
    val placeholders = remember(snippet.id) { snippet.placeholders() }
    val values = remember(snippet.id) { mutableStateOf(placeholders.associate { (n, d) -> n to (d ?: "") }) }
    val missing = placeholders.filter { (n, _) -> values.value[n].isNullOrEmpty() }.map { it.first }
    // The preview keeps an unfilled placeholder as its accent token instead of a hole in the command.
    val rendered = snippet.render(values.value + missing.associateWith { "{{$it}}" })

    fun send(action: SnippetAction) {
        vm.runSnippet(session, snippet, values.value, action)
        onDismiss()
    }

    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(snippet.name, "into ${record.title.ifBlank { record.hostSnapshot.name }}")
            for ((n, d) in placeholders) {
                BerthField(
                    values.value[n] ?: "",
                    { v -> values.value = values.value + (n to v) },
                    label = n,
                    placeholder = d ?: "required",
                    mono = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
            }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Will send")
                Text(highlightedBody(rendered, oneLine = false), style = BerthType.mono, color = c.text1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Run", kind = if (pending.action == SnippetAction.RUN) ButtonKind.PRIMARY else ButtonKind.SECONDARY, enabled = missing.isEmpty(), onClick = { send(SnippetAction.RUN) })
                BerthButton("Paste", kind = if (pending.action == SnippetAction.PASTE) ButtonKind.PRIMARY else ButtonKind.SECONDARY, enabled = missing.isEmpty(), onClick = { send(SnippetAction.PASTE) })
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
            if (missing.isNotEmpty()) {
                Text("Fill in ${missing.joinToString(", ")} first.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/**
 * From the session sheet: the snippets visible to this session's host and workspace. Tap runs
 * (through the placeholder sheet when needed); long-press pastes instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetPickerSheet(vm: AppViewModel, session: TerminalSession, onDismiss: () -> Unit) {
    val c = Berth.colors
    val snippets by vm.snippets.collectAsState()
    val record by session.record.collectAsState()
    var pending by remember { mutableStateOf<PendingSnippet?>(null) }
    var editor by remember { mutableStateOf(false) }
    val visible = snippets.filter { it.visibleFor(record.hostId, record.workspaceId) }.sortedBy { it.name.lowercase() }

    fun start(snippet: Snippet, action: SnippetAction) {
        if (snippet.hasPlaceholders) pending = PendingSnippet(snippet, action) else { vm.runSnippet(session, snippet, action = action); onDismiss() }
    }

    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Snippets", record.title.ifBlank { record.hostSnapshot.name })
            if (visible.isEmpty()) {
                Text("No snippets for this host yet.", style = BerthType.body, color = c.text2)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in visible) {
                        SnippetRow(
                            snippet = s,
                            canRun = true,
                            onTap = { start(s, s.defaultAction) },
                            onRun = { start(s, SnippetAction.RUN) },
                            onPaste = { start(s, SnippetAction.PASTE) },
                            onPin = { vm.saveSnippet(s.copy(pinnedToDeck = !s.pinnedToDeck)) },
                        )
                    }
                }
            }
            BerthButton("New snippet for ${record.hostSnapshot.name}", kind = ButtonKind.TEXT, onClick = { editor = true })
        }
    }
    pending?.let { p -> SnippetRunSheet(vm, session, p, onDismiss = { pending = null; onDismiss() }) }
    if (editor) SnippetEditorSheet(vm, existing = null, onDismiss = { editor = false }, defaultHostId = record.hostId)
}
