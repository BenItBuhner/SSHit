package app.berth.android.ui.keyboard

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
 * under two screens on a phone and one in the tablet's dialog; a note under a group for what is
 * said once (how the strip and the Deck are walked, where Alt is set), and a caption naming the
 * setting that moves Ctrl+T and Ctrl+W between the app and the shell. A [BerthSheet], so on a
 * window that fits two panes (spec C23) it opens as a dialog like every other sheet.
 *
 * The rows of the app's own chords are the remap table (spec C22, A44) when the sheet has an
 * [onRemap] to write with: a tap on one listens for the next chord the keyboard sends and says,
 * under the row, what taking it would cost or why it cannot be taken ([ChordConflict], the
 * conflict detection against the shell-bound set); Use or Enter binds it, Default gives the row its
 * prefix chord back, Esc or Cancel leaves it as it was. A rebound row shows its chord in accent and
 * says under it what it was and what the shell lost. The strip's browser conventions and the
 * terminal's own keys are read, not rebound.
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
                    for (entry in group.entries) {
                        val action = entry.chord
                        if (action != null && onRemap != null) {
                            RemapRow(
                                entry = entry,
                                table = table,
                                capturing = capturing == action,
                                onCapture = { capturing = if (it) action else null },
                                onRemap = { chord ->
                                    onRemap(action, chord)
                                    capturing = null
                                },
                            )
                        } else {
                            ShortcutRow(entry)
                        }
                    }
                    if (group.note != null) PanelNote(group.note)
                }
            }
        }
    }
}

/** The tag on the node that listens for a remap's chord, for a test to send the keys to. */
const val ChordCaptureTag = "chord-capture"

/**
 * One chord as a line of C22's table: the action as the row's title in body, the keys in mono and
 * `text.2` at the trailing edge, right-aligned, on a 40 dp row. A wide chord takes its width first
 * and the action wraps beside it, to two lines for the few long ones; the keys wrap only past the
 * row's whole width, never clip. TalkBack hears the action first: "Next tab, Ctrl+Tab".
 */
@Composable
private fun ShortcutRow(entry: ShortcutEntry) {
    ListRow(
        title = entry.action,
        titleStyle = BerthType.body,
        titleMaxLines = 2,
        trailing = {
            Text(entry.keys, style = BerthType.mono, color = Berth.colors.text2, textAlign = TextAlign.End)
        },
        surface = Color.Transparent,
        minHeight = 40.dp,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${entry.action}, ${entry.keys.spoken()}" },
    )
}

/**
 * The row of one of the app's chords, and the remap table's one control (spec C22, A44): the
 * [ShortcutRow] with a tap, its chord in accent once rebound with the caption saying what it was
 * and what the shell lost. Tapped, the row steps up a tone and listens: the caption asks for the
 * chord, then names the one pressed and what binding it would cost ([conflictText]), and a row of
 * Use, Default and Cancel stands under it, the keyboard's Enter and Esc being Use and Cancel. A
 * chord that blocks (another action's, the strip's, a signal, a bare key) is said and not offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemapRow(
    entry: ShortcutEntry,
    table: ChordTable,
    capturing: Boolean,
    onCapture: (Boolean) -> Unit,
    onRemap: (ChordKey?) -> Unit,
) {
    val c = Berth.colors
    val action = entry.chord ?: return
    val current = table.chord(action)
    var pressed by remember(capturing) { mutableStateOf<ChordKey?>(null) }
    val chord = pressed
    val conflict = chord?.let { table.conflict(action, it) }
    val bindable = chord != null && chord != current && conflict?.blocks != true
    val caption = when {
        !capturing -> if (entry.remapped) listOfNotNull("Default ${entry.default}", entry.takes?.let { "takes $it from the shell" }).joinToString(CHORD_SEPARATOR) else null
        chord == null -> "Press the new chord on the keyboard. Esc keeps ${current.label()}."
        chord == current -> "${current.label()} is what it has now."
        conflict == null -> "${chord.label()} is free. Enter binds it."
        conflict.blocks -> conflictText(conflict, chord)
        else -> "${conflictText(conflict, chord)} Enter binds it."
    }
    val spoken = buildString {
        append(entry.action)
        when {
            capturing && chord == null -> append(", listening for the new chord")
            capturing -> append(", ${chord?.label()}, ${caption ?: ""}")
            else -> {
                append(", ${entry.keys.spoken()}")
                if (entry.remapped) append(", rebound from ${entry.default}")
                entry.takes?.let { append(", takes $it from the shell") }
                append(". Rebind")
            }
        }
    }
    ListRow(
        title = entry.action,
        titleStyle = BerthType.body,
        titleMaxLines = 2,
        subtitle = caption,
        subtitleMaxLines = 2,
        selected = capturing,
        surface = Color.Transparent,
        minHeight = 40.dp,
        onClick = { onCapture(!capturing) },
        role = Role.Button,
        trailing = {
            val shown = if (capturing) chord?.label() ?: "\u2026" else entry.keys
            Text(shown, style = BerthType.mono, color = if (capturing || entry.remapped) c.accent else c.text2, textAlign = TextAlign.End)
        },
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
    )
    if (capturing) {
        // The listening row's own reader, so a Leader tapped here is this row's and never the Stage's.
        val reader = remember { ChordReader() }
        val leaderKey = table.settings.leaderKey.takeIf { table.settings.chordPrefix == ChordPrefix.LEADER }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        FlowRow(
            Modifier
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    captureKey(
                        event = event,
                        reader = reader,
                        leaderKey = leaderKey,
                        onChord = { pressed = it },
                        onUse = {
                            if (bindable) onRemap(chord)
                            bindable
                        },
                        onCancel = { onCapture(false) },
                    )
                }
                .testTag(ChordCaptureTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
 * scrolls or moves its focus on a key meant as a chord; a release or a modifier alone is nobody's.
 */
private fun captureKey(
    event: KeyEvent,
    reader: ChordReader,
    leaderKey: LeaderKey?,
    onChord: (ChordKey) -> Unit,
    onUse: () -> Boolean,
    onCancel: () -> Unit,
): Boolean {
    val read = when (val read = reader.read(event, leaderKey)) {
        ChordRead.Consumed -> return true
        ChordRead.Ignored -> return false
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

/** `Ctrl+T · Ctrl+Shift+T` as a reader says it: "Ctrl+T or Ctrl+Shift+T". */
private fun String.spoken(): String = replace(CHORD_SEPARATOR, " or ")
