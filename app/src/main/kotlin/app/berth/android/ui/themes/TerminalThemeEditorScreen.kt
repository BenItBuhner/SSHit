package app.berth.android.ui.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.TouchTargetSize
import app.berth.android.ui.a11y.bleedInto
import app.berth.android.ui.a11y.touchTarget
import app.berth.android.ui.byId
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Chip
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.PickerRow
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.io.rememberSaveTextFile
import app.berth.android.ui.io.shareText
import app.berth.android.ui.terminal.PreviewScript
import app.berth.android.ui.terminal.TerminalPreview
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.ColorMath
import app.berth.domain.model.HexColorSerializer
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.ThemeSlot
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** What the editor's primary action applies the theme to. */
@Serializable
sealed interface ThemeScope {
    @Serializable data object AppDefault : ThemeScope
    @Serializable data class ForHost(val hostId: String) : ThemeScope
    @Serializable data class ForWorkspace(val workspaceId: String) : ThemeScope
}

/**
 * The terminal theme editor (UX spec C19): a live preview on a real emulator, the sixteen ANSI
 * swatches, the named colours as rows, and a primary action that applies the theme to the app
 * default, a host or a workspace. Stock themes are never edited in place; saving one stores a copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalThemeEditorScreen(
    vm: AppViewModel,
    themeId: String,
    scope: ThemeScope,
    onDone: () -> Unit,
    onOpenTheme: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val context = LocalContext.current
    val themes by vm.terminalThemes.collectAsState()
    val default by vm.defaultTerminalTheme.collectAsState()
    val font by vm.terminalFont.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val stored = themes.firstOrNull { it.id == themeId }
    if (stored == null) {
        // A theme saved a moment ago may still be on its way to disk; only a theme that stays
        // missing (deleted, or never existed) sends the editor back.
        LaunchedEffect(themeId) {
            delay(1_500)
            onDone()
        }
        return
    }

    var draft by remember(themeId) { mutableStateOf(stored) }
    var editing by remember { mutableStateOf<ThemeSlot?>(null) }
    var sampling by remember { mutableStateOf<ThemeSlot?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var applyTo by remember(scope) { mutableStateOf(scope) }
    var note by remember { mutableStateOf<String?>(null) }
    val saver = rememberSaveTextFile()
    val dirty = draft != stored

    fun scopeLabel(s: ThemeScope): String = when (s) {
        ThemeScope.AppDefault -> "App default"
        is ThemeScope.ForHost -> hosts.firstOrNull { it.id == s.hostId }?.name ?: "host"
        is ThemeScope.ForWorkspace -> workspaces.byId(s.workspaceId)?.name ?: "workspace"
    }

    /** Stores the draft: in place for a custom theme, as a fresh copy for a stock one. Returns the stored id. */
    fun save(): String {
        if (!stored.builtIn) {
            vm.saveTerminalTheme(draft)
            return draft.id
        }
        val id = AppViewModel.newThemeId()
        val name = if (draft.name == stored.name) "${stored.name} copy" else draft.name
        vm.saveTerminalTheme(draft.copy(id = id, name = name, builtIn = false))
        onOpenTheme(id)
        return id
    }

    fun apply() {
        val id = if (dirty) save() else stored.id
        when (val s = applyTo) {
            ThemeScope.AppDefault -> vm.setDefaultTerminalTheme(id)
            is ThemeScope.ForHost -> vm.setHostTerminalTheme(s.hostId, id)
            is ThemeScope.ForWorkspace -> vm.setWorkspaceTerminalTheme(s.workspaceId, id)
        }
        note = "Applied to ${scopeLabel(applyTo).lowercase().let { if (applyTo == ThemeScope.AppDefault) "the app default" else it }}."
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(draft.name, onBack = onDone) {
            BerthButton(if (stored.builtIn) "Save copy" else "Save", onClick = { save() }, kind = ButtonKind.TEXT, enabled = dirty)
            Box {
                IconAction(onClick = { menu = true }, description = "More") { BerthIcon(BerthIcons.moreVert) }
                BerthMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuItem("Duplicate") {
                        menu = false
                        val id = AppViewModel.newThemeId()
                        vm.saveTerminalTheme(draft.duplicate(id))
                        onOpenTheme(id)
                    }
                    MenuItem("Rename") { menu = false; renaming = true }
                    if (default.id != stored.id) MenuItem("Set as app default") { menu = false; applyTo = ThemeScope.AppDefault; apply() }
                    MenuItem("Share JSON") { menu = false; shareText(context, "${draft.name}.json", draft.toJson()) }
                    MenuItem("Save to file") { menu = false; saver.save("${draft.id}.json", draft.toJson()) }
                    if (!stored.builtIn) MenuItem("Delete", destructive = true) { menu = false; vm.deleteTerminalTheme(stored.id); onDone() }
                }
            }
        }
        Text(
            when {
                stored.builtIn -> "Terminal theme \u00B7 stock. Saving keeps ${stored.name} as it is and stores your changes as a copy."
                dirty -> "Terminal theme \u00B7 unsaved changes"
                else -> "Terminal theme"
            },
            style = BerthType.caption,
            color = c.text3,
            modifier = Modifier.padding(horizontal = BerthSpace.screenMargin).padding(bottom = 12.dp),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(BerthSpace.sectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val target = sampling
                if (target != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tap the preview to sample a colour for ${target.title.lowercase()}", style = BerthType.caption, color = c.accent, modifier = Modifier.weight(1f))
                        BerthButton("Cancel", onClick = { sampling = null }, kind = ButtonKind.TEXT, fillHeight = 32.dp)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(BerthRadius.panel))
                        .background(draft.background.toColor())
                        .padding(14.dp),
                ) {
                    TerminalPreview(
                        theme = draft,
                        font = font,
                        script = PreviewScript.FULL,
                        onSample = if (target != null) {
                            { rgb ->
                                draft = draft.with(target, rgb)
                                sampling = null
                                editing = target
                            }
                        } else null,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("ANSI palette", Modifier.padding(start = 4.dp))
                // Equal cells, each at least a 48 dp target with the 40 dp swatch centred, so no two
                // swatches share a target: eight to a row (the normal eight over the bright eight)
                // where eight targets fit once the rows take 4 dp of target from either margin, the
                // swatches themselves staying on the margin line; four to a row on a narrower phone.
                BoxWithConstraints(Modifier.fillMaxWidth().bleedInto(4.dp)) {
                    val perRow = if (maxWidth >= TouchTargetSize * 8) 8 else 4
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (rowStart in 0 until 16 step perRow) {
                            Row(Modifier.fillMaxWidth()) {
                                for (i in rowStart until rowStart + perRow) {
                                    val slot = ThemeSlot.Ansi(i)
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                        PaletteSwatch(
                                            rgb = draft.ansi[i],
                                            label = i.toString(),
                                            title = slot.title,
                                            selected = editing == slot || sampling == slot,
                                            onClick = { editing = slot },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Panel(label = "Colours") {
                for (slot in ThemeSlot.named) {
                    val rgb = draft.color(slot)
                    ListRow(
                        title = slot.title,
                        subtitle = when (slot) {
                            ThemeSlot.Bold -> if (rgb == null) "Inherits the foreground" else null
                            ThemeSlot.CursorText -> "Text under a block cursor"
                            ThemeSlot.Selection -> "Behind selected text"
                            ThemeSlot.Links -> "Underlined hyperlinks"
                            else -> null
                        },
                        surface = Color.Transparent,
                        minHeight = 44.dp,
                        selected = editing == slot,
                        onClick = { editing = slot },
                        trailing = {
                            if (rgb == null) {
                                Text("Inherit", style = BerthType.body, color = c.text2)
                                BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp)
                            } else {
                                ColorDot(rgb, 24.dp)
                            }
                        },
                    )
                }
            }

            Panel(label = "Use for") {
                Box {
                    PickerRow("Apply to", scopeLabel(applyTo), onClick = { scopeMenu = true })
                    BerthMenu(expanded = scopeMenu, onDismiss = { scopeMenu = false }) {
                        MenuItem("App default") { scopeMenu = false; applyTo = ThemeScope.AppDefault }
                        for (ws in workspaces) MenuItem("Workspace \u00B7 ${ws.name}") { scopeMenu = false; applyTo = ThemeScope.ForWorkspace(ws.id) }
                        for (h in hosts) MenuItem("Host \u00B7 ${h.name}") { scopeMenu = false; applyTo = ThemeScope.ForHost(h.id) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BerthButton("Apply to ${scopeLabel(applyTo).lowercase().let { if (applyTo == ThemeScope.AppDefault) "app default" else it }}", onClick = ::apply, kind = ButtonKind.PRIMARY, modifier = Modifier.weight(1f))
                    BerthButton("Export", onClick = { shareText(context, "${draft.name}.json", draft.toJson()) })
                }
                val n = note
                if (n != null) Text(n, style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
        }
    }

    val slot = editing
    if (slot != null) {
        ColourSheet(
            slot = slot,
            theme = draft,
            onChange = { rgb -> draft = draft.with(slot, rgb) },
            onPickFromPreview = { editing = null; sampling = slot },
            onDismiss = { editing = null },
        )
    }
    if (renaming) {
        RenameSheet(current = draft.name, onDismiss = { renaming = false }) { name ->
            draft = draft.copy(name = name)
            renaming = false
        }
    }
}

/**
 * One ANSI swatch with its index beneath: a 40 dp square, the colour at `swatch` radius inside a
 * 2 dp step that turns surface.4 when selected (`swatch + 2`, concentric), centred in a 48 dp
 * target. To a screen reader it is a radio button named for its slot and its hex, `Red #d9776b`.
 */
@Composable
private fun PaletteSwatch(rgb: Int, label: String, title: String, selected: Boolean, onClick: () -> Unit) {
    val c = Berth.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .selectable(selected = selected, role = Role.RadioButton, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
                .semantics { contentDescription = "$title ${HexColorSerializer.toHex(rgb)}" }
                .touchTarget(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(BerthRadius.swatch + 2.dp))
                    .background(if (selected) c.surface4 else Color.Transparent)
                    .padding(2.dp),
            ) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(BerthRadius.swatch)).background(rgb.toColor()))
            }
        }
        Text(label, style = BerthType.caption, color = if (selected) c.text1 else c.text3)
    }
}

/** A colour at `indicator` radius: the trailing element of a 44 dp row, one radius step in from the row's. */
@Composable
internal fun ColorDot(rgb: Int, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(BerthRadius.indicator)).background(rgb.toColor()))
}

/**
 * The colour sheet: hex field, HSL sliders, contrast against the background and "pick from
 * preview". Every change applies immediately so the preview above tracks the sliders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColourSheet(
    slot: ThemeSlot,
    theme: TerminalTheme,
    onChange: (Int?) -> Unit,
    onPickFromPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    val current = theme.color(slot)
    val base = current ?: theme.foreground
    val initial = remember(slot) { current }
    var hex by remember(slot) { mutableStateOf(HexColorSerializer.toHex(base)) }
    var hsl by remember(slot) { mutableStateOf(ColorMath.toHsl(base)) }
    var hexError by remember(slot) { mutableStateOf(false) }

    fun setRgb(rgb: Int) {
        hex = HexColorSerializer.toHex(rgb)
        hexError = false
        onChange(rgb)
    }
    fun setHsl(h: Float, s: Float, l: Float) {
        hsl = floatArrayOf(h, s, l)
        setRgb(ColorMath.fromHsl(h, s, l))
    }

    val against = if (slot == ThemeSlot.Background) theme.foreground else theme.background
    val contrast = ColorMath.contrast(base, against)
    val grade = when {
        contrast >= 7 -> "AAA"
        contrast >= 4.5 -> "AA"
        contrast >= 3 -> "AA large"
        else -> "low"
    }

    BerthSheet(
        onDismiss = onDismiss,
        // Opens at its full height: its actions are at the bottom, and a half-open sheet would cut
        // them off (the audit's touch-target check saw a 29 dp Done). It scrolls on a short screen.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = c.scrim,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetTitle(slot.title, "Contrast %.1f : 1 against the ${if (slot == ThemeSlot.Background) "foreground" else "background"} \u00B7 $grade".format(contrast))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The colour on a hint of what it sits against: `swatch` outside, 4 dp in, `indicator`
                // inside, the ring translucent so it reads as context rather than as a frame.
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(BerthRadius.swatch))
                        .background(against.toColor().copy(alpha = 0.35f))
                        .padding(4.dp),
                ) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(BerthRadius.indicator)).background(base.toColor()))
                }
                BerthField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        val parsed = HexColorSerializer.parse(text)
                        hexError = parsed == null
                        if (parsed != null) {
                            hsl = ColorMath.toHsl(parsed)
                            onChange(parsed)
                        }
                    },
                    label = "Hex",
                    mono = true,
                    isError = hexError,
                    modifier = Modifier.weight(1f),
                )
            }
            if (slot == ThemeSlot.Bold) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Inherit foreground", selected = current == null) { onChange(null) }
                    Chip("Own colour", selected = current != null) { if (current == null) setRgb(theme.foreground) }
                }
            }
            val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
            HslSlider("Hue", h, 0f..360f, hueTrack(s, l), base) { setHsl(it, s, l) }
            HslSlider("Saturation", s, 0f..1f, ramp { ColorMath.fromHsl(h, it, l) }, base) { setHsl(h, it, l) }
            HslSlider("Lightness", l, 0f..1f, ramp { ColorMath.fromHsl(h, s, it) }, base) { setHsl(h, s, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton("Done", onClick = onDismiss, kind = ButtonKind.PRIMARY)
                BerthButton("Pick from preview", onClick = onPickFromPreview)
                Spacer(Modifier.weight(1f))
                if (initial != current) BerthButton("Revert", onClick = { onChange(initial); initial?.let { hsl = ColorMath.toHsl(it); hex = HexColorSerializer.toHex(it) } }, kind = ButtonKind.TEXT)
            }
        }
    }
}

/** Twelve stops of [stop] from 0 to 1, the gradient a slider's track is painted with. */
private fun ramp(stop: (Float) -> Int): List<Color> = (0..12).map { stop(it / 12f).toColor() }

/**
 * The hue track at the colour's own saturation and lightness, kept vivid enough to read even
 * for a grey, so the track shows roughly what each hue would give this slot.
 */
private fun hueTrack(s: Float, l: Float): List<Color> = ramp { ColorMath.fromHsl(it * 360f, s.coerceAtLeast(0.4f), l.coerceIn(0.3f, 0.7f)) }

/**
 * A slider whose track is the range of colours the channel can reach and whose thumb is the
 * colour being edited, so the eye can pick a value before the finger moves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HslSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, track: List<Color>, current: Int, onChange: (Float) -> Unit) {
    val c = Berth.colors
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = BerthType.caption, color = c.text2, modifier = Modifier.weight(1f))
            Text(if (range.endInclusive > 1f) "${value.toInt()}\u00B0" else "${(value * 100).toInt()}%", style = BerthType.caption, color = c.text3)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = "$title slider" },
            thumb = {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(c.text1)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(current.toColor()),
                )
            },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Brush.horizontalGradient(track)),
                )
            },
        )
    }
}

/** One field and one action; renames by default, and serves any short text prompt such as a tmux prefix. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSheet(
    current: String,
    onDismiss: () -> Unit,
    title: String = "Rename",
    label: String = "Name",
    caption: String? = null,
    action: String = "Rename",
    allowBlank: Boolean = false,
    mono: Boolean = false,
    onRename: (String) -> Unit,
) {
    val c = Berth.colors
    var name by remember { mutableStateOf(current) }
    BerthSheet(onDismiss = onDismiss, scrimColor = c.scrim) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = BerthSpace.screenMargin)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetTitle(title, caption)
            BerthField(name, { name = it }, label = label, mono = mono)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton(action, onClick = { onRename(name.trim()) }, kind = ButtonKind.PRIMARY, enabled = allowBlank || name.isNotBlank())
                BerthButton("Cancel", onClick = onDismiss, kind = ButtonKind.TEXT)
            }
        }
    }
}