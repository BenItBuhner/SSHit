package app.berth.android.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.berth.domain.model.ColorMath
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import kotlin.math.roundToInt

/** Which sample the preview types into its emulator. */
enum class PreviewScript(val rows: Int) {
    /** Prompt, `ls` colours, ok / error / warn lines, bold and dim text, a link, a selection, the palette, cursor. */
    FULL(9),

    /** Gallery tile: a prompt line, three coloured names and the sixteen colours. */
    TILE(4),
}

/**
 * A real [TerminalEmulator] rendered through [TerminalRenderer], so the editor's preview and the
 * gallery tiles show a theme exactly as the Stage will. The emulator is fed a fixed script that
 * touches every slot of the theme; the script is re-typed whenever the theme or width changes
 * because selection and link colours are written as truecolor escapes.
 */
@Composable
fun TerminalPreview(
    theme: TerminalTheme,
    font: TerminalFont,
    modifier: Modifier = Modifier,
    script: PreviewScript = PreviewScript.FULL,
    showCursor: Boolean = true,
    onSample: ((rgb: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val paints = remember(font, density.density, density.fontScale) { TerminalPaints(context, font, density.density, density.fontScale) }
    var version by remember { mutableLongStateOf(0L) }
    val emulator = remember {
        TerminalEmulator(
            cols = 40,
            rows = script.rows,
            maxScrollback = 0,
            listener = object : TerminalListenerAdapter() {
                override fun onScreenChanged() {
                    version++
                }
            },
        )
    }
    var cols by remember { mutableIntStateOf(0) }
    val currentSample by rememberUpdatedState(onSample)
    val heightDp = with(density) { (paints.cellHeight * script.rows).toDp() }

    LaunchedEffect(theme, cols, script, paints) {
        if (cols < 2) return@LaunchedEffect
        emulator.reset()
        emulator.resize(cols, script.rows)
        // After reset: a full reset restores the stock palette.
        emulator.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        emulator.write(previewText(theme, script, cols))
        version++
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            .onSizeChanged { size ->
                val c = (size.width / paints.cellWidth).toInt()
                if (c >= 2 && c != cols) {
                    cols = c
                    emulator.cellWidthPx = paints.cellWidth.roundToInt()
                    emulator.cellHeightPx = paints.cellHeight.roundToInt()
                }
            }
            .semantics { contentDescription = "Preview of ${theme.name}" }
            .then(
                if (onSample != null) {
                    Modifier.pointerInput(paints, theme, font.boldAsBright) {
                        detectTapGestures { offset ->
                            val (col, row) = paints.cellAt(offset)
                            TerminalRenderer.sampleColor(emulator, theme, font.boldAsBright, col, row)?.let { currentSample?.invoke(it) }
                        }
                    }
                } else Modifier,
            ),
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        drawIntoCanvas { canvas ->
            TerminalRenderer.draw(
                nc = canvas.nativeCanvas,
                emulator = emulator,
                paints = paints,
                theme = theme,
                boldAsBright = font.boldAsBright,
                width = size.width,
                height = size.height,
                showCursor = showCursor,
                focused = true,
            )
        }
    }
}

private const val ESC = "\u001b["
private const val R = "\u001b[0m"

private fun fg(i: Int) = if (i < 8) "$ESC${30 + i}m" else "$ESC${90 + i - 8}m"
private fun bg(i: Int) = if (i < 8) "$ESC${40 + i}m" else "$ESC${100 + i - 8}m"
private fun fgRgb(rgb: Int) = "${ESC}38;2;${ColorMath.red(rgb)};${ColorMath.green(rgb)};${ColorMath.blue(rgb)}m"
private fun bgRgb(rgb: Int) = "${ESC}48;2;${ColorMath.red(rgb)};${ColorMath.green(rgb)};${ColorMath.blue(rgb)}m"
private const val BOLD = "\u001b[1m"
private const val DIM = "\u001b[2m"
private const val UL = "\u001b[4m"

/** The sixteen colours as two rows of blocks, two cells each. */
private fun paletteRows(): String =
    (0..7).joinToString("") { bg(it) + "  " } + R + "\r\n" + (8..15).joinToString("") { bg(it) + "  " } + R

/**
 * The sample script for [script]. Long lines wrap at [cols], so the full script trims its
 * decorations on narrow previews instead of spilling onto the next row.
 */
internal fun previewText(theme: TerminalTheme, script: PreviewScript, cols: Int): String {
    val wide = cols >= 44
    val prompt = "${fg(2)}ben$R@${BOLD}berth$R:${fg(4)}~/srv$R$ "
    return when (script) {
        PreviewScript.TILE -> listOf(
            "${fg(2)}~$R$ ls",
            "$BOLD${fg(4)}docs/$R ${fg(2)}run.sh$R ${fg(5)}notes.md$R",
            paletteRows(),
        ).joinToString("\r\n")
        PreviewScript.FULL -> listOf(
            prompt + "ls -la",
            "$BOLD${fg(4)}docs/$R  $BOLD${fg(4)}scripts/$R  ${fg(2)}deploy.sh$R  ${bgRgb(theme.selection)}config.toml$R" + if (wide) "  ${fg(5)}notes.md$R" else "",
            "${fg(2)}ok$R     configuration loaded",
            "${fg(1)}error$R  failed to connect to host",
            "${fg(3)}warn$R   retrying in 4 s" + if (wide) "  ${DIM}(attempt 2 of 5)$R" else "  ${DIM}(2 of 5)$R",
            "${BOLD}Bold heading$R  " + (if (wide) "${fg(6)}cyan$R ${fg(5)}magenta$R  " else "") + "$UL${fgRgb(theme.links)}https://berth.app$R",
            paletteRows(),
            prompt,
        ).joinToString("\r\n")
    }
}
