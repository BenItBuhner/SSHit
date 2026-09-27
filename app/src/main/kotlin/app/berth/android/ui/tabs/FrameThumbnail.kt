package app.berth.android.ui.tabs

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.berth.android.session.TerminalSession
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalRenderer
import app.berth.android.ui.terminal.rememberTerminalPaints
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
 *
 * Every card at one font shares the Stage's cached [TerminalPaints], and each keeps its frame as a
 * bitmap ([ThumbnailFrame]) drawn again only when what it shows moved, so a draw of the whole
 * switcher redraws the cards whose frames changed and blits the rest.
 */
@Composable
fun FrameThumbnail(
    session: TerminalSession,
    theme: TerminalTheme,
    font: TerminalFont,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val paints = rememberTerminalPaints(font)
    val version: State<Long> = if (live) session.screenVersion.collectAsState() else remember { mutableLongStateOf(0L) }
    val frame = remember { ThumbnailFrame() }
    Canvas(modifier.semantics { contentDescription = "Frame of ${session.record.value.displayTitle}" }) {
        @Suppress("UNUSED_EXPRESSION") version.value
        val bitmap = frame.drawn(session, paints, theme, font.boldAsBright, live, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null) }
    }
}

/**
 * One card's frame as it was last drawn, and what it was drawn from: the screen's version, the
 * paints (font, density and scale), the theme, bold-as-bright, the cursor and the card's size. It
 * is drawn again when any of them moved and handed back as it stands otherwise. The version is
 * read before the screen is copied and bumps after every change to it, so a frame is never older
 * than the version it is kept under. One bitmap at the card's size in pixels, held while the card
 * is composed and let go with it.
 */
private class ThumbnailFrame {
    private val canvas = NativeCanvas()
    private var bitmap: Bitmap? = null
    private var session: TerminalSession? = null
    private var version = -1L
    private var paints: TerminalPaints? = null
    private var theme: TerminalTheme? = null
    private var boldAsBright = false
    private var cursor = false

    /** The frame for a card [width] by [height] pixels, drawn first if it is not the one kept; null for a card with no area. */
    fun drawn(session: TerminalSession, paints: TerminalPaints, theme: TerminalTheme, boldAsBright: Boolean, cursor: Boolean, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val version = session.screenVersion.value
        var kept = bitmap
        if (kept != null && kept.width == width && kept.height == height) {
            if (session === this.session && version == this.version && paints === this.paints && theme == this.theme &&
                boldAsBright == this.boldAsBright && cursor == this.cursor
            ) {
                return kept
            }
            kept.eraseColor(0)
        } else {
            kept = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
            bitmap = kept
            canvas.setBitmap(kept)
        }
        drawFrame(canvas, session, paints, theme, boldAsBright, cursor, width.toFloat(), height.toFloat())
        this.session = session
        this.version = version
        this.paints = paints
        this.theme = theme
        this.boldAsBright = boldAsBright
        this.cursor = cursor
        return kept
    }
}

/** Draws [session]'s screen onto [nc], a card [width] by [height] pixels: scaled to [THUMBNAIL_COLS] across and slid to its cursor row. */
private fun drawFrame(nc: NativeCanvas, session: TerminalSession, paints: TerminalPaints, theme: TerminalTheme, boldAsBright: Boolean, cursor: Boolean, width: Float, height: Float) {
    val emulator = session.emulator
    val cols = emulator.cols.coerceAtLeast(1)
    val rows = emulator.rows.coerceAtLeast(1)
    val frameWidth = cols * paints.cellWidth
    val frameHeight = rows * paints.cellHeight
    val scale = width / (minOf(cols, THUMBNAIL_COLS) * paints.cellWidth)
    val shownHeight = frameHeight * scale
    // Slide the frame up until the cursor row is the last one in the box, never past the frame's end.
    val shift = if (shownHeight > height) {
        val cursorBottom = (emulator.cursorY + 1) * paints.cellHeight * scale
        (cursorBottom - height).coerceIn(0f, shownHeight - height)
    } else 0f
    val saved = nc.save()
    nc.translate(0f, -shift)
    nc.scale(scale, scale)
    TerminalRenderer.draw(
        nc = nc,
        emulator = emulator,
        paints = paints,
        theme = theme,
        boldAsBright = boldAsBright,
        width = frameWidth,
        height = maxOf(frameHeight, (height + shift) / scale),
        showCursor = cursor,
        focused = true,
    )
    nc.restoreToCount(saved)
}

/** The most columns a thumbnail fits across the card; a wider screen is cropped at the right. */
private const val THUMBNAIL_COLS = 40
