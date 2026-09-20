package app.berth.android.screenshots

import android.os.Build
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.ViewChecker
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import com.google.common.collect.ImmutableSet
import org.robolectric.shadows.ShadowBuild
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The audit's findings, named. A failing [captureAudited] reports a rectangle (`View with bounds:
 * [95,2317][983,2399]`) and a check, which says nothing about which control it is; with
 * `BERTH_A11Y_DUMP` set, every capture also prints one `A11YDIAG` line per finding at the WARNING
 * level and above, before any exemption, with the Compose node under that rectangle: its test
 * tag, role, description, text, state and the text inside it. `grep A11YDIAG` on the test output
 * is the list to work from. Exemptions are not applied here, so a Deck key's width shows up too;
 * the test's own pass or fail is what counts.
 */
fun ComposeTestRule.dumpA11yFindings(label: String) {
    val roots = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
    val nodes = ArrayList<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        nodes.add(node)
        node.children.forEach(::walk)
    }
    roots.forEach(::walk)
    val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)
    // The framework skips its checks on Robolectric's fingerprint; Roborazzi's checker lifts it the same way.
    val fingerprint = Build.FINGERPRINT
    if (fingerprint == "robolectric") ShadowBuild.setFingerprint("roborazzi")
    try {
        for (root in roots) {
            val view = (root.root as ViewRootForTest).view
            val density = view.resources.displayMetrics.density
            val results = ViewChecker().apply { setObtainCharacterLocations(true) }
                .runChecksOnView(ImmutableSet.copyOf(checks), view, Parameters().apply { setSaveViewImages(false) })
            for (result in results) {
                if (result.type != AccessibilityCheckResultType.ERROR && result.type != AccessibilityCheckResultType.WARNING) continue
                val element = result.element ?: continue
                val bounds = element.boundsInScreen
                val node = nodes.minByOrNull { n ->
                    val b = n.boundsInWindow
                    abs(b.left - bounds.left) + abs(b.top - bounds.top) + abs(b.right - bounds.right) + abs(b.bottom - bounds.bottom)
                }
                val check = result.accessibilityHierarchyCheckResult?.sourceCheckClass?.simpleName ?: result.javaClass.simpleName
                val size = "${(bounds.width / density).roundToInt()}x${(bounds.height / density).roundToInt()}dp"
                val el = "res=${element.resourceName} cd=${element.contentDescription} text=${element.text} at=$bounds window=${element.window.boundsInScreen}"
                // The framework's own ancestry of the element, with bounds: a target's box is cut where an ancestor's ends.
                val above = ArrayList<String>()
                var parent: ViewHierarchyElement? = element.parentView
                while (parent != null && above.size < 4) {
                    val className: CharSequence? = parent.className
                    val description: CharSequence? = parent.contentDescription
                    val named: String = if (description == null) "" else "($description)"
                    above += "${className?.toString()?.substringAfterLast('.')}$named@${parent.boundsInScreen}"
                    parent = parent.parentView
                }
                println("A11YDIAG\t$label\t${result.type}\t$check\t$size\t$el\tabove=${above.joinToString(" < ")}\t${node?.let(::describe) ?: "no node"}")
            }
        }
    } finally {
        if (fingerprint == "robolectric") ShadowBuild.setFingerprint(fingerprint)
    }
}

private fun describe(node: SemanticsNode): String {
    val c = node.config
    val parts = ArrayList<String>()
    c.getOrNull(SemanticsProperties.TestTag)?.let { parts += "tag=$it" }
    c.getOrNull(SemanticsProperties.Role)?.let { parts += "role=$it" }
    c.getOrNull(SemanticsProperties.ContentDescription)?.let { parts += "cd=${it.joinToString("/")}" }
    c.getOrNull(SemanticsProperties.Text)?.let { parts += "text=${it.joinToString("/") { a -> a.text }.take(40)}" }
    c.getOrNull(SemanticsProperties.StateDescription)?.let { parts += "state=$it" }
    c.getOrNull(SemanticsProperties.ToggleableState)?.let { parts += "toggle=$it" }
    c.getOrNull(SemanticsProperties.Selected)?.let { parts += "selected=$it" }
    if (c.contains(SemanticsActions.OnClick)) parts += "click"
    if (c.contains(SemanticsActions.OnLongClick)) parts += "longClick"
    if (c.isMergingSemanticsOfDescendants) parts += "merge"
    if (c.isClearingSemantics) parts += "clear"
    val inside = ArrayList<String>()
    fun collect(n: SemanticsNode, depth: Int) {
        if (depth > 3) return
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { inside += it.text.take(20) }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { inside += "cd:" + it.take(20) }
        n.children.forEach { collect(it, depth + 1) }
    }
    node.children.forEach { collect(it, 1) }
    if (inside.isNotEmpty()) parts += "inside=${inside.take(4).joinToString(",")}"
    parts += "bounds=${node.boundsInWindow}"
    // The box accessibility measures: the layout grown to the minimum touch target where there is room.
    parts += "touch=${node.touchBoundsInRoot}"
    return "[" + parts.joinToString(" ") + "]"
}
