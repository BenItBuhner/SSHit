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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.BerthMotion
import app.berth.android.ui.a11y.TouchTargetSize
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.a11y.touchTarget
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.keyboard.StageFocus
import app.berth.android.ui.keyboard.StageRegion
import app.berth.android.ui.keyboard.rememberStageFocus
import app.berth.android.ui.keyboard.stageRegion
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
import app.berth.domain.model.SessionState
import app.berth.terminal.PasteAnalysis
import app.berth.terminal.PasteClassifier
import app.berth.terminal.ScrollbackSearch
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
     * Every route a paste can take (the bar, the two-finger tap, the Deck, the keyboard's paste)
     * comes through here, so this is where a session that is not Live turns it down: a paste
     * into a detached or failed tab has no shell to land in, and says so instead of vanishing.
     */
    fun paste(session: TerminalSession, text: String, haptics: DeckHaptics?) {
        if (session.state != SessionState.LIVE) {
            notice = NOT_CONNECTED
            return
        }
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

    companion object {
        /** The notice for a paste into a session without a shell. */
        const val NOT_CONNECTED = "Not connected"
    }
}

@Composable
fun rememberStageTools(tabId: String?): StageTools = remember(tabId) { StageTools() }

/**
 * The Stage's header area: the tab [header], or the selection bar in its place while text is
 * selected (spec C18), with the search bar sliding in under either while a search is open (C17).
 * Both bars act on [session]; without a terminal tab only the header shows. To the keyboard either
 * bar is the Stage's [StageRegion.Bar], the region Escape leaves for the terminal, closing the search.
 */
@Composable
fun StageToolbar(tools: StageTools, session: TerminalSession?, focus: StageFocus = rememberStageFocus(), header: @Composable () -> Unit) {
    val selected = session != null && tools.selection.active
    if (session != null && selected) Box(Modifier.stageRegion(focus, StageRegion.Bar)) { SelectionBar(tools, session) } else header()
    AnimatedVisibility(visible = session != null && tools.search.open, enter = BerthMotion.unfoldIn(), exit = BerthMotion.foldOut()) {
        if (session != null) Box(Modifier.stageRegion(focus, StageRegion.Bar)) { SearchBar(tools, session) }
    }
    BackHandler(enabled = selected) { tools.selection.clear() }
    BackHandler(enabled = session != null && tools.search.open) { tools.closeSearch() }
}

/**
 * `24 chars · Copy · Paste · Search · Share · ⋮ · ×` where the tab strip was (spec C18). Copy
 * gives one Confirm tick and a passing "Copied" pill; the overflow holds Select all and, when the
 * selection is a link, Open. Paste is there only while the session is Live: on a frozen frame there
 * is nothing to paste into, and its absence says so. Same height and fill as the header so nothing
 * below moves.
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
    val record by session.record.collectAsState()
    val live = record.state == SessionState.LIVE
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
                modifier = Modifier.padding(start = 12.dp, end = 4.dp).semantics { contentDescription = "Selection, ${selection.summary}" },
            )
            Spacer(Modifier.weight(1f))
            BarAction("Copy", onClick = ::copy)
            if (live) {
                BarAction("Paste") {
                    clipboard.getText()?.text?.let { tools.paste(session, it, haptics) }
                    selection.clear()
                }
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
                BerthMenu(expanded = menu, onDismiss = { menu = false }) {
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
    val focused = interaction.showsFocus()
    Box(
        Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(if (pressed || focused) c.surface3 else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            // A short word (Copy) still answers to a 48 dp column; the longer ones set their own width.
            .defaultMinSize(minWidth = TouchTargetSize)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BerthType.label, color = c.accent, maxLines = 1)
    }
}

/**
 * The search bar under the header (spec C17): the query field with the `Aa` and `.*` toggles
 * behind it, `3/12`, previous, next and close. Enter and the keyboard's search action step to the
 * next match; the view scrolls to the current match and never moves the input line. The fill and
 * the island geometry come from the tab strip's resolved style, as the selection bar's do, so the
 * bar sits in the header's chrome under any direction.
 */
@Composable
private fun SearchBar(tools: StageTools, session: TerminalSession) {
    val c = Berth.colors
    val style = LocalTabStripStyle.current
    val resolved = rememberResolvedTabStyle(style)
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
    val row: @Composable (Modifier) -> Unit = { m -> SearchBarRow(tools, field, { field = it }, focus, ::step, m) }
    when (style.chrome) {
        StripChrome.FLAT -> row(Modifier.fillMaxWidth().background(resolved.headerFill))
        StripChrome.ISLAND -> Box(
            Modifier
                .fillMaxWidth()
                .background(resolved.headerFill)
                .padding(start = style.islandInset, end = style.islandInset, bottom = style.islandInset),
        ) {
            row(Modifier.fillMaxWidth().clip(RoundedCornerShape(resolved.islandRadius)).background(resolved.islandFill))
        }
    }
}

/** The search bar's row: the field with its toggles, the count, previous, next and close. */
@Composable
private fun SearchBarRow(
    tools: StageTools,
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    step: (Int) -> Unit,
    modifier: Modifier,
) {
    val c = Berth.colors
    val search = tools.search
    Row(modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BerthField(
            value = field,
            onValueChange = {
                onField(it)
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
                // A pattern that does not compile is searched as literal text; the glyph in the danger colour says so.
                Toggle(".*", search.regex, "Regular expression", warn = search.regex && search.query.isNotEmpty() && !ScrollbackSearch.isValidRegex(search.query)) {
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

/** `Aa` or `.*` behind the field: mono Caption, accent when on, text.3 when off, danger while [warn] (the pattern is invalid and the search is literal). */
@Composable
private fun Toggle(glyph: String, on: Boolean, description: String, warn: Boolean = false, onChange: (Boolean) -> Unit) {
    val c = Berth.colors
    // A 32 dp glyph box in a 48 dp target; the switch's own on/off is what a screen reader hears
    // unless the pattern does not compile, which then stands in for the state.
    Box(
        Modifier
            .toggleable(value = on, role = Role.Switch, indication = null, interactionSource = remember { MutableInteractionSource() }, onValueChange = onChange)
            .semantics {
                contentDescription = description
                if (warn) stateDescription = INVALID_PATTERN
            }
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(BerthRadius.row))
                .background(if (on) c.surface4 else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = BerthType.mono.copy(fontSize = BerthType.caption.fontSize), color = if (warn) c.danger else if (on) c.accent else c.text3, maxLines = 1)
        }
    }
}

/** The `.*` toggle's state while its pattern does not compile and the literal text is searched instead. */
const val INVALID_PATTERN = "Invalid pattern, searching the text as typed"

/**
 * The look before a paste that is more than one line, long, or carries control characters (spec
 * C18, as a sheet): the text with control characters shown by their caret names, how many lines,
 * and, with bracketed paste off on the remote, how many commands the shell will run as the lines
 * land. Two shapes. Plain text: Paste, filled, sends it as it is; Paste as one line folds it. With
 * control characters the sheet is a warning, and the weight goes to the safe answer: the filled
 * button is Paste without control characters (they are dropped, the lines kept), Paste as is
 * stands beside it plain, Paste as one line third when there are lines to fold, and the warning
 * with the count and the names is the title's caption. Should the session stop being Live while
 * the sheet is open, the sending buttons disable and the caption says Not connected.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PastePreviewSheet(analysis: PasteAnalysis, session: TerminalSession, haptics: DeckHaptics, onDismiss: () -> Unit) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val live = record.state == SessionState.LIVE
    val bracketed = session.emulator.bracketedPaste
    val title = when {
        analysis.lines > 1 -> "Paste ${analysis.lines} lines"
        else -> "Paste ${"%,d".format(analysis.chars)} characters"
    }
    val warning = if (analysis.hasControlChars) controlWarning(analysis) else null
    val status = when {
        !live -> StageTools.NOT_CONNECTED
        bracketed -> "The shell has bracketed paste on, so the text lands as one block."
        analysis.lineBreaks > 0 -> "Bracketed paste is off: the shell will run ${analysis.lineBreaks} ${if (analysis.lineBreaks == 1) "command" else "commands"} as the lines land."
        else -> null
    }
    val statusColor = if (live) c.text2 else c.danger
    fun send(text: String) {
        if (session.state != SessionState.LIVE) return
        session.paste(text)
        haptics.paste()
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
            if (warning != null) {
                SheetTitle(title, warning, captionColor = c.danger)
                if (status != null) Text(status, style = BerthType.caption, color = statusColor)
            } else {
                SheetTitle(title, status, captionColor = statusColor)
            }
            Panel {
                Text(
                    previewOf(analysis.text),
                    style = MonoBody,
                    color = c.text1,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (warning != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Paste without control characters", kind = ButtonKind.PRIMARY, enabled = live, onClick = { send(PasteClassifier.stripControl(analysis.text)) })
                    BerthButton("Paste as is", enabled = live, onClick = { send(analysis.text) })
                    if (analysis.isMultiLine) {
                        BerthButton("Paste as one line", enabled = live, onClick = { send(PasteClassifier.asOneLine(PasteClassifier.stripControl(analysis.text))) })
                    }
                    BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Paste", kind = ButtonKind.PRIMARY, enabled = live, onClick = { send(analysis.text) })
                    BerthButton("Paste as one line", enabled = live, onClick = { send(PasteClassifier.asOneLine(analysis.text)) })
                    Spacer(Modifier.weight(1f))
                    BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                }
            }
        }
    }
}

/** `Has 2 control characters (^[, ^C) the shell would act on.`: the count and the first names, for the sheet's caption. */
fun controlWarning(analysis: PasteAnalysis): String {
    val n = analysis.controlChars
    return "Has $n control ${if (n == 1) "character" else "characters"} (${analysis.controlNames.joinToString(", ")}) the shell would act on."
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

/** The first lines of a paste for the sheet, control characters shown as their caret names, the same names the warning uses. */
private fun previewOf(text: String): String {
    val lines = text.split("\r\n", "\n", "\r")
    val shown = lines.take(PREVIEW_LINES).joinToString("\n") { line ->
        buildString {
            var i = 0
            while (i < line.length) {
                val cp = line.codePointAt(i)
                if (PasteClassifier.isControl(cp)) append(PasteClassifier.caretName(cp)) else appendCodePoint(cp)
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
