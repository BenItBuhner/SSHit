package app.berth.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.terminal.FontChoice
import app.berth.android.ui.terminal.FontFace
import app.berth.android.ui.terminal.TerminalFonts
import app.berth.android.ui.terminal.TypefaceCache
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The terminal's font (spec C20, Fonts): every family the phone has, each under the sample line
 * that tells coding fonts apart, set in its own face; the family in use is marked as a row is (the
 * tonal step and the accent dot). Bundled families first, then the device's monospace, then the
 * imports by name, each captioned with where it came from and what it lacks, since a family with
 * one face has its bold made for it and a proportional face will not hold a column. Import font
 * file takes a TTF or OTF from the document picker and files it under the family its own name
 * table gives, so a bold imported after a regular joins it. An import is a file arriving in the
 * list, not a choice: the family in use stays what it was until a row is tapped, since the two
 * imports the app itself invites (a Nerd Font for the fallback's icons, a face to keep at hand)
 * are not asks to set the terminal in them. An imported family has a Remove beside it; the
 * terminal set in it goes back to the default. The samples honour the Ligatures switch, so a row
 * shows what the terminal would draw.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontPickerSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val font by vm.terminalFont.collectAsState()
    // Re-read when an import lands or a family goes: the list and its faces both come from disk.
    val version = TerminalFonts.version
    val choices = remember(version) { TerminalFonts.choices(context) }
    // The faces the samples are set in, loaded off the main thread: an import can be a large file.
    val faces by produceState<Map<String, FontFamily>>(emptyMap(), version, font.nerdFontFallback) {
        value = withContext(Dispatchers.IO) {
            choices.associate { it.name to FontFamily(Typeface(TypefaceCache.forFamily(context, it.name, font.nerdFontFallback)[0])) }
        }
    }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                when (val result = TerminalFonts.import(context, uri)) {
                    is TerminalFonts.ImportResult.Done -> note = "Imported ${result.family}, ${result.face.label()}" to false
                    is TerminalFonts.ImportResult.Failed -> note = result.reason to true
                }
                busy = false
            }
        }
    }

    // A picker of seven families opens at its full height, as the app's other list sheets do: half of it would show two.
    BerthSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Terminal font", "Each family set in its own face")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (choice in choices) {
                    FontRow(
                        choice = choice,
                        face = faces[choice.name],
                        ligatures = font.ligatures,
                        selected = choice.name == font.family,
                        onClick = { vm.setTerminalFont(font.copy(family = choice.name)) },
                        onRemove = if (choice.kind == FontChoice.Kind.IMPORTED) {
                            {
                                scope.launch {
                                    TerminalFonts.imported(context, choice.name)?.let { TerminalFonts.remove(context, it) }
                                    if (vm.terminalFont.value.family == choice.name) vm.setTerminalFont(vm.terminalFont.value.copy(family = TerminalFonts.DEFAULT))
                                    note = "Removed ${choice.name}" to false
                                }
                            }
                        } else null,
                    )
                }
            }
            note?.let { (text, error) ->
                Text(text, style = BerthType.caption, color = if (error) c.danger else c.text2, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton(if (busy) "Importing\u2026" else "Import font file", enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) })
                Spacer(Modifier.weight(1f))
                BerthButton("Done", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
            Text(
                "A TTF or OTF file goes under the family its own name says; a bold or an italic imported after joins it. Imported fonts stay on this phone.",
                style = BerthType.caption,
                color = c.text3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * One family: its name, the sample line in its own face in text.1, and its caption. The sample is
 * set in the default family until the face has loaded, so the row stands at its height from the
 * first frame, and with [ligatures] off its `=>` and `->` stay two glyphs, as the terminal's would.
 * An imported family carries Remove at its trailing edge.
 */
@Composable
private fun FontRow(choice: FontChoice, face: FontFamily?, ligatures: Boolean, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val c = Berth.colors
    val subtitle = buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = face ?: JetBrainsMono, color = c.text1, fontFeatureSettings = if (ligatures) null else NO_LIGATURES)) { append(TerminalFonts.SAMPLE) }
        append('\n')
        withStyle(SpanStyle(fontFamily = BerthType.caption.fontFamily, fontSize = BerthType.caption.fontSize, fontWeight = BerthType.caption.fontWeight, letterSpacing = BerthType.caption.letterSpacing)) {
            append(choice.note)
        }
    }
    ListRow(
        title = choice.name,
        subtitle = subtitle,
        subtitleStyle = BerthType.mono,
        subtitleMaxLines = 3,
        selected = selected,
        onClick = onClick,
        trailing = onRemove?.let { remove ->
            {
                IconAction(onClick = remove, description = "Remove ${choice.name}") { BerthIcon(BerthIcons.trash, tint = c.text2, size = 20.dp) }
            }
        },
    )
}

/** The OpenType features a ligature is made of, off: the terminal's own paint setting for the switch off ([TerminalCanvas][app.berth.android.ui.terminal.TerminalCanvas]). */
private const val NO_LIGATURES = "-liga, -calt"

/** The face as the import note names it. */
private fun FontFace.label(): String = when (this) {
    FontFace.REGULAR -> "regular"
    FontFace.BOLD -> "bold"
    FontFace.ITALIC -> "italic"
    FontFace.BOLD_ITALIC -> "bold italic"
}
