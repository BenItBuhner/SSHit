package app.berth.android.ui.keyboard

import android.view.KeyCharacterMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ui.a11y.TouchTargetSize
import app.berth.android.ui.a11y.alwaysFocusable
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordKey
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.LeaderKey

/**
 * The hardware keyboard shortcut sheet (spec C22): what Ctrl+Shift+/ opens and Settings › Hardware
 * keyboard links to. C22's two-column table, one panel per group: a row per chord with what it
 * does as the row's title and the keys in mono at the trailing edge (A3's mono for keys), one line
 * each, so a reader scanning for "Close tab" finds it where the eye lands and the whole sheet is
 * about two screens on a phone; a note under a group for what is
 * said once (how the strip and the Deck are walked, where Alt is set), and a caption naming the
 * setting that moves Ctrl+T and Ctrl+W between the app and the shell. A [BerthSheet], so on a
 * window that fits two panes (spec C23) it opens as a dialog like every other sheet.
 *
 * The rows of the app's own chords are the remap table (spec C22, A44) when the sheet has an
 * [onRemap] to write with: a tap on one takes the keyboard's focus, listens for the next chord the
 * keyboard sends and says, under the row, what taking it would cost or why it cannot be taken
 * ([ChordConflict], the conflict detection against the shell-bound set); Use or Enter binds it,
 * Default gives the row its prefix chord back, Esc or Cancel leaves it as it was. A rebound row
 * shows its chord in accent and says under it what it was and what the shell lost. The strip's
 * browser conventions and the terminal's own keys are read, not rebound; the Tabs note says which.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutSheet(
    table: ChordTable,
    onDismiss: () -> Unit,
    panes: Boolean = false,
    /** Binds an action to a chord, or back to its default with null; without it the sheet only reads. */
    onRemap: ((ChordAction, ChordKey?) -> Unit)? = null,
) {
    // One row listens at a time; a tap on another moves the listening there.
    var capturing by remember { mutableStateOf<ChordAction?>(null) }
    val prefix = table.prefixPhrase()
    BerthSheet(onDismiss = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A row's content: the sheet's width less its margin, the panel's padding and the row's own.
            val contentWidth = maxWidth - BerthSpace.screenMargin * 2 - Berth.density.panelPadding * 2 - ROW_PADDING * 2
            val measurer = rememberTextMeasurer(cacheSize = 64)
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BerthSpace.screenMargin)
                    .padding(bottom = 32.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(BerthSpace.panelGap),
            ) {
                SheetTitle(
                    "Keyboard shortcuts",
                    if (onRemap != null) "The app\u2019s chords start with $prefix. Tap one to rebind it; Settings \u203A Hardware keyboard changes the prefix."
                    else "The app\u2019s chords start with $prefix; Settings \u203A Hardware keyboard changes the prefix and rebinds them.",
                )
                for (group in shortcutGroups(table, panes)) {
                    Panel(label = group.title) {
                        // The rows as one child of the panel, so they meet: the panel's 4 dp between
                        // its children is for rows on their own surfaces, and these are a table's.
                        Column {
                            for (entry in group.entries) {
                                val action = entry.chord
                                val keysMaxWidth = keysWidth(entry.action, contentWidth, measurer)
                                if (action != null && onRemap != null) {
                                    RemapRow(
                                        entry = entry,
                                        table = table,
                                        keysMaxWidth = keysMaxWidth,
                                        capturing = capturing == action,
                                        onCapture = { capturing = if (it) action else null },
                                        onRemap = { chord ->
                                            onRemap(action, chord)
                                            capturing = null
                                        },
                                    )
                                } else {
                                    ShortcutRow(entry, keysMaxWidth)
                                }
                            }
                        }
                        if (group.note != null) PanelNote(group.note)
                    }
                }
            }
        }
    }
}

/** A [ListRow]'s horizontal padding and the gap it puts between its title and its trailing column. */
internal val ROW_PADDING = 12.dp
internal val COLUMN_GAP = 12.dp

/** The share of a row the keys keep when the action wants the whole line: two fifths, the action the three it is read by. */
internal const val KEYS_SHARE = 0.4f

/** What the action is lent over its measured line: the row lays the line out in whole pixels, and a line given its width to the pixel can come up a fraction short and wrap. */
private val LINE_SLACK = 1.dp

/**
 * How wide the keys may stand on a row before they wrap: the width the action's own line leaves
 * them (the row's [contentWidth] less the action set on one line in body, its [LINE_SLACK] and the
 * gap between the columns), and never less than [KEYS_SHARE] of the row. So a short action lends its spare width
 * and `Ctrl+T · Ctrl+Shift+T` sits on one line beside `New tab`; an action too long for its line
 * wraps beside keys that keep two fifths, the larger share going to the column the eye scans, and
 * at the font cap the long action has three lines whole rather than four with the last cut. The
 * action is measured as [ListRow] sets it, in [BerthType.body] on one line.
 */
