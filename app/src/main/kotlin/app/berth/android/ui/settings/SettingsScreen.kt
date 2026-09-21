package app.berth.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.diagnostics.ReportKind
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSlider
import app.berth.android.ui.components.ColorOption
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PanelNote
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.importer.ImportHostsSheet
import app.berth.android.ui.importer.ImportKeySheet
import app.berth.android.ui.importer.ImportKnownHostsSheet
import app.berth.android.ui.terminal.resolvedFamily
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalSettings

/** Interface and terminal defaults. Panels, not a preference tree. */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onKnownHosts: () -> Unit,
    modifier: Modifier = Modifier,
    onThemes: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onDeckEditor: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
) {
    val c = Berth.colors
    val reports by vm.reports.reports.collectAsState()
    val theme by vm.interfaceTheme.collectAsState()
    val font by vm.terminalFont.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    val defaultTheme by vm.defaultTerminalTheme.collectAsState()
    val deck by vm.deckLayout.collectAsState()
    val haptics by vm.hapticLevel.collectAsState()
    val tabSwipe by vm.tabSwipeGesture.collectAsState()
    val terminal by vm.terminalSettings.collectAsState()
    val context = LocalContext.current
    var importConfig by remember { mutableStateOf(false) }
    var importKnownHosts by remember { mutableStateOf(false) }
    var importKey by remember { mutableStateOf(false) }
    var fontPicker by remember { mutableStateOf(false) }

    if (importConfig) ImportHostsSheet(vm, onDismiss = { importConfig = false })
    if (importKnownHosts) ImportKnownHostsSheet(vm, onDismiss = { importKnownHosts = false })
    if (importKey) ImportKeySheet(vm, onDismiss = { importKey = false })
    if (fontPicker) FontPickerSheet(vm, onDismiss = { fontPicker = false })

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Settings", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.panelGap),
        ) {
            // Rows that navigate end in the drawn chevron, never a text glyph.
            val chevron: @Composable RowScope.() -> Unit = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) }
            Panel(label = "Interface") {
                Text("Appearance", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                SegmentedControl(
                    listOf("System", "Light", "Dark", "Black"),
                    listOf(InterfaceVariant.SYSTEM, InterfaceVariant.LIGHT, InterfaceVariant.DARK, InterfaceVariant.TRUE_BLACK).indexOf(theme.variant),
                    { vm.setInterfaceTheme(theme.copy(variant = listOf(InterfaceVariant.SYSTEM, InterfaceVariant.LIGHT, InterfaceVariant.DARK, InterfaceVariant.TRUE_BLACK)[it])) },
                )
                Text("Tone", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Warm", style = BerthType.caption, color = c.text3)
                    // Bipolar: the notch is neutral graphite, the fill shows how far warm or cool.
                    BerthSlider(
                        value = theme.tone,
                        onValueChange = { vm.setInterfaceTheme(theme.copy(tone = it)) },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        neutral = 0.5f,
                    )
                    Text("Cool", style = BerthType.caption, color = c.text3)
                }
                Text("Accent", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
                // Each option is a 48 dp target around its 32 dp swatch, so the row's pitch is the target's.
                Row {
                    for ((name, accent) in ACCENTS) {
                        ColorOption(
                            color = accent.toColor(),
                            name = name,
                            selected = theme.accent == accent && !theme.materialYou,
                            onClick = { vm.setInterfaceTheme(theme.copy(accent = accent, materialYou = false)) },
                            size = 32.dp,
                            inset = 5.dp,
                        )
                    }
                }
                ToggleRow("Material You accent", theme.materialYou, { vm.setInterfaceTheme(theme.copy(materialYou = it)) }, caption = "Follow the wallpaper colour on Android 12 and later")
                ToggleRow("High contrast", theme.contrast == InterfaceContrast.HIGH, { vm.setInterfaceTheme(theme.copy(contrast = if (it) InterfaceContrast.HIGH else InterfaceContrast.STANDARD)) })
                ToggleRow("System font", theme.useSystemFont, { vm.setInterfaceTheme(theme.copy(useSystemFont = it)) }, caption = "Use the device's interface font instead of Plex Sans")
                ListRow("Interface editor", subtitle = "Presets, corner radius, density and a live preview", surface = Color.Transparent, minHeight = 44.dp, onClick = onAppearance, trailing = chevron)
            }

            Panel(label = "Terminal") {
                ListRow("Terminal themes", subtitle = "${themes.size} themes \u00B7 ${defaultTheme.name} is the default", surface = Color.Transparent, minHeight = 44.dp, onClick = onThemes, trailing = chevron)
                CyclePicker("Theme", themes.map { it.id }, defaultTheme.id, { id -> themes.firstOrNull { it.id == id }?.name ?: id }) { vm.setDefaultTerminalTheme(it) }
                // The family opens its own sheet (spec C20, Fonts): a list with each family set in its own face, and the import.
                PickerRow("Terminal font", font.resolvedFamily(context), onClick = { fontPicker = true })
                CyclePicker("Size", (TerminalFont.MIN_SIZE_SP..TerminalFont.MAX_SIZE_SP).toList(), font.sizeSp, { "$it sp" }) { vm.setTerminalFont(font.copy(sizeSp = it)) }
                ToggleRow("Follow system text size", font.followSystemScale, { vm.setTerminalFont(font.copy(followSystemScale = it)) }, caption = "Scale the terminal with the device's font size as well; off, the size above is the size")
                CyclePicker("Line height", listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f), font.lineHeight, { "%.1f".format(it) }) { vm.setTerminalFont(font.copy(lineHeight = it)) }
                ToggleRow("Ligatures", font.ligatures, { vm.setTerminalFont(font.copy(ligatures = it)) })
                ToggleRow("Nerd Font fallback", font.nerdFontFallback, { vm.setTerminalFont(font.copy(nerdFontFallback = it)) }, caption = "Prompt separators from a Nerd Font when the family has none; import one for its icons too")
                ToggleRow("Bold as bright", font.boldAsBright, { vm.setTerminalFont(font.copy(boldAsBright = it)) })
                // The cursor the user chose stands until the program on the other end asks for its own (DECSCUSR).
                Text("Cursor", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
                SegmentedControl(CURSOR_SHAPES.map { it.second }, CURSOR_SHAPES.indexOfFirst { it.first == font.cursorShape.lowercase() }.coerceAtLeast(0), { vm.setTerminalFont(font.copy(cursorShape = CURSOR_SHAPES[it].first)) })
                ToggleRow("Blink", font.cursorBlink, { vm.setTerminalFont(font.copy(cursorBlink = it)) })
                CyclePicker("Scrollback", TerminalSettings.SCROLLBACK_CHOICES, terminal.scrollbackLines, { "%,d lines".format(it) }, caption = "History kept above each terminal's screen") { lines -> vm.updateTerminalSettings { it.copy(scrollbackLines = lines) } }
            }

            CommandHistorySettings(vm)

            Panel(label = "Deck") {
                ListRow("Edit layers and keys", subtitle = deck.layers.joinToString(", ") { it.name }, surface = Color.Transparent, minHeight = 44.dp, onClick = onDeckEditor, trailing = chevron)
                CyclePicker("Height", listOf(40, 44, 48, 52), deck.heightDp, { "$it dp" }) { vm.setDeckLayout(deck.copy(heightDp = it)) }
                // D3 levels; Subtle keeps the key taps and drops the rest of the vocabulary.
                CyclePicker("Haptics", HapticLevel.entries, haptics, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { vm.setHapticLevel(it) }
                PanelNote("Hold the Deck's layer key on the Stage to open the editor from a session; on a Deck of one layer that key is the editor's.")
            }

            Panel(label = "Gestures") {
                CyclePicker("Switch tabs", TabSwipeGesture.entries, tabSwipe, ::swipeLabel) { vm.setTabSwipeGesture(it) }
                // Spec D1's optional drag: off, a sideways drag on the terminal does nothing, as it always has.
                ToggleRow("Drag for arrow keys", terminal.horizontalDragArrows, { on -> vm.updateTerminalSettings { it.copy(horizontalDragArrows = on) } }, caption = "A one-finger sideways drag on the terminal sends Left and Right, one per cell")
                PanelNote("One-finger drags always stay with the terminal, so programs that scroll or take touches are untouched.")
            }

            HardwareKeyboardPanel(vm)

            NotificationsSection(vm.notifier)

            SecurityPanel(vm, onKnownHosts)

            Panel(label = "Data") {
                ListRow("Import ssh config", subtitle = "Hosts and forwards from ~/.ssh/config", surface = Color.Transparent, minHeight = 44.dp, onClick = { importConfig = true }, trailing = chevron)
                ListRow("Import known_hosts", subtitle = "Server keys from ~/.ssh/known_hosts", surface = Color.Transparent, minHeight = 44.dp, onClick = { importKnownHosts = true }, trailing = chevron)
                ListRow("Import private key", subtitle = "OpenSSH, PEM, PKCS#8 or PuTTY", surface = Color.Transparent, minHeight = 44.dp, onClick = { importKey = true }, trailing = chevron)
            }

            Panel(label = "Diagnostics") {
                val crashes = reports.count { it.kind == ReportKind.CRASH }
                ListRow(
                    "Crash and connection reports",
                    subtitle = when {
                        reports.isEmpty() -> "None yet \u00B7 written to this phone only, never sent"
                        crashes == 0 -> "${reports.size} on this phone \u00B7 never sent by themselves"
                        else -> "${reports.size} on this phone, $crashes ${if (crashes == 1) "crash" else "crashes"} \u00B7 never sent by themselves"
                    },
                    surface = Color.Transparent,
                    minHeight = 44.dp,
                    onClick = onDiagnostics,
                    trailing = chevron,
                )
            }

            Panel(label = "About") {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
                Text("Berth $version".trim(), style = BerthType.body, color = c.text1)
                Text("No account. No telemetry. Everything stays on this device.", style = BerthType.caption, color = c.text3)
                Text("Fonts: IBM Plex Sans, IBM Plex Mono, JetBrains Mono, Fira Code and Source Code Pro under the SIL Open Font License; Hack and the Powerline symbols from Nerd Fonts under the MIT licence. SSH transport: sshj (Apache 2.0).", style = BerthType.caption, color = c.text3)
            }
        }
    }
}

/** The accent choices with the names a screen reader gives them; the colour alone never tells them apart. */
private val ACCENTS = listOf(
    "Copper" to 0xE0A458,
    "Coral" to 0xD9776B,
    "Aqua" to 0x7AD3C6,
    "Moss" to 0x8FB573,
    "Periwinkle" to 0x89A7E0,
    "Lilac" to 0xC79BD8,
)

/** The cursor shapes as [TerminalFont.cursorShape] stores them and as the control names them (spec C20: block, underline, bar). */
private val CURSOR_SHAPES = listOf("block" to "Block", "underline" to "Underline", "bar" to "Bar")

private fun swipeLabel(gesture: TabSwipeGesture): String = when (gesture) {
    TabSwipeGesture.TWO_FINGER -> "Two-finger swipe"
    TabSwipeGesture.RIGHT_EDGE -> "Right edge swipe"
    TabSwipeGesture.NONE -> "Off"
}
