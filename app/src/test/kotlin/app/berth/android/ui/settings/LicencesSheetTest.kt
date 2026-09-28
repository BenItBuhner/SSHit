package app.berth.android.ui.settings

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A shipped text in Settings › About › Licences. Its header stands while the text scrolls under
 * it, so the back action is in view at the end of sshj's Apache text and its NOTICE as at the
 * start, where it stood. The text is read off the main thread, and in every frame until it lands
 * the sheet stands at the list's height: it does not drop to the header and rise again with the
 * text (A9's content height, reached once).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class LicencesSheetTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val inSheet = hasAnyAncestor(isDialog())
    private val back = hasContentDescription("Back to Licences") and inSheet
    private val sshj = "sshj, the SSH transport"
    private val sshjText = "sshj - SSHv2 library for Java"

    private fun open() {
        compose.setContent { BerthTheme(InterfaceTheme.DEFAULT) { LicencesSheet(onDismiss = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun textShown() = compose.onAllNodes(hasText(sshjText, substring = true) and inSheet).fetchSemanticsNodes().isNotEmpty()

    /** The sheet's own height, handle and content, as it measures itself: the pane its surface names, whatever its offset is doing. */
    private fun sheetHeight(): Int {
        val panes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle) and inSheet).fetchSemanticsNodes()
        assertEquals("one sheet pane: ${panes.map { it.config.getOrNull(SemanticsProperties.PaneTitle) to it.size }}", 1, panes.size)
        return panes.single().size.height
    }

    /** Where [back] stands in the window, as much of it as the window shows. */
    private fun backShown(): Rect = compose.onNode(back).fetchSemanticsNode().boundsInWindow

    private fun assertBackWhole(where: String) {
        val node = compose.onNode(back).fetchSemanticsNode()
        val shown = node.boundsInWindow
        assertTrue("$where: the back action shows ${shown.height} of its ${node.size.height} px", shown.height >= node.size.height - 1 && shown.width >= node.size.width - 1)
    }

    @Test
    fun `a long licence's header stands while its text scrolls, the back action in view at the end`() {
        open()
        compose.onNode(hasText(sshj) and inSheet).performScrollTo().performClick()
        compose.waitUntil(5_000) { textShown() }
        compose.waitForIdle()
        assertBackWhole("the text opened")
        val before = backShown()

        val column = compose.onNode(hasScrollAction() and inSheet)
        column.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
        val range = column.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the text scrolled to its end: ${range.value()} of ${range.maxValue()}", range.maxValue() > 0f && range.value() == range.maxValue())
        assertTrue("the text's first paragraph has scrolled away", compose.onAllNodes(hasText(sshjText, substring = true) and inSheet).fetchSemanticsNodes().single().boundsInWindow.height < 1f)
        assertBackWhole("the text at its end")
        assertEquals("the back action stands where it stood", before, backShown())
    }

    @Test
    fun `while a licence's text loads the sheet holds the list's height, frame by frame`() {
        open()
        compose.onNode(hasText(sshj) and inSheet).performScrollTo()
        compose.waitForIdle()
        val list = sheetHeight()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNode(hasText(sshj) and inSheet).performClick()
            // The frames with the text's header up and its paragraphs not yet in: the ones it loads in.
            // The read is a worker's, on the wall clock, so no count of frames is sure to outlast it.
            val loading = ArrayList<Int>()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                compose.mainClock.advanceTimeByFrame()
                if (textShown()) break
                if (compose.onAllNodes(back).fetchSemanticsNodes().isNotEmpty()) loading += sheetHeight()
            }
            assertTrue("the text landed within 10 s", textShown())
            assertTrue("the header stood a frame or more before the text landed, so the sheet was read while it loaded", loading.isNotEmpty())
            assertTrue("the sheet stood the list's $list px in every frame the text loaded in: ${loading.size} frames at ${loading.distinct()} px", loading.all { it >= list - 1 })
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertTrue("and the text is up", textShown())
    }
}
