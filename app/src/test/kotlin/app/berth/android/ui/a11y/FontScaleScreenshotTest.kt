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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.diagnostics.BerthLog
import app.berth.android.diagnostics.CrashReporter
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.assertNoTextCut
import app.berth.android.screenshots.captureAudited
import app.berth.android.screenshots.textLayout
import app.berth.android.session.HostKeyChangedDecision
import app.berth.android.session.LinkFingerprint
import app.berth.android.session.Prompt
import app.berth.android.ui.diagnostics.CrashReportHost
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalFont
import app.berth.ssh.HostKeyRequest
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.TimeUnit

/**
 * Font scaling (spec A11): with the system's font size at its largest, 2×, interface text is set
 * at 1.3× and the terminal at 1×, and follows the system only when the font asks to. The Stage,
 * the settings and the hosts are captured at that size, with the audit's checks on each, and three
 * things that clipped at the cap are held: a Deck key's alternate hint stays clear of its label,
 * no text on the Settings screen is cut (a title ellipsized, a caption stopped at one line), and
 * the two tallest sheets, the changed-key sheet with a link's row and the crash sheet, scroll to
 * their buttons rather than measuring them to nothing.
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

    /**
     * The tallest of the prompt sheets at the cap: the changed-key sheet with a link's fingerprint as
     * its third row stands taller than the phone's window, so the sheet scrolls and its three answers
     * are laid out at a height and reached by scrolling, where an unscrolled column laid them out at
     * none. The host's name stays whole beside its endpoint, on the line under it when the two no
     * longer share one.
     */
    @Test
    fun `the changed-key sheet with a link row at 2x scrolls to its three answers, the host's name whole`() {
        StageFixture.seed(graph)
        val host = prodApi()
        val key = SshKeys.generate(KeyAlgorithm.ED25519).public
        val other = SshKeys.generate(KeyAlgorithm.ED25519).public
        val third = SshKeys.generate(KeyAlgorithm.ED25519).public
        val request = HostKeyRequest(
            host = host.address,
            port = host.port,
            keyType = "ssh-ed25519",
            publicKey = key,
            publicKeyBase64 = SshKeys.openSshPublic(key).split(" ")[1],
            fingerprintSha256 = SshKeys.fingerprintSha256(key),
        )
        val saved = KnownHostKey("k1", host.address, host.port, "ssh-ed25519", SshKeys.openSshPublic(other).split(" ")[1], SshKeys.fingerprintSha256(other), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }
        val asking = CoroutineScope(Dispatchers.IO).launch { graph.prompts.hostKeyChanged(host, request, saved, link = LinkFingerprint.of(SshKeys.fingerprintSha256(third), key, saved)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.HostKeyChanged }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("LINK   SHA256")).fetchSemanticsNodes().isNotEmpty() }
        assertTextWhole("prod-api")
        assertTextWhole("deploy@203.0.113.10")
        // As opened: the title, the host on two lines, the three fingerprints; then scrolled to the answers.
        capture("prompt-host-key-changed-font-scale-2x")
        assertSheetButtonsReachable("Disconnect", "Connect once without saving", "Replace saved key \u2014 hold to confirm")
        capture("prompt-host-key-changed-font-scale-2x-scrolled")
        (graph.prompts.current.value as Prompt.HostKeyChanged).decide(HostKeyChangedDecision.DISCONNECT)
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
        asking.cancel()
    }

    /** The crash sheet at the cap: the report's box, the paragraph under it and then Share, Copy and Keep for later, each at a height and the last reached by scrolling. */
    @Test
    fun `the crash sheet at 2x scrolls to Keep for later`() {
        StageFixture.seed(graph)
        crashPreviousRun()
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            CrashReportHost(graph.reports)
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth crashed last time")).fetchSemanticsNodes().isNotEmpty() }
        // The file is read off the main thread; the box holds it once it is here.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth crash report", substring = true)).fetchSemanticsNodes().size == 1 }
        capture("crash-sheet-font-scale-2x")
        assertSheetButtonsReachable("Share report", "Copy report", "Keep for later")
        capture("crash-sheet-font-scale-2x-scrolled")
    }

    /** The host the trust sheets are about in the captures: named, so its line carries name and endpoint both. */
    private fun prodApi() = Host(
        id = "prod-api",
        name = "prod-api",
        color = SwatchColor.COPPER,
        monogram = Host.monogramFor("prod-api"),
        address = "203.0.113.10",
        user = "deploy",
        auth = AuthMethod.Key("id-laptop"),
        lastConnectedAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(130),
        createdAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30),
    )

    /** The previous run's crash, through the handler's own path into this graph's store, re-read as the launch after would. */
    private fun crashPreviousRun() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        BerthLog.i("App", "process started")
        BerthLog.i("Session", "[homelab] detached \u2192 connecting")
        val previousRun = CrashReporter(graph.reportsDir, { CrashReporter.describeInstall(context) }, BerthLog.ring)
        previousRun.onCrash(Thread("main"), IllegalStateException("Frame 1 of 1 has no cells for row 24", ArrayIndexOutOfBoundsException("Index 24 out of bounds for length 24")))
        graph.reports.reload()
    }

    /**
     * Every button on the sheet is measured to a size, the named ones among them (B8: a sheet taller
     * than the window without a scroll measured its last rows to no height), and the last of them can
     * be scrolled into view, so it can be pressed however tall the sheet stands. The measured size is
     * what is read, not the bounds in the root, which a scroll clips to what it shows.
     */
    private fun assertSheetButtonsReachable(vararg labels: String) {
        val buttons = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        assertTrue("the sheet's buttons, ${labels.size} named, were on it: ${buttons.size} found", buttons.size >= labels.size)
        val flat = buttons.filter { it.size.height <= 0 || it.size.width <= 0 }
        assertTrue("buttons measured to no size: ${flat.map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }}", flat.isEmpty())
        for (label in labels) {
            val size = compose.onNodeWithText(label).fetchSemanticsNode().size
            assertTrue("'$label' is measured to no height: $size", size.height > 0 && size.width > 0)
        }
        compose.onNodeWithText(labels.last()).performScrollTo().assertIsDisplayed()
    }

    /** The text named [text] is on screen whole: its one line is not ellipsized. */
    private fun assertTextWhole(text: String) {
        val layout = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertTrue("'$text' has a layout", layout != null)
        assertFalse("'$text' is cut at the cap", layout!!.isLineEllipsized(layout.lineCount - 1))
    }
}
