package app.berth.android.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono

/**
 * The hardware keyboard shortcut sheet (spec C22): what Ctrl+Shift+/ opens and Settings › Hardware
 * keyboard links to. One panel per group, a row per chord with the keys in mono over what they do,
 * and a caption naming the setting that moves Ctrl+T, Ctrl+W and Ctrl+F between the app and the
 * shell. Nothing here is a control: the sheet reads, the keys act.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutSheet(ctrlTabKeysReachTerminal: Boolean, onDismiss: () -> Unit, panes: Boolean = false) {
    val c = Berth.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
        dragHandle = { SheetHandle() },
    ) {
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
                if (ctrlTabKeysReachTerminal) "Ctrl+T, Ctrl+W and Ctrl+F go to the shell; their Shift chords act here (Settings \u203A Hardware keyboard)."
                else "Ctrl+Shift+/ opens this sheet; Settings \u203A Hardware keyboard hands Ctrl+T and Ctrl+W to the shell.",
            )
            for (group in shortcutGroups(ctrlTabKeysReachTerminal, panes)) {
                Panel(label = group.title) {
                    for (entry in group.entries) ShortcutRow(entry)
                }
            }
        }
    }
}

/**
 * One chord: the keys as the row's mono title, what they do as the caption under it, so a wide
 * chord never squeezes its action. TalkBack hears the action first: "Next tab, Ctrl+Tab".
 */
@Composable
private fun ShortcutRow(entry: ShortcutEntry) {
    ListRow(
        title = entry.keys,
        subtitle = entry.action,
        subtitleMaxLines = 2,
        titleStyle = BerthType.bodyMedium.copy(fontFamily = JetBrainsMono),
        surface = Color.Transparent,
        minHeight = 40.dp,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${entry.action}, ${entry.keys.replace(" \u00B7 ", " or ")}" },
    )
}
