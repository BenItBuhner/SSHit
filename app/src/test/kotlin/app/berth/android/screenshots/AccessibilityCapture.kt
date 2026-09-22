package app.berth.android.screenshots

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import app.berth.android.ui.a11y.TouchTargetSize
import app.berth.android.ui.stage.DeckKeyTag
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import java.io.File
import kotlin.math.abs

/**
 * Every screenshot is also an accessibility audit. After the frame is written, the Accessibility
 * Test Framework's latest preset (speakable text on every control, 48 dp touch targets, text
 * contrast measured against the pixels just drawn, duplicate descriptions, traversal order) runs
 * over every Compose root on screen, so a sheet or a menu is audited along with the screen under
 * it, and a result at the ERROR level fails the test that took the picture. `BERTH_A11Y_LEVEL`
 * (`Warning`, `LogOnly`) moves the bar for a local run that wants the whole list. A few findings
 * are exempt by what they are, never by lowering the bar: a Deck key's width, a row cut at the
 * window's edge on its way in or out of a list or under a half-open sheet, a whole control cut by
 * the reach of a row the list beside it has scrolled past, the strip of scrim a tall sheet leaves
 * above itself, and the contrast of a disabled control's text.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun ComposeTestRule.captureAudited(file: File) {
    waitForIdle()
    file.parentFile?.mkdirs()
    captureScreenRoboImage(file.path)
    if (System.getenv("BERTH_A11Y_DUMP") != null) dumpA11yFindings(file.nameWithoutExtension)
    val minTarget = with(density) { TouchTargetSize.toPx() }
    val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
    for (index in 0 until roots) {
        val root = onAllNodes(isRoot())[index]
        val rootNode = root.fetchSemanticsNode()
        val sheet = rootNode.holdsDialog()
        val nodes = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().first { it.id == rootNode.id }.flatten()
        root.checkRoboAccessibility(roborazziATFAccessibilityCheckOptions = auditOptions(sheet, UnderAScrolledRowsReach(nodes, minTarget)))
    }
}

private fun SemanticsNode.holdsDialog(): Boolean =
    config.contains(SemanticsProperties.IsDialog) || children.any { it.holdsDialog() }

private fun SemanticsNode.flatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.flatten() }

/**
 * Lets [ms] of real time pass while the compose clock keeps ticking. The platform ripple a
 * `clickable` draws runs on the render thread's own clock, not the test's, and its sparkle is
 * seeded from that clock, so a frame taken within half a second of a click holds a ripple in a
 * state no two runs share; a capture that follows a click settles first.
 */
fun ComposeTestRule.settle(ms: Long) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        mainClock.advanceTimeBy(64)
        waitForIdle()
        Thread.sleep(16)
    }
}

@OptIn(ExperimentalRoborazziApi::class)
private val Level = RoborazziATFAccessibilityChecker.CheckLevel.valueOf(System.getenv("BERTH_A11Y_LEVEL") ?: "Error")

/**
 * The audit of one window. [reach] is built from that window's own semantics tree, since it reads
 * the controls' real boxes; the other exemptions read the framework's hierarchy alone. A sheet's
 * or a menu's window ([sheet]) takes the same audit, and neither a row the half-open sheet has not
 * yet shown nor the strip of scrim a tall sheet leaves above itself is a finding there.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun auditOptions(sheet: Boolean, reach: UnderAScrolledRowsReach) = RoborazziATFAccessibilityCheckOptions(
    checker = RoborazziATFAccessibilityChecker(
        preset = AccessibilityCheckPreset.LATEST,
        suppressions = if (sheet) {
            anyOf(DeckKeyTargets, ScrolledPastTheEdge, UnderTheHalfOpenSheet, SheetScrimSliver, reach, DisabledControlContrast)
        } else {
            anyOf(DeckKeyTargets, ScrolledPastTheEdge, reach, DisabledControlContrast)
        },
    ),
    failureLevel = Level,
)

/**
 * One exemption: the Deck's keys ([DeckKeyTag], published as their resource id) are a keyboard's
 * keys, seven or more to a row, 43 dp wide on a phone by the spec's 44 tall, and cannot each be a
 * 48 dp square; the framework's own scanner exempts a keyboard's keys the same way. Every other
 * check still runs on them, so a key with no name or no role still fails.
 */
