package app.berth.android.ui.stage

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.tabs.LocalTabStripStyle
import app.berth.android.ui.tabs.StripChrome
import app.berth.android.ui.tabs.rememberResolvedTabStyle
import app.berth.android.ui.terminal.TerminalSearch
import app.berth.android.ui.terminal.TerminalSelection
import app.berth.android.ui.terminal.TerminalViewport
import app.berth.android.ui.terminal.offsetShowing
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.terminal.PasteAnalysis
import app.berth.terminal.PasteClassifier
import kotlinx.coroutines.delay

/**
 * The Stage's text tools for one terminal tab (spec C16, C17, C18): its selection, its search,
 * its viewport, the paste waiting for a look in the preview sheet, the passing notice pill and
 * whether the history sheet is open. Remembered per tab id, so a tab switch starts clean and a
 * selection made on one tab never lands on another.
 */
class StageTools {
    val selection = TerminalSelection()
    val search = TerminalSearch()
    val viewport = TerminalViewport()
    var pendingPaste by mutableStateOf<PasteAnalysis?>(null)
    var notice by mutableStateOf<String?>(null)
    var historyOpen by mutableStateOf(false)

    /**
     * Sends [text] to the session as a paste, or holds it for the preview sheet when it is more
     * than one line, longer than [PasteClassifier.PREVIEW_CHARS] or carries control characters.
     */
    fun paste(session: TerminalSession, text: String, haptics: DeckHaptics?) {
        val analysis = PasteClassifier.analyze(text)
        if (analysis.needsPreview) {
            pendingPaste = analysis
        } else {
            session.paste(text)
            haptics?.paste()
        }
    }

    /** Opens the search bar, [prefill]ed when given, remembering where the view was. */
    fun openSearch(prefill: String? = null) {
        search.open(prefill?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }, viewport.scrollOffset)
    }

    /** Closes the search bar and puts the view back where it was when it opened. */
    fun closeSearch() {
        search.close()?.let { viewport.scrollOffset = it }
    }
}

@Composable
fun rememberStageTools(tabId: String?): StageTools = remember(tabId) { StageTools() }

/**
 * The Stage's header area: the tab [header], or the selection bar in its place while text is
 * selected (spec C18), with the search bar sliding in under either while a search is open (C17).
 * Both bars act on [session]; without a terminal tab only the header shows.
 */
@Composable
fun StageToolbar(tools: StageTools, session: TerminalSession?, header: @Composable () -> Unit) {
    val selected = session != null && tools.selection.active
    if (session != null && selected) SelectionBar(tools, session) else header()
    AnimatedVisibility(visible = session != null && tools.search.open) {
        if (session != null) SearchBar(tools, session)
    }
    BackHandler(enabled = selected) { tools.selection.clear() }
    BackHandler(enabled = session != null && tools.search.open) { tools.closeSearch() }
}

/**
 * `24 chars · Copy · Paste · Search · Share · ⋮ · ×` where the tab strip was (spec C18). Copy
 * gives one Confirm tick and a passing "Copied" pill; the overflow holds Select all and, when the
 * selection is a link, Open. Same height and fill as the header so nothing below moves.
 */
