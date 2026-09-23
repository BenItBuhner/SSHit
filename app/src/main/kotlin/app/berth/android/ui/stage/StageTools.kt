package app.berth.android.ui.stage

import android.content.ActivityNotFoundException
import android.content.Intent
import android.icu.text.IDNA
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
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
import app.berth.android.ui.components.BerthMenuItem
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
import app.berth.android.ui.tabs.NOTICE_BAR_MS
import app.berth.android.ui.tabs.StripChrome
import app.berth.android.ui.tabs.rememberResolvedTabStyle
import app.berth.android.ui.terminal.LinkTap
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
import java.util.EnumSet
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
    /** The one-line hint with an action in the notice's slot (spec C22, the Ctrl+W hint); a new one replaces the last. */
    var hint by mutableStateOf<StageHint?>(null)
    var historyOpen by mutableStateOf(false)

    /** The OSC 8 link tapped on the canvas and waiting for a look in [LinkOpenSheet] (spec A60). */
    var pendingLink by mutableStateOf<LinkTap?>(null)

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

        /** The selection bar's overflow toggle for block selection (spec C18 as ruled in review #9). */
        const val RECTANGULAR = "Rectangular"

        /** The notice when no app on the phone takes a link's scheme. */
        const val NOTHING_OPENS = "Nothing on this phone opens that link"
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
 * gives one Confirm tick and a passing "Copied" pill; the overflow holds Select all, Rectangular
 * and, when the selection is a link, Open. Paste is there only while the session is Live: on a
 * frozen frame there is nothing to paste into, and its absence says so. Same height and fill as
 * the header so nothing below moves. Where the row is too narrow for all of them (a phone at the
 * interface's font cap), Share and then Search step into the overflow rather than squeeze the
 * buttons after them under their 48 dp: the bar measures what its actions need against the width
 * it has, so nothing is cut and nothing is guessed from the scale.
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
    fun search() {
        val t = text()
        selection.clear()
        tools.openSearch(t)
    }
    fun shareText() {
        val t = text()
        selection.clear()
        if (t.isNotEmpty()) share(context, t)
    }
    val row: @Composable (Modifier) -> Unit = { m ->
        BoxWithConstraints(m.height(style.height)) {
            val kept = barActionsThatFit(constraints.maxWidth, selection.summary, listOfNotNull("Copy", if (live) "Paste" else null), listOf("Search", "Share"))
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selection.summary,
                    style = BerthType.label,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = SUMMARY_PAD_START, end = SUMMARY_PAD_END).semantics { contentDescription = "Selection, ${selection.summary}" },
                )
                Spacer(Modifier.weight(1f))
                BarAction("Copy", onClick = ::copy)
                if (live) {
                    BarAction("Paste") {
                        clipboard.getText()?.text?.let { tools.paste(session, it, haptics) }
                        selection.clear()
                    }
                }
                if (kept >= 1) BarAction("Search", onClick = ::search)
                if (kept >= 2) BarAction("Share", onClick = ::shareText)
                Box {
                    IconAction(onClick = { menu = true }, description = "Selection options") { BerthIcon(BerthIcons.moreVert) }
                    BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                        if (kept < 1) BerthMenuItem("Search", onClick = { menu = false; search() })
                        if (kept < 2) BerthMenuItem("Share", onClick = { menu = false; shareText() })
                        BerthMenuItem("Select all", onClick = {
                            menu = false
                            synchronized(session.emulator.lock) { selection.selectAll(session.emulator) }
                        })
                        // The block between the selection's corners instead of the stream from one to the
                        // other (review #9: a toggle, not a second-finger gesture); on, in accent, until
                        // switched off, for this tab's later selections too.
                        BerthMenuItem(StageTools.RECTANGULAR, selected = selection.rectangular, onClick = {
                            menu = false
                            synchronized(session.emulator.lock) { selection.setRectangular(session.emulator, !selection.rectangular) }
                        })
                        // The link the selection is: the OSC 8 link its first cell was printed under (how a
                        // link is reached while the application has the mouse, spec A60), else its text when
                        // the whole of it is an address. Either goes through the link sheet for a look.
                        val link = remember(selection.range, selection.rectangular) { linkUnder(session, selection) ?: linkIn(text())?.let { LinkTap(it, it) } }
                        if (link != null) {
                            BerthMenuItem("Open link", onClick = {
                                menu = false
                                selection.clear()
                                tools.pendingLink = link
                            })
                        }
                    }
                }
                IconAction(onClick = { selection.clear() }, description = "Clear selection") { BerthIcon(BerthIcons.close) }
            }
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
            .padding(horizontal = BAR_ACTION_PAD),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BerthType.label, color = c.accent, maxLines = 1)
    }
}