private object DeckKeyTargets : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun describeTo(description: Description) {
        description.appendText("a touch-target finding on a Deck key")
    }

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean =
        result.accessibilityHierarchyCheck == TouchTargetSizeCheck::class.java && result.element?.resourceName == DeckKeyTag
}

/**
 * The second: a row scrolled partly out of a list. Compose reports a node's bounds clipped to the
 * window, so the last row of a sheet's list, cut at the screen's bottom edge, reads as a 9 dp
 * target with no text on it; the framework can see the clipping for a View but not for a Compose
 * node, and would otherwise fail every capture of a list longer than the screen. A finding is
 * exempt only when the node's bounds meet the window's edge and a scrollable ancestor holds it,
 * which is exactly a row on its way in or out; a small control resting inside the screen is not.
 */
private object ScrolledPastTheEdge : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun describeTo(description: Description) {
        description.appendText("a finding on a row scrolled partly out of the window")
    }

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (!result.isClippingFinding()) return false
        val element = result.element ?: return false
        var ancestor: ViewHierarchyElement? = element.parentView
        while (ancestor != null) {
            if (ancestor.isScrollable == true) return element.meetsWindowEdge()
            ancestor = ancestor.parentView
        }
        return false
    }
}

/**
 * The third: a whole control cut by the reach of a row the list beside it has scrolled past.
 * Compose measures a control's box for a reader as its target, the layout grown to 48 dp, and
 * where a clipping parent cuts a row it keeps the 24 dp of reach a finger gets around it, so a
 * row scrolled just past the top of a list still claims the 24 dp above the list's edge, and a
 * header control standing there is reported less that band: the Deck editor's Done and Undo at
 * the font cap, their 48 dp boxes read as 28 where the scrolled preview's keys reach up under
 * them. The finger's tap there is still the header's (a direct hit beats a hit in a neighbour's
 * reach). A finding is exempt only when the control's own target is full and whole in the
 * window, the framework's box is that target less one band, and every node reaching into the
 * band is a row of a list whose window lies clear of it, read from the window's own semantics
 * tree; a control a neighbour beside it really claims from, or one that is itself short, is not.
 */
private class UnderAScrolledRowsReach(private val nodes: List<SemanticsNode>, private val minTarget: Float) : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun describeTo(description: Description) {
        description.appendText("a touch-target finding on a whole control cut by a scrolled row's reach")
    }

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.accessibilityHierarchyCheck != TouchTargetSizeCheck::class.java) return false
        val element = result.element ?: return false
        val cut = element.boundsInScreen.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        // The control the framework measured: the smallest one that takes a tap and whose target is the finding's plus one band.
        val control = nodes
            .filter { it.config.contains(SemanticsActions.OnClick) && it.touchBoundsInRoot.isCutTo(cut) }
            .minByOrNull { it.touchBoundsInRoot.width * it.touchBoundsInRoot.height } ?: return false
        val target = control.touchBoundsInRoot
        if (target.width < minTarget - 1 || target.height < minTarget - 1) return false
        val shown = control.boundsInWindow
        if (abs(shown.width - control.size.width) > 1 || abs(shown.height - control.size.height) > 1) return false
        val band = target.less(cut)
        val related = HashSet<Int>()
        var up: SemanticsNode? = control
        while (up != null) { related += up.id; up = up.parent }
        control.flatten().forEach { related += it.id }
        val reaching = nodes.filter { it.id !in related && it.touchBoundsInRoot.overlaps(band) }
        if (reaching.isEmpty()) return false
        return reaching.all { row ->
            !row.boundsInWindow.overlaps(band) && row.scrollingAncestor()?.boundsInWindow?.overlaps(band) == false
        }
    }

    private fun SemanticsNode.scrollingAncestor(): SemanticsNode? {
        var up = parent
        while (up != null) {
            if (up.config.contains(SemanticsActions.ScrollBy)) return up
            up = up.parent
        }
        return null
    }

    /** This box is [cut] plus one band: three edges shared within a pixel, the fourth further out. */
    private fun Rect.isCutTo(cut: Rect): Boolean {
        val shared = listOf(abs(left - cut.left) <= 1, abs(top - cut.top) <= 1, abs(right - cut.right) <= 1, abs(bottom - cut.bottom) <= 1)
        if (shared.count { it } != 3) return false
        return left <= cut.left + 1 && top <= cut.top + 1 && right >= cut.right - 1 && bottom >= cut.bottom - 1
    }

    /** The band this box has and [cut] has not. */
    private fun Rect.less(cut: Rect): Rect = when {
        abs(bottom - cut.bottom) > 1 -> Rect(left, cut.bottom, right, bottom)
        abs(top - cut.top) > 1 -> Rect(left, top, right, cut.top)
        abs(right - cut.right) > 1 -> Rect(cut.right, top, right, bottom)
        else -> Rect(left, top, cut.left, bottom)
    }
}

