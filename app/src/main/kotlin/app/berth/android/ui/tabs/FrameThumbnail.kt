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
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalRenderer
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme

/**
 * A tab's frame at a reduced size (spec C3, Switcher): the emulator's real cells drawn through
 * [TerminalRenderer] with the canvas scaled so the full width of the screen fits, top-aligned and
 * clipped at the bottom. [live] cards follow the screen as it changes; the rest draw the frame as
 * it stood when the card appeared, which is the frame a switch shows first anyway.
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
    val paints = remember(font, density.density, density.fontScale) { TerminalPaints(context, font, density.density, density.fontScale) }
    val version: State<Long> = if (live) session.screenVersion.collectAsState() else remember { mutableLongStateOf(0L) }
    val emulator = session.emulator
    Canvas(modifier.semantics { contentDescription = "Frame of ${session.record.value.displayTitle}" }) {
        @Suppress("UNUSED_EXPRESSION") version.value
        val cols = emulator.cols.coerceAtLeast(1)
        val rows = emulator.rows.coerceAtLeast(1)
        val frameWidth = cols * paints.cellWidth
        val frameHeight = rows * paints.cellHeight
        val scale = size.width / frameWidth
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val saved = nc.save()
            nc.scale(scale, scale)
            TerminalRenderer.draw(
                nc = nc,
                emulator = emulator,
                paints = paints,
                theme = theme,
                boldAsBright = font.boldAsBright,
                width = frameWidth,
                height = maxOf(frameHeight, size.height / scale),
                showCursor = live,
                focused = true,
            )
            nc.restoreToCount(saved)
        }
    }
}
