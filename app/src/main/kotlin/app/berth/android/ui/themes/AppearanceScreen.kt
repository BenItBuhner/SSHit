package app.berth.android.ui.themes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthSlider
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SegmentedControl
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.io.PickedText
import app.berth.android.ui.io.TEXT_DOCUMENT_TYPES
import app.berth.android.ui.io.rememberOpenNamedTextFile
import app.berth.android.ui.io.rememberSaveTextFile
import app.berth.android.ui.io.shareText
import app.berth.android.ui.stage.Deck
import app.berth.android.ui.stage.ModifierLatch
import app.berth.android.ui.stage.StageInput
import app.berth.android.ui.stage.StatePill
import app.berth.android.ui.terminal.PreviewScript
import app.berth.android.ui.terminal.TerminalPreview
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.DensityTokens
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.Density
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import kotlin.math.roundToInt

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
    var note by remember { mutableStateOf<String?>(null) }
    val saver = rememberSaveTextFile()
    val openFile = rememberOpenNamedTextFile(TEXT_DOCUMENT_TYPES) { picked ->
        val imported = (picked as? PickedText.Read)?.let { runCatching { InterfaceTheme.fromJson(it.text) }.getOrNull() }
        note = when {
            picked !is PickedText.Read -> picked.refusal("an interface theme")
            imported == null -> "That file is not an interface theme."
            else -> { vm.setInterfaceTheme(imported); "Imported." }
        }
    }
    var pasteSheet by remember { mutableStateOf(false) }
    fun set(t: InterfaceTheme) = vm.setInterfaceTheme(t)
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
                    // Bipolar: the notch is neutral graphite, the fill shows how far warm or cool.
                    BerthSlider(
                        value = theme.tone,
                        onValueChange = { set(theme.copy(tone = it)) },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = "Tone" },
                        neutral = 0.5f,
                    )
                    Text("Cool", style = BerthType.caption, color = c.text3)
                }
                Caption("Accent", top = 8.dp)
                AccentPicker(theme.accentChoice, onChoose = { set(theme.withAccent(it)) }, customSeed = theme.accent)
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
                    // Tenths from 0.5 to 1.4: eight stops between the ends, rounded so the JSON stays clean.
                    BerthSlider(
                        value = theme.radiusScale,
                        onValueChange = { set(theme.copy(radiusScale = (it * 100).roundToInt() / 100f)) },
                        valueRange = InterfaceTheme.MIN_RADIUS_SCALE..InterfaceTheme.MAX_RADIUS_SCALE,
                        steps = RADIUS_STOPS,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = "Corner radius" },
                    )
                    Text("Round", style = BerthType.caption, color = c.text3)
                }
                Spacer(Modifier.height(8.dp))
                Caption("Interface font")
                SegmentedControl(listOf("IBM Plex Sans", "System"), if (theme.useSystemFont) 1 else 0, onSelect = { set(theme.copy(useSystemFont = it == 1)) })
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Import file", onClick = openFile)
                    BerthButton("Paste", onClick = { pasteSheet = true })
                    BerthButton("Save to file", onClick = { saver.save("berth-interface.json", theme.toJson()) })
                }
                val n = note
                if (n != null) Text(n, style = BerthType.caption, color = c.text1, modifier = Modifier.padding(start = 4.dp))
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

/** The corner-radius slider stops at tenths: the values strictly between 0.5 and 1.4. */
private const val RADIUS_STOPS = 8

/**
 * A Stage in miniature: the ribbon's anatomy with the drawn rail glyph, a detached terminal frame,
 * the real [StatePill] and the real Deck, so the preview cannot drift from what the Stage draws.
 * The Deck sits flush at the bottom as it does on the Stage, so the mock is cut at the row radius:
 * a panel's 20 dp arc would run through the outer keys' corners.
 */
@Composable
private fun StageMock(terminalTheme: app.berth.domain.model.TerminalTheme, font: app.berth.domain.model.TerminalFont, deck: app.berth.domain.model.DeckLayout) {
    val c = Berth.colors
    val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
    val detachedAt = remember { System.currentTimeMillis() - 4 * 60_000L }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(c.surface0)
            .semantics { contentDescription = "Interface preview" },
    ) {
        Row(
            Modifier.fillMaxWidth().height(DensityTokens.Comfortable.header).background(c.surface1).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The ribbon's IconActions are 44 dp wide inside the 40 dp strip.
            Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { BerthIcon(BerthIcons.workspace) }
            Spacer(Modifier.width(2.dp))
            Swatch(SwatchColor.VERDIGRIS, "HL", 24.dp)
            Spacer(Modifier.width(10.dp))
            Text("homelab", style = BerthType.label, color = c.text1, modifier = Modifier.weight(1f))
            Pill("1 needs you", color = c.attention.copy(alpha = 0.18f), textColor = c.attention)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { BerthIcon(BerthIcons.moreVert) }
        }
        Box(Modifier.fillMaxWidth().background(terminalTheme.background.toColor()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            TerminalPreview(theme = terminalTheme, font = font.copy(sizeSp = 10), script = PreviewScript.TILE, showCursor = true)
        }
        StatePill(state = SessionState.DETACHED, retryIn = null, lastLiveAt = detachedAt, now = detachedAt + 4 * 60_000L, onReconnect = {}, onDetach = {}, onClose = {})
        Deck(layout = deck, layerIndex = 0, onLayerIndexChange = {}, input = input)
    }
}

@Composable
private fun Caption(text: String, top: androidx.compose.ui.unit.Dp = 0.dp, modifier: Modifier = Modifier) {
    Text(text, style = BerthType.caption, color = Berth.colors.text2, modifier = modifier.padding(start = 4.dp, top = top, bottom = 6.dp))
}
