package app.berth.android.ui.a11y

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.assertNoTextCut
import app.berth.android.screenshots.captureAudited
import app.berth.android.screenshots.textLayout
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalFont
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Font scaling (spec A11): with the system's font size at its largest, 2×, interface text is set
 * at 1.3× and the terminal at 1×, and follows the system only when the font asks to. The Stage,
 * the settings and the hosts are captured at that size, with the audit's checks on each, and two
 * things that clipped at the cap are held: a Deck key's alternate hint stays clear of its label,
 * and no text on the Settings screen is cut (a title ellipsized, a caption stopped at one line).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class FontScaleScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        // The system's largest font size, set before the first composition reads the configuration.
        RuntimeEnvironment.setFontScale(2f)
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Test
    fun `stage at 2x, interface text at 1,3x and the terminal at 1x unless it follows`() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        // Homelab's tab reading Live, so the Stage stands its Deck under the terminal.
        val live = StageFixture.liveHomelab()
        var interfaceScale = 0f
        var systemScale = 0f
        var independent = 0f
        var following = 0f
        themed {
            interfaceScale = LocalDensity.current.fontScale
            systemScale = LocalSystemFontScale.current
            independent = terminalFontScale(TerminalFont())
            following = terminalFontScale(TerminalFont(followSystemScale = true))
            val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
            StageScreen(graph.viewModel, live, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("the system's scale, as set", 2f, systemScale)
        assertEquals("interface text stops at the cap", MAX_INTERFACE_FONT_SCALE, interfaceScale)
        assertEquals("the terminal's size is its own", 1f, independent)
        assertEquals("a font that follows takes the system's scale, uncapped", 2f, following)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes().isNotEmpty() }
        capture("stage-font-scale-2x")
        assertDeckHintsClearOfLabels()
    }

    @Test
    fun `settings at 2x, the terminal's Follow row among them`() {
        StageFixture.seed(graph)
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Follow system text size").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Follow system text size").performClick()
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.followSystemScale }
        capture("settings-font-scale-2x")
        compose.assertNoTextCut("the Settings screen at the interface's font cap")
    }

    /**
     * A Deck key's texts are sized from the key, not the system, so its swipe alternate at the top
     * right never runs into the label under it: the hint's baseline stays above the label's tallest
     * ink by at least a dp, at this scale as at 1×. The label's ink top is taken as 0.76 of its font
     * size above its baseline, an ascender's height in Inter, Roboto and JetBrains Mono (a capital
     * stops lower, at about 0.73). Each text's pixels come from the density its layout was made
     * with, the interface's at its cap, not the system's.
     */
    private fun assertDeckHintsClearOfLabels() {
        val keys = compose.onAllNodes(hasTestTag(DeckKeyTag), useUnmergedTree = true).fetchSemanticsNodes()
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

    private fun SemanticsNode.textDescendants(): List<SemanticsNode> = buildList {
        for (child in children) {
            if (child.config.getOrNull(SemanticsProperties.Text) != null) add(child)
            addAll(child.textDescendants())
        }
    }

    @Test
    fun `hosts at 2x`() {
        StageFixture.seed(graph)
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("homelab")).fetchSemanticsNodes().isNotEmpty() }
        capture("hosts-font-scale-2x")
    }
}
