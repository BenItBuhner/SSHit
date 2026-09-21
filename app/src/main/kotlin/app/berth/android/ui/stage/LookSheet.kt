package app.berth.android.ui.stage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.terminal.TerminalFonts
import app.berth.domain.model.AppearanceOverride
import app.berth.domain.model.Host
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Workspace

/** The terminal's look as it resolves for one host: the theme it draws in and the font it sets. */
@Immutable
data class TerminalLook(val theme: TerminalTheme, val font: TerminalFont) {
    /** The look in a row's second line: `Berth Dark · JetBrains Mono · 13 sp`. */
    val summary: String get() = "${theme.name} \u00B7 ${font.family} \u00B7 ${font.sizeSp} sp"
}

/**
 * The font families a host may set (spec C20, Fonts): the ones Settings › Terminal's picker lists,
 * the bundled, the device's monospace and the imports, in the picker's order, read again when an
 * import lands or a family goes ([TerminalFonts.version]), so a family imported in Settings is a
 * host's to pick the moment it is there. A host naming a family since removed keeps its name on
 * the row (the terminal draws the default for it, [app.berth.android.ui.terminal.resolvedFamily])
 * until another is picked.
 */
@Composable
fun rememberTerminalFontFamilies(): List<String> {
    val context = LocalContext.current
    val version = TerminalFonts.version
    return remember(version) { TerminalFonts.choices(context).map { it.name } }
}

/**
 * What a terminal on [host] draws with: the host's own theme, then the workspace's, then the
 * app's default ([AppViewModel.resolveTerminalTheme]); the app's font with the size and family the
 * host sets over it, when it sets them. The Stage and the sheets that describe a host's look read
 * the same answer, so what the Look sheet says the Stage shows is what the Stage shows.
 */
fun resolveLook(themes: List<TerminalTheme>, defaultTheme: TerminalTheme, fontSetting: TerminalFont, host: Host, workspace: Workspace?): TerminalLook {
    val theme = AppViewModel.resolveTerminalTheme(themes, defaultTheme, host, workspace)
    var font = fontSetting
    host.appearance.fontSizeSp?.let { font = font.copy(sizeSp = it) }
    host.appearance.fontFamily?.let { font = font.copy(family = it) }
    return TerminalLook(theme, font)
}

/**
 * The host's look (spec C6, Look): its theme, font and size, each a pick that lands on the host as
 * it is made, so the Stage under the sheet is the preview, live. The sheet has no scrim, since a
 * scrim would dim the very thing being looked at, and it rises no higher than its three rows on a
 * phone so the terminal keeps most of the window; on a large window it is a panel over the Stage
 * with the Stage in view around it. `Inherit` is the app's setting (Settings › Terminal), or for
 * the theme the workspace's when it has one. What the host editor's Look panel sets, this sets;
 * a host that has gone (deleted under the sheet) leaves nothing to set, and the sheet is gone with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(vm: AppViewModel, hostId: String, onDismiss: () -> Unit) {
    val hosts by vm.hosts.collectAsState()
    val host = hosts.firstOrNull { it.id == hostId }
    val themes by vm.terminalThemes.collectAsState()
    if (host == null) {
        // Deleted under the sheet: there is nothing left to set, so the sheet closes rather than
        // standing open with nothing in it and holding the Session sheet's place (#20 review).
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val appearance = host.appearance
    val inheritsAll = appearance.terminalThemeId == null && appearance.fontFamily == null && appearance.fontSizeSp == null
    fun set(next: AppearanceOverride) = vm.saveHost(host.copy(appearance = next), password = null)

    BerthSheet(
        onDismiss = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
        dialogScrimColor = Color.Transparent,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle("Look", host.name)
            Panel {
                CyclePicker(
                    "Theme",
                    listOf<String?>(null) + themes.map { it.id },
                    appearance.terminalThemeId,
                    { id -> id?.let { wanted -> themes.firstOrNull { it.id == wanted }?.name } ?: "Inherit" },
                ) { set(appearance.copy(terminalThemeId = it)) }
                CyclePicker(
                    "Font",
                    listOf<String?>(null) + rememberTerminalFontFamilies(),
                    appearance.fontFamily,
                    { it ?: "Inherit" },
                ) { set(appearance.copy(fontFamily = it)) }
                CyclePicker(
                    "Size",
                    listOf<Int?>(null) + (TerminalFont.MIN_SIZE_SP..TerminalFont.MAX_SIZE_SP).toList(),
                    appearance.fontSizeSp,
                    { it?.let { s -> "$s sp" } ?: "Inherit" },
                ) { set(appearance.copy(fontSizeSp = it)) }
                PanelNote(
                    if (inheritsAll) "Inherit is Settings \u203A Terminal, or the group's theme where the group has one. A pick here is this host's, on every tab it opens."
                    else "Set on this host, on every tab it opens; the Stage behind shows it live.",
                )
            }
        }
    }
}
