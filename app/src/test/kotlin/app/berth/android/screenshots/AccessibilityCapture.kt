package app.berth.android.screenshots

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import app.berth.android.ui.stage.DeckKeyTag
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import java.io.File

/**
 * Every screenshot is also an accessibility audit. After the frame is written, the Accessibility
 * Test Framework's latest preset (speakable text on every control, 48 dp touch targets, text
 * contrast measured against the pixels just drawn, duplicate descriptions, traversal order) runs
 * over every Compose root on screen, so a sheet or a menu is audited along with the screen under
 * it, and a result at the ERROR level fails the test that took the picture. `BERTH_A11Y_LEVEL`
 * (`Warning`, `LogOnly`) moves the bar for a local run that wants the whole list.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun ComposeTestRule.captureAudited(file: File) {
    waitForIdle()
    file.parentFile?.mkdirs()
    captureScreenRoboImage(file.path)
    if (System.getenv("BERTH_A11Y_DUMP") != null) dumpA11yFindings(file.nameWithoutExtension)
    val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
    for (index in 0 until roots) {
        val root = onAllNodes(isRoot())[index]
        val sheet = root.fetchSemanticsNode().holdsDialog()
        root.checkRoboAccessibility(roborazziATFAccessibilityCheckOptions = if (sheet) SheetAuditOptions else AuditOptions)
    }
}

private fun SemanticsNode.holdsDialog(): Boolean =
    config.contains(SemanticsProperties.IsDialog) || children.any { it.holdsDialog() }

@OptIn(ExperimentalRoborazziApi::class)
private val Level = RoborazziATFAccessibilityChecker.CheckLevel.valueOf(System.getenv("BERTH_A11Y_LEVEL") ?: "Error")

@OptIn(ExperimentalRoborazziApi::class)
private val AuditOptions = RoborazziATFAccessibilityCheckOptions(
    checker = RoborazziATFAccessibilityChecker(preset = AccessibilityCheckPreset.LATEST, suppressions = anyOf(DeckKeyTargets, ScrolledPastTheEdge)),
    failureLevel = Level,
)

/** A sheet's or a menu's window: the same audit, and a row the half-open sheet has not yet shown is not a finding. */
@OptIn(ExperimentalRoborazziApi::class)
private val SheetAuditOptions = RoborazziATFAccessibilityCheckOptions(
    checker = RoborazziATFAccessibilityChecker(preset = AccessibilityCheckPreset.LATEST, suppressions = anyOf(DeckKeyTargets, ScrolledPastTheEdge, UnderTheHalfOpenSheet)),
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
 * The other: a row scrolled partly out of a list. Compose reports a node's bounds clipped to the
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
 * And in a sheet's window only: a list sheet opens at half height by the spec (the New tab sheet,
 * the Session sheet: "drag up for the full list"), and the row it cuts at the screen's bottom edge
 * is one it has not shown yet, not a short control. The sheet's handle carries Expand for a reader,
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

/** The two checks a cut at the window's edge trips: the bounds shrink, and the label inside them is gone. */
private fun AccessibilityViewCheckResult.isClippingFinding(): Boolean =
    accessibilityHierarchyCheck == TouchTargetSizeCheck::class.java || accessibilityHierarchyCheck == SpeakableTextPresentCheck::class.java

private fun ViewHierarchyElement.meetsWindowEdge(): Boolean {
    val bounds = boundsInScreen
    val window = window.boundsInScreen
    return bounds.top <= window.top + 1 || bounds.bottom >= window.bottom - 1
}
