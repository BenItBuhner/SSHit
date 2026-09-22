package app.berth.android.screenshots

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue

/**
 * The sheet on screen stands as spec A9's Sheet rule has it: open at its content's height, the
 * window's at most, its column scrolling when the window is shorter. Every button in the sheet's
 * window is measured to a size (#15's B8: a sheet taller than the window with no scroll measured
 * its last rows to no height), and each of [labels], a button's text or a control's description,
 * is either whole in the window as the sheet opened or, the content being taller than the window,
 * the sheet stands at the window's top edge and the label is one scroll away. A sheet that opened
 * at half the window with its buttons below the fold (#15's B8, #19's blocker 4) passes neither
 * arm: its buttons are cut and its handle sits in the middle of the window.
 */
fun ComposeTestRule.assertSheetAtContentHeight(vararg labels: String) {
    val inSheet = hasAnyAncestor(isDialog())
    val buttons = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and inSheet).fetchSemanticsNodes()
    val flat = buttons.filter { it.size.height <= 0 || it.size.width <= 0 }
    assertTrue("buttons measured to no size: ${flat.map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }}", flat.isEmpty())
    val handle = onNode(hasContentDescription("Drag handle") and inSheet).fetchSemanticsNode()
    val handleTop = handle.boundsInWindow.top
    val atTheTop = handleTop <= handle.size.height
    for (label in labels) {
        val matcher = (hasText(label) or hasContentDescription(label, substring = true)) and inSheet
        val node = onNode(matcher).fetchSemanticsNode()
        assertTrue("'$label' is measured to no size: ${node.size}", node.size.height > 0 && node.size.width > 0)
        val shown = node.boundsInWindow
        if (shown.height >= node.size.height - 1 && shown.width >= node.size.width - 1) continue
        assertTrue(
            "'$label' is cut by the window (${shown.height.toInt()} of ${node.size.height} px tall shown) on a sheet that is not at the window's top: its handle is ${handleTop.toInt()} px down",
            atTheTop,
        )
        onNode(matcher).performScrollTo().assertIsDisplayed()
    }
}
