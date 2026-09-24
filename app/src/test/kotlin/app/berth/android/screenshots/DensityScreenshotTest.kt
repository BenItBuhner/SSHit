package app.berth.android.screenshots

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.PaneSide
import app.berth.android.ui.AppRoot
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.stage.deckKeyHeight
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.DensityTokens
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Density
import app.berth.domain.model.Host
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Density (spec A12) on the surfaces its tokens reach, each at Comfortable and then Compact, at the
 * system's 1× and at 2×, where interface text stops at its 1.3× cap: the Interface editor, whose
 * Density control is the switch, Settings' panels and rows, the Hosts list, the host editor's
 * fields, and the Stage's chrome: its strip upright and on its side, the panes' headers on a
 * tablet, and the Deck. The interface theme comes from the view model as the shell's does, so the
 * pick in the editor is the density every screen draws at. Written to `build/outputs/roborazzi` in
 * pairs, `density-<surface>-comfortable` and `density-<surface>-compact`, with the phone's strip
 * under a status bar as a phone has one, and Compact's once more without.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DensityScreenshotTest(private val systemFontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "system font scale {0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf(1f), arrayOf(2f))
    }

    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val now = System.currentTimeMillis()
    private val suffix = if (systemFontScale > 1f) "-font-scale-2x" else ""

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        RuntimeEnvironment.setFontScale(systemFontScale)
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        seed()
    }

    @After
    fun tearDown() {
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name$suffix.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            val theme by graph.viewModel.interfaceTheme.collectAsState()
            BerthTheme(theme) { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    private fun setDensity(density: Density) {
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = density))
        awaitDensity(density)
    }

    private fun awaitDensity(density: Density) {
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == density }
        compose.waitForIdle()
    }

    private fun bounds(text: String): Rect = compose.onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot

    private fun Float.inDp(): Float = this / compose.density.density

    @Test
    fun `compact picked in the interface editor tightens its panels`() {
        themed { AppearanceScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        capture("density-appearance-comfortable")
        val comfortable = bounds("Variant")
        // Within a dp: the title's line and the row each round to whole pixels at 2.625 a dp.
        assertEquals("the preview's ribbon is Comfortable's 40", 40f, mockRibbonDp(), 1f)

        compose.onNodeWithText("Compact").performScrollTo().performClick()
        awaitDensity(Density.COMPACT)
        compose.onNodeWithContentDescription("Interface preview").performScrollTo()
        compose.waitForIdle()
        capture("density-appearance-compact")
        assertEquals("the Look panel's padding is 12 dp, not 16", 4f, (comfortable.left - bounds("Variant").left).inDp(), 0.5f)
        assertEquals("the preview's ribbon is Compact's 28, the Stage's own", 28f, mockRibbonDp(), 1f)
    }

    /** The ribbon of the editor's Stage in miniature, from the top of the preview to twice the centre of its title, which the row centres. */
    private fun mockRibbonDp(): Float {
        val preview = compose.onNodeWithContentDescription("Interface preview").fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("homelab") and hasAnyAncestor(hasContentDescription("Interface preview"))).fetchSemanticsNode().boundsInRoot
        return ((title.center.y - preview.top) * 2).inDp()
    }

    // ---- the Stage's chrome ------------------------------------------------------------------------

    /**
     * The Stage header (spec A12, ribbon 40 → 28; C3): under a phone's status bar the strip's row
     * steps from 40 to 28 with its tabs from 32 to 24, every title and chip label standing in a box
     * as tall as its line at the cap too, and a tab still answers a 48 dp target, the reach taking
     * the 12 dp the row gave up out of the inset. With no status bar to lend them the step would
     * come out of the targets or stand as empty band, so the skin's strip stands there whole: a
     * 40 dp row of 32 dp tabs with 20 dp swatches, Comfortable's own, and over it the 4 dp band of
     * the header's fill no inset lends it (C3 l.351), its targets 44.
     */
    @Test
    fun `stage header and strip`() {
        mountShell()
        statusBar(24)
        capture("density-stage-comfortable")
        assertEquals(40f, stripRowDp(), 1f)
        assertEquals("the strip lends its targets 8 dp of the status bar", 48f, stripHeightDp(), 0.5f)
        assertStripLinesWhole("the Comfortable strip")

        setDensity(Density.COMPACT)
        capture("density-stage-compact")
        assertEquals(28f, stripRowDp(), 1f)
        assertEquals("and 20 dp to the 28 dp row", 48f, stripHeightDp(), 0.5f)
        assertEquals("a tab is a 48 dp target on it", 48f, compose.onNode(hasContentDescription("homelab, detached", substring = true)).fetchSemanticsNode().size.height.toFloat().inDp(), 0.5f)
        assertEquals("its swatch Compact's 16", 16f, swatchDp(), 0.5f)
        assertStripLinesWhole("the Compact strip")

        statusBar(0)
        capture("density-stage-compact-no-status-bar")
        assertEquals("with no status bar the skin's 40 dp row stands", 40f, stripRowDp(), 1f)
        assertEquals("and the 4 dp band over it", 44f, stripHeightDp(), 0.5f)
        assertEquals("its swatch the skin's 20, in a 32 dp tab", 20f, swatchDp(), 0.5f)
        assertStripLinesWhole("the skin's strip under Compact")
    }

    /**
     * A phone on its side (spec C23): under its status bar the strip's own 32 steps to Compact's 28
     * as well, its lines whole. With none there is no inset to lend the short strip its reach, so the
     * skin's 40 dp row stands with the 4 dp band over it, its targets l.102's 44 as upright.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun `stage strip on a phone on its side`() {
        mountShell()
        statusBar(24)
        capture("density-stage-landscape-comfortable")
        assertEquals(32f, stripRowDp(), 1f)
        assertEquals("its targets reach 16 dp into the status bar", 48f, stripHeightDp(), 0.5f)

        setDensity(Density.COMPACT)
        capture("density-stage-landscape-compact")
        assertEquals(28f, stripRowDp(), 1f)
        assertEquals("and 20 dp from the 28 dp row, its own 16 and the 4 it gave up", 48f, stripHeightDp(), 0.5f)
        assertStripLinesWhole("the Compact strip on its side")

        statusBar(0)
        assertEquals("with no status bar the skin's 40 dp row stands", 40f, stripRowDp(), 1f)
        assertEquals("and the 4 dp band over it", 44f, stripHeightDp(), 0.5f)
    }

    /**
     * The panes' headers on a tablet (spec C23: a header of the strip's height over each pane). With
     * no status bar to lend the strip Compact's step, the skin's strip stands whole under Compact, a
     * 40 dp row of 32 dp tabs under the 4 dp band, and the panes' headers stay 40 with its row. Under a status bar the strip
     * steps to 28 and a pane's header with it, or to its title's line at the cap where that is
     * taller; never cut either way, and the audit of each frame holds the header's targets.
     */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-320dpi")
    fun `pane headers on a tablet`() {
        mountShell()
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("pi-hole, right pane")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("density-panes-comfortable")
        assertEquals(40f, paneHeaderDp(), 0.5f)

        setDensity(Density.COMPACT)
        capture("density-panes-compact")
        assertEquals("the skin's 40 dp row", 40f, stripRowDp(), 1f)
        assertEquals("and the 4 dp band over it", 44f, stripHeightDp(), 0.5f)
        assertEquals("its swatch the skin's 20", 20f, swatchDp(), 0.5f)
        assertEquals("the panes' headers the strip's 40", 40f, paneHeaderDp(), 0.5f)
        assertLinesWhole("the Compact panes' headers", hasContentDescription(" pane", substring = true))

        statusBar(24)
        capture("density-panes-compact-status-bar")
        assertEquals("under a status bar the strip steps to 28", 28f, stripRowDp(), 1f)
        // Body's 22 sp line at the capped scale, rounded up to a whole pixel as the text's box is.
        val line = kotlin.math.ceil(22f * minOf(systemFontScale, 1.3f) * compose.density.density) / compose.density.density
        assertEquals("and a pane's header with it, or its title's line where taller", maxOf(28f, line), paneHeaderDp(), 0.5f)
        assertLinesWhole("the stepped panes' headers", hasContentDescription(" pane", substring = true))
    }

    /**
     * The Deck (spec A12, Deck 44 → 40; lines 102 and 186, 40 × 44 as the target): its keys a step
     * down under Compact; each key's swipe hint clear of its label at either height; and no status
     * bar here, so the strip above it is the skin's 40. At every height the setting offers (A9, 40
     * to 52), Comfortable and Compact, the 4 dp gaps above and below a key's face are the key's to a
     * finger, so a tap anywhere across the row lands on a key, and the face is drawn as it was.
     */
    @Test
    fun `the deck`() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        val live = StageFixture.liveHomelab()
        themed { StageScreen(graph.viewModel, live, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}) }
        awaitDeck()
        capture("density-deck-comfortable")
        assertEquals(44f, ctrlFaceDp(), 0.5f)
        compose.assertDeckHintsClearOfLabels()

        setDensity(Density.COMPACT)
        capture("density-deck-compact")
        assertEquals(40f, ctrlFaceDp(), 0.5f)
        compose.assertDeckHintsClearOfLabels()
        assertEquals(40f, stripRowDp(), 1f)

        for (density in listOf(Density.COMFORTABLE, Density.COMPACT)) {
            setDensity(density)
            for (setting in listOf(40, 44, 48, 52)) {
                setKeyHeight(setting)
                assertGapsAreTheKeys("$density at $setting", deckKeyHeight(setting, DensityTokens.of(density)).value)
            }
        }
    }

    private fun setKeyHeight(setting: Int) {
        graph.viewModel.setDeckLayout(graph.viewModel.deckLayout.value.copy(heightDp = setting))
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.heightDp == setting }
        compose.waitForIdle()
    }

    /**
     * Ctrl's face is [face] dp and its touch the face and both gaps, a 48 dp target or more; a finger
     * 3 dp into the gap above the face, 1 dp under the row's top, latches it one-shot, and 3 dp into
     * the gap below it locked; the face's middle lets go.
     */
    private fun assertGapsAreTheKeys(where: String, face: Float) {
        assertEquals("$where: Ctrl's face", face, ctrlFaceDp(), 0.5f)
        val ctrl = compose.onNode(ctrlKey)
        assertEquals("$where: Ctrl's touch takes the gaps above and below its face", face + 8f, ctrlKeyDp(), 0.5f)
        assertTrue("$where: a 48 dp target", ctrl.fetchSemanticsNode().touchBoundsInRoot.height.inDp() >= 47.5f)
        val into = 3 * compose.density.density
        val halfFace = face * compose.density.density / 2
        ctrl.performTouchInput { down(Offset(centerX, centerY - halfFace - into)); up() }
        compose.waitForIdle()
        ctrl.assertContentDescriptionEquals("Ctrl, one-shot")
        ctrl.performTouchInput { down(Offset(centerX, centerY + halfFace + into)); up() }
        compose.waitForIdle()
        ctrl.assertContentDescriptionEquals("Ctrl, locked")
        ctrl.performTouchInput { down(center); up() }
        compose.waitForIdle()
        ctrl.assertContentDescriptionEquals("Ctrl")
    }

    /** The shell as it mounts, on the Stage fixture's three detached tabs with homelab on stage. */
    private fun mountShell() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        compose.setContent {
            composeView = LocalView.current
            AppRoot(graph.viewModel)
        }
        compose.waitUntil(10_000) { graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("homelab, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The compose view under test, for the insets Robolectric's window never sends it. */
    private var composeView: View? = null

    /** Gives the window a status bar [dp] tall, dispatched to the compose view as the window would. */
    private fun statusBar(dp: Int) {
        val view = checkNotNull(composeView) { "mountShell first" }
        val px = (dp * compose.density.density).roundToInt()
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0)).build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    /** The strip's whole height: its row and the reach above it. */
    private fun stripHeightDp(): Float = compose.onNode(onTheStrip).fetchSemanticsNode().size.height.toFloat().inDp()

    /**
     * The strip's row alone: the active tab's title centres on it, so the row is twice the title's
     * centre's height over the strip's foot. Within a dp: the line and the row each round to whole pixels.
     */
    private fun stripRowDp(): Float {
        val strip = compose.onNode(onTheStrip).fetchSemanticsNode()
        val title = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(onTheStrip), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first { it.textLayout()?.layoutInput?.text?.text == "homelab" }
        val foot = strip.positionInRoot.y + strip.size.height
        return ((foot - (title.positionInRoot.y + title.size.height / 2f)) * 2).inDp()
    }

    private val onTheStrip = hasContentDescription("Tabs, ", substring = true)

    /**
     * The focused pane's header: the × centres its target on the row, so the row is twice its
     * centre's depth into the pane. Read unclipped: the pane's corners clip the target's top, and
     * nothing its foot over the body.
     */
    private fun paneHeaderDp(): Float {
        val close = compose.onNode(hasContentDescription("Close pane")).fetchSemanticsNode()
        val pane = compose.onAllNodes(hasContentDescription(" pane", substring = true) and hasStateDescription("Focused")).onFirst().fetchSemanticsNode().boundsInRoot
        return ((close.positionInRoot.y + close.size.height / 2f - pane.top) * 2).inDp()
    }

    /** The active tab's swatch, across: the width its monogram is laid out in. */
    private fun swatchDp(): Float = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(onTheStrip), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { it.textLayout() }
        .first { it.layoutInput.text.text == "HO" }
        .layoutInput.constraints.maxWidth.toFloat().inDp()

    private val ctrlKey = hasTestTag(DeckKeyTag) and hasContentDescription("Ctrl", substring = true)

    private fun ctrlKeyDp(): Float = compose.onNode(ctrlKey).fetchSemanticsNode().size.height.toFloat().inDp()

    private fun ctrlFaceDp(): Float = compose.deckKeyFaceDp("Ctrl")

    private fun awaitDeck() {
        compose.waitUntil(10_000) { compose.onAllNodes(ctrlKey).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Composable
    private fun tabActions(): TabActions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }

    /**
     * The strip's titles and chip labels are whole ([assertLinesWhole]), and it has both to read;
     * and each monogram stands inside its swatch with at least a dp to spare either side, where at
     * the font cap two letters set for the 20 dp swatch would stand as wide as Compact's 16.
     */
    private fun assertStripLinesWhole(where: String) {
        val read = assertLinesWhole(where, onTheStrip)
        assertTrue("the strip's titles and chips read: $read", read.containsAll(listOf("homelab", "pi-hole", "HOME")))
        val monograms = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(onTheStrip), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.textLayout() }
            .filter { it.layoutInput.text.text in listOf("HO", "PH") }
        assertTrue("the strip's monograms read", monograms.size >= 2)
        val spare = 2 * compose.density.density
        val tight = monograms
            .filter { it.layoutInput.constraints.maxWidth - it.multiParagraph.maxIntrinsicWidth < spare }
            .map { "${it.layoutInput.text.text}, ${it.multiParagraph.maxIntrinsicWidth} px in ${it.layoutInput.constraints.maxWidth}" }
        assertTrue("a monogram meeting its swatch's edges on $where: $tight", tight.isEmpty())
    }

    /**
     * No text under [within] stands in a box shorter than its line, the cut that takes a descender's
     * foot. Width is not read: a title too wide for its tab ends in the strip's own ellipsis.
     * Returns what it read.
     */
    private fun assertLinesWhole(where: String, within: SemanticsMatcher): List<String> {
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(within), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { it.textLayout() }
        val short = texts.filter { it.size.height < it.multiParagraph.height }.map { "${it.layoutInput.text.text}, ${it.size.height} px of ${it.multiParagraph.height}" }
        assertTrue("a line taller than its box on $where: $short", short.isEmpty())
        return texts.map { it.layoutInput.text.text }
    }

    @Test
    fun `settings panels and rows`() {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Interface editor").performScrollTo()
        compose.waitForIdle()
        capture("density-settings-comfortable")
        val comfortable = bounds("System font")

        setDensity(Density.COMPACT)
        compose.onNodeWithText("Interface editor").performScrollTo()
        compose.waitForIdle()
        capture("density-settings-compact")
        val compact = bounds("System font")
        assertEquals("the panel's padding is 12 dp, not 16", 4f, (comfortable.left - compact.left).inDp(), 0.5f)
        // At least 8: the row is 8 dp wider as well, so its caption may take a line fewer.
        assertTrue("a captioned row loses its 8 dp of padding", (comfortable.height - compact.height).inDp() >= 7.5f)
    }

    /** Settings › Connection: its pickers are rows like the rest, so they take the same tokens. */
    @Test
    fun `settings connection rows`() {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Sessions keep running", substring = true).performScrollTo()
        compose.waitForIdle()
        capture("density-settings-connection-comfortable")
        val comfortable = bounds("Detach idle sessions")

        setDensity(Density.COMPACT)
        compose.onNodeWithText("Sessions keep running", substring = true).performScrollTo()
        compose.waitForIdle()
        capture("density-settings-connection-compact")
        val compact = bounds("Detach idle sessions")
        assertEquals("the panel's padding is 12 dp, not 16", 4f, (comfortable.left - compact.left).inDp(), 0.5f)
        assertTrue("the captioned row loses its 8 dp of padding", (comfortable.height - compact.height).inDp() >= 7.5f)
    }

    @Test
    fun `hosts list rows`() {
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("prod-api").fetchSemanticsNodes().isNotEmpty() }
        capture("density-hosts-comfortable")
        val comfortable = bounds("prod-api")

        setDensity(Density.COMPACT)
        capture("density-hosts-compact")
        assertEquals("a row with its address under the name is 8 dp shorter", 8f, (comfortable.height - bounds("prod-api").height).inDp(), 0.5f)
    }

    @Test
    fun `host editor fields`() {
        themed { HostEditorScreen(graph.viewModel, hostId = "prod-api", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("203.0.113.10").fetchSemanticsNodes().isNotEmpty() }
        capture("density-host-editor-comfortable")
        val address = bounds("203.0.113.10")
        val user = bounds("deploy")

        setDensity(Density.COMPACT)
        capture("density-host-editor-compact")
        val compactAddress = bounds("203.0.113.10")
        val compactUser = bounds("deploy")
        assertEquals("the Address field is 40 dp, not 44, so User stands 4 dp nearer", 4f, ((user.top - address.top) - (compactUser.top - compactAddress.top)).inDp(), 0.5f)
        assertEquals("inside the Connection panel's 12 dp", 4f, (address.left - compactAddress.left).inDp(), 0.5f)
    }

    private fun seed() = runBlocking {
        fun host(id: String, name: String, address: String, user: String, color: SwatchColor, lastConnectedAgoMinutes: Long?) = Host(
            id = id,
            name = name,
            color = color,
            monogram = Host.monogramFor(name),
            address = address,
            port = 22,
            user = user,
            auth = AuthMethod.AskEachTime,
            lastConnectedAt = lastConnectedAgoMinutes?.let { now - TimeUnit.MINUTES.toMillis(it) },
            createdAt = now - TimeUnit.DAYS.toMillis(30),
        )
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, 130))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, 18))
        graph.hosts.upsert(host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, 60 * 26))
        graph.hosts.upsert(host("build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, null))
    }
}