@Composable
private fun SelectionBar(tools: StageTools, session: TerminalSession) {
    val c = Berth.colors
    val style = LocalTabStripStyle.current
    val resolved = rememberResolvedTabStyle(style)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberDeckHaptics()
    val selection = tools.selection
    var menu by remember { mutableStateOf(false) }
    fun text(): String = selection.text(session.emulator)
    fun copy() {
        val t = text()
        if (t.isNotEmpty()) {
            clipboard.setText(AnnotatedString(t))
            haptics.copy()
            tools.notice = "Copied"
        }
        selection.clear()
    }
    val row: @Composable (Modifier) -> Unit = { m ->
        Row(m.height(style.height), verticalAlignment = Alignment.CenterVertically) {
            Text(
                selection.summary,
                style = BerthType.label,
                color = c.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp).semantics { contentDescription = "Selected ${selection.summary}" },
            )
            Spacer(Modifier.weight(1f))
            BarAction("Copy", onClick = ::copy)
            BarAction("Paste") {
                clipboard.getText()?.text?.let { tools.paste(session, it, haptics) }
                selection.clear()
            }
            BarAction("Search") {
                val t = text()
                selection.clear()
                tools.openSearch(t)
            }
            BarAction("Share") {
                val t = text()
                selection.clear()
                if (t.isNotEmpty()) share(context, t)
            }
            Box {
                IconAction(onClick = { menu = true }, description = "Selection options") { BerthIcon(BerthIcons.moreVert) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface3, shape = RoundedCornerShape(BerthRadius.row)) {
                    DropdownMenuItem(
                        text = { Text("Select all", style = BerthType.body, color = c.text1) },
                        onClick = {
                            menu = false
                            synchronized(session.emulator.lock) { selection.selectAll(session.emulator) }
                        },
                    )
                    val link = remember(selection.range) { linkIn(text()) }
                    if (link != null) {
                        DropdownMenuItem(
                            text = { Text("Open link", style = BerthType.body, color = c.text1) },
                            onClick = {
                                menu = false
                                selection.clear()
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                        )
                    }
                }
            }
            IconAction(onClick = { selection.clear() }, description = "Clear selection") { BerthIcon(BerthIcons.close) }
        }
    }
    when (style.chrome) {
        StripChrome.FLAT -> row(Modifier.fillMaxWidth().background(resolved.headerFill).statusBarsPadding())
        StripChrome.ISLAND -> Box(
            Modifier
                .fillMaxWidth()
                .background(resolved.headerFill)
                .statusBarsPadding()
                .padding(start = style.islandInset, end = style.islandInset, top = style.islandInset),
        ) {
            row(Modifier.fillMaxWidth().clip(RoundedCornerShape(resolved.islandRadius)).background(resolved.islandFill))
        }
    }
}

/** A text action in a bar: accent Label, 44 dp tall, one tonal step when pressed. */
@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(if (pressed) c.surface3 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BerthType.label, color = c.accent, maxLines = 1)
    }
}

/**
 * The search bar under the header (spec C17): the query field with the `Aa` and `.*` toggles
 * behind it, `3/12`, previous, next and close. Enter and the keyboard's search action step to the
 * next match; the view scrolls to the current match and never moves the input line.
 */
@Composable
private fun SearchBar(tools: StageTools, session: TerminalSession) {
    val c = Berth.colors
    val search = tools.search
    val viewport = tools.viewport
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(search.query, TextRange(search.query.length))) }
    // A "Search for this" from the selection bar lands in the field selected, ready to be replaced.
    LaunchedEffect(Unit) {
        if (search.query.isNotEmpty()) field = TextFieldValue(search.query, TextRange(0, search.query.length))
        runCatching { focus.requestFocus() }
    }
    fun step(delta: Int) {
        val range = search.step(delta, session.emulator) ?: return
        viewport.scrollOffset = synchronized(session.emulator.lock) { offsetShowing(session.emulator, range.start.row, viewport.scrollOffset) }
    }
    // A fresh set of matches brings the current one into view when it is not already.
    LaunchedEffect(search.matches, search.current) {
        val range = search.currentRange(session.emulator) ?: return@LaunchedEffect
        viewport.scrollOffset = synchronized(session.emulator.lock) { offsetShowing(session.emulator, range.start.row, viewport.scrollOffset) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface1)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BerthField(
            value = field,
            onValueChange = {
                field = it
                if (search.query != it.text) {
                    search.query = it.text
                    search.invalidate()
                }
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Find in scrollback" },
            placeholder = "Find",
            mono = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { step(1) }, onDone = { step(1) }),
            focusRequester = focus,
            trailing = {
                Toggle("Aa", search.caseSensitive, "Match case") {
                    search.caseSensitive = it
                    search.invalidate()
                }
                Toggle(".*", search.regex, "Regular expression") {
                    search.regex = it
                    search.invalidate()
                }
            },
        )
        Text(
            search.countLabel,
            style = BerthType.caption,
            color = if (search.query.isNotEmpty() && search.matches.isEmpty()) c.danger else c.text2,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "Matches ${search.countLabel.ifEmpty { "none" }}" },
        )
        IconAction(onClick = { step(-1) }, description = "Previous match", enabled = search.matches.isNotEmpty()) {
            BerthIcon(BerthIcons.chevronRight, modifier = Modifier.rotate(-90f))
        }
        IconAction(onClick = { step(1) }, description = "Next match", enabled = search.matches.isNotEmpty()) {
            BerthIcon(BerthIcons.chevronRight, modifier = Modifier.rotate(90f))
        }
        IconAction(onClick = { tools.closeSearch() }, description = "Close search") { BerthIcon(BerthIcons.close) }
    }
}

