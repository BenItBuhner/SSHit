package app.berth.android.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import app.berth.android.ui.stage.DeckKeyTag
import org.junit.Assert.assertTrue

/**
 * Every text on screen whose layout cut it: more lines than it may show, a box too short for its
 * lines, or its last line ellipsized. A screen that scrolls as one column has every text composed
 * whatever is in view, so one call covers the whole of it. The width overflow flag is left out on
 * purpose: the layout a text hands back through semantics is rebuilt against the width it was
 * offered, so that flag is up for every text narrower than its room. With [within] the texts are
 * those under a node it matches, a menu's popup or a sheet's dialog, so a menu is held apart from
 * the screen under it, whose tab titles the strip cuts on purpose.
 */
fun ComposeTestRule.cutTexts(within: SemanticsMatcher? = null): List<String> {
    val texts = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
    return onAllNodes(if (within == null) texts else texts and hasAnyAncestor(within), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.textLayout()?.takeIf { it.isCut() }?.layoutInput?.text?.text }
}

/**
 * No text on [where] is cut (A11): a caption that runs past its lines at 1× is cut at 1×, and one
 * that fits at 1× may still be cut at the interface's 1.3× cap, so a screen is held at both.
 * [within] narrows the texts to those under a node it matches, as [cutTexts] does.
 */
fun ComposeTestRule.assertNoTextCut(where: String, within: SemanticsMatcher? = null) {
    val cut = cutTexts(within)
    assertTrue("text cut on $where: $cut", cut.isEmpty())
}

private fun TextLayoutResult.isCut(): Boolean = didOverflowHeight || (lineCount > 0 && isLineEllipsized(lineCount - 1))

/**
 * Every text whose layout broke a line inside a word: a line that ends on a letter or a digit with
 * the rest of the word on the next, which the layout does when a word alone is wider than its room.
 * Neither an overflow nor an ellipsis, so [cutTexts] passes it, as it passed "Run / ning" beside a
 * value that had taken the row (#20 review, nit 11). A break after a space or a hyphen is a wrap.
 */
fun ComposeTestRule.brokenWords(within: SemanticsMatcher? = null): List<String> {
    val texts = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
    return onAllNodes(if (within == null) texts else texts and hasAnyAncestor(within), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.textLayout()?.takeIf { it.breaksAWord() }?.layoutInput?.text?.text }
}

/** No text on [where] breaks a line inside a word, as [brokenWords] reads it; [within] as [cutTexts]'s. */
fun ComposeTestRule.assertNoBrokenWords(where: String, within: SemanticsMatcher? = null) {
    val broken = brokenWords(within)
    assertTrue("a word broken across lines on $where: $broken", broken.isEmpty())
}

private fun TextLayoutResult.breaksAWord(): Boolean {
    val text = layoutInput.text.text
    for (line in 0 until lineCount - 1) {
        val end = getLineEnd(line)
        if (end <= 0 || end >= text.length) continue
        if (text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return true
    }
    return false
}

/**
 * A Deck key's texts are sized from the key, not the system, so its swipe alternate at the top
 * right never runs into the label under it: the hint's baseline stays above the label's tallest
 * ink by at least a dp, at the interface's font cap as at 1×, and on a 40 dp key (Compact's, or
 * the setting's least) as on a 44. The label's ink top is taken as 0.76 of its font size above its
 * baseline, an ascender's height in Inter, Roboto and JetBrains Mono (a capital stops lower, at
 * about 0.73). Each text's pixels come from the density its layout was made with, the interface's
 * at its cap, not the system's.
 */
fun ComposeTestRule.assertDeckHintsClearOfLabels() {
    val keys = onAllNodes(hasTestTag(DeckKeyTag), useUnmergedTree = true).fetchSemanticsNodes()
    var checked = 0
    for (key in keys) {
        val texts = key.textDescendants().mapNotNull { node -> node.textLayout()?.let { node to it } }
        if (texts.size < 2) continue
        val (hintNode, hint) = texts.minBy { it.first.boundsInRoot.top }
        val (labelNode, label) = texts.maxBy { it.first.boundsInRoot.top }
        val hintBaseline = hintNode.boundsInRoot.top + hint.lastBaseline
        val labelFontPx = with(label.layoutInput.density) { label.layoutInput.style.fontSize.toPx() }
        val labelInkTop = labelNode.boundsInRoot.top + label.firstBaseline - 0.76f * labelFontPx
        val gapDp = (labelInkTop - hintBaseline) / label.layoutInput.density.density
        assertTrue(
            "'${hint.layoutInput.text}' over '${label.layoutInput.text}': the hint's baseline is ${"%.1f".format(gapDp)} dp above the label's ink, less than the 1 dp it keeps",
            gapDp >= 1f,
        )
        checked++
    }
    assertTrue("keys with a hint over a label were on the Deck", checked > 0)
}

/**
 * The face of the Deck key labelled [label], in dp: its label is laid out in the whole of it. The
 * key's node is its touch, the face and the gaps above and below it.
 */
fun ComposeTestRule.deckKeyFaceDp(label: String): Float {
    val layout = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag(DeckKeyTag)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { it.textLayout() }
        .first { it.layoutInput.text.text == label }
    return layout.layoutInput.constraints.maxHeight / layout.layoutInput.density.density
}

private fun SemanticsNode.textDescendants(): List<SemanticsNode> = buildList {
    for (child in children) {
        if (child.config.getOrNull(SemanticsProperties.Text) != null) add(child)
        addAll(child.textDescendants())
    }
}

/** The layout a text node reports, or null for a node that is not a text. */
fun SemanticsNode.textLayout(): TextLayoutResult? {
    val results = ArrayList<TextLayoutResult>()
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    return if (action(results)) results.firstOrNull() else null
}
