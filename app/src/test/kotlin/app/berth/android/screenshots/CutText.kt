package app.berth.android.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertTrue

/**
 * Every text on screen whose layout cut it: more lines than it may show, a box too short for its
 * lines, or its last line ellipsized. A screen that scrolls as one column has every text composed
 * whatever is in view, so one call covers the whole of it. The width overflow flag is left out on
 * purpose: the layout a text hands back through semantics is rebuilt against the width it was
 * offered, so that flag is up for every text narrower than its room.
 */
fun ComposeTestRule.cutTexts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
    .fetchSemanticsNodes()
    .mapNotNull { node -> node.textLayout()?.takeIf { it.isCut() }?.layoutInput?.text?.text }

/**
 * No text on [where] is cut (A11): a caption that runs past its lines at 1× is cut at 1×, and one
 * that fits at 1× may still be cut at the interface's 1.3× cap, so a screen is held at both.
 */
fun ComposeTestRule.assertNoTextCut(where: String) {
    val cut = cutTexts()
    assertTrue("text cut on $where: $cut", cut.isEmpty())
}

private fun TextLayoutResult.isCut(): Boolean = didOverflowHeight || (lineCount > 0 && isLineEllipsized(lineCount - 1))

/** The layout a text node reports, or null for a node that is not a text. */
fun SemanticsNode.textLayout(): TextLayoutResult? {
    val results = ArrayList<TextLayoutResult>()
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    return if (action(results)) results.firstOrNull() else null
}