private val BAR_ACTION_PAD = 8.dp
private val SUMMARY_PAD_START = 12.dp
private val SUMMARY_PAD_END = 4.dp

/**
 * How many of the bar's [optional] actions, taken from the front, stand in a row [width] pixels
 * wide beside [summary], the [always] actions and the two icon actions after them, each text
 * action as wide as [BarAction] makes it: its label in the Label style plus its padding, and 48 dp
 * at the least. The rest go into the overflow. Measured, not read off the font scale, so a wider
 * bar (a tablet, landscape) keeps them all at any scale and a narrow one sheds only what it must.
 */
@Composable
private fun barActionsThatFit(width: Int, summary: String, always: List<String>, optional: List<String>): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(width, summary, always, optional, density) {
        with(density) {
            fun action(label: String) = maxOf(TouchTargetSize.roundToPx(), measurer.measure(label, BerthType.label).size.width + (BAR_ACTION_PAD * 2).roundToPx())
            val fixed = measurer.measure(summary, BerthType.label).size.width + (SUMMARY_PAD_START + SUMMARY_PAD_END).roundToPx() +
                always.sumOf { action(it) } + 2 * TouchTargetSize.roundToPx()
            var kept = optional.size
            while (kept > 0 && fixed + optional.take(kept).sumOf { action(it) } > width) kept--
            kept
        }
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

/**
 * The look before an OSC 8 link opens (spec A60), on the paste preview's posture: the address in
 * full, since the text it wore on screen is the remote's choice and need not be where it goes. What
 * the sheet says and offers follows the link's scheme ([LinkLook]): a web page or a mail address
 * has Open filled; a `file://` link, what `ls --hyperlink` prints, names a file on the remote that
 * nothing on this phone can open, so its answers are Copy path and, on a live tab, Paste path (the
 * path quoted for the shell, through the paste path so bracketed paste applies); an `ssh://` or
 * `sftp://` link says that Berth itself is what opens it; any other scheme is another app's, and
 * the weight goes to Cancel with Open anyway plain beside it. When the text on screen claimed
 * another address than the link's (a link dressed as another site), the caption says so in the
 * danger colour and the weight goes to Cancel likewise, as the clipboard notice weighs its
 * answers, and the panel draws the host as the caption names it, in ASCII ([LinkLook.address]),
 * so the sheet's largest text and its warning agree; Open anyway and Copy take the address as it
 * came. Copy puts the address on the clipboard, Berth's copy, for a look elsewhere first. A
 * link nothing on the phone handles says so in the notice pill rather than doing nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LinkOpenSheet(link: LinkTap, session: TerminalSession, tools: StageTools, haptics: DeckHaptics, onDismiss: () -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val look = remember(link) { LinkLook.of(link) }
    val record by session.record.collectAsState()
    val live = record.state == SessionState.LIVE
    fun open() {
        onDismiss()
        // A plain view of the address, never Intent.parseUri: the URL cannot name an intent of its own.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            tools.notice = StageTools.NOTHING_OPENS
        } catch (e: RuntimeException) {
            tools.notice = "Couldn't open that link"
        }
    }
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        tools.notice = "Copied"
        onDismiss()
    }
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Open link", look.caption, captionColor = if (look.warning) c.danger else c.text2)
            Panel {
                // Left to right whatever the address holds, so a right-to-left run in a path can never re-order the scheme and host.
                Text(
                    look.address,
                    style = MonoBody.copy(textDirection = TextDirection.Ltr),
                    color = c.text1,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).semantics { contentDescription = "Link address, ${look.address}" },
                )
            }
            when {
                look.posture == LinkLook.Posture.FILE -> {
                    val path = look.path.orEmpty()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BerthButton("Copy path", kind = ButtonKind.PRIMARY, onClick = { copy(path) })
                        if (live) {
                            BerthButton("Paste path", onClick = {
                                onDismiss()
                                tools.paste(session, LinkLook.shellQuote(path), haptics)
                            })
                        }
                        BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                    }
                }
                look.warning || look.posture == LinkLook.Posture.OTHER_APP -> {
                    // Three answers side by side, wrapping where they will not fit (the interface's font cap), as the paste warning's do.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BerthButton("Cancel", onClick = onDismiss)
                        BerthButton("Open anyway", kind = ButtonKind.TEXT, onClick = ::open)
                        BerthButton("Copy", kind = ButtonKind.TEXT, onClick = { copy(link.url) })
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BerthButton("Open", kind = ButtonKind.PRIMARY, onClick = ::open)
                        BerthButton("Copy", kind = ButtonKind.TEXT, onClick = { copy(link.url) })
                        Spacer(Modifier.weight(1f))
                        BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

/**
 * What the link sheet says over the address and which answers it lays out (spec A60): the
 * [posture] from the link's scheme, the [caption] from where the link goes and from the text it
 * wore on screen, and [warning] when that text claimed another address than the link's. A text
 * claims an address when it carries a scheme, begins with `www.`, or is a bare host whose last
 * label is letters, two or more of them, that is not a file extension a terminal prints all day
 * (`notes.txt`, `main.py`, `Node.js`, `report.pdf` are names, not sites) and is not a segment of
 * the link's own path, since a text that appears in the address is the address naming itself
 * (a directory listing's every entry). Version numbers and decimals end in digits and claim
 * nothing. A claim is the link's own when it is the link's host, give or take `www.`, or a parent
 * of it (`github.com` over gist.github.com), which no stranger's host can be, unless the parent
 * is a platform that hands its labels or its pages to anyone ([UserContentHosts]: `github.io`
 * over evil.github.io, `google.com` over sites.google.com/view/paypal-login), where the claim is
 * true of the platform and says nothing of the page, so it warns. The query is not exempt:
 * `?r=google.com` is how a redirect dresses up. Every host the caption names is in ASCII, a
 * label outside it as its punycode ([asciiHost]), so `https://аpple.com/` with a Cyrillic а reads
 * *goes to xn--pple-43d.com* and not as Apple's. The panel's [address] is the link's as it came,
 * save that when the text claimed another site than the link's the host is drawn there as the
 * caption names it ([withAsciiHost]), so the sheet's largest text cannot read apple.com over a
 * caption that says otherwise; a host the text names truly, or claims nothing over, stands as
 * written, since that is what is opened. For a `file://`
 * link [path] is the file's path, percent-decoded. An `http`, `https` or `file` address is read
 * the way the browser and Android's `Uri` that will open it read it, a `\` as a `/`, so its
 * authority ends at the first of either and `https://evil.example\@google.com/` goes to
 * evil.example, not to the host after the `@`; the sheet shows and opens the URL as it is. Pure,
 * so the rule is tested on its own ([LinkLookTest][app.berth.android.ui.stage.LinkLookTest]).
 */
class LinkLook(val caption: String, val warning: Boolean, val posture: Posture, val address: String, val path: String? = null) {
    enum class Posture {
        /** A web page or a mail address: Open filled, Copy and Cancel beside it. */
        OPEN,
        /** A file on the remote: nothing here opens it; Copy path, Paste path on a live tab, Cancel. */
        FILE,
        /** `ssh://` or `sftp://`: Berth itself opens it, through Quick connect's own look; Open filled. */
        BERTH,
        /** Any other scheme is another app's to open: Cancel carries the weight, Open anyway and Copy stand plain. */
        OTHER_APP,
    }

    companion object {
        /** Scheme, an authority whose userinfo runs to its last `@` (the browser's and `SshLink`'s reading), then the host: a bracketed IPv6 literal or a name. */
        private val SCHEME_HOST = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://(?:[^/?#\s]*@)?(\[[^\]]*\]|[^/?#:;@\s]+)""")
        /** A host of two labels or more in any script (a homograph claims like the host it apes), `www.` or not, a port and a tail drawn with any of the slashes a text can wear. */
        private val BARE_HOST = Regex("""^(?:www\.)?([\p{L}\p{N}\-]+(?:\.[\p{L}\p{N}\-]+)+)(?::\d+)?(?:[/?#\\].*)?$""")
        private val EMAIL = Regex("""^[^\s@<>"']+@[a-zA-Z0-9\-]+(?:\.[a-zA-Z0-9\-]+)+$""")
        private val SCHEME_PREFIX = Regex("""^[a-z][a-z0-9+.\-]*:(?://)?""")
        /** A `mailto:` link's domain: what follows the mailbox's last `@`, to its `?` if it carries one. */
        private val MAILTO_DOMAIN = Regex("""^mailto:[^?]*@([^?\s]+)""", RegexOption.IGNORE_CASE)

        /**
         * The schemes whose addresses a WHATWG parser, Chrome and Android's `Uri` among them, reads
         * with `\` as `/`: the special schemes the sheet parses a host from. The parsers that read
         * `https://evil.example\@google.com/` as google.com's are the ones that will never open it.
         */
        private val SLASH_SCHEMES = setOf("http", "https", "file")

        /** What a terminal prints as file names all day: a bare word ending in one of these is a name, not a host. */
        private val FILE_EXTENSIONS = setOf(
            "txt", "md", "rst", "log", "csv", "tsv", "json", "yaml", "yml", "toml", "xml", "html", "htm", "css", "ini", "cfg", "conf", "env", "lock", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt",
            "py", "js", "ts", "tsx", "jsx", "rs", "go", "kt", "kts", "java", "c", "h", "cc", "cpp", "hpp", "cs", "rb", "php", "pl", "pm", "sh", "bash", "zsh", "fish", "lua", "swift", "scala", "clj", "ex", "exs", "erl", "hs", "ml", "sql", "vue", "svelte", "gradle", "cmake", "mk", "nix", "tf", "proto",
            "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico", "mp3", "mp4", "mkv", "webm", "wav", "flac", "ogg", "mov", "avi",
            "zip", "gz", "tgz", "bz2", "xz", "zst", "tar", "rar", "jar", "war", "apk", "aab", "deb", "rpm", "iso", "img", "dmg", "exe", "dll", "so", "dylib", "bin", "o", "a", "class", "pyc", "wasm",
            "bak", "tmp", "swp", "orig", "rej", "patch", "diff", "map", "min", "sum", "sig", "asc", "gpg", "pem", "crt", "key", "pub",
        )

        fun of(link: LinkTap): LinkLook {
            val url = link.url.trim()
            val scheme = url.substringBefore(':', "").lowercase()
            // `ls` quotes a name with a space in it; the quotes are the listing's, not the name's.
            val shown = link.text.trim().trim('\'', '"')
            val truncated = "\u201C${shown.take(SHOWN_CHARS)}${if (shown.length > SHOWN_CHARS) "\u2026" else ""}\u201D"
            var path: String? = null
            // A `mailto:` link's mailbox, decoded and lowercased: what a shown mail address is measured against.
            var mailbox: String? = null
            // Where the link goes, as the caption's second half, and the host a shown text is measured against.
            val to: String?
            val where: String
            val posture: Posture
            when (scheme) {
                "http", "https" -> {
                    to = hostOf(url)
                    where = if (to != null) "goes to $to" else "a web link"
                    posture = Posture.OPEN
                }
                "file" -> {
                    to = hostOf(url)?.takeIf { it.isNotEmpty() }
                    path = percentDecode(afterAuthority(url).substringBefore('?').substringBefore('#')).ifEmpty { "/" }
                    where = if (to != null) "a file on $to: $path" else "a file: $path"
                    posture = Posture.FILE
                }
                "ssh", "sftp" -> {
                    to = hostOf(url)
                    where = "opens in Berth: ${sshTarget(url)}"
                    posture = Posture.BERTH
                }
                "mailto" -> {
                    val address = percentDecode(url.substringAfter(':').substringBefore('?')).trim()
                    mailbox = address.lowercase()
                    val domain = address.substringAfterLast('@', "")
                    to = asciiHost(domain).ifEmpty { null }
                    // The mailbox as written, save a domain outside ASCII, which reads as its punycode like every host the caption names.
                    where = "mails ${if (domain.all { it.code < 0x80 }) address else address.substringBeforeLast('@') + "@" + to}"
                    posture = Posture.OPEN
                }
                else -> {
                    to = null
                    where = "another app on this phone would open this ${scheme.ifEmpty { "plain" }} link"
                    posture = Posture.OTHER_APP
                }
            }
            val namesItself = shown.isEmpty() || sameAddress(shown, url) ||
                (posture == Posture.FILE && path != null && shown.lowercase() in segments(path)) ||
                (mailbox != null && percentDecode(shown.lowercase().removePrefix("mailto:")) == mailbox)
            if (namesItself) return LinkLook(where.replaceFirstChar(Char::uppercase), warning = false, posture = posture, address = link.url, path = path)
            // A mail address as the text claims that mailbox; the same one named itself above, so what is left is another's.
            val claim = hostClaim(shown, url) ?: EMAIL.matchEntire(shown)?.value?.lowercase()?.takeIf { mailbox != null }
            val deceptive = claim != null && (to == null || !sameSite(claim, to))
            // The panel's host as the caption's, once the caption says "but": the two agree on where the tap goes.
            val address = if (deceptive) withAsciiHost(url) else link.url
            return LinkLook("Shown as $truncated, ${if (deceptive) "but " else ""}$where", warning = deceptive, posture = posture, address = address, path = path)
        }

        /**
         * [claim] names the site the link goes [to]: that host, give or take `www.`, or a parent of it,
         * since `github.com` over gist.github.com is no lie and an attacker cannot make evil.example
         * end in `.google.com`, only carry it. One way: a claim deeper than the host is another host.
         * A parent is a domain, not a bare TLD: `www.com` shorn of its `www.` is no parent of every `.com`.
         * And a parent that is a platform's own name, or above it ([UserContentHosts]), is the
         * platform's and not the page's: `github.io` over evil.github.io, `s3.amazonaws.com` over
         * evil-bucket.s3.amazonaws.com, `google.com` over sites.google.com; the claim has to reach
         * below the platform, to the customer's label (`evil.github.io` over docs.evil.github.io), to
         * name the site. Both are ASCII already, as [hostOf] and [hostClaim] give them.
         */
        private fun sameSite(claim: String, to: String): Boolean {
            val c = claim.removePrefix("www.")
            val t = to.removePrefix("www.")
            if (c == t) return true
            if ('.' !in c || !t.endsWith(".$c")) return false
            val platform = UserContentHosts.boundaryOf(t) ?: return true
            // Both end the host at a dot; the longer of the two is the lower.
            return c.length > platform.length
        }

        /**
         * The host of an address with a scheme and an authority, as the parser that opens it would
         * read it ([asRead]), lowercased and in ASCII ([asciiHost]); null for one without (`mailto:`, a bare word),
         * and for a host made only of code points UTS #46 deletes (a lone soft hyphen), which names
         * nothing and which the browser refuses, so the caption names no host rather than an empty one.
         */
        fun hostOf(url: String): String? =
            SCHEME_HOST.find(asRead(url.trim()))?.groupValues?.get(1)?.takeUnless(::namesNothing)?.let(::asciiHost)

        private fun namesNothing(host: String): Boolean =
            host.any { it.code >= 0x80 } && UTS46.nameToASCII(host.lowercase(), StringBuilder(), IDNA.Info()).isEmpty()

        /**
         * The UTS #46 processor the browsers name a host by, with the WHATWG URL Standard's options
         * as Chrome sets them (`url_idna_icu.cc`): non-transitional, so `ß` and a final `ς` stand
         * and are encoded where IDNA2003 (`java.net.IDN`) mapped them to `ss` and `σ`; the bidi and
         * joiner checks; and not STD3's character set, so a `_` label beside one outside ASCII is
         * encoded as the browser encodes it. Built once, on the first host outside ASCII; the
         * instance is immutable.
         */
        private val UTS46: IDNA by lazy {
            IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ)
        }

        /**
         * What ICU reports that the WHATWG host parser sets aside (`beStrict` false: CheckHyphens
         * and VerifyDnsLength off), masked as Chrome masks them, so a label past 63 bytes or with
         * hyphens in its third and fourth places is encoded rather than refused.
         */
        private val LENIENT = EnumSet.of(
            IDNA.Error.LEADING_HYPHEN, IDNA.Error.TRAILING_HYPHEN, IDNA.Error.HYPHEN_3_4,
            IDNA.Error.EMPTY_LABEL, IDNA.Error.LABEL_TOO_LONG, IDNA.Error.DOMAIN_NAME_TOO_LONG,
        )

        /**
         * [host] as the resolver reads it: lowercased, and a label outside ASCII as its punycode
         * under UTS #46 as Chrome runs it ([UTS46], [LENIENT]), the way the browser that opens the
         * link writes a confusable host in its address bar, so `аpple.com` with a Cyrillic а is
         * xn--pple-43d.com wherever the caption names it and a claim of `apple.com` over it is
         * measured against that, and `straße.de` is xn--strae-oqa.de, the name the tap resolves,
         * rather than strasse.de (IDNA2003's reading, and a name another registrant may hold). A
         * bracketed IPv6 literal is ASCII already; a host the browser refuses too (a code point
         * UTS #46 disallows, a label opening on a combining mark, a bidi or joiner rule broken, or
         * nothing left once it is mapped, which Chrome counts as a failure) stands as it is, lowercased.
         */
        fun asciiHost(host: String): String {
            val lower = host.lowercase()
            if (lower.all { it.code < 0x80 }) return lower
            val info = IDNA.Info()
            val ascii = UTS46.nameToASCII(lower, StringBuilder(), info).toString()
            return if (ascii.isEmpty() || info.errors.any { it !in LENIENT }) lower else ascii.lowercase()
        }

        /**
         * [url] with its host as [asciiHost] names it and the rest as it came: the panel's [address]
         * when the text on screen claimed another site than the link's, so the sheet's largest text
         * and the caption's *but goes to* agree and a Cyrillic а cannot read as apple's there. The
         * host is the authority's after its last `@` ([SCHEME_HOST]), or a `mailto:` mailbox's domain;
         * an address with no host, one whose host is ASCII already, or one UTS #46 refuses, is [url]
         * itself. Read as [asRead], which keeps every index, so the range is [url]'s own.
         */
        fun withAsciiHost(url: String): String {
            val range = SCHEME_HOST.find(asRead(url))?.groups?.get(1)?.range
                ?: MAILTO_DOMAIN.find(url)?.groups?.get(1)?.range
                ?: return url
            val host = url.substring(range)
            if (host.all { it.code < 0x80 }) return url
            val ascii = asciiHost(host)
            return if (ascii.any { it.code >= 0x80 }) url else url.replaceRange(range, ascii)
        }

        /**
         * [url] as the parser that will open it reads it: in the [SLASH_SCHEMES] every `\` is a `/`,
         * so the authority ends at the first of either. For reading only; `Uri.parse` gets the URL as
         * it is, since that is what is opened, and so does the panel save the one host [withAsciiHost] redraws.
         */
        private fun asRead(url: String): String =
            if (url.indexOf('\\') >= 0 && url.substringBefore(':', "").lowercase() in SLASH_SCHEMES) url.replace('\\', '/') else url

        /**
         * The host a shown [text] claims to be, or null when it claims none: the host of an address
         * with a scheme, a `www.` name, or a bare host that is neither a file name nor a segment of
         * [url]'s own path. In ASCII ([asciiHost]): a Cyrillic `аpple.com` claims xn--pple-43d.com,
         * which is a claim all the same, measured against where the link goes.
         */
        fun hostClaim(text: String, url: String): String? {
            hostOf(text)?.let { return it }
            val host = BARE_HOST.matchEntire(text)?.groupValues?.get(1)?.lowercase() ?: return null
            if (text.startsWith("www.", ignoreCase = true)) return asciiHost(host)
            val last = host.substringAfterLast('.')
            if (last.length < 2 || !last.all { it.isLetter() }) return null
            if (last in FILE_EXTENSIONS) return null
            val ascii = asciiHost(host)
            val segments = segments(percentDecode(afterAuthority(url).substringBefore('?').substringBefore('#')))
            if (host in segments || ascii in segments) return null
            return ascii
        }

        /**
         * [path] as one word for a POSIX shell: as it is when it holds nothing the shell reads, else
         * in single quotes, a quote of its own closed, escaped and reopened, so a name with a space
         * or a `$` lands on the prompt as the name it is.
         */
        fun shellQuote(path: String): String {
            if (path.isNotEmpty() && path.all { it.isLetterOrDigit() && it.code < 128 || it in "-_./:+=@,%" }) return path
            return "'" + path.replace("'", "'\\''") + "'"
        }

        /** `%XX` runs read as UTF-8 bytes; anything malformed is left as it stands. */
        fun percentDecode(s: String): String {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                    out.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    out.write(bytes, 0, bytes.size)
                    i++
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** What follows `scheme://authority`, read as [asRead]: the path with its query and fragment, or nothing for an address with no path. */
        private fun afterAuthority(url: String): String {
            val read = asRead(url)
            val start = read.indexOf("://")
            if (start < 0) return ""
            val slash = read.indexOf('/', start + 3)
            return if (slash < 0) "" else read.substring(slash)
        }

        /** The path's segments, lowercased, empty ones dropped. */
        private fun segments(path: String): Set<String> = path.split('/').mapNotNullTo(HashSet()) { it.lowercase().takeIf { s -> s.isNotEmpty() } }

        /**
         * `user@host:port` for an `ssh://` or `sftp://` link, read as `SshLink` reads it: the authority to
         * the first `/`, `?` or `#`, the host after its last `@` (in ASCII, [asciiHost]), and the user's
         * `;fingerprint=` parameter (the draft URI keeps it in the userinfo) and password, never a
         * caption's business, left out.
         */
        private fun sshTarget(url: String): String {
            val start = url.indexOf("://") + 3
            val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
            val authority = url.substring(start, end)
            val at = authority.lastIndexOf('@')
            val user = if (at < 0) "" else authority.substring(0, at).substringBefore(';').substringBefore(':')
            val hostPort = authority.substring(at + 1).substringBefore(';')
            // A bracketed literal is ASCII and holds colons of its own; a name may carry a port after its only one.
            val target = when {
                hostPort.startsWith("[") || hostPort.all { it.code < 0x80 } -> hostPort
                ':' in hostPort -> asciiHost(hostPort.substringBeforeLast(':')) + ":" + hostPort.substringAfterLast(':')
                else -> asciiHost(hostPort)
            }
            return if (user.isEmpty()) target else "$user@$target"
        }

        /** The text is the address itself, give or take the scheme, `www.`, a closing slash and how its slashes are drawn ([asRead]). */
        private fun sameAddress(text: String, url: String): Boolean {
            fun norm(s: String) = asRead(s.trim()).lowercase().replace(SCHEME_PREFIX, "").removePrefix("www.").trimEnd('/')
            return norm(text) == norm(url)
        }

        private const val SHOWN_CHARS = 40
    }
}

/** `Has 2 control characters (^[, ^C) the shell would act on.`: the count and the first names, for the sheet's caption. */
fun controlWarning(analysis: PasteAnalysis): String {
    val n = analysis.controlChars
    return "Has $n control ${if (n == 1) "character" else "characters"} (${analysis.controlNames.joinToString(", ")}) the shell would act on."
}

/**
 * The passing confirmation over the terminal ("Copied"): the existing pill, gone after a moment.
 * A [StageTools.hint] takes the slot instead while it stands: the one line that says something
 * once and offers one action.
 */
@Composable
fun NoticePill(tools: StageTools, modifier: Modifier = Modifier) {
    tools.hint?.let { hint ->
        HintBar(hint, onDone = { if (tools.hint == hint) tools.hint = null }, modifier = modifier)
        return
    }
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

/**
 * One line said once, with one action: `Ctrl+W closes the tab here · Shell keeps it` (spec C22, the
 * hint after a first Ctrl+W). Held as long as the shell's notice bars ([NOTICE_BAR_MS]), or until
 * the action, and gone for good after; the Stage decides when a hint is due.
 */
data class StageHint(val text: String, val action: String, val onAction: () -> Unit)

/**
 * The hint drawn the way the shell's notice bars are (`Closed prod-web · Reopen`): a 32 dp
 * full-radius bar on surface.3 in a 44 dp row, Caption text, a middle dot, the action in accent
 * with a concentric surface.4 pill when pressed. Over the top of the terminal, where "Copied" sits,
 * rather than its foot: the prompt the user is typing at is at the foot.
 */
@Composable
private fun HintBar(hint: StageHint, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    LaunchedEffect(hint) {
        delay(NOTICE_BAR_MS)
        onDone()
    }
    Row(
        modifier
            .padding(top = 6.dp)
            .padding(horizontal = 12.dp)
            .height(44.dp)
            .drawBehind {
                val h = 32.dp.toPx()
                drawRoundRect(color = c.surface3, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
            }
            .padding(horizontal = 6.dp)
            .testTag(StageHintTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The text yields to the action, the way the shell's bars do.
        Text(hint.text, style = BerthType.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp))
        Text("\u00B7", style = BerthType.caption, color = c.text3)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val focused = interaction.showsFocus()
        Box(
            Modifier
                .fillMaxHeight()
                .clickable(interactionSource = interaction, indication = null, onClick = { hint.onAction(); onDone() })
                .semantics { role = Role.Button }
                .drawBehind {
                    if (pressed || focused) {
                        val h = 24.dp.toPx()
                        drawRoundRect(color = c.surface4, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                    }
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(hint.action, style = BerthType.caption, color = c.accent, maxLines = 1)
        }
    }
}

/** The hint bar's tag, for a test to find the one line it waits for. */
const val StageHintTag = "stage-hint"

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

/** The OSC 8 link under the selection's first cell, with the selected text as what it wore; null when that cell is plain. */
private fun linkUnder(session: TerminalSession, selection: TerminalSelection): LinkTap? {
    val emulator = session.emulator
    return synchronized(emulator.lock) {
        val start = selection.current(emulator)?.start ?: return@synchronized null
        emulator.linkAt(start.row, start.col)?.let { LinkTap(it, selection.text(emulator)) }
    }
}

private fun share(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
