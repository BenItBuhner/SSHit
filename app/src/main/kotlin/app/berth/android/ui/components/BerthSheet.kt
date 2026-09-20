package app.berth.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.berth.android.ui.layout.windowLayout
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius

/** The widest a sheet's content goes when it opens as a dialog (spec C23): a phone's width and a half. */
private val SheetDialogMaxWidth = 560.dp

/**
 * A Berth sheet (spec A, Sheet): radius 28 on `surface.1` with the handle, rising from the bottom
 * on a phone. On a window where a strip across the bottom would be absurd (spec C23, any width and
 * height past compact) the same content opens as a dialog: a panel of the sheet's radius and
 * surface, centred, at most [SheetDialogMaxWidth] wide, over the theme's scrim, with nothing else
 * changed for its content. On a phone the call is the bottom sheet, parameter for parameter, so
 * the phone's frames are what they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BerthSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Berth.colors
    if (windowLayout().dialogs) {
        SheetDialog(onDismiss = onDismiss, modifier = modifier, content = content)
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = modifier,
            sheetState = sheetState,
            containerColor = c.surface1,
            scrimColor = scrimColor,
            shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
            dragHandle = { SheetHandle() },
            content = content,
        )
    }
}

/**
 * The sheet as a dialog: its content in a panel at the sheet's radius on `surface.1`, centred and
 * clear of the keyboard, over the theme's scrim (the window's own dim is turned off, so the scrim
 * is one layer, as the bottom sheet's is). The handle's 20 dp zone stays as room above the content,
 * so a title sits where it sits in the sheet; the system insets are consumed at the panel, so
 * content that pads for a navigation bar under a sheet pads for nothing here.
 */
@Composable
private fun SheetDialog(onDismiss: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Berth.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.84f).dp
        Box(
            Modifier
                .fillMaxSize()
                .background(c.scrim)
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .semantics(mergeDescendants = false) {
                    contentDescription = "Close"
                    onClick { onDismiss(); true }
                }
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .widthIn(max = SheetDialogMaxWidth)
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(BerthRadius.sheet))
                    .background(c.surface1)
                    // A tap on the panel stays on the panel.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .consumeWindowInsets(WindowInsets.safeDrawing),
            ) {
                Spacer(Modifier.height(20.dp))
                content()
            }
        }
    }
}
