package app.berth.android.screenshots

import android.app.Application
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.AuthResolver
import app.berth.android.session.PaneSide
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** A Pixel-class phone upright, the size every other screenshot class renders at. */
private const val PHONE_PORTRAIT = "w411dp-h914dp-420dpi"

/** The same phone on its side: an expanded width over a compact height. */
private const val PHONE_LANDSCAPE = "w914dp-h411dp-land-420dpi"

/** A Pixel Fold open, held the wide way: just past the expanded line, medium in height. */
private const val FOLD_LANDSCAPE = "w841dp-h701dp-land-420dpi"

/** The same fold turned tall: a medium width. */
private const val FOLD_PORTRAIT = "w701dp-h841dp-port-420dpi"

/** A 10-inch tablet on its side. */
private const val TABLET_LANDSCAPE = "w1280dp-h800dp-land-320dpi"

/** The same tablet upright: medium in width, so the narrow rail, with panes and dialogs. */
private const val TABLET_PORTRAIT = "w800dp-h1280dp-port-320dpi"

/**
 * The app past a phone in portrait (spec A12, C23): the shell as [AppRoot] mounts it, at a phone
 * on its side, a foldable open in both orientations and a tablet in both, through Robolectric's
 * native graphics into `build/outputs/roborazzi`. Each size gets the two-pane Stage where it fits,
 * the 280 dp rail where the width is expanded and the 72 dp column of swatches and glyphs where it
 * is medium (spec C7), sheets as dialogs where a strip across the bottom would be absurd, and the
 * shorter strip where the height is compact. The fixtures are the phone
 * classes' three detached tabs and a Files tab, so the strip reads as it does in their frames, and
 * for one case a Tunnels tab (spec C14) beside a terminal; the phone in portrait is captured
 * through the same shell as the set's reference. The two live cases drive a real sshj login against
 * the local sshd when the `SSH_TEST_*` variables are set, so the Deck in those frames is a live
 * session's.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = PHONE_PORTRAIT)
class LargeScreenScreenshotTest {
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

    // ---- the phone -------------------------------------------------------------------------------

    /** The set's reference: the phone upright through the same shell. No pane, no rail, no pane mark; the Stage the goldens keep. */
    @Test
    fun `phone upright, the Stage as it was`() {
        mountApp()
        assertNull(graph.sessions.panes.value)
        compose.onAllNodes(hasContentDescription(", left pane", substring = true)).assertCountEquals(0)
        drawerIsASheet()
        assertEquals(40f, stripHeightDp(), 0.5f)
        capture("phone-portrait-stage")
    }

    /**
     * The phone on its side (spec C23): the strip drops to 32 dp so the terminal keeps the rows,
     * the drawer stays a sheet (too short for the rail), and the width still fits two panes.
     */
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun `phone on its side, the strip shortens and two panes fit`() {
        mountApp()
        assertEquals(32f, stripHeightDp(), 0.5f)
        drawerIsASheet()
        capture("phone-landscape-stage")

        split("s-pihole", PaneSide.RIGHT)
        pane("s-homelab", PaneSide.LEFT).assertIsDisplayed()
        capture("phone-landscape-split")
    }

    // ---- the foldable ----------------------------------------------------------------------------

    /**
     * The fold open the wide way (spec C7, C23): the drawer stands as the rail with the groups in
     * view, the Stage splits, and a pane closes and reopens without touching either tab.
     */
    @Test
    @Config(qualifiers = FOLD_LANDSCAPE)
    fun `foldable open wide, the rail stands and a pane closes and reopens`() {
        mountApp()
        // The rail: the drawer's rows are on screen with nothing opened.
        compose.onNodeWithText("New group").assertIsDisplayed()
        split("s-pihole", PaneSide.RIGHT)
        capture("foldable-landscape-split")

        // × on the focused (right) pane: the split ends, homelab takes the keys, pi-hole is still a tab.
        compose.onNodeWithContentDescription("Close pane").performClick()
        compose.waitUntil(5_000) { graph.sessions.panes.value == null && graph.sessions.activeTabId.value == "s-homelab" }
        compose.waitForIdle()
        assertEquals(4, graph.viewModel.tabs.value.size)
        compose.onAllNodes(hasContentDescription(", right pane", substring = true)).assertCountEquals(0)
        tab("pi-hole").assertIsDisplayed()
        capture("foldable-landscape-pane-closed")

        // Reopened from the strip: the tab's own action puts it back in the right pane, homelab keeps the left.
        tab("pi-hole").performCustomAccessibilityActionWithLabel("Open in right pane")
        waitForPane("s-pihole", PaneSide.RIGHT)
        val panes = graph.sessions.panes.value!!
        assertEquals("s-homelab", panes.left.id)
        assertEquals("s-pihole", panes.right.id)
        assertEquals(PaneSide.RIGHT, panes.focused)
    }

    /**
     * The fold turned tall: a medium width, so panes and dialogs, and the drawer standing as the 72 dp
     * column of swatches and glyphs (spec C7) rather than the rail with names. A tab in neither pane
     * offers Open beside in its long-press menu and lands in the pane opposite the focused one.
     */
    @Test
    @Config(qualifiers = FOLD_PORTRAIT)
    fun `foldable turned tall, the narrow rail, and a tab opens beside the active one from its menu`() {
        mountApp()
        drawerIsTheNarrowRail()
        split("s-pihole", PaneSide.RIGHT)

        tab("build box").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Open beside")).fetchSemanticsNodes().isNotEmpty() }
        capture("foldable-portrait-open-beside")

        compose.onNodeWithText("Open beside").performClick()
        // Beside pi-hole (right, focused) is the left pane: build takes it and the keys; pi-hole keeps its pane.
        waitForPane("s-build", PaneSide.LEFT)
        val panes = graph.sessions.panes.value!!
        assertEquals("s-build", panes.left.id)
        assertEquals("s-pihole", panes.right.id)
        assertEquals("s-build", graph.sessions.activeTabId.value)
        // The strip says where each sits.
        tab("build box").assert(hasContentDescription(", in the left pane", substring = true))
        tab("pi-hole").assert(hasContentDescription(", in the right pane", substring = true))
        capture("foldable-portrait-split")
    }

    // ---- the tablet ------------------------------------------------------------------------------

    /**
     * The tablet on its side: the rail, one tab on the Stage; the one tab itself cannot be carried
     * (nothing takes it: below the strip its drag stays a reorder, no ghost); a tab held in the strip
     * and pulled onto the body's right half steps that half up and, let go, splits the Stage with it
     * there (spec C23); the divider dragged toward a third settles on the snap; a tab carried onto the
     * right pane lands there; the switcher opens as a dialog.
     */
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun `tablet on its side, the rail, a tab carried onto the Stage splits it, the divider's snap, a tab carried onto a pane, a dialog`() {
        mountApp()
        compose.onNodeWithText("New group").assertIsDisplayed()
        capture("tablet-landscape-stage")

        // The tab filling the Stage has no beside-itself: held and pulled below the strip (with a nudge
        // sideways, so letting go is not a long-press menu) it is never lifted out, and the Stage stays one.
        val homelab = tab("homelab")
        homelab.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        homelab.performTouchInput { moveBy(Offset(touchSlop() + 8f, 30f)) }
        homelab.performTouchInput { moveBy(Offset(0f, 200f)) }
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("Carrying", substring = true)).assertCountEquals(0)
        homelab.performTouchInput { up() }
        compose.waitForIdle()
        assertNull(graph.sessions.panes.value)

        // Another tab carried onto the one body (spec C23): over its right half the half steps up and the
        // ghost travels with the finger; letting go splits the Stage, pi-hole on the right with the keys.
        val body = compose.onNode(hasTestTag(TerminalTag)).fetchSemanticsNode().boundsInRoot
        val pihole = carry("pi-hole", Offset(body.left + body.width * 0.75f, body.center.y))
        capture("tablet-landscape-carry-to-split")
        pihole.performTouchInput { up() }
        waitForPane("s-pihole", PaneSide.RIGHT)
        assertEquals("s-homelab", graph.sessions.panes.value!!.left.id)
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        compose.onAllNodes(hasContentDescription("Carrying", substring = true)).assertCountEquals(0)
        capture("tablet-landscape-split")

        // The divider: let go within reach of a third, it snaps there.
        val divider = compose.onNode(hasContentDescription("Divider between the panes"))
        divider.assert(hasStateDescription("Left pane 50 percent"))
        dragDivider("s-homelab", "s-pihole", -1f / 6f)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Divider between the panes") and hasStateDescription("Left pane 33 percent")).fetchSemanticsNodes().isNotEmpty()
        }
        capture("tablet-landscape-split-third")

        // A tab carried from the strip onto a pane (spec C23): over the right pane the pane steps up to
        // take it, and letting go puts it there.
        val build = carry("build box", paneBounds("s-pihole", PaneSide.RIGHT).center)
        capture("tablet-landscape-tab-carry")
        build.performTouchInput { up() }
        waitForPane("s-build", PaneSide.RIGHT)
        val panes = graph.sessions.panes.value!!
        assertEquals("s-homelab", panes.left.id)
        assertEquals("s-build", panes.right.id)
        assertEquals("s-build", graph.sessions.activeTabId.value)
        compose.onAllNodes(hasContentDescription("Carrying", substring = true)).assertCountEquals(0)

        // The switcher, a sheet on the phone, is a dialog here.
        compose.onNode(hasContentDescription("open the tab switcher", substring = true)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Frame of homelab")).fetchSemanticsNodes().isNotEmpty() }
        capture("tablet-landscape-switcher-dialog")
    }

    /**
     * A carry's targets are the layer's that is up, never a layer's that was. After a split whose
     * divider was moved to a third and a pane closed, the one body's halves take the drop either side
     * of the window's middle: a tab let go at 40 percent of the way across lands on the left, where
     * the bounds of the right pane as it was (from a third on) would have put it on the right.
     */
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun `tablet on its side, after a split and a close the one body's halves take the drop, not the panes that were`() {
        mountApp()
        split("s-pihole", PaneSide.RIGHT)
        dragDivider("s-homelab", "s-pihole", -1f / 6f)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Divider between the panes") and hasStateDescription("Left pane 33 percent")).fetchSemanticsNodes().isNotEmpty()
        }
        // × on the focused (right) pane: homelab alone on the Stage, with the keys.
        compose.onNodeWithContentDescription("Close pane").performClick()
        compose.waitUntil(5_000) { graph.sessions.panes.value == null && graph.sessions.activeTabId.value == "s-homelab" }
        compose.waitForIdle()

        val body = compose.onNode(hasTestTag(TerminalTag)).fetchSemanticsNode().boundsInRoot
        carry("build box", Offset(body.left + body.width * 0.4f, body.center.y)).performTouchInput { up() }
        waitForPane("s-build", PaneSide.LEFT)
        val panes = graph.sessions.panes.value!!
        assertEquals("s-build", panes.left.id)
        assertEquals("s-homelab", panes.right.id)
        assertEquals("s-build", graph.sessions.activeTabId.value)
    }

    /**
     * The tablet upright: medium, so the narrow rail, and the Stage splits. The reference layout
     * first (one tab under the strip, the 72 dp column beside it), the New tab sheet as a dialog over
     * it, and once that is closed from its scrim a Files tab beside a terminal (the pane owns the
     * bottom inset, so the browser pads for nothing).
     */
    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun `tablet upright, the narrow rail, the New tab sheet as a dialog over the Stage, and a Files tab beside a terminal`() {
        mountApp()
        drawerIsTheNarrowRail()
        capture("tablet-portrait-stage")

        openNewTabSheet()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("build box")).fetchSemanticsNodes().isNotEmpty() }
        capture("tablet-portrait-new-tab-dialog")
        // The scrim closes the dialog, as a tap beside a bottom sheet closes the sheet.
        compose.onNode(hasContentDescription("Close") and hasAnyDescendant(hasText("build box"))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("build box")).fetchSemanticsNodes().isEmpty() }

        split("f-homelab", PaneSide.RIGHT)
        pane("s-homelab", PaneSide.LEFT).assertIsDisplayed()
        capture("tablet-portrait-files-beside-terminal")
    }

    /**
     * A Tunnels tab (spec C14) beside a terminal: the one body dispatch gives the pane the forwards
     * over their pill, with no Deck to hand down and no keys to take, and the pane, not the body,
     * pays the window's bottom inset. Robolectric's window has no navigation bar, so one is given
     * to it: alone on the Stage the body's own spacer holds the pill 48 px off the bottom; in a pane
     * the layer has consumed that inset, the spacer is nothing, the pill sits on the pane's edge and
     * the chrome under both panes pays the bar once, as it does for a Files tab's browser.
     */
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun `tablet on its side, a Tunnels tab beside a terminal pays no bottom inset of its own`() {
        seedTunnelsTab()
        mountApp(activeId = "t-proddb")
        compose.onNodeWithText(TunnelRowText).assertIsDisplayed()
        val bar = 48
        navigationBar(bar)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val alone = root.bottom - pill().bottom

        split("s-homelab", PaneSide.LEFT)
        graph.sessions.setActive("t-proddb")
        compose.waitUntil(5_000) { graph.sessions.panes.value?.let { it.right.id == "t-proddb" && it.focused == PaneSide.RIGHT } == true }
        compose.waitForIdle()
        val pane = paneBounds("t-proddb", PaneSide.RIGHT)
        assertEquals("the layer pays the bar under both panes", root.bottom - bar, pane.bottom, 1f)
        assertEquals("the body's spacer is nothing inside a pane", alone - bar, pane.bottom - pill().bottom, 1f)
        compose.onNodeWithText(TunnelRowText).assertIsDisplayed()
        compose.onNodeWithText("Tunnels run while this login is up").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Ctrl", substring = true)).assertCountEquals(0)

        navigationBar(0)
        capture("tablet-landscape-tunnels-beside-terminal")
    }

    // ---- live, against the local sshd ------------------------------------------------------------

    /** The phone on its side with a live session: the 32 dp strip, the terminal, and the Deck one row of 40 (spec C23). */
    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun `live session on a phone on its side, the Deck one row of 40`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        seedTestBox()
        mountApp()
        val session = connectTestBox()
        settle(1_200)
        session.sendText("export PS1='\\[\\e[38;5;108m\\]\\u@berth\\[\\e[0m\\]:\\[\\e[38;5;179m\\]\\w\\[\\e[0m\\]\\$ ' && clear && ls --color=always -la /\n")
        settle(1_500)
        assertEquals(32f, stripHeightDp(), 0.5f)
        val ctrl = compose.onNode(hasContentDescription("Ctrl", substring = true)).fetchSemanticsNode()
        assertEquals(40f, ctrl.size.height / compose.density.density, 1f)
        capture("phone-landscape-live-deck")
    }

    /**
     * A live session in a pane beside a detached one: one Deck under both panes, the live terminal's
     * (spec C23). A touch on the detached pane moves the focus and the keys, and nothing else: the
     * detached frame has no Deck to show, so the live one's stays where it is and the live frame
     * keeps its rows (a focus change must never send the remote a window change).
     */
    @Test
    @Config(qualifiers = TABLET_LANDSCAPE)
    fun `live session beside a detached one, one Deck under both panes, staying with the terminal that has one`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        seedTestBox()
        mountApp()
        val session = connectTestBox()
        settle(1_200)
        session.sendText("export PS1='\\[\\e[38;5;108m\\]\\u@berth\\[\\e[0m\\]:\\[\\e[38;5;179m\\]\\w\\[\\e[0m\\]\\$ ' && clear && ls --color=always -la /\n")
        settle(1_000)

        // homelab takes the left pane and the keys; staging the live tab again trades the roles and keeps the panes.
        graph.sessions.placeInPane("s-homelab", PaneSide.LEFT)
        compose.waitUntil(5_000) { graph.sessions.panes.value?.left?.id == "s-homelab" }
        graph.sessions.setActive(session.id)
        compose.waitUntil(5_000) { graph.sessions.panes.value?.let { it.right.id == session.id && it.focused == PaneSide.RIGHT } == true }
        waitForPane(session.id, PaneSide.RIGHT)
        settle(600)
        val deck = compose.onNode(hasContentDescription("Ctrl", substring = true))
        deck.assertIsDisplayed()
        val deckBounds = deck.fetchSemanticsNode().boundsInRoot
        val liveBounds = paneBounds(session.id, PaneSide.RIGHT)
        val rows = session.emulator.rows
        val cols = session.emulator.cols
        capture("tablet-landscape-live-split-deck")

        // A touch in the left pane focuses it. homelab is detached and has no Deck to put under the panes,
        // so the live terminal's stays, and the live frame is exactly the size it was: no resize reaches the remote.
        pane("s-homelab", PaneSide.LEFT).performTouchInput { click(center) }
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == "s-homelab" && graph.sessions.panes.value?.focused == PaneSide.LEFT }
        settle(600)
        deck.assertIsDisplayed()
        assertEquals(deckBounds, deck.fetchSemanticsNode().boundsInRoot)
        assertEquals(liveBounds, paneBounds(session.id, PaneSide.RIGHT))
        assertEquals(rows, session.emulator.rows)
        assertEquals(cols, session.emulator.cols)
        compose.onNodeWithContentDescription("Close pane").assertIsDisplayed()
        capture("tablet-landscape-live-split-focus-left")

        // And back: the same Deck, the same frame.
        pane(session.id, PaneSide.RIGHT).performTouchInput { click(center) }
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == session.id && graph.sessions.panes.value?.focused == PaneSide.RIGHT }
        settle(600)
        assertEquals(deckBounds, deck.fetchSemanticsNode().boundsInRoot)
        assertEquals(rows, session.emulator.rows)
        assertEquals(cols, session.emulator.cols)
    }

    // ---- the shell and its panes -----------------------------------------------------------------

    /** The app as the shell mounts it, restored onto [activeId], up to the strip showing the four tabs. */
    private fun mountApp(activeId: String = "s-homelab") {
        seedLibrary()
        seedDetachedSessions()
        seedFilesTab()
        runBlocking { graph.sessions.restore() }
        graph.sessions.setActive(activeId)
        compose.setContent {
            composeView = LocalView.current
            AppRoot(graph.viewModel)
        }
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 4 && graph.viewModel.activeTabId.value == activeId }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("homelab, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /**
     * The drawer is a sheet here, not a rail: its rows are composed off the left edge, not in view.
     * (The count is not zero; the modal drawer keeps its sheet composed where it will slide in from.)
     */
    private fun drawerIsASheet() = compose.onNodeWithText("New group").assertIsNotDisplayed()

    /**
     * The drawer stands as the 72 dp column (spec C7): no row has a name (the words "New group" are
     * nowhere, not even off screen), the same rows stand as a 48 dp swatch or glyph each, named for
     * the reader, in 12 dp of padding, and the Stage's strip begins past the column and its gutter.
     */
    private fun drawerIsTheNarrowRail() {
        compose.onAllNodes(hasText("New group")).assertCountEquals(0)
        compose.onNodeWithContentDescription("New group").assertIsDisplayed()
        val hosts = compose.onNodeWithContentDescription("Hosts").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals("a 48 dp slot", 48f, hosts.width / density, 0.5f)
        assertEquals("in the column's 12 dp of padding", 12f, hosts.left / density, 0.5f)
        val strip = compose.onNode(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNode().boundsInRoot
        assertEquals("the strip starts past the 72 dp column and the 12 dp gutter", 84f, strip.left / density, 0.5f)
    }

    /** Puts [id] in the pane on [side] and waits for the pane to show under the tab's title. */
    private fun split(id: String, side: PaneSide) {
        graph.sessions.placeInPane(id, side)
        waitForPane(id, side)
    }

    private fun waitForPane(id: String, side: PaneSide) {
        compose.waitUntil(5_000) { compose.onAllNodes(paneMatcher(id, side)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The pane showing [id], found by the header's title as it reads now: a live session's title follows its shell. */
    private fun paneMatcher(id: String, side: PaneSide): SemanticsMatcher =
        hasContentDescription("${graph.sessions.tab(id)!!.record.value.displayTitle}, ${if (side == PaneSide.LEFT) "left" else "right"} pane")

    private fun pane(id: String, side: PaneSide) = compose.onNode(paneMatcher(id, side))

    private fun paneBounds(id: String, side: PaneSide): Rect = pane(id, side).fetchSemanticsNode().boundsInRoot

    /** The Tunnels tab's state pill, by its text: the one frame detached seven minutes ago. */
    private fun pill(): Rect = compose.onNodeWithText("Detached \u00B7 7 min ago").fetchSemanticsNode().boundsInRoot

    /** The compose view under test, for the insets Robolectric's window never sends it. */
    private var composeView: View? = null

    /**
     * Gives the window a navigation bar [px] tall along the bottom, dispatched to the compose view
     * as the window would, so what pays that inset can be seen paying it; 0 takes the bar away.
     */
    private fun navigationBar(px: Int) {
        val view = checkNotNull(composeView) { "mountApp first" }
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px)).build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    /** The strip item for the detached tab titled [title]. */
    private fun tab(title: String) = compose.onNode(hasContentDescription("$title, detached", substring = true))

    private fun touchSlop(): Float = ViewConfiguration.get(ApplicationProvider.getApplicationContext()).scaledTouchSlop.toFloat()

    /**
     * Holds the strip item titled [title] until it lifts, pulls it below the strip and carries it to
     * [to] (root coordinates), returning once the ghost is up; the finger is still down, for the
     * caller to let go or capture first.
     */
    private fun carry(title: String, to: Offset): SemanticsNodeInteraction {
        val item = tab(title)
        val itemBounds = item.fetchSemanticsNode().boundsInRoot
        item.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        item.performTouchInput { moveBy(Offset(0f, 30f)) }
        item.performTouchInput { moveBy(Offset(0f, 200f)) }
        item.performTouchInput { moveTo(to - itemBounds.topLeft) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Carrying $title")).fetchSemanticsNodes().isNotEmpty() }
        return item
    }

    /** Drags the divider by [fraction] of the two panes' width (past the touch slop first) and lets go, for the snap to settle it. */
    private fun dragDivider(leftId: String, rightId: String, fraction: Float) {
        val usable = paneBounds(leftId, PaneSide.LEFT).width + paneBounds(rightId, PaneSide.RIGHT).width
        val slop = touchSlop()
        compose.onNode(hasContentDescription("Divider between the panes")).performTouchInput {
            down(center)
            moveBy(Offset(if (fraction < 0f) -(slop + 1f) else slop + 1f, 0f))
            moveBy(Offset(usable * fraction, 0f))
            up()
        }
    }

    /** The strip's height in dp: the style's height, since Robolectric's window has no status-bar inset to reach into. */
    private fun stripHeightDp(): Float =
        compose.onNode(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNode().size.height / compose.density.density

    /** The plus tab is the strip's last item; with tabs before it the lazy row has not composed it until it scrolls there. */
    private fun openNewTabSheet() {
        compose.onNode(hasContentDescription("Tabs, ", substring = true)).performScrollToNode(hasContentDescription("New tab"))
        compose.onNodeWithContentDescription("New tab").performClick()
    }

    /** Opens the test box from the New tab sheet, trusts its key and waits for Live. */
    private fun connectTestBox(): TerminalSession {
        openNewTabSheet()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth test box")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        return graph.sessions.activeSession.value!!
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

    // ---- fixtures (the phone classes' library, so the strip reads as it does in their frames) ----

    private val now = System.currentTimeMillis()

    private fun host(
        id: String,
        name: String,
        address: String,
        user: String,
        color: SwatchColor,
        auth: AuthMethod,
        port: Int = 22,
        lastConnectedAgoMinutes: Long? = null,
        tags: List<String> = emptyList(),
        persistence: PersistencePolicy = PersistencePolicy(),
    ) = Host(
        id = id,
        name = name,
        color = color,
        monogram = Host.monogramFor(name),
        address = address,
        port = port,
        user = user,
        auth = auth,
        persistence = persistence,
        tags = tags,
        lastConnectedAt = lastConnectedAgoMinutes?.let { now - TimeUnit.MINUTES.toMillis(it) },
        createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun seedLibrary() = runBlocking {
        val ed = SshKeys.generate(KeyAlgorithm.ED25519)
        val ec = SshKeys.generate(KeyAlgorithm.ECDSA_P256)
        val deploy = SshKeys.generate(KeyAlgorithm.ED25519)
        graph.identities.insert(
            Identity("id-laptop", "laptop ed25519", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(ed.public, "ben@laptop"), SshKeys.fingerprintSha256(ed.public), "ben@laptop", createdAt = now - TimeUnit.DAYS.toMillis(200)),
            SshKeys.openSshPrivate(ed, "ben@laptop", "correct horse".toCharArray()).toByteArray(),
        )
        graph.identities.insert(
            Identity("id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(ec.public, "berth@pixel"), SshKeys.fingerprintSha256(ec.public), "berth@pixel", keystoreAlias = "berth-id-phone", createdAt = now - TimeUnit.DAYS.toMillis(12)),
            null,
        )
        graph.identities.insert(
            Identity("id-deploy", "deploy key", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE, SshKeys.openSshPublic(deploy.public, "deploy"), SshKeys.fingerprintSha256(deploy.public), "deploy", createdAt = now - TimeUnit.DAYS.toMillis(3)),
            SshKeys.openSshPrivate(deploy, "deploy").toByteArray(),
        )

        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, AuthMethod.Key("id-laptop"), lastConnectedAgoMinutes = 130, tags = listOf("prod", "eu-west")))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password("host-password:homelab"), lastConnectedAgoMinutes = 18))
        graph.hosts.upsert(host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, AuthMethod.Key("id-phone"), lastConnectedAgoMinutes = 60 * 26))
        graph.hosts.upsert(
            host(
                "build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, AuthMethod.Key("id-deploy"),
                persistence = PersistencePolicy(tmux = TmuxMode.ATTACH_OR_CREATE, reconnectMinutes = 60),
                tags = listOf("tmux"),
            ),
        )
        graph.hosts.upsert(host("staging-db", "staging db", "db-staging.example.net", "postgres", SwatchColor.PLUM, AuthMethod.AskEachTime, port = 2200))
        graph.hosts.upsert(host("vps", "vps", "vps.example.org", "root", SwatchColor.RUST, AuthMethod.Key("id-laptop")))

        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.workspaces.upsert(Workspace("ws-lab", "Homelab", SwatchColor.MOSS, "HL", sortOrder = 2, reconnectAtLaunch = false, createdAt = now - TimeUnit.DAYS.toMillis(10)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
    }

    /** The local sshd as a saved host with its password in the secret store, and the last active tab as the phone has it. */
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
        graph.settings.setLastActiveSessionId("s-homelab")
    }

    private fun seedDetachedSessions() = runBlocking {
        val hosts = graph.hosts.items.value.associateBy { it.id }
        fun record(id: String, hostId: String, ws: String, order: Int, lastLiveMinutesAgo: Long, cwd: String?, lastCommand: String?) = SessionRecord(
            id = id,
            workspaceId = ws,
            hostId = hostId,
            hostSnapshot = hosts.getValue(hostId),
            state = SessionState.DETACHED,
            layer = PersistenceLayer.LOCAL_FRAME,
            title = hosts.getValue(hostId).name,
            cwd = cwd,
            lastCommand = lastCommand,
            sortOrder = order,
            createdAt = now - TimeUnit.HOURS.toMillis(5),
            lastLiveAt = now - TimeUnit.MINUTES.toMillis(lastLiveMinutesAgo),
        )
        graph.sessionRecords.upsert(record("s-homelab", "homelab", Workspace.DEFAULT_ID, 0, 12, "~/srv", "docker compose ps"))
        graph.sessionRecords.upsert(record("s-pihole", "pi-hole", Workspace.DEFAULT_ID, 1, 95, "/etc/pihole", "tail -f pihole.log"))
        graph.sessionRecords.upsert(record("s-build", "build-box", "ws-work", 2, 400, "~/work/berth", "./gradlew assembleDebug"))
        graph.sessionRecords.saveFrame(
            "s-homelab",
            frame(
                listOf(
                    "ben@homelab:~/srv$ docker compose ps",
                    "NAME        IMAGE               STATUS        PORTS",
                    "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
                    "gitea       gitea/gitea:1.22    Up 3 days     3000/tcp",
                    "postgres    postgres:16         Up 3 days     5432/tcp",
                    "ben@homelab:~/srv$ ",
                ),
            ),
        )
        graph.sessionRecords.saveFrame("s-pihole", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log", "Sep 18 20:41:02 dnsmasq[712]: query[A] api.berth.app from 192.168.1.30", "Sep 18 20:41:02 dnsmasq[712]: forwarded api.berth.app to 1.1.1.1")))
        graph.sessionRecords.saveFrame("s-build", frame(listOf("ci@build:~/work/berth$ ./gradlew assembleDebug", "BUILD SUCCESSFUL in 1m 12s", "ci@build:~/work/berth$ ")))
    }

    /** A Files tab for homelab at the end of the default group, left on the berth project folder; it rides the homelab terminal. */
    private fun seedFilesTab() = runBlocking {
        val homelab = graph.hosts.items.value.first { it.id == "homelab" }
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "f-homelab",
                workspaceId = Workspace.DEFAULT_ID,
                hostId = homelab.id,
                hostSnapshot = homelab,
                state = SessionState.DETACHED,
                title = "Files \u00B7 berth",
                cwd = "/home/demo/projects/berth",
                sortOrder = 2,
                createdAt = now - TimeUnit.HOURS.toMillis(2),
                lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
                kind = TabKind.Files,
            ),
        )
    }

    /**
     * A tunnels-only host, prod-db, with a Postgres forward, a web forward and a SOCKS proxy switched
     * off, and its Tunnels tab (spec C14) at the end of the default group, detached seven minutes ago.
     * Seeded before [mountApp], which restores it with the rest.
     */
    private fun seedTunnelsTab() = runBlocking {
        val prodDb = Host(
            id = "prod-db",
            name = "prod-db",
            color = SwatchColor.PLUM,
            monogram = Host.monogramFor("prod-db"),
            address = "10.0.4.12",
            port = 22,
            user = "deploy",
            auth = AuthMethod.AskEachTime,
            tunnelsOnly = true,
            lastConnectedAt = now - TimeUnit.MINUTES.toMillis(7),
            createdAt = now - TimeUnit.DAYS.toMillis(30),
        )
        graph.hosts.upsert(prodDb)
        graph.tunnels.upsert(Tunnel("t-pg", prodDb.id, TunnelType.LOCAL, "127.0.0.1", 5433, "localhost", 5432))
        graph.tunnels.upsert(Tunnel("t-web", prodDb.id, TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80))
        graph.tunnels.upsert(Tunnel("t-socks", prodDb.id, TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0, enabled = false))
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "t-proddb",
                workspaceId = Workspace.DEFAULT_ID,
                hostId = prodDb.id,
                hostSnapshot = prodDb,
                state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME,
                title = "Tunnels \u00B7 ${prodDb.name}",
                sortOrder = 3,
                createdAt = now - TimeUnit.HOURS.toMillis(2),
                lastLiveAt = now - TimeUnit.MINUTES.toMillis(7),
                kind = TabKind.Tunnels,
            ),
        )
    }

    private fun frame(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(1)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
        }
        return out.toByteArray()
    }
}

/** The Postgres forward's row on the Tunnels stage, as `Tunnel.rowSpec` sets it: the arrow holds the destination on its line. */
private const val TunnelRowText = "127.0.0.1:5433 \u2192\u00A0localhost:5432"
