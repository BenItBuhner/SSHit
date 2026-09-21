package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.AuthResolver
import app.berth.android.session.ManagedTab
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.LocalDeckFit
import app.berth.android.ui.stage.SessionSheet
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.stage.TwoRowDeckFit
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckSettings
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** A Pixel-class phone upright, the size every other screenshot class renders at. */
private const val PHONE_PORTRAIT = "w411dp-h914dp-420dpi"

/** A Pixel Fold turned tall: a medium width, where the drawer stands as the 72 dp column. */
private const val FOLD_PORTRAIT = "w701dp-h841dp-port-420dpi"

/** A 10-inch tablet upright: medium in width too, and tall. */
private const val TABLET_PORTRAIT = "w800dp-h1280dp-port-320dpi"

/** The same tablet on its side: the room for the Deck's second row. */
private const val TABLET_LANDSCAPE = "w1280dp-h800dp-land-320dpi"

/**
 * The Stage's chrome past the phone's one row (wave three, slice 2): the drawer as the 72 dp
 * column of swatches and glyphs on a medium width (spec C7, A12), the Deck of two rows on a tablet
 * (spec C4), the Deck's gestures on a phone (spec D2: the tertiary by a swipe down with its glyph at
 * the key's foot, the alternates as a popover on a hold, the layer by a swipe across, the grip's
 * drag up), the Session sheet's Look row over the Stage it previews (spec C6), and the two rows in
 * Settings that turn the gestures and the second row. Through Robolectric's native graphics into
 * `build/outputs/roborazzi`. The gestures that send are driven against the local sshd when the
 * `SSH_TEST_*` variables are set, so what the terminal shows after each is what the finger meant.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = PHONE_PORTRAIT)
class DeckRailScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    // ---- the medium rail (spec C7, A12) --------------------------------------------------------------

    /**
     * The fold turned tall: the drawer stands as the 72 dp column, the groups as their swatches with
     * the current one on its tonal step, then New group and the library as glyphs, and each does
     * what its row does: a swatch's tap makes the group current, its long-press opens the group's
     * menu, and a glyph opens the screen it names with the column still standing beside it.
     */
    @Test
    @Config(qualifiers = FOLD_PORTRAIT)
    fun `the fold turned tall stands the drawer as the 72 dp column, whose swatches and glyphs do what the rows do`() {
        mountApp()
        theNarrowRail()
        compose.onNodeWithContentDescription("Home, 2 tabs").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Work, 1 tab").assertIsDisplayed().assertIsNotSelected()
        capture("medium-rail-fold-portrait")

        // A swatch's tap is the row's: the group becomes current, and the step moves to its swatch.
        compose.onNodeWithContentDescription("Work, 1 tab").performClick()
        compose.waitUntil(5_000) { graph.viewModel.currentWorkspaceId.value == "ws-work" }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Work, 1 tab").assertIsSelected()
        compose.onNodeWithContentDescription("Home, 2 tabs").assertIsNotSelected()
        capture("medium-rail-fold-portrait-work")

        // A long-press is the row's menu, the same six rows the chip's has.
        compose.onNodeWithContentDescription("Work, 1 tab").performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("New tab here")
        compose.onNodeWithText("Delete group").assertIsDisplayed()
        capture("medium-rail-group-menu")
        compose.onNodeWithText("Collapse").performClick()
        compose.waitUntil(5_000) { graph.workspaces.items.value.first { it.id == "ws-work" }.collapsed }
        waitForNoText("New tab here")

        // A glyph opens the screen it names; the column stays, since there is nothing to close.
        compose.onNodeWithContentDescription("Hosts").performClick()
        waitForText("Hosts")
        compose.onNodeWithText("build box").assertIsDisplayed()
        theNarrowRail()
        capture("medium-rail-hosts")
    }

    /** The tablet upright is medium in width too: the same column, with a taller run of the height to itself. */
    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun `the tablet upright stands the 72 dp column too`() {
        mountApp()
        theNarrowRail()
        compose.onNodeWithContentDescription("Home, 2 tabs").assertIsSelected()
        capture("medium-rail-tablet-portrait")
    }

    // ---- the Deck of two rows (spec C4; #14 review, nit 6; #15 review, nit 8) ---------------------------

    /**
     * The tablet on its side, the shell's fit for it in force: the Deck of two rows, the saved layer
     * over Nav/Fn, each row with its own layer key and its own swipe across. Its keys stop at 120 dp
     * on the wide row and the run sits centred (#15 review, nit 8), where the phone's seven divide
     * the row between them.
     */
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun `the tablet's Deck has two rows, each with its own layer, and keys that stop at 120 dp`() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        val live = StageFixture.liveHomelab()
        themed { CompositionLocalProvider(LocalDeckFit provides TwoRowDeckFit) { Stage(live) } }
        awaitDeck()
        compose.onAllNodes(hasContentDescription("Layer")).assertCountEquals(2)
        compose.onNode(hasContentDescription("Layer") and hasStateDescription("Base")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Layer") and hasStateDescription("Nav/Fn")).assertIsDisplayed()
        val ctrl = key("Ctrl").fetchSemanticsNode().boundsInRoot
        val home = key("Home").fetchSemanticsNode().boundsInRoot
        assertEquals("a key on the wide row stops at 120 dp", 120f, ctrl.width / compose.density.density, 0.5f)
        assertEquals(120f, home.width / compose.density.density, 0.5f)
        assertTrue("the second row is under the first", home.top >= ctrl.bottom)
        // Centred: the first key stands well in from the grip, not against it.
        val esc = key("Esc").fetchSemanticsNode().boundsInRoot
        val grip = compose.onNode(hasContentDescription("Grip", substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue("the run is centred in the row", esc.left - grip.right > 40.dp.px())
        capture("two-row-deck-tablet")

        // A swipe across the second row steps its layer alone (spec C4: both rows swipe independently).
        key("Home").performTouchInput { down(center); moveBy(Offset(60.dp.px(), 0f)); up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Layer") and hasStateDescription("tmux")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Layer") and hasStateDescription("Base")).assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Layer") and hasStateDescription("Nav/Fn")).assertCountEquals(0)
    }

    // ---- the Deck's hand on a phone (spec D2) --------------------------------------------------------

    /**
     * The gestures a key takes, offline: the tertiary's glyph stands at the key's foot once swipe down
     * is on, a swipe down previews it on the key, a hold raises the alternates as a popover whose chip
     * under the finger fills accent, a swipe across steps the layer, and the grip's drag up asks for
     * the Session sheet at its full height where its tap asks for the half one.
     */
    @Test
    fun `a key's swipe down, hold and swipe across, and the grip's drag up, on a phone`() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        graph.settings.deckGestures.value = DeckSettings(swipeDown = true)
        runBlocking { graph.settings.setDeckLayout(layoutWithTertiaries()) }
        val live = StageFixture.liveHomelab()
        var taps = 0
        var dragsUp = 0
        themed {
            StageScreen(graph.viewModel, live, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = { taps++ }, onEditHost = {}, onOpenSessionSheetExpanded = { dragsUp++ })
        }
        awaitDeck()
        // The glyphs: the swipe-up's at the top right as ever, the swipe-down's at the bottom right.
        key("Esc").assert(hasText("`")).assert(hasText("~"))
        key("/").assert(hasText("\\")).assert(hasText("?"))
        capture("deck-tertiary-glyphs")

        // A swipe down in progress: the label is what a release would send.
        key("-").performTouchInput { down(center); moveBy(Offset(0f, 40.dp.px())) }
        compose.waitForIdle()
        key("-").assert(hasText("_"))
        capture("deck-swipe-down-preview")
        key("-").performTouchInput { up() }
        compose.waitForIdle()

        // A hold: the alternates rise as chips over the key; the finger slides onto the second.
        key("/").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Text \u201C\\\u201D").assertIsDisplayed()
        compose.onNodeWithContentDescription("Text \u201C?\u201D").assertIsDisplayed()
        key("/").performTouchInput { moveTo(center + Offset(20.dp.px(), -30.dp.px())) }
        compose.waitForIdle()
        capture("deck-alternates-popover")
        key("/").performTouchInput { up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Text \u201C?\u201D")).fetchSemanticsNodes().isEmpty() }

        // A swipe across a key steps the row's layer: right for the next, left for the one before.
        key("Ctrl").performTouchInput { down(center); moveBy(Offset(60.dp.px(), 0f)); up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Layer") and hasStateDescription("Symbols")).fetchSemanticsNodes().isNotEmpty() }
        capture("deck-layer-swipe-symbols")
        key("|").performTouchInput { down(center); moveBy(Offset(-60.dp.px(), 0f)); up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Layer") and hasStateDescription("Base")).fetchSemanticsNodes().isNotEmpty() }

        // The grip: a tap opens the sheet, a drag up opens it expanded (spec C4).
        val grip = compose.onNode(hasContentDescription("Grip", substring = true))
        grip.performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(1, taps)
        assertEquals(0, dragsUp)
        grip.performTouchInput { down(center); moveBy(Offset(0f, -40.dp.px())); up() }
        compose.waitForIdle()
        assertEquals(1, taps)
        assertEquals(1, dragsUp)
    }

    /**
     * The same hand against the local sshd, through the shell: what each gesture sends is what the
     * terminal echoes. The swipe down on `-` lands `_`, the hold on `/` and a slide onto its second
     * chip lands `?`, and the swipe across steps the layer without sending a thing.
     */
    @Test
    fun `live on a phone, the swipe down and the popover's chip land what the finger meant`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        graph.settings.deckGestures.value = DeckSettings(swipeDown = true)
        runBlocking { graph.settings.setDeckLayout(layoutWithTertiaries()) }
        seedTestBox()
        compose.setContent { AppRoot(graph.viewModel) }
        val session = connectTestBox()
        settle(1_200)
        session.sendText("export PS1='\\[\\e[38;5;108m\\]\\u@berth\\[\\e[0m\\]:\\[\\e[38;5;179m\\]\\w\\[\\e[0m\\]\\$ ' && clear\n")
        settle(1_000)
        awaitDeck()

        key("-").performTouchInput { down(center); moveBy(Offset(0f, 40.dp.px())); up() }
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.endsWith("$ _") } }

        key("/").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Text \u201C?\u201D").assertIsDisplayed()
        key("/").performTouchInput { moveTo(center + Offset(20.dp.px(), -30.dp.px())) }
        compose.waitForIdle()
        capture("deck-alternates-popover-live")
        key("/").performTouchInput { up() }
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.endsWith("$ _?") } }

        // A hold released on no chip sends nothing: the line stands.
        key("/").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        key("/").performTouchInput { up() }
        settle(400)
        assertTrue(session.emulator.screenText().any { it.endsWith("$ _?") })

        // The layer swipe is the Deck's own: the terminal sees nothing of it.
        key("Ctrl").performTouchInput { down(center); moveBy(Offset(60.dp.px(), 0f)); up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Layer") and hasStateDescription("Symbols")).fetchSemanticsNodes().isNotEmpty() }
        settle(400)
        assertTrue(session.emulator.screenText().any { it.endsWith("$ _?") })
        capture("deck-live-after-gestures")

        // The grip's drag up: the Session sheet, expanded, with the Look row on it.
        compose.onNode(hasContentDescription("Grip", substring = true)).performTouchInput { down(center); moveBy(Offset(0f, -40.dp.px())); up() }
        waitForText("Look")
        compose.onNodeWithText("Berth Dark \u00B7 JetBrains Mono \u00B7 13 sp").assertIsDisplayed()
        settle(400)
        capture("session-sheet-expanded-live")

        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
    }

    // ---- the Session sheet's Look row (spec C6) -------------------------------------------------------

    /**
     * The Look row reads the theme, font and size the terminal draws with now and opens the Look
     * sheet over the Stage with no scrim, so the Stage is the preview: a theme picked in it lands on
     * the saved host and the canvas under the sheet redraws in it, and the row reads the pick when
     * the sheet closes.
     */
    @Test
    fun `the Session sheet's Look row opens the host's look over the Stage, and a pick shows on the Stage as it lands`() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        val live = StageFixture.liveHomelab()
        themed {
            Stage(live)
            SessionSheet(graph.viewModel, live, onDismiss = {}, onSwitch = {}, onEditHost = {}, onNewSession = {})
        }
        waitForText("Look")
        compose.onNodeWithText("Berth Dark \u00B7 JetBrains Mono \u00B7 13 sp").assertIsDisplayed()
        capture("session-sheet-look-row")

        compose.onNodeWithText("Look").performClick()
        waitForText("Theme")
        compose.onNodeWithText("Inherit is Settings \u203A Terminal, or the group's theme where the group has one. A pick here is this host's, on every tab it opens.").assertIsDisplayed()
        capture("look-sheet")

        val gruvbox = TerminalTheme.builtIns.first { it.name == "Gruvbox Dark" }
        compose.onNodeWithText("Theme").performClick()
        waitForText("Gruvbox Dark")
        compose.onNodeWithText("Gruvbox Dark").performClick()
        compose.waitUntil(5_000) { graph.hosts.items.value.first { it.id == "homelab" }.appearance.terminalThemeId == gruvbox.id }
        waitForText("Set on this host, on every tab it opens; the Stage behind shows it live.")
        settle(300)
        capture("look-sheet-theme-picked")

        compose.onNodeWithText("Size").performClick()
        waitForText("16 sp")
        compose.onNodeWithText("16 sp").performClick()
        compose.waitUntil(5_000) { graph.hosts.items.value.first { it.id == "homelab" }.appearance.fontSizeSp == 16 }
        settle(300)
        capture("look-sheet-size-picked")
    }

    // ---- Settings › Deck and Gestures ---------------------------------------------------------------------

    /** The rows that turn the second row and the two gestures, each a toggle with a caption that says what it does. */
    @Test
    fun `Settings has the Deck's two rows and the gestures' two toggles`() {
        StageFixture.seed(graph)
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Swipe across the Deck").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Two rows on a large screen").assertIsDisplayed()
        compose.onNodeWithText("Swipe down on a Deck key").assertIsDisplayed()
        capture("settings-deck-gestures")

        compose.onNodeWithText("Swipe down on a Deck key").performClick()
        compose.waitUntil(5_000) { graph.settings.deckGestures.value.swipeDown }
        compose.onNodeWithText("Two rows on a large screen").performClick()
        compose.waitUntil(5_000) { !graph.settings.deckGestures.value.twoRowsOnLargeScreens }
        assertTrue("the other gesture stands as it was", graph.settings.deckGestures.value.layerSwipe)
    }

    // ---- the shell, the Stage and the fixtures -----------------------------------------------------------

    /** The app as the shell mounts it over the fixture's three detached tabs, homelab on stage. */
    private fun mountApp() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("homelab, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /**
     * The drawer stands as the 72 dp column (spec C7): no row has a name (the words "New group" are
     * nowhere, not even off screen), the same rows stand as a 48 dp swatch or glyph each, named for
     * the reader, in 12 dp of padding, and the Stage's strip begins past the column and its gutter.
     */
    private fun theNarrowRail() {
        compose.onAllNodes(hasText("New group")).assertCountEquals(0)
        compose.onNodeWithContentDescription("New group").assertIsDisplayed()
        for (glyph in listOf("Hosts", "Keys", "Tunnels", "Snippets", "Settings")) {
            compose.onNodeWithContentDescription(glyph).assertIsDisplayed()
        }
        val hosts = compose.onNodeWithContentDescription("Hosts").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals("a 48 dp slot", 48f, hosts.width / density, 0.5f)
        assertEquals(48f, hosts.height / density, 0.5f)
        assertEquals("in the column's 12 dp of padding", 12f, hosts.left / density, 0.5f)
        val strip = compose.onNode(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNode().boundsInRoot
        assertEquals("the strip starts past the 72 dp column and the 12 dp gutter", 84f, strip.left / density, 0.5f)
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** Tab actions without the shell: data changes reach the manager, sheet requests land in a state nobody renders. */
    @Composable
    private fun tabActions(): TabActions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }

    /** The Stage as the shell mounts it, minus navigation. */
    @Composable
    private fun Stage(tab: ManagedTab?) {
        StageScreen(graph.viewModel, tab, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
    }

    /** The Deck is up once its Ctrl key stands. */
    private fun awaitDeck() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Ctrl", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The Deck key labelled [label], on whichever row. */
    private fun key(label: String) = compose.onNode(hasTestTag(DeckKeyTag) and hasContentDescription(label))

    /**
     * The stock layout with a tertiary on three of Base's keys, the way a hand that turned swipe down
     * on would bind them: Esc's `~` under its backtick, `-`'s `_` under its pipe, `/`'s `?` under its
     * backslash.
     */
    private fun layoutWithTertiaries(): DeckLayout {
        val stock = DeckLayout.default()
        val base = stock.layers.first()
        val keys = base.keys.map { key ->
            when (key.label) {
                "Esc" -> key.copy(down = DeckAction.Text("~"))
                "-" -> key.copy(down = DeckAction.Text("_"))
                "/" -> key.copy(down = DeckAction.Text("?"))
                else -> key
            }
        }
        return stock.copy(layers = listOf(base.copy(keys = keys)) + stock.layers.drop(1))
    }

    private fun waitForText(text: String, timeout: Long = 5_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForNoText(text: String, timeout: Long = 5_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }
    }

    /** Real time passes for the remote shell while the compose clock keeps ticking. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun androidx.compose.ui.unit.Dp.px(): Float = with(compose.density) { toPx() }

    private val now = System.currentTimeMillis()

    /** The local sshd as a saved host with its password in the secret store, alone in the library. */
    private fun seedTestBox() = runBlocking {
        val box = Host(
            id = "berth-test-box",
            name = "Berth test box",
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor("Berth test box"),
            address = sshHost,
            port = sshPort,
            user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("berth-test-box")),
            tags = listOf("local"),
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
        graph.hosts.upsert(box)
    }

    /** Opens the test box from the New tab sheet, trusts its key and waits for Live. */
    private fun connectTestBox(): TerminalSession {
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("New tab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New tab").performClick()
        waitForText("Berth test box")
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        return graph.sessions.activeSession.value!!
    }
}
