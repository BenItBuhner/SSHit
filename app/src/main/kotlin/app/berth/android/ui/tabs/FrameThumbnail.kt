package app.berth.android.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.terminalFontScale
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalRenderer
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme

/**
 * A tab's frame at a reduced size (spec C3, Switcher): the emulator's real cells drawn through
 * [TerminalRenderer] with the canvas scaled so the first [THUMBNAIL_COLS] columns fit the card's
 * width (a wider screen is cropped at the right, so type on a phone stays a legible ~6 sp rather
 * than texture), and a frame taller than the box anchored on the cursor row, so the prompt and
 * the rows above it show rather than the oldest rows. [live] cards follow the screen as it
 * changes; the rest draw the frame as it stood when the card appeared, which is the frame a
 * switch shows first anyway.
 */
@Composable
fun FrameThumbnail(
    session: TerminalSession,
    theme: TerminalTheme,
    font: TerminalFont,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scale = terminalFontScale(font)
    val paints = remember(font, density.density, scale) { TerminalPaints(context, font, density.density, scale) }
    val version: State<Long> = if (live) session.screenVersion.collectAsState() else remember { mutableLongStateOf(0L) }
    val emulator = session.emulator
    Canvas(modifier.semantics { contentDescription = "Frame of ${session.record.value.displayTitle}" }) {
        @Suppress("UNUSED_EXPRESSION") version.value
        val cols = emulator.cols.coerceAtLeast(1)
        val rows = emulator.rows.coerceAtLeast(1)
        val frameWidth = cols * paints.cellWidth
        val frameHeight = rows * paints.cellHeight
        val scale = size.width / (minOf(cols, THUMBNAIL_COLS) * paints.cellWidth)
        val shownHeight = frameHeight * scale
        // Slide the frame up until the cursor row is the last one in the box, never past the frame's end.
        val shift = if (shownHeight > size.height) {
            val cursorBottom = (emulator.cursorY + 1) * paints.cellHeight * scale
            (cursorBottom - size.height).coerceIn(0f, shownHeight - size.height)
        } else 0f
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val saved = nc.save()
            nc.translate(0f, -shift)
            nc.scale(scale, scale)
            TerminalRenderer.draw(
                nc = nc,
                emulator = emulator,
                paints = paints,
                theme = theme,
                boldAsBright = font.boldAsBright,
                width = frameWidth,
                height = maxOf(frameHeight, (size.height + shift) / scale),
                showCursor = live,
                focused = true,
            )
            nc.restoreToCount(saved)
        }
    }
}

/** The most columns a thumbnail fits across the card; a wider screen is cropped at the right. */
private const val THUMBNAIL_COLS = 40
