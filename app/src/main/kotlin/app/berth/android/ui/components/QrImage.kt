package app.berth.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.berth.android.qr.QrCode
import kotlin.math.floor

/** The light a QR code is printed on: warm paper, not the theme's surface, since a scanner wants dark on light. */
val QrPaper = Color(0xFFF4F1EC)

/** The ink of its modules. */
val QrInk = Color(0xFF17140F)

/**
 * [code] drawn to fill a square, dark modules on [QrPaper] with the four-module quiet zone the
 * standard asks for. Modules are whole pixels, the code centred in what is left, so no module
 * edge is blurred across two pixels for a camera to misread. One node to a reader, described as
 * what the code holds ([contentDescription]) rather than as a picture.
 */
@Composable
fun QrImage(code: QrCode, contentDescription: String, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        drawRect(QrPaper)
        val quiet = 4
        val cells = code.size + 2 * quiet
        val cell = floor(size.minDimension / cells)
        if (cell < 1f) return@Canvas
        val start = Offset((size.width - cell * cells) / 2f + cell * quiet, (size.height - cell * cells) / 2f + cell * quiet)
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code[x, y]) drawRect(QrInk, Offset(start.x + x * cell, start.y + y * cell), Size(cell, cell))
            }
        }
    }
}
