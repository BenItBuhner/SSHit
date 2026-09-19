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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.ToggleRow
import app.berth.android.ui.hosts.CyclePicker
import app.berth.android.ui.importer.ImportHostsSheet
import app.berth.android.ui.importer.ImportKeySheet
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.TerminalFont

/** Interface and terminal defaults. Panels, not a preference tree. */
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onKnownHosts: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val theme by vm.interfaceTheme.collectAsState()
    val font by vm.terminalFont.collectAsState()
    val themes by vm.terminalThemes.collectAsState()
    val defaultTheme by vm.defaultTerminalTheme.collectAsState()
    val deck by vm.deckLayout.collectAsState()
    val known by vm.knownHosts.collectAsState()
    var importConfig by remember { mutableStateOf(false) }
    var importKey by remember { mutableStateOf(false) }

    if (importConfig) ImportHostsSheet(vm, onDismiss = { importConfig = false })
    if (importKey) ImportKeySheet(vm, onDismiss = { importKey = false })

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
                    Slider(
                        value = theme.tone,
                        onValueChange = { vm.setInterfaceTheme(theme.copy(tone = it)) },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface4),
                    )
                    Text("Cool", style = BerthType.caption, color = c.text3)
                }
                Text("Accent", style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (accent in ACCENTS) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(BerthRadius.swatch))
                                .background(if (theme.accent == accent && !theme.materialYou) c.surface4 else c.surface2)
                                .clickable { vm.setInterfaceTheme(theme.copy(accent = accent, materialYou = false)) }
                                .padding(5.dp),
                        ) {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(accent.toColor()))
                        }
                    }
                }
                ToggleRow("Material You accent", theme.materialYou, { vm.setInterfaceTheme(theme.copy(materialYou = it)) }, caption = "Follow the wallpaper colour on Android 12 and later")
                ToggleRow("High contrast", theme.contrast == InterfaceContrast.HIGH, { vm.setInterfaceTheme(theme.copy(contrast = if (it) InterfaceContrast.HIGH else InterfaceContrast.STANDARD)) })
                ToggleRow("System font", theme.useSystemFont, { vm.setInterfaceTheme(theme.copy(useSystemFont = it)) }, caption = "Use the device's interface font instead of Plex Sans")
            }

            Panel(label = "Terminal") {
                CyclePicker("Theme", themes.map { it.id }, defaultTheme.id, { id -> themes.firstOrNull { it.id == id }?.name ?: id }) { vm.setDefaultTerminalTheme(it) }
                CyclePicker("Font", listOf("JetBrains Mono", "System monospace"), font.family, { it }) { vm.setTerminalFont(font.copy(family = it)) }
                CyclePicker("Size", (TerminalFont.MIN_SIZE_SP..TerminalFont.MAX_SIZE_SP).toList(), font.sizeSp, { "$it sp" }) { vm.setTerminalFont(font.copy(sizeSp = it)) }
                CyclePicker("Line height", listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f), font.lineHeight, { "%.1f".format(it) }) { vm.setTerminalFont(font.copy(lineHeight = it)) }
                ToggleRow("Ligatures", font.ligatures, { vm.setTerminalFont(font.copy(ligatures = it)) })
                ToggleRow("Bold as bright", font.boldAsBright, { vm.setTerminalFont(font.copy(boldAsBright = it)) })
            }

            Panel(label = "Deck") {
                CyclePicker("Height", listOf(40, 44, 48, 52), deck.heightDp, { "$it dp" }) { vm.setDeckLayout(deck.copy(heightDp = it)) }
                Text("Layers: " + deck.layers.joinToString(", ") { it.name }, style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                Text("Editing keys and layers arrives with the layout editor; the layout is already data, so it will not require a migration.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
            }

            // Rows that navigate end in the same chevron the Terminal pickers use.
            val chevron: @Composable RowScope.() -> Unit = { Text("\u203A", style = BerthType.body, color = c.text3) }
            Panel(label = "Trust") {
                ListRow("Known hosts", subtitle = if (known.isEmpty()) "Saved server keys" else "${known.size} saved server ${if (known.size == 1) "key" else "keys"}" + known.count { it.pinned }.let { if (it > 0) " \u00B7 $it pinned" else "" }, surface = Color.Transparent, minHeight = 44.dp, onClick = onKnownHosts, trailing = chevron)
            }

            Panel(label = "Data") {
                ListRow("Import ssh config", subtitle = "Hosts and forwards from ~/.ssh/config", surface = Color.Transparent, minHeight = 44.dp, onClick = { importConfig = true }, trailing = chevron)
                ListRow("Import private key", subtitle = "OpenSSH, PEM, PKCS#8 or PuTTY", surface = Color.Transparent, minHeight = 44.dp, onClick = { importKey = true }, trailing = chevron)
            }

            Panel(label = "About") {
                val context = LocalContext.current
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
                Text("Berth $version".trim(), style = BerthType.body, color = c.text1)
                Text("No account. No telemetry. Everything stays on this device.", style = BerthType.caption, color = c.text3)
                Text("Fonts: IBM Plex Sans and JetBrains Mono under the SIL Open Font License. SSH transport: sshj (Apache 2.0).", style = BerthType.caption, color = c.text3)
            }
        }
    }
}

private val ACCENTS = listOf(0xE0A458, 0xD9776B, 0x7AD3C6, 0x8FB573, 0x89A7E0, 0xC79BD8)