/** `Aa` or `.*` behind the field: mono Caption, accent when on, text.3 when off. */
@Composable
private fun Toggle(glyph: String, on: Boolean, description: String, onChange: (Boolean) -> Unit) {
    val c = Berth.colors
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .width(32.dp)
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(if (on) c.surface4 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onChange(!on) }
            .semantics {
                contentDescription = description
                role = Role.Switch
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = BerthType.mono.copy(fontSize = BerthType.caption.fontSize), color = if (on) c.accent else c.text3, maxLines = 1)
    }
}

/**
 * The look before a paste that is more than one line, long, or carries control characters (spec
 * C18, as a sheet): the text, how many lines, a warning for control characters and, with
 * bracketed paste off on the remote, how many commands the shell will run as the lines land.
 * Paste sends the text as it is; Paste as one line folds it, control characters dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastePreviewSheet(analysis: PasteAnalysis, session: TerminalSession, haptics: DeckHaptics, onDismiss: () -> Unit) {
    val c = Berth.colors
    val bracketed = session.emulator.bracketedPaste
    val title = when {
        analysis.lines > 1 -> "Paste ${analysis.lines} lines"
        else -> "Paste ${"%,d".format(analysis.chars)} characters"
    }
    val caption = when {
        bracketed -> "The shell has bracketed paste on, so the text lands as one block."
        analysis.lineBreaks > 0 -> "Bracketed paste is off: the shell will run ${analysis.lineBreaks} ${if (analysis.lineBreaks == 1) "command" else "commands"} as the lines land."
        else -> null
    }
    fun send(text: String) {
        session.paste(text)
        haptics.paste()
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(title, caption)
            if (analysis.hasControlChars) {
                Text("Contains control characters the shell would act on.", style = BerthType.caption, color = c.danger)
            }
            Panel {
                Text(
                    previewOf(analysis.text),
                    style = MonoBody,
                    color = c.text1,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton("Paste", kind = ButtonKind.PRIMARY, onClick = { send(analysis.text) })
                BerthButton("Paste as one line", onClick = { send(PasteClassifier.asOneLine(PasteClassifier.stripControl(analysis.text))) })
                Spacer(Modifier.weight(1f))
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

/** The passing confirmation over the terminal ("Copied"): the existing pill, gone after a moment. */
@Composable
fun NoticePill(tools: StageTools, modifier: Modifier = Modifier) {
    val text = tools.notice ?: return
    val c = Berth.colors
    LaunchedEffect(text) {
        delay(NOTICE_MS)
        if (tools.notice == text) tools.notice = null
    }
    Box(modifier.padding(top = 12.dp)) {
        Pill(text, color = c.surface3, textColor = c.text1)
    }
}

private const val NOTICE_MS = 1_400L
private const val PREVIEW_LINES = 12

/** The first lines of a paste for the sheet, control characters shown as their caret names. */
private fun previewOf(text: String): String {
    val lines = text.split("\r\n", "\n", "\r")
    val shown = lines.take(PREVIEW_LINES).joinToString("\n") { line ->
        buildString {
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                if (PasteClassifier.isControl(cp) && cp != '\t'.code) append('^').append(((cp + 0x40) and 0x7F).toChar()) else appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
    }
    return if (lines.size > PREVIEW_LINES) "$shown\n\u2026 ${lines.size - PREVIEW_LINES} more" else shown
}

private val LINK = Regex("""https?://\S+|ssh://\S+|ftp://\S+""")

/** The link a selection is, when the whole of it is one. */
private fun linkIn(text: String): String? {
    val t = text.trim()
    return LINK.matchEntire(t)?.value
}

private fun share(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