@Composable
private fun keysWidth(action: String, contentWidth: Dp, measurer: TextMeasurer): Dp {
    val density = LocalDensity.current
    return remember(action, contentWidth, density.density, density.fontScale) {
        val line = with(density) { measurer.measure(action, style = BerthType.body, softWrap = false, maxLines = 1).size.width.toDp() }
        (contentWidth - line - LINE_SLACK - COLUMN_GAP).coerceAtLeast(contentWidth * KEYS_SHARE)
    }
}

/** The tag on the one row listening for a remap's chord, for a test to send the keys to; no row carries it otherwise. */
const val ChordCaptureTag = "chord-capture"

/**
 * One chord as a line of C22's table: the action as the row's title in body, the keys in mono and
 * `text.2` at the trailing edge, right-aligned, on a row of a touch target's height (48 dp), since
 * the app's rows are the remap table's controls and the fixed rows keep step with them; the rows
 * meet, so the table's pitch is the row. At 1× the action and its keys are each one line on a
 * phone, what would qualify an action said in the group's note; at the font cap either may take the
 * second line a one-line text is given there, the keys wrapping past [keysMaxWidth] ([keysWidth]),
 * never clipped. TalkBack hears the action first: "Next tab, Ctrl+Tab".
 */
@Composable
private fun ShortcutRow(entry: ShortcutEntry, keysMaxWidth: Dp) {
    ListRow(
        title = entry.action,
        titleStyle = BerthType.body,
        titleMaxLines = 2,
        trailing = { KeysText(entry.keys, Berth.colors.text2, keysMaxWidth) },
        surface = Color.Transparent,
        minHeight = TouchTargetSize,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${entry.action}, ${entry.keys.spoken()}" },
    )
}

/** The keys of a row, in mono at the trailing edge and right-aligned, wrapping past [maxWidth]. */
@Composable
private fun KeysText(keys: String, color: Color, maxWidth: Dp) {
    Text(keys, style = BerthType.mono, color = color, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = maxWidth))
}