/**
 * And in a sheet's window only: a list sheet opens at half height by the spec (the New tab sheet,
 * the Files sheets' longer lists; the Session sheet did until it opened whole, its content being
 * controls and not a list), and the row it cuts at the screen's bottom edge is one it has not
 * shown yet, not a short control. The sheet's handle carries Expand for a reader,
 * which is the route to the rest of the list. A sheet's content ends in padding, so nothing that
 * is whole ever touches that edge; a finding there is the cut alone.
 */
private object UnderTheHalfOpenSheet : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun describeTo(description: Description) {
        description.appendText("a finding on a row a half-open sheet has not shown yet")
    }

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (!result.isClippingFinding()) return false
        val element = result.element ?: return false
        return element.boundsInScreen.bottom >= element.window.boundsInScreen.bottom - 1
    }
}

/**
 * And in a sheet's window too: the scrim's sliver. M3's bottom sheet dismisses from its scrim, a
 * node the size of the window that Compose reports to a reader less whatever is drawn over it, so
 * above a sheet whose content stops a few dp short of the top edge it is a "Close sheet" target a
 * few dp tall (the known_hosts import with four keys listed leaves 3). That is Material's
 * geometry, not a control of the sheet's: the handle carries Dismiss for a reader, a swipe or the
 * back gesture for a finger, and every sheet ends in its own answer. Exempt only a touch-target
 * finding on the scrim itself, named by the string Compose gives it, where it meets the window's
 * top edge; a short control of ours resting anywhere else in the sheet is not.
 */
private object SheetScrimSliver : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    private val closeSheet: String by lazy {
        ApplicationProvider.getApplicationContext<Context>().getString(androidx.compose.ui.R.string.close_sheet)
    }

    override fun describeTo(description: Description) {
        description.appendText("a touch-target finding on the strip of scrim above a tall sheet")
    }

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.accessibilityHierarchyCheck != TouchTargetSizeCheck::class.java) return false
        val element = result.element ?: return false
        return element.contentDescription?.toString() == closeSheet && element.boundsInScreen.top <= element.window.boundsInScreen.top + 1
    }
}

/**
 * And the contrast of a disabled control's text. The Deck's keys on a Stage that is not connected
 * are disabled (`enabled = live`) and drawn at half alpha, so their `text.1` samples under the
 * 4.5:1 the check asks of read text; WCAG 1.4.3, which the check implements, exempts the text of
 * an inactive user interface component, and this exempts exactly that: a contrast finding on an
 * element that is disabled, or inside one. The check's level is untouched, so the same text on a
 * live Stage, or any read text anywhere else, still shows when it falls short.
 */
private object DisabledControlContrast : TypeSafeMatcher<AccessibilityViewCheckResult>() {
    override fun describeTo(description: Description) {
        description.appendText("a contrast finding on a disabled control's text")
    }

    override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
        if (result.accessibilityHierarchyCheck != TextContrastCheck::class.java) return false
        var element: ViewHierarchyElement? = result.element
        while (element != null) {
            if (element.isEnabled == false) return true
            element = element.parentView
        }
        return false
    }
}

/** The two checks a cut at the window's edge trips: the bounds shrink, and the label inside them is gone. */
private fun AccessibilityViewCheckResult.isClippingFinding(): Boolean =
    accessibilityHierarchyCheck == TouchTargetSizeCheck::class.java || accessibilityHierarchyCheck == SpeakableTextPresentCheck::class.java

private fun ViewHierarchyElement.meetsWindowEdge(): Boolean {
    val bounds = boundsInScreen
    val window = window.boundsInScreen
    return bounds.top <= window.top + 1 || bounds.bottom >= window.bottom - 1
}
