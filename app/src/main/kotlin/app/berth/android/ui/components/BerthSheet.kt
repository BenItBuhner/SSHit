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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import app.berth.android.ui.a11y.CappedFontScale
import app.berth.android.ui.layout.windowLayout
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius

/** The widest a sheet's content goes when it opens as a dialog (spec C23): a phone's width and a half. */
val SheetDialogMaxWidth = 560.dp

/** How a [BerthSheet] is on screen: rising from the bottom edge, or as a panel in the middle of a large window. */
enum class SheetPresentation { SHEET, DIALOG }

/** The presentation of the [BerthSheet] this content is in; [SheetPresentation.SHEET] outside one. */
val LocalSheetPresentation = compositionLocalOf { SheetPresentation.SHEET }

/**
 * Whether Berth's windows block screenshots, recording and casting right now, from the same
 * state `WindowSecurity` applies to the Activity's window. A sheet as a dialog is a window of its
 * own, which the Activity's `FLAG_SECURE` does not reach, so it takes the flag from here; the shell
 * provides it, and where nothing does (previews, tests) the dialog inherits its parent window's.
 */
val LocalWindowSecure = compositionLocalOf { false }

/**
 * Whether the sheet's content should fill the sheet's height. As a bottom sheet a list that fills
 * the height is the design (spec A: a search field over a list, room for the keyboard); as a dialog
 * the panel wraps what it holds up to its cap and scrolls past it, so six rows are six rows and not
 * a panel that is mostly empty.
 */
@Composable
fun sheetFillsHeight(): Boolean = LocalSheetPresentation.current == SheetPresentation.SHEET

/**
 * A Berth sheet (spec A, Sheet): radius 28 on `surface.1` with the handle, rising from the bottom
 * on a phone. On a window where a strip across the bottom would be absurd (spec C23, any width and
 * height past compact) the same content opens as a dialog: a panel of the sheet's radius and
 * surface, centred, at most [dialogMaxWidth] wide, over the theme's scrim, with nothing else
 * changed for its content but what it asks through [sheetFillsHeight]. On a phone the call is the
 * bottom sheet, parameter for parameter, so the phone's frames are what they were. A sheet that is
 * about what is under it (the Look sheet, spec C6, over the Stage it previews) asks for no scrim in
 * either form, through [scrimColor] and [dialogScrimColor]; the two default to each form's own.
 *
 * Either way the sheet is a window of its own, whose Compose view provides the density afresh from
 * its Context; the theme's interface cap (A11, 1.3×) is applied again inside it through
 * [CappedFontScale], so a sheet's text at the system's larger sizes stops where the screen's does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BerthSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dialogMaxWidth: Dp = SheetDialogMaxWidth,
    dialogScrimColor: Color = Berth.colors.scrim,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Berth.colors
    if (windowLayout().dialogs) {
        CompositionLocalProvider(LocalSheetPresentation provides SheetPresentation.DIALOG) {
            SheetDialog(onDismiss = onDismiss, modifier = modifier, maxWidth = dialogMaxWidth, scrim = dialogScrimColor, content = content)
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = modifier,
            sheetState = sheetState,
            containerColor = c.surface1,
            scrimColor = scrimColor,
            shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
            dragHandle = { SheetHandle() },
        ) {
            CappedFontScale { content() }
        }
    }
}

/**
 * The sheet as a dialog: its content in a panel at the sheet's radius on `surface.1`, centred and
 * clear of the keyboard, over the theme's scrim (the window's own dim is turned off, so the scrim
 * is one layer, as the bottom sheet's is). The handle's 20 dp zone stays as room above the content,
 * so a title sits where it sits in the sheet; the system insets are consumed at the panel, so
 * content that pads for a navigation bar under a sheet pads for nothing here. The dialog is a
 * second window, so it carries `FLAG_SECURE` itself while [LocalWindowSecure] says Berth's windows
 * are secure: a trust sheet's fingerprint or a host list must not cast from a tablet when it would
 * not from a phone. Otherwise it inherits the window under it, as every Compose dialog does.
 */
@Composable
private fun SheetDialog(onDismiss: () -> Unit, modifier: Modifier, maxWidth: Dp, scrim: Color, content: @Composable ColumnScope.() -> Unit) {
    val c = Berth.colors
    val secure = LocalWindowSecure.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.84f).dp
        CappedFontScale {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrim)
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
                        .widthIn(max = maxWidth)
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
}