/**
 * The row of one of the app's chords, and the remap table's one control (spec C22, A44): the
 * [ShortcutRow] with a tap, its chord in accent once rebound with the caption saying what it was
 * and what the shell lost. Tapped, the row steps up a tone, takes the keyboard's focus and listens:
 * the caption asks for the chord, then names the one pressed and what binding it would cost
 * ([conflictText]), and a row of Use, Default and Cancel stands under it, the keyboard's Enter and
 * Esc being Use and Cancel. A chord that blocks (another action's, the strip's, the system's, a
 * signal, a bare key) is said and not offered, and stands in `text.2` rather than the accent an
 * accepted chord wears; a key AltGr types (`@` on a German layout's Q) is no chord at all, and the
 * line says what it typed and where a chord's Alt is ([altGrText]) rather than listen on in silence.
 * The row is what listens, rather than a node of its own under it,
 * so what holds the focus is the thing a reader names ("Find in scrollback, listening for the new
 * chord") and the focus stays on the row once it is bound, reading its new keys. It takes the focus
 * in touch mode too ([alwaysFocusable]): a tapped row is in touch mode, and the Ctrl chord typed
 * next does not leave it, so a clickable's own rule would refuse the focus and the chord would land
 * elsewhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemapRow(
    entry: ShortcutEntry,
    table: ChordTable,
    keysMaxWidth: Dp,
    capturing: Boolean,
    onCapture: (Boolean) -> Unit,
    onRemap: (ChordKey?) -> Unit,
) {
    val c = Berth.colors
    val action = entry.chord ?: return
    val current = table.chord(action)
    var pressed by remember(capturing) { mutableStateOf<ChordKey?>(null) }
    // The line for the last key, when AltGr typed it instead of a chord; the next chord clears it.
    var typed by remember(capturing) { mutableStateOf<String?>(null) }
    val chord = pressed
    val conflict = chord?.let { table.conflict(action, it) }
    val bindable = chord != null && chord != current && conflict?.blocks != true
    val caption = when {
        !capturing -> if (entry.remapped) listOfNotNull("Default ${entry.default}", entry.takes?.let { "takes $it from the shell" }).joinToString(CHORD_SEPARATOR) else null
        chord == null -> typed ?: "Press the new chord on the keyboard. Esc keeps ${current.label()}."
        chord == current -> "${current.label()} is what it has now."
        conflict == null -> "${chord.label()} is free. Enter binds it."
        conflict.blocks -> conflictText(conflict, chord)
        else -> "${conflictText(conflict, chord)} Enter binds it."
    }
    val spoken = buildString {
        append(entry.action)
        when {
            capturing && chord == null -> append(if (typed == null) ", listening for the new chord" else ", listening for the new chord, $typed")
            capturing -> append(", ${chord?.label()}, ${caption ?: ""}")
            else -> {
                append(", ${entry.keys.spoken()}")
                if (entry.remapped) append(", rebound from ${entry.default}")
                entry.takes?.let { append(", takes $it from the shell") }
                append(". Rebind")
            }
        }
    }
    // A reader per listening, so a Leader tapped here is this row's and never the Stage's, and one left armed by a Cancel is not the next listening's.
    val reader = remember(capturing) { ChordReader() }
    val leaderKey = table.settings.leaderKey.takeIf { table.settings.chordPrefix == ChordPrefix.LEADER }
    val focus = remember { FocusRequester() }
    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }
    ListRow(
        title = entry.action,
        titleStyle = BerthType.body,
        titleMaxLines = 2,
        subtitle = caption,
        subtitleMaxLines = 2,
        selected = capturing,
        surface = Color.Transparent,
        minHeight = TouchTargetSize,
        onClick = { onCapture(!capturing) },
        role = Role.Button,
        trailing = {
            val shown = if (capturing) chord?.label() ?: "\u2026" else entry.keys
            // Accent for a chord the table has taken or will take; a chord it refuses stands in the fixed rows' tone, as the sentence under it says no.
            val refused = capturing && chord != null && conflict?.blocks == true
            KeysText(shown, if ((capturing || entry.remapped) && !refused) c.accent else c.text2, keysMaxWidth)
        },
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .focusRequester(focus)
            // A tapped row is in touch mode, and a Ctrl chord does not leave it: the row's focus is
            // taken by request the way the Stage's landing controls take theirs (alwaysFocusable).
            .alwaysFocusable()
            .onPreviewKeyEvent { event ->
                capturing && captureKey(
                    event = event,
                    reader = reader,
                    leaderKey = leaderKey,
                    onChord = { pressed = it; typed = null },
                    onTyped = { typed = it; pressed = null },
                    onUse = {
                        if (bindable) onRemap(chord)
                        bindable
                    },
                    onCancel = { onCapture(false) },
                )
            }
            .then(if (capturing) Modifier.testTag(ChordCaptureTag) else Modifier),
    )
    if (capturing) {
        // Use starts where the row's title does.
        FlowRow(Modifier.padding(start = ROW_PADDING, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Use", onClick = { onRemap(chord) }, kind = ButtonKind.PRIMARY, enabled = bindable)
            if (entry.remapped) BerthButton("Default", onClick = { onRemap(null) })
            BerthButton("Cancel", onClick = { onCapture(false) }, kind = ButtonKind.TEXT)
        }
    }
}

/**
 * One key event while a row listens: a plain Escape cancels, a plain Enter binds what was pressed
 * (when it can be bound; before a press it is a candidate like any key, and said to be typing),
 * and any other chord becomes the candidate. Consumed either way, so the sheet under the row never
 * scrolls or moves its focus on a key meant as a chord; a release or a modifier alone is nobody's,
 * and a key AltGr types is the layout's, said under the row ([altGrText]).
 */
private fun captureKey(
    event: KeyEvent,
    reader: ChordReader,
    leaderKey: LeaderKey?,
    onChord: (ChordKey) -> Unit,
    onTyped: (String) -> Unit,
    onUse: () -> Boolean,
    onCancel: () -> Unit,
): Boolean {
    val read = when (val read = reader.read(event, leaderKey)) {
        ChordRead.Consumed -> return true
        ChordRead.Ignored -> {
            // The one named key pressed that the reader leaves is a key AltGr types (`@` on a German layout's Q,
            // [ChordRead.Ignored]): the row says so from the event's own character, rather than listen on in silence.
            if (event.type == KeyEventType.KeyDown) chordName(event.key.nativeKeyCode)?.let { onTyped(altGrText(it, event.utf16CodePoint)) }
            return false
        }
        is ChordRead.Chord -> read
    }
    val chord = read.chord
    val plain = !chord.hasModifier && !chord.shift
    if (plain && chord.key == "ESCAPE") {
        onCancel()
        return true
    }
    if (plain && chord.key == "ENTER" && onUse()) return true
    onChord(chord)
    return true
}

/**
 * The line under a listening row for a key AltGr types, `@` on a German layout's Q: the layout's own
 * character and no chord's ([ChordReader]), so the row says what it typed and where a chord's Alt is.
 * A dead key's accent is shown as the spacing accent the map gives it.
 */
private fun altGrText(key: String, character: Int): String {
    val label = ChordKey.keyLabel(key)
    val typed = (character and KeyCharacterMap.COMBINING_ACCENT.inv()).toChar()
    return "AltGr+$label types $typed on this keyboard. Hold the left Alt for Alt+$label."
}

/** `Ctrl+T · Ctrl+Shift+T` as a reader says it: "Ctrl+T or Ctrl+Shift+T". */
private fun String.spoken(): String = replace(CHORD_SEPARATOR, " or ")
