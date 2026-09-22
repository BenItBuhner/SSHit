package app.berth.android.ui.themes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.io.TextFileSaver
import app.berth.android.ui.io.shareText
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthSpace
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TerminalThemes
import app.berth.domain.model.ThemeFormat
import app.berth.domain.model.ThemeSlot

/**
 * Export of one terminal theme (spec A10): Berth JSON and each other format that carries the theme
 * whole. A format that would lose a colour stays in the list but is off, and says which colours it
 * has no place for. [saver] belongs to the screen: the sheet closes while the system's save dialog
 * is up, and a launcher of its own would go with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeExportSheet(theme: TerminalTheme, saver: TextFileSaver, onDismiss: () -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val formats = remember(theme) { ThemeFormat.entries.filter { it.exports }.map { it to TerminalThemes.dropped(theme, it) } }
    var chosen by remember(theme) { mutableStateOf(ThemeFormat.BERTH) }
    BerthSheet(onDismiss = onDismiss, scrimColor = c.scrim) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetTitle("Export ${theme.name}")
            Column(verticalArrangement = Arrangement.spacedBy(BerthSpace.rowGap)) {
                for ((format, lost) in formats) {
                    ListRow(
                        title = format.label,
                        subtitle = if (lost.isEmpty()) TerminalThemes.fileName(theme, format) else "Would lose ${slotList(lost)}",
                        selected = format == chosen,
                        enabled = lost.isEmpty(),
                        role = Role.RadioButton,
                        onClick = { chosen = format },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton(
                    "Save to file",
                    onClick = {
                        saver.save(TerminalThemes.fileName(theme, chosen), TerminalThemes.export(theme, chosen), saveMime(chosen))
                        onDismiss()
                    },
                    kind = ButtonKind.PRIMARY,
                )
                BerthButton(
                    "Share",
                    onClick = {
                        shareText(context, TerminalThemes.fileName(theme, chosen), TerminalThemes.export(theme, chosen), shareMime(chosen))
                        onDismiss()
                    },
                )
            }
        }
    }
}

private val ThemeFormat.isJson: Boolean get() = extension == "json"

// A document provider appends the extension of any type it knows to a name that lacks it, so a
// typed save would write Dracula.itermcolors.xml or Dracula.properties.txt; octet-stream keeps
// the name as given.
private fun saveMime(format: ThemeFormat): String = if (format.isJson) "application/json" else "application/octet-stream"

private fun shareMime(format: ThemeFormat): String = if (format.isJson) "application/json" else "text/plain"

/** "links", "cursor text and links", "cursor text, selection and links". */
private fun slotList(slots: List<ThemeSlot>): String {
    val names = slots.map { it.title.lowercase() }
    return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
}
