package app.berth.android.ui.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.touchTarget
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.LocalPanelSurface
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.MaterialYouAvailable
import app.berth.android.ui.theme.materialYouAccent
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.AccentPreset
import app.berth.domain.model.HexColorSerializer
import app.berth.domain.model.InterfaceTheme

/** What an accent control is set to: a colour, the system's wallpaper accent, or nothing of its own. */
sealed interface AccentChoice {
    data object Inherit : AccentChoice
    data object MaterialYou : AccentChoice
    data class Colour(val rgb: Int) : AccentChoice
}

/** The interface theme's accent as the picker shows it; Material You only where the system has it. */
val InterfaceTheme.accentChoice: AccentChoice
    get() = if (materialYou && MaterialYouAvailable) AccentChoice.MaterialYou else AccentChoice.Colour(accent)

/** The colour the interface theme's accent draws in as 0xRRGGBB: the system's while Material You is on and there is one. */
@Composable
fun InterfaceTheme.shownAccentRgb(): Int =
    (if (materialYou) materialYouAccent(Berth.colors.isDark) else null)?.let { it.toArgb() and 0xFFFFFF } ?: accent

/** [choice] applied to the interface theme; a colour turns Material You off, Material You keeps the colour under it. */
fun InterfaceTheme.withAccent(choice: AccentChoice): InterfaceTheme = when (choice) {
    AccentChoice.Inherit -> this
    AccentChoice.MaterialYou -> copy(materialYou = true)
    is AccentChoice.Colour -> copy(accent = choice.rgb, materialYou = false)
}

/**
 * The one accent control (spec A2, C19): the seven presets by name, Material You where the system
 * has a wallpaper accent, and Custom with a hex field, as chips that wrap. Settings, the Interface
 * editor and the group editor all show this list, so a colour picked in one is the same named chip
 * in the others. [inherited] offers a leading Inherit, drawn with the colour it inherits, for an
 * override that can be cleared; such an override is a colour, so Material You is not offered.
 * Custom stays chosen for a preset's colour only when the hex field is what set it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentPicker(
    choice: AccentChoice,
    onChoose: (AccentChoice) -> Unit,
    modifier: Modifier = Modifier,
    inherited: Int? = null,
    customSeed: Int = (choice as? AccentChoice.Colour)?.rgb ?: inherited ?: AccentPreset.COPPER.rgb,
) {
    val colour = (choice as? AccentChoice.Colour)?.rgb
    val preset = colour?.let(AccentPreset::of)
    var customRgb by remember { mutableStateOf(colour?.takeIf { preset == null }) }
    val custom = colour != null && (preset == null || colour == customRgb)
    var customHex by remember { mutableStateOf(HexColorSerializer.toHex(customSeed)) }
    LaunchedEffect(colour, custom) {
        if (custom && colour != null && HexColorSerializer.parse(customHex) != colour) customHex = HexColorSerializer.toHex(colour)
    }
    val system = if (inherited == null) materialYouAccent(Berth.colors.isDark) else null

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (inherited != null) {
                AccentChip("Inherit", inherited.toColor(), selected = choice == AccentChoice.Inherit) {
                    customRgb = null
                    onChoose(AccentChoice.Inherit)
                }
            }
            for (p in AccentPreset.entries) {
                AccentChip(p.title, p.rgb.toColor(), selected = preset == p && !custom) {
                    customRgb = null
                    onChoose(AccentChoice.Colour(p.rgb))
                }
            }
            if (system != null) {
                AccentChip("Material You", system, selected = choice == AccentChoice.MaterialYou) {
                    customRgb = null
                    onChoose(AccentChoice.MaterialYou)
                }
            }
            AccentChip("Custom", if (custom) colour?.toColor() else null, selected = custom) {
                val rgb = colour ?: customSeed
                customHex = HexColorSerializer.toHex(rgb)
                customRgb = rgb
                onChoose(AccentChoice.Colour(rgb))
            }
        }
        if (custom) {
            BerthField(
                value = customHex,
                onValueChange = { text ->
                    customHex = text
                    HexColorSerializer.parse(text)?.let { rgb ->
                        customRgb = rgb
                        onChoose(AccentChoice.Colour(rgb))
                    }
                },
                label = "Accent hex",
                mono = true,
                isError = HexColorSerializer.parse(customHex) == null,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** An accent as the pickers name it: the preset's title, or the hex of any other colour. */
fun accentName(rgb: Int): String = AccentPreset.of(rgb)?.title ?: HexColorSerializer.toHex(rgb)

/**
 * The line under the app's accent picker while the current group has an accent of its own (spec
 * A2, C8). The chrome is drawn in the group's then, so without it a pick here changes nothing the
 * user can see; with no group accent in force there is no line.
 */
@Composable
fun GroupAccentNote(vm: AppViewModel) {
    val groups by vm.workspaces.collectAsState()
    val current by vm.currentWorkspaceId.collectAsState()
    val group = groups.firstOrNull { it.id == current } ?: return
    val rgb = group.accentRgb ?: return
    Text(
        "While ${group.name} is current the interface uses its accent, ${accentName(rgb)}. This sets the app's, for groups on Inherit.",
        style = BerthType.caption,
        color = Berth.colors.text2,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/**
 * A chip with its colour as a leading dot (none for a Custom not yet chosen), in a 48 dp target;
 * chosen chips step up to surface.4. In a surface.2 panel an idle chip steps down to surface.1, as
 * the segmented control's track does, so it still reads as a chip.
 */
@Composable
private fun AccentChip(text: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    val c = Berth.colors
    val idle = if (LocalPanelSurface.current == c.surface2) c.surface1 else c.surface2
    Box(
        Modifier
            .selectable(selected = selected, role = Role.RadioButton, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .touchTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(BerthRadius.swatch))
                .background(if (selected) c.surface4 else idle)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dot != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(dot))
            Text(text, style = BerthType.label, color = c.text1, maxLines = 1)
        }
    }
}
