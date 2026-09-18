package app.berth.android.ui.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.io.rememberOpenTextFile
import app.berth.android.ui.io.rememberSaveTextFile
import app.berth.android.ui.io.shareText
import app.berth.android.ui.stage.Deck
import app.berth.android.ui.stage.ModifierLatch
import app.berth.android.ui.stage.StageInput
import app.berth.android.ui.terminal.PreviewScript
import app.berth.android.ui.terminal.TerminalPreview
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.AccentPreset
import app.berth.domain.model.Density
import app.berth.domain.model.HexColorSerializer
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.SwatchColor

private val VARIANTS = listOf(InterfaceVariant.DARK, InterfaceVariant.TRUE_BLACK, InterfaceVariant.LIGHT, InterfaceVariant.SYSTEM)

/**
 * Interface appearance (UX spec C19): variant, tone, accent, contrast, density, radius scale and
 * the interface font. Every change applies immediately, so the whole app is the preview; the mock
 * Stage at the top keeps the ribbon, terminal and Deck in view while the controls scroll.
 */
@Composable
fun AppearanceScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val context = LocalContext.current
    val theme by vm.interfaceTheme.collectAsState()
    val terminalTheme by vm.defaultTerminalTheme.collectAsState()
    val font by vm.terminalFont.collectAsState()
    val deck by vm.deckLayout.collectAsState()
    var customHex by remember { mutableStateOf(HexColorSerializer.toHex(theme.accent)) }
    var note by remember { mutableStateOf<String?>(null) }
    val saver = rememberSaveTextFile()
    val openFile = rememberOpenTextFile { text ->
        val imported = runCatching { InterfaceTheme.fromJson(text) }.getOrNull()
        note = if (imported == null) "That file is not an interface theme." else { vm.setInterfaceTheme(imported); "Imported." }
    }
    var pasteSheet by remember { mutableStateOf(false) }
    fun set(t: InterfaceTheme) = vm.setInterfaceTheme(t)
    val presetAccent = AccentPreset.entries.firstOrNull { it.rgb == theme.accent }
    val customAccent = !theme.materialYou && presetAccent == null

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Interface", onBack = onBack) {
            BerthButton("Share", onClick = { shareText(context, "berth-interface.json", theme.toJson()) }, kind = ButtonKind.TEXT)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.sectionGap),
        ) {
            StageMock(terminalTheme, font, deck)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Presets", Modifier.padding(start = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((name, preset) in InterfaceTheme.presets) {
                        Chip(name, selected = theme == preset) { set(preset) }
                    }
                }
            }

            Panel(label = "Look") {
                Caption("Variant")
                SegmentedControl(listOf("Dark", "Black", "Light", "System"), VARIANTS.indexOf(theme.variant), onSelect = { set(theme.copy(variant = VARIANTS[it])) })
                Caption("Tone", top = 14.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Warm", style = BerthType.caption, color = c.text3)
                    Slider(
                        value = theme.tone,
                        onValueChange = { set(theme.copy(tone = it)) },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Tone" },
                        colors = sliderColors(),
                    )
                    Text("Cool", style = BerthType.caption, color = c.text3)
                }
                Caption("Accent", top = 8.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (accent in AccentPreset.entries) {
                        AccentChip(accent.name.lowercase().replaceFirstChar { it.uppercase() }, accent.rgb, selected = presetAccent == accent && !theme.materialYou) {
                            set(theme.copy(accent = accent.rgb, materialYou = false))
                        }
                    }
                    Chip("Material You", selected = theme.materialYou) { set(theme.copy(materialYou = true)) }
                    Chip("Custom", selected = customAccent) { set(theme.copy(materialYou = false, accent = HexColorSerializer.parse(customHex) ?: theme.accent)) }
                }
                if (customAccent) {
                    Spacer(Modifier.height(8.dp))
                    BerthField(
                        value = customHex,
                        onValueChange = { text ->
                            customHex = text
                            HexColorSerializer.parse(text)?.let { set(theme.copy(accent = it, materialYou = false)) }
                        },
                        label = "Accent hex",
                        mono = true,
                        isError = HexColorSerializer.parse(customHex) == null,
                    )
                }
                Caption("Contrast", top = 14.dp)
                SegmentedControl(
                    listOf("Standard", "High"),
                    if (theme.contrast == InterfaceContrast.HIGH) 1 else 0,
                    onSelect = { set(theme.copy(contrast = if (it == 1) InterfaceContrast.HIGH else InterfaceContrast.STANDARD)) },
                )
                Caption("Density", top = 14.dp)
                SegmentedControl(
                    listOf("Comfortable", "Compact"),
                    if (theme.density == Density.COMPACT) 1 else 0,
                    onSelect = { set(theme.copy(density = if (it == 1) Density.COMPACT else Density.COMFORTABLE)) },
                )
            }

            Panel(label = "Shape and type") {
                Row(Modifier.fillMaxWidth()) {
                    Caption("Corner radius", modifier = Modifier.weight(1f))
                    Text("\u00D7%.2f".format(theme.radiusScale), style = BerthType.caption, color = c.text3)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Square", style = BerthType.caption, color = c.text3)
                    Slider(
                        value = theme.radiusScale,
                        onValueChange = { set(theme.copy(radiusScale = it)) },
                        valueRange = InterfaceTheme.MIN_RADIUS_SCALE..InterfaceTheme.MAX_RADIUS_SCALE,
                        steps = 8,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Corner radius" },
                        colors = sliderColors(),
                    )
                    Text("Round", style = BerthType.caption, color = c.text3)
                }
                Text("Every radius in the app scales together, so nested corners stay concentric.", style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                Caption("Interface font")
                SegmentedControl(listOf("IBM Plex Sans", "System"), if (theme.useSystemFont) 1 else 0, onSelect = { set(theme.copy(useSystemFont = it == 1)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Import file", onClick = openFile)
                    BerthButton("Paste", onClick = { pasteSheet = true })
                    BerthButton("Save to file", onClick = { saver.save("berth-interface.json", theme.toJson()) })
                }
                Text(note ?: "Interface JSON carries the variant, tone, accent, contrast, density, radius scale and font choice.", style = BerthType.caption, color = if (note != null) c.text1 else c.text3, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }

    if (pasteSheet) {
        PasteTextSheet(
            title = "Paste interface theme",
            caption = "Berth interface JSON",
            action = "Apply",
            onDismiss = { pasteSheet = false },
            onSubmit = { text ->
                val imported = runCatching { InterfaceTheme.fromJson(text) }.getOrNull()
                note = if (imported == null) "That text is not an interface theme." else { set(imported); "Applied." }
                pasteSheet = false
            },
        )
    }
}

/** A Stage in miniature: ribbon, a detached terminal frame, the state pill row and the real Deck. */
@Composable
private fun StageMock(terminalTheme: app.berth.domain.model.TerminalTheme, font: app.berth.domain.model.TerminalFont, deck: app.berth.domain.model.DeckLayout) {
    val c = Berth.colors
    val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.panel))
            .background(c.surface0)
            .semantics { contentDescription = "Interface preview" },
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(c.surface1).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("\u2261", style = BerthType.label, color = c.text2)
            Swatch(SwatchColor.VERDIGRIS, "HL", 24.dp)
            Text("homelab", style = BerthType.label, color = c.text1, modifier = Modifier.weight(1f))
            Pill("1 needs you", color = c.attention.copy(alpha = 0.18f), textColor = c.attention)
        }
        Box(Modifier.fillMaxWidth().background(terminalTheme.background.toColor()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            TerminalPreview(theme = terminalTheme, font = font.copy(sizeSp = 10), script = PreviewScript.TILE, showCursor = true)
        }
        Row(
            Modifier.fillMaxWidth().background(c.surface1).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Detached 4 min ago", style = BerthType.label, color = c.text2, modifier = Modifier.weight(1f))
            BerthButton("Reconnect", onClick = {}, kind = ButtonKind.PRIMARY, modifier = Modifier.height(36.dp))
            BerthButton("Close", onClick = {}, modifier = Modifier.height(36.dp))
        }
        Deck(layout = deck, layerIndex = 0, onLayerIndexChange = {}, input = input)
    }
}

@Composable
private fun Caption(text: String, top: androidx.compose.ui.unit.Dp = 0.dp, modifier: Modifier = Modifier) {
    Text(text, style = BerthType.caption, color = Berth.colors.text2, modifier = modifier.padding(start = 4.dp, top = top, bottom = 6.dp))
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Berth.colors.accent, activeTrackColor = Berth.colors.accent, inactiveTrackColor = Berth.colors.surface4)

/** A chip with the accent colour as a leading dot, selected chips step up a surface. */
@Composable
private fun AccentChip(text: String, rgb: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Berth.colors
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(BerthRadius.swatch))
            .background(if (selected) c.surface4 else c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(rgb.toColor()))
        Text(text, style = BerthType.label, color = c.text1, maxLines = 1)
    }
}
