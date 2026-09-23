package app.berth.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One thing Berth ships that came under terms of its own: its name, those terms in a few words,
 * and the licence files under `assets/licenses/` that carry them, verbatim from upstream. A
 * palette whose upstream has no licence file ([files] empty) is credited in [terms] alone.
 */
internal data class ShippedNotice(val name: String, val terms: String, val files: List<String> = emptyList())

private const val OFL = "SIL Open Font License 1.1"

/** What Settings › Licences lists, by kind, in the order the About line used to name them. */
internal val SHIPPED_NOTICES: List<Pair<String, List<ShippedNotice>>> = listOf(
    "Fonts" to listOf(
        ShippedNotice("IBM Plex Sans", OFL, listOf("ibm-plex-sans-OFL.txt")),
        ShippedNotice("IBM Plex Mono", OFL, listOf("ibm-plex-mono-OFL.txt")),
        ShippedNotice("JetBrains Mono", OFL, listOf("jetbrains-mono-OFL.txt")),
        ShippedNotice("Fira Code", OFL, listOf("fira-code-OFL.txt")),
        ShippedNotice("Source Code Pro", OFL, listOf("source-code-pro-OFL.txt")),
        ShippedNotice("Hack", "MIT and the Bitstream Vera License", listOf("hack-LICENSE.txt")),
        ShippedNotice(
            "Symbols Nerd Font Mono, from Nerd Fonts",
            "MIT; its icon sets under their own: Font Awesome and Codicons CC BY 4.0, Material Design Icons Apache 2.0, " +
                "Weather Icons and Pomicons under the SIL Open Font License, Font Logos the Unlicense, the rest MIT",
            listOf("nerd-fonts-symbols-MIT.txt", "nerd-fonts-symbols-icon-sets.txt"),
        ),
    ),
    "Libraries" to listOf(
        ShippedNotice("sshj, the SSH transport", "Apache License 2.0"),
        ShippedNotice("Public Suffix List, for link captions", "Mozilla Public License 2.0"),
    ),
    "Terminal palettes" to listOf(
        ShippedNotice("Catppuccin Mocha and Latte", "MIT", listOf("theme-catppuccin-MIT.txt")),
        ShippedNotice(
            "Gruvbox Dark and Light",
            "Colours from gruvbox by Pavel Pertsev (github.com/morhetz/gruvbox) under the MIT/X11 licence its README states",
        ),
        ShippedNotice("Nord", "MIT", listOf("theme-nord-MIT.txt")),
        ShippedNotice("Solarized Dark and Light", "MIT", listOf("theme-solarized-MIT.txt")),
        ShippedNotice("Ros\u00E9 Pine", "MIT", listOf("theme-rose-pine-MIT.txt")),
        ShippedNotice("Tokyo Night, by folke", "Apache License 2.0", listOf("theme-tokyo-night-Apache-2.0.txt")),
        ShippedNotice("Kanagawa", "MIT", listOf("theme-kanagawa-MIT.txt")),
        ShippedNotice("Everforest", "MIT", listOf("theme-everforest-MIT.txt")),
        ShippedNotice("Dracula", "MIT", listOf("theme-dracula-MIT.txt")),
        ShippedNotice("One Dark", "MIT", listOf("theme-one-dark-MIT.txt")),
        ShippedNotice("Ayu", "MIT", listOf("theme-ayu-MIT.txt")),
        ShippedNotice(
            "GitHub Dark and Light High Contrast",
            "MIT: Primer's colours and its VS Code theme's",
            listOf("theme-github-primer-primitives-MIT.txt", "theme-github-vscode-theme-MIT.txt"),
        ),
    ),
)

/**
 * Settings › About › Licences: a content-height sheet listing what Berth ships under terms of its
 * own, one row each with those terms beneath; a row with a shipped text opens it in place, and the
 * back action or Back returns to the list. A credit with no text is a row without an action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesSheet(onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<ShippedNotice?>(null) }
    BerthSheet(onDismiss = onDismiss) {
        BackHandler(enabled = reading != null) { reading = null }
        val open = reading
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (open == null) NoticeList(onOpen = { reading = it }) else LicenceText(open, onBack = { reading = null })
        }
    }
}

@Composable
private fun NoticeList(onOpen: (ShippedNotice) -> Unit) {
    val c = Berth.colors
    SheetTitle("Licences", "What Berth ships that came under terms of its own")
    for ((kind, notices) in SHIPPED_NOTICES) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(kind, Modifier.padding(start = 4.dp, bottom = 2.dp))
            for (notice in notices) {
                ListRow(
                    notice.name,
                    subtitle = notice.terms,
                    titleMaxLines = 2,
                    // Terms are never ellipsised: the sheet scrolls.
                    subtitleMaxLines = Int.MAX_VALUE,
                    onClick = if (notice.files.isEmpty()) null else ({ onOpen(notice) }),
                    trailing = if (notice.files.isEmpty()) null else ({ BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) }),
                )
            }
        }
    }
}

@Composable
private fun LicenceText(notice: ShippedNotice, onBack: () -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, notice) {
        value = withContext(Dispatchers.IO) {
            notice.files.flatMap { file -> licenceParagraphs(context.assets.open("licenses/$file").bufferedReader().use { it.readText() }) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(onClick = onBack, description = "Back to Licences") { BerthIcon(BerthIcons.back) }
        Column(Modifier.padding(start = 4.dp)) { SheetTitle(notice.name, notice.terms) }
    }
    for (p in paragraphs.orEmpty()) Text(p, style = BerthType.body, color = c.text2)
}

/**
 * A licence file's paragraphs with its hard wraps joined, so its words flow to the sheet's width
 * rather than breaking twice a line. A line of 48 characters or more runs on into the next; a
 * shorter one (a heading, a title block, an address) keeps its break, as does a rule of dashes.
 * A table's row (three or more cells padded apart) is a paragraph of its own, its cells set apart
 * by [TABLE_CELL_SEPARATOR], since proportional type cannot keep the padding's columns.
 */
internal fun licenceParagraphs(text: String): List<String> {
    val out = mutableListOf<String>()
    val paragraph = StringBuilder()
    var runsOn = false
    fun endParagraph() {
        if (paragraph.isNotEmpty()) out += paragraph.toString()
        paragraph.clear()
        runsOn = false
    }
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) {
            endParagraph()
            continue
        }
        // One gap of two spaces is a sentence's end in older texts; two make a table's row.
        val cells = line.split(COLUMN_GAP)
        if (cells.size >= 3) {
            endParagraph()
            out += cells.joinToString(TABLE_CELL_SEPARATOR)
            continue
        }
        val words = line.any(Char::isLetter)
        when {
            paragraph.isEmpty() -> Unit
            runsOn && words -> paragraph.append(' ')
            else -> paragraph.append('\n')
        }
        paragraph.append(line)
        runsOn = words && line.length >= 48
    }
    endParagraph()
    return out
}

internal const val TABLE_CELL_SEPARATOR = " \u00B7 "

private val COLUMN_GAP = Regex(" {2,}")
