package app.berth.android.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType

/**
 * The hardware keyboard shortcut sheet (spec C22): what Ctrl+Shift+/ opens and Settings › Hardware
 * keyboard links to. C22's two-column table, one panel per group: a row per chord with what it
 * does as the row's title and the keys in mono at the trailing edge (A3's mono for keys), one line
 * each, so a reader scanning for "Close tab" finds it where the eye lands and the whole sheet is
 * under two screens on a phone and one in the tablet's dialog; a note under a group for what is
 * said once (how the strip and the Deck are walked, where Alt is set), and a caption naming the
 * setting that moves Ctrl+T and Ctrl+W between the app and the shell. Nothing here is a control:
 * the sheet reads, the keys act. A [BerthSheet], so on a window that fits two panes (spec C23) it
 * opens as a dialog like every other sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutSheet(ctrlTabKeysReachTerminal: Boolean, onDismiss: () -> Unit, panes: Boolean = false) {
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
                if (ctrlTabKeysReachTerminal) "Ctrl+T and Ctrl+W go to the shell; Ctrl+Shift+T and Ctrl+Shift+W act here (Settings \u203A Hardware keyboard)."
                else "Ctrl+Shift+/ opens this sheet; Settings \u203A Hardware keyboard hands Ctrl+T and Ctrl+W to the shell.",
            )
            for (group in shortcutGroups(ctrlTabKeysReachTerminal, panes)) {
                Panel(label = group.title) {
                    for (entry in group.entries) ShortcutRow(entry)
                    if (group.note != null) PanelNote(group.note)
                }
            }
        }
    }
}

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
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${entry.action}, ${entry.keys.replace(" \u00B7 ", " or ")}" },
    )
}
