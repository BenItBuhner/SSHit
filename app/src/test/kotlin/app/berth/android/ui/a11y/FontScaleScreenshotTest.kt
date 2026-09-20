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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.captureAudited
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.settings.SettingsScreen
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
 * the settings and the hosts are captured at that size, with the audit's checks on each, and no
 * text on the Settings screen is cut at the cap (a title ellipsized, a caption stopped short).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class FontScaleScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

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
        var interfaceScale = 0f
        var systemScale = 0f
        var independent = 0f
        var following = 0f
        themed {
            interfaceScale = LocalDensity.current.fontScale
            systemScale = LocalSystemFontScale.current
            independent = terminalFontScale(TerminalFont())
            following = terminalFontScale(TerminalFont(followSystemScale = true))
            val tab by graph.viewModel.activeTab.collectAsState()
            val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
            StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("the system's scale, as set", 2f, systemScale)
        assertEquals("interface text stops at the cap", MAX_INTERFACE_FONT_SCALE, interfaceScale)
        assertEquals("the terminal's size is its own", 1f, independent)
        assertEquals("a font that follows takes the system's scale, uncapped", 2f, following)
        capture("stage-font-scale-2x")
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
        val cut = overflowingTexts()
        assertTrue("text cut at the interface's font cap: $cut", cut.isEmpty())
    }

    /**
     * Every text on screen whose layout cut it: more lines than it may show, a box too short for
     * its lines, or its last line ellipsized. The width overflow flag is left out on purpose: the
     * layout a text hands back through semantics is rebuilt against the width it was offered, so
     * that flag is up for every text narrower than its room.
     */
    private fun overflowingTexts(): List<String> = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.textLayout()?.takeIf { it.isCut() }?.layoutInput?.text?.text }

    private fun TextLayoutResult.isCut(): Boolean = didOverflowHeight || (lineCount > 0 && isLineEllipsized(lineCount - 1))

    private fun SemanticsNode.textLayout(): TextLayoutResult? {
        val results = ArrayList<TextLayoutResult>()
        val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        return if (action(results)) results.firstOrNull() else null
    }

    @Test
    fun `hosts at 2x`() {
        StageFixture.seed(graph)
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("homelab")).fetchSemanticsNodes().isNotEmpty() }
        capture("hosts-font-scale-2x")
    }
}
