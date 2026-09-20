package app.berth.android.screenshots

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.security.BerthClipboard
import app.berth.android.security.FakeAuthenticator
import app.berth.android.security.LockState
import app.berth.android.session.AuthResolver
import app.berth.android.session.ManagedTab
import app.berth.android.session.Prompt
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.security.BerthClipboardLocals
import app.berth.android.ui.security.LockCover
import app.berth.android.ui.security.LockWindow
import app.berth.android.ui.stage.INVALID_PATTERN
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.stage.StageTools
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.terminal.HANDLE_RADIUS
import app.berth.android.ui.terminal.HandleSpot
import app.berth.android.ui.terminal.SelectionHandle
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalPaintsCache
import app.berth.android.ui.terminal.handleCenters
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import app.berth.terminal.PasteClassifier
import app.berth.terminal.TerminalKey
import app.berth.terminal.TerminalText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The terminal's text tools (spec C16 to C18) in Berth Dark on a Pixel-class phone: a selection
 * with its handles and the bar in the header's place, the paste preview sheet, the search bar with
 * its matches lit in the canvas, and the history sheet. Offline cases put a detached tab on stage
 * with a frame that has history behind the screen, wrapped lines, wide cells and a link, and hand
 * the Stage the test's own [StageTools] so the state a gesture leaves can be read; the gestures
 * themselves (long-press, drag, the handles, double-tap, two fingers) are driven for real. The
 * paste preview with its buttons enabled needs a tab that reads Live, which the offline cases
 * get from a session whose record says so and which has no shell, so nothing goes anywhere.
 * `terminal tools live flow` opens the real app against the local sshd and selects, copies,
 * searches, pastes and re-runs real output.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalToolsScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()

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
        graph = TestGraph(context)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** Real time passes (for the sshd, the frame worker and the session's own timers) while the compose clock keeps ticking. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun waitForText(text: String, timeout: Long = 5_000, substring: Boolean = false) {
        try {
            compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { t -> t.text } ?: emptyList() }
            throw AssertionError("'$text' never showed; the texts on screen were $shown", e)
        }
    }

    private fun waitForNoText(text: String, timeout: Long = 5_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }
    }

    /** The text field inside a BerthField described as [description] (the description sits on the field's column). */
    private fun field(description: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(description)))

    private fun clipboardText(): String? = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    /**
     * The clipboard holds [text] as a copy Berth made (spec C20, Clipboard): one that went through
     * [BerthClipboard], so it carries the token its clear timer looks for and is kept out of the
     * system's preview, rather than a plain write to the platform.
     */
    private fun assertBerthsClip(text: String) {
        assertEquals(text, clipboardText())
        val extras = context.getSystemService(ClipboardManager::class.java).primaryClipDescription?.extras
        assertNotNull("a copy of Berth's carries its token", extras)
        assertTrue("a copy of Berth's carries its token", extras!!.containsKey(BerthClipboard.EXTRA_TOKEN))
        assertTrue("a copy of Berth's is marked sensitive", extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    private fun setClipboard(text: String) = context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", text))

    @Composable
    private fun tabActions(): TabActions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }

    /**
     * The Stage as the shell mounts it, inside the clipboard locals that make every Copy Berth's
     * own, with the test's own tools in place of the remembered ones.
     */
    @Composable
    private fun Stage(tab: ManagedTab?, tools: StageTools) {
        BerthClipboardLocals(graph.clipboard) {
            StageScreen(graph.viewModel, tab, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, tools = tools)
        }
    }

    // ---- geometry ---------------------------------------------------------------------------------

    /** The paints the canvas draws with, for the cell size in pixels. */
    private fun paints(): TerminalPaints {
        val res = context.resources
        val font = runBlocking { graph.settings.terminalFont.first() }
        return TerminalPaintsCache.get(context, font, res.displayMetrics.density, res.configuration.fontScale)
    }

    /** The centre of the view cell at [row], [col] in the canvas node's coordinates. */
    private fun cellCenter(row: Int, col: Int): Offset {
        val p = paints()
        return Offset((col + 0.5f) * p.cellWidth, (row + 0.5f) * p.cellHeight)
    }

    /** The view row and column where [token] first shows on the live screen. */
    private fun cellOf(session: TerminalSession, token: String): Pair<Int, Int> {
        val rows = session.emulator.screenText()
        val row = rows.indexOfFirst { it.contains(token) }
        assertTrue("'$token' is on screen", row >= 0)
        return row to rows[row].indexOf(token)
    }

    private val handleRadius: Float get() = HANDLE_RADIUS.value * context.resources.displayMetrics.density

    /** The selection's two handles as the canvas places them for the view now: under their cells, or above on the bottom row, inside the edges. */
    private fun handles(session: TerminalSession, tools: StageTools): Pair<HandleSpot?, HandleSpot?> {
        val p = paints()
        val range = tools.selection.range!!
        val top = session.emulator.scrollbackSize - tools.viewport.scrollOffset
        val size = compose.onNodeWithContentDescription("Terminal").fetchSemanticsNode().size
        return handleCenters(range.shiftRows(-top), session.emulator.rows, p.cellWidth, p.cellHeight, handleRadius, size.width.toFloat(), size.height.toFloat())
    }

    /** Where the selection's end handle is on the canvas. */
    private fun endHandle(session: TerminalSession, tools: StageTools): Offset = handles(session, tools).second!!.center

    /** Both handles' discs lie inside the canvas by their radius; fails naming the one that does not. */
    private fun assertHandlesInside(session: TerminalSession, tools: StageTools) {
        val size = compose.onNodeWithContentDescription("Terminal").fetchSemanticsNode().size
        val r = handleRadius
        val (s, e) = handles(session, tools)
        for ((name, spot) in listOf("start" to s, "end" to e)) {
            if (spot == null) continue
            val (x, y) = spot.center
            assertTrue("the $name handle's x $x is inside 0..${size.width} by $r", x >= r && x <= size.width - r)
            assertTrue("the $name handle's y $y is inside 0..${size.height} by $r", y >= r && y <= size.height - r)
        }
    }

    /** Holds a finger at [at] past the long-press timeout, so the word under it is selected; the finger stays down. */
    private fun longPress(at: Offset, selected: () -> Boolean) {
        compose.onNodeWithContentDescription("Terminal").performTouchInput { down(at) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5_000, selected)
    }

    /** The last buffer row with anything on it. */
    private fun lastContentRow(session: TerminalSession): Int = synchronized(session.emulator.lock) {
        val grid = session.emulator.grid
        var last = grid.rowCount - 1
        while (last > 0 && grid.line(last).isBlank()) last--
        last
    }

    // ---- the detached tab on stage ------------------------------------------------------------------

    /** The detached homelab tab on stage with the test's tools: its frame restored and the grid sized to the canvas, so cells map to pixels. */
    private fun stageDetached(withHistory: Boolean = false): Pair<TerminalSession, StageTools> {
        seedHomelab(withHistory)
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        val tools = StageTools()
        themed { Stage(session, tools) }
        awaitGrid(session)
        return session to tools
    }

    /**
     * The same tab on stage reading Live, with no shell behind it: the record says Live and the
     * frame is the same, so the paste routes see a connected tab, the preview's buttons are enabled,
     * and what they send has nowhere to go. The strip has no chip for it, which the sheet covers.
     */
    private fun stageLive(): Pair<TerminalSession, StageTools> {
        seedHomelab(withHistory = false)
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = emptyFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        val session = TerminalSession(homelabRecord(SessionState.LIVE, PersistenceLayer.IN_APP), CoroutineScope(SupervisorJob() + Dispatchers.Default), env) {}
        session.restoreFrame(frame(FRAME_LINES, emptyList()))
        val tools = StageTools()
        themed { Stage(session, tools) }
        awaitGrid(session)
        assertEquals(SessionState.LIVE, session.state)
        return session to tools
    }

    /** Waits for the canvas to size the grid to itself, so cells map to pixels, and for history to be behind the screen. */
    private fun awaitGrid(session: TerminalSession) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Terminal")).fetchSemanticsNodes().isNotEmpty() }
        val p = paints()
        compose.waitUntil(5_000) {
            val size = compose.onNodeWithContentDescription("Terminal").fetchSemanticsNode().size
            val cols = (size.width / p.cellWidth).toInt()
            val rows = (size.height / p.cellHeight).toInt()
            cols >= 2 && session.emulator.cols == cols && session.emulator.rows == rows
        }
        settle(300)
        assertTrue("history behind the screen", session.emulator.scrollbackSize > 0)
    }

    // ---- selection (spec C18) -------------------------------------------------------------------------

    @Test
    fun `selection with handles and the action bar`() {
        val (session, tools) = stageDetached()
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val (row, col) = cellOf(session, "gitea/gitea:1.22")

        // A long-press selects the word under the finger, punctuation and all; a drag two rows down grows it by words.
        longPress(cellCenter(row, col + 3)) { tools.selection.active }
        assertEquals("gitea/gitea:1.22", tools.selection.text(session.emulator))
        waitForText("16 chars")
        canvas.performTouchInput { moveTo(cellCenter(row + 2, col + 3)) }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.summary == "3 lines" }
        val text = tools.selection.text(session.emulator)
        assertEquals(3, text.lines().size)
        assertTrue(text.startsWith("gitea/gitea:1.22"))
        assertTrue(text.lines().last().endsWith("demo@homelab:~/srv$"))
        assertTrue(text.lines().none { it.endsWith(" ") })
        compose.onNodeWithText("Copy").assertIsDisplayed()
        // A frozen frame has nothing to paste into, so the bar does not offer it; the rest stands.
        compose.onAllNodesWithText("Paste").assertCountEquals(0)
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        assertHandlesInside(session, tools)
        settle(200)
        capture("terminal-selection")

        // The end handle: grabbed, it moves that end by the finger's travel from the cell it marks,
        // one row up and three columns back here, cell by cell.
        val p = paints()
        val endCol = tools.selection.range!!.end.col
        canvas.performTouchInput { down(endHandle(session, tools)) }
        compose.waitUntil(5_000) { tools.selection.dragging == SelectionHandle.END }
        canvas.performTouchInput { moveBy(Offset((col + 5 - endCol) * p.cellWidth, -p.cellHeight)) }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.summary == "2 lines" && tools.selection.dragging == null }
        assertEquals(col + 5, tools.selection.range!!.end.col)
        assertTrue(tools.selection.text(session.emulator).startsWith("gitea/gitea:1.22"))
        settle(200)
        capture("terminal-selection-handle")

        // Copy: the clipboard holds the text as Berth's copy, the pill says so, the selection is gone.
        val copied = tools.selection.text(session.emulator)
        compose.onNodeWithText("Copy").performClick()
        waitForText("Copied")
        assertBerthsClip(copied)
        assertFalse(tools.selection.active)
        capture("terminal-copied")
        compose.mainClock.advanceTimeBy(1_600)
        waitForNoText("Copied")

        // A tap with a selection only dismisses it.
        longPress(cellCenter(row, col)) { tools.selection.active }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        canvas.performTouchInput { click(cellCenter(row + 4, 2)) }
        compose.waitUntil(5_000) { !tools.selection.active }
        waitForNoText("Copy")
    }

    @Test
    fun `double-tap selects a word and double-tap-drag selects whole lines`() {
        val (session, tools) = stageDetached()
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val (row, col) = cellOf(session, "gitea/gitea:1.22")
        val at = cellCenter(row, col + 3)

        canvas.performTouchInput { click(at); click(at) }
        compose.waitUntil(5_000) { tools.selection.active }
        assertEquals("gitea/gitea:1.22", tools.selection.text(session.emulator))
        waitForText("16 chars")
        canvas.performTouchInput { click(cellCenter(row + 4, 2)) }
        compose.waitUntil(5_000) { !tools.selection.active }

        // A second tap that moves: whole lines from the tapped one to the finger, the wrapped prompt line as one.
        canvas.performTouchInput {
            click(at)
            down(at)
            moveTo(cellCenter(row + 2, col))
            up()
        }
        compose.waitUntil(5_000) { tools.selection.active && tools.selection.summary == "3 lines" }
        val lines = tools.selection.text(session.emulator).lines()
        assertEquals("gitea     gitea/gitea:1.22  Up 3 days", lines[0])
        assertEquals("postgres  postgres:16       Up 3 days", lines[1])
        assertEquals("demo@homelab:~/srv$ journalctl -u caddy -n 4 --no-pager", lines[2])
    }

    @Test
    fun `a drag held past the top edge scrolls history under the selection`() {
        val (session, tools) = stageDetached()
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val (row, col) = cellOf(session, "NAME")
        val sb = session.emulator.scrollbackSize
        val nameRow = sb + row

        longPress(cellCenter(row, col + 1)) { tools.selection.active }
        assertEquals("NAME", tools.selection.text(session.emulator))
        canvas.performTouchInput { moveTo(Offset(cellCenter(row, col).x, -20f)) }
        compose.mainClock.advanceTimeBy(370)
        compose.waitUntil(5_000) { tools.viewport.scrollOffset >= 5 }
        canvas.performTouchInput { up() }
        val scrolled = tools.viewport.scrollOffset
        val range = tools.selection.range!!
        assertTrue("the selection reaches the row now at the top", range.start.row <= sb - scrolled)
        assertTrue(range.start.row < nameRow)
        assertEquals(nameRow, range.end.row)
        val text = tools.selection.text(session.emulator)
        assertTrue(text.endsWith("NAME"))
        assertTrue(text.lines().size > 2)
        settle(200)
        capture("terminal-selection-scrollback")
    }

    @Test
    fun `select all takes every row, wide cells and wrapped lines whole, and a link offers Open`() {
        val (session, tools) = stageDetached()
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val url = "https://caddyserver.com/docs/"
        val (row, col) = cellOf(session, "docs at https")
        longPress(cellCenter(row, col + 10)) { tools.selection.active }
        canvas.performTouchInput { up() }
        // The link runs past the wrap; the word comes back whole and reads as one line.
        assertEquals(url, tools.selection.text(session.emulator))
        waitForText("${url.length} chars")
        compose.onNodeWithContentDescription("Selection options").performClick()
        waitForText("Open link")
        compose.onNodeWithText("Open link").performClick()
        compose.waitUntil(5_000) { !tools.selection.active }
        val opened = shadowOf(context as Application).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(url, opened.dataString)

        longPress(cellCenter(row, 2)) { tools.selection.active }
        canvas.performTouchInput { up() }
        compose.onNodeWithContentDescription("Selection options").performClick()
        waitForText("Select all")
        compose.onAllNodesWithText("Open link").assertCountEquals(0)
        compose.onNodeWithText("Select all").performClick()
        compose.waitUntil(5_000) { tools.selection.range?.start?.row == 0 }
        val range = tools.selection.range!!
        assertEquals(lastContentRow(session), range.end.row)
        val all = tools.selection.text(session.emulator)
        // Every row with content, from the top of history; the wide cells come out as their characters once each.
        assertTrue(all.startsWith("demo@homelab:~/srv$ journalctl -u caddy -f"))
        assertTrue(all.contains("\u540d\u524d: gitea \u72b6\u614b: \u7a3c\u50cd\u4e2d \u30dd\u30fc\u30c8: 3000"))
        // The lines that wrapped onto further rows are one line again.
        assertTrue(all.lines().any { it.endsWith("with automatic certificates from the internal CA") })
        assertTrue(all.lines().any { it.endsWith("docs at $url") })
        assertTrue(all.lines().none { it.endsWith(" ") })
        assertTrue(all.endsWith("demo@homelab:~/srv$"))
        assertEquals("${all.lines().size} lines", tools.selection.summary)
        // The end is at the last column: its handle is pulled inside the right edge, and a finger there takes it.
        assertHandlesInside(session, tools)
        val endSpot = handles(session, tools).second!!
        settle(200)
        // The start hangs off screen in history, so only the end handle draws.
        capture("terminal-select-all")
        canvas.performTouchInput { down(endSpot.center) }
        compose.waitUntil(5_000) { tools.selection.dragging == SelectionHandle.END }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.dragging == null }
        assertEquals(all, tools.selection.text(session.emulator))

        // Share hands the text to the system chooser and clears.
        compose.onNodeWithText("Share").performClick()
        compose.waitUntil(5_000) { !tools.selection.active }
        val shared = shadowOf(context as Application).nextStartedActivity
        assertNotNull(shared)
        assertEquals(Intent.ACTION_CHOOSER, shared.action)
    }

    @Test
    fun `handles on the bottom row flip above it and stay inside the canvas`() {
        val (session, tools) = stageDetached()
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val rows = session.emulator.rows
        // The restored frame leaves the cursor on a blank bottom row; a prompt typed there puts text on it.
        session.emulator.write("demo@homelab:~/srv$ tail -n 3 caddy/access.log")
        compose.waitUntil(5_000) { session.emulator.screenText().getOrNull(rows - 1)?.contains("access.log") == true }
        settle(300)
        val (row, col) = cellOf(session, "caddy/access.log")
        assertEquals("the prompt is on the bottom row", rows - 1, row)

        longPress(cellCenter(row, col + 8)) { tools.selection.active }
        canvas.performTouchInput { up() }
        assertEquals("caddy/access.log", tools.selection.text(session.emulator))
        // No room under the bottom row (the pill row and the Deck are there, and a touch there is
        // theirs): both handles sit above the row, shoulder down, inside the canvas.
        val (start, end) = handles(session, tools)
        assertTrue("the start handle flips above the row", start!!.above)
        assertTrue("the end handle flips above the row", end!!.above)
        assertHandlesInside(session, tools)
        settle(200)
        capture("terminal-selection-bottom-row")

        // The flipped handle takes the finger, and the end follows its travel on the same row: four
        // columns back shortens the word by four cells.
        canvas.performTouchInput { down(end.center) }
        compose.waitUntil(5_000) { tools.selection.dragging == SelectionHandle.END }
        canvas.performTouchInput { moveBy(Offset(-4 * paints().cellWidth, 0f)) }
        canvas.performTouchInput { up() }
        compose.waitUntil(5_000) { tools.selection.dragging == null && tools.selection.text(session.emulator) == "caddy/access" }
        assertEquals(row, tools.selection.range!!.end.row - (session.emulator.scrollbackSize - tools.viewport.scrollOffset))
    }

    // ---- paste with a preview (spec C18) ----------------------------------------------------------------

    @Test
    fun `paste on a detached tab is turned down with a notice`() {
        val (session, tools) = stageDetached()
        // Every route comes through the tools: without a shell the paste says Not connected and holds nothing for the sheet.
        compose.runOnIdle { tools.paste(session, "docker compose ps", null) }
        assertNull(tools.pendingPaste)
        assertEquals(StageTools.NOT_CONNECTED, tools.notice)
        waitForText("Not connected")
        compose.mainClock.advanceTimeBy(1_600)
        waitForNoText("Not connected")

        // The two-finger tap, the same: the notice, no sheet.
        setClipboard("echo one\necho two\n")
        compose.onNodeWithContentDescription("Terminal").performTouchInput {
            down(0, cellCenter(5, 5))
            down(1, cellCenter(5, 25))
            up(0)
            up(1)
        }
        waitForText("Not connected")
        assertNull(tools.pendingPaste)
        compose.onAllNodesWithText("Paste 2 lines").assertCountEquals(0)
        compose.mainClock.advanceTimeBy(1_600)
        waitForNoText("Not connected")

        // A sheet that was open when the tab stopped being Live: the sending buttons disable and the caption says why.
        compose.runOnIdle { tools.pendingPaste = PasteClassifier.analyze("cd /srv\ndocker compose pull\n\u001b[Aecho done\n") }
        waitForText("Paste 3 lines")
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Paste without control characters").assertIsNotEnabled()
        compose.onNodeWithText("Paste as is").assertIsNotEnabled()
        compose.onNodeWithText("Paste as one line").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        settle(300)
        capture("terminal-paste-preview-detached")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { tools.pendingPaste == null }
    }

    @Test
    fun `paste preview sheet`() {
        val (session, tools) = stageLive()
        // One plain line goes straight through, no sheet and no notice.
        compose.runOnIdle { tools.paste(session, "docker compose ps", null) }
        assertNull(tools.pendingPaste)
        assertNull(tools.notice)

        // Several lines, one with an escape in it: the sheet is a warning. The title's caption counts
        // and names the control characters, the filled button drops them, Paste as is stands plain
        // beside it, and the escape shows by its caret name rather than acting on the sheet.
        val text = "cd /srv\ndocker compose pull\ndocker compose up -d --remove-orphans\n\u001b[Aecho done\n"
        compose.runOnIdle { tools.paste(session, text, null) }
        waitForText("Paste 4 lines")
        compose.onNodeWithText("Has 1 control character (^[) the shell would act on.").assertIsDisplayed()
        compose.onNodeWithText("Bracketed paste is off: the shell will run 4 commands as the lines land.").assertIsDisplayed()
        waitForText("^[[Aecho done", substring = true)
        compose.onNodeWithText("Paste without control characters").assertIsEnabled()
        compose.onNodeWithText("Paste as is").assertIsEnabled()
        compose.onNodeWithText("Paste as one line").assertIsEnabled()
        compose.onAllNodesWithText("Paste").assertCountEquals(0)
        settle(300)
        capture("terminal-paste-preview")
        compose.onNodeWithText("Paste without control characters").performClick()
        compose.waitUntil(5_000) { tools.pendingPaste == null }
        waitForNoText("Paste 4 lines")

        // Without control characters the sheet is the spec's: Paste filled, Paste as one line, Cancel, and no warning.
        compose.runOnIdle { tools.paste(session, "echo one\necho two\n", null) }
        waitForText("Paste 2 lines")
        compose.onNodeWithText("Paste").assertIsEnabled()
        compose.onNodeWithText("Paste as one line").assertIsEnabled()
        compose.onAllNodesWithText("Paste as is").assertCountEquals(0)
        compose.onAllNodes(hasText("control character", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { tools.pendingPaste == null }

        // A long single line is looked at too, and Cancel keeps it off the wire.
        compose.runOnIdle { tools.paste(session, "x".repeat(300), null) }
        waitForText("Paste 300 characters")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { tools.pendingPaste == null }

        // A two-finger tap on the canvas pastes the clipboard, through the same look.
        setClipboard("echo one\necho two\n")
        compose.onNodeWithContentDescription("Terminal").performTouchInput {
            down(0, cellCenter(5, 5))
            down(1, cellCenter(5, 25))
            up(0)
            up(1)
        }
        waitForText("Paste 2 lines")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { tools.pendingPaste == null }
    }

    // ---- scrollback search (spec C17) --------------------------------------------------------------------

    @Test
    fun `search bar with matches highlighted`() {
        val (session, tools) = stageDetached()
        val before = tools.viewport.scrollOffset
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Find").performClick()
        compose.waitUntil(5_000) { tools.search.open }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Find in scrollback")).fetchSemanticsNodes().isNotEmpty() }
        field("Find in scrollback").performTextInput("caddy")
        compose.waitUntil(5_000) { tools.search.matches.isNotEmpty() }
        settle(300)
        val total = tools.search.matches.size
        val top = session.emulator.scrollbackSize
        val inView = tools.search.matches.count { it.start.row - top in 0 until session.emulator.rows }
        assertTrue("matches in view: $inView of $total", inView in 1 until total)
        // The current match is the last one, nearest the prompt.
        assertEquals("$total/$total", tools.search.countLabel)
        compose.onNodeWithContentDescription("Matches $total/$total").assertIsDisplayed()
        capture("terminal-search")

        // Aa: `Caddyfile` stops matching.
        compose.onNodeWithContentDescription("Match case").performClick()
        compose.waitUntil(5_000) { tools.search.matches.size == total - 1 }
        compose.onNodeWithContentDescription("Match case").performClick()
        compose.waitUntil(5_000) { tools.search.matches.size == total }

        // `.*` with a pattern that does not compile (`caddy[`, half a class) finds the literal text,
        // every `caddy[812]` line, rather than the red 0 of no matches, and the `.*` glyph goes to the
        // danger colour to say so; closed, the class is a pattern again, the glyph is the accent again,
        // and `caddy` before a digit is nowhere.
        val invalid = hasContentDescription("Regular expression") and hasStateDescription(INVALID_PATTERN)
        compose.onNodeWithContentDescription("Regular expression").performClick()
        compose.waitUntil(5_000) { tools.search.regex }
        compose.onAllNodes(invalid).assertCountEquals(0)
        field("Find in scrollback").performTextInput("[")
        // The run settles behind the field, so wait for the count to move off `caddy`'s before reading it.
        compose.waitUntil(5_000) { tools.search.query == "caddy[" && tools.search.matches.size != total }
        assertEquals(FRAME_LINES.count { it.contains("caddy[") }, tools.search.matches.size)
        compose.onNode(invalid).assertIsDisplayed()
        settle(200)
        capture("terminal-search-invalid-pattern")
        field("Find in scrollback").performTextInput("0-9]")
        compose.waitUntil(5_000) { tools.search.query == "caddy[0-9]" && tools.search.matches.isEmpty() }
        assertEquals("0", tools.search.countLabel)
        compose.onAllNodes(invalid).assertCountEquals(0)
        field("Find in scrollback").performTextClearance()
        field("Find in scrollback").performTextInput("caddy")
        compose.onNodeWithContentDescription("Regular expression").performClick()
        compose.waitUntil(5_000) { !tools.search.regex && tools.search.query == "caddy" && tools.search.matches.size == total }
        compose.waitUntil(5_000) { tools.search.countLabel == "$total/$total" }
        compose.onAllNodes(invalid).assertCountEquals(0)

        // Previous steps back through the matches and into history once they leave the screen.
        var steps = 0
        while (tools.viewport.scrollOffset == 0 && steps < total) {
            compose.onNodeWithContentDescription("Previous match").performClick()
            compose.waitForIdle()
            steps++
        }
        assertTrue("the view scrolled into history after $steps steps", tools.viewport.scrollOffset > 0)
        assertEquals("${total - steps}/$total", tools.search.countLabel)
        settle(300)
        capture("terminal-search-history")

        // Nothing matching says 0 in the danger colour; close puts the view back where it was.
        field("Find in scrollback").performTextInput("zzz")
        compose.waitUntil(5_000) { tools.search.matches.isEmpty() && tools.search.query == "caddyzzz" }
        assertEquals("0", tools.search.countLabel)
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitUntil(5_000) { !tools.search.open }
        assertEquals(before, tools.viewport.scrollOffset)
    }

    @Test
    fun `search for this from the selection bar opens the search prefilled`() {
        val (session, tools) = stageDetached()
        val (row, col) = cellOf(session, "postgres:16")
        longPress(cellCenter(row, col + 2)) { tools.selection.active }
        compose.onNodeWithContentDescription("Terminal").performTouchInput { up() }
        compose.onNodeWithText("Search").performClick()
        compose.waitUntil(5_000) { tools.search.open && !tools.selection.active }
        assertEquals("postgres:16", tools.search.query)
        compose.waitUntil(5_000) { tools.search.matches.size == 1 }
        assertEquals("1/1", tools.search.countLabel)
    }

    // ---- the grid changing under a selection and a search (spec C17, C18) -------------------------------------

    /** Waits for a bar to take height from the canvas and rows from the grid with it, naming what did not move. */
    private fun awaitFewerRows(session: TerminalSession, canvas: SemanticsNodeInteraction, heightBefore: Int, rowsBefore: Int) {
        try {
            // Every frame of the bar's expansion redraws the terminal through native graphics, so this is wall time.
            compose.waitUntil(10_000) { canvas.fetchSemanticsNode().size.height < heightBefore && session.emulator.rows < rowsBefore }
        } catch (e: ComposeTimeoutException) {
            val size = canvas.fetchSemanticsNode().size
            throw AssertionError("the canvas is $size from a height of $heightBefore; the grid ${session.emulator.cols}x${session.emulator.rows} from $rowsBefore rows", e)
        }
    }

    /** The logical line under the search's current match, which a re-wrap moves but does not change. */
    private fun matchLine(session: TerminalSession, tools: StageTools): String {
        val range = tools.search.currentRange(session.emulator)!!
        return synchronized(session.emulator.lock) {
            val grid = session.emulator.grid
            TerminalText.extract(grid, TerminalText.snapToLine(grid, range.start))
        }
    }

    @Test
    fun `a selection carries through a change of height and ends with a change of width, and a search keeps its place through both`() {
        val (session, tools) = stageDetached()
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val (row, col) = cellOf(session, "gitea/gitea:1.22")
        val rowsBefore = session.emulator.rows
        val colsBefore = session.emulator.cols

        // A word selected; the search bar opening under the selection bar takes rows from the grid, the
        // way the keyboard or the Deck does, and the selection holds with its text.
        longPress(cellCenter(row, col + 3)) { tools.selection.active }
        canvas.performTouchInput { up() }
        assertEquals("gitea/gitea:1.22", tools.selection.text(session.emulator))
        val heightBefore = canvas.fetchSemanticsNode().size.height
        compose.runOnIdle { tools.openSearch() }
        awaitFewerRows(session, canvas, heightBefore, rowsBefore)
        settle(400)
        assertEquals(colsBefore, session.emulator.cols)
        assertTrue(tools.selection.active)
        assertEquals("gitea/gitea:1.22", tools.selection.text(session.emulator))
        compose.onNodeWithText("Copy").assertIsDisplayed()

        // The search finds its matches and steps back three.
        field("Find in scrollback").performTextInput("caddy")
        compose.waitUntil(5_000) { tools.search.matches.isNotEmpty() }
        val total = tools.search.matches.size
        repeat(3) {
            compose.onNodeWithContentDescription("Previous match").performClick()
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) { tools.search.countLabel == "${total - 3}/$total" }
        val matched = matchLine(session, tools)

        // A bigger font, as a pinch step gives: fewer columns, history re-wrapped. The selection ends and
        // the bar with it, rather than standing over nothing with Copy giving nothing; the search keeps
        // its third-from-last by index, and the same line is under it.
        val font = runBlocking { graph.settings.terminalFont.first() }
        compose.runOnIdle { graph.viewModel.setFontSize(font.sizeSp + 4) }
        compose.waitUntil(10_000) { session.emulator.cols < colsBefore }
        settle(500)
        compose.waitUntil(5_000) { !tools.selection.active }
        waitForNoText("Copy")
        compose.waitUntil(5_000) { tools.search.matches.size == total && tools.search.countLabel == "${total - 3}/$total" }
        assertEquals(matched, matchLine(session, tools))
        settle(200)
        capture("terminal-search-after-resize")
    }

    // ---- command history (spec C16) ------------------------------------------------------------------------

    @Test
    fun `history sheet`() {
        val (session, tools) = stageDetached(withHistory = true)
        assertEquals(HISTORY.size, session.commands.value.size)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("History").performClick()
        compose.waitUntil(5_000) { tools.historyOpen }
        waitForText("${HISTORY.size} commands", substring = true)
        waitForText("docker compose ps")
        settle(300)
        capture("terminal-history")

        // A long-press on a detached tab's entry: Copy, Save as snippet and Delete; Run and Paste need the session Live.
        compose.onNode(hasContentDescription("Command docker compose ps")).performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("Save as snippet")
        compose.onAllNodesWithText("Run").assertCountEquals(0)
        capture("terminal-history-menu")
        compose.onNodeWithText("Copy").performClick()
        compose.waitUntil(5_000) { clipboardText() == "docker compose ps" && tools.notice == "Copied" }
        assertBerthsClip("docker compose ps")

        compose.onNode(hasContentDescription("Command ls -la")).performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("Delete")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { session.commands.value.size == HISTORY.size - 1 }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Command ls -la")).fetchSemanticsNodes().isEmpty() }
        // The deleted entry is out of the next frame; the others travel with it.
        val kept = historyIn(session.snapshotFrame())
        assertFalse(kept.contains("ls -la"))
        assertTrue(kept.contains("docker compose ps"))

        // The filter narrows the list.
        field("Search history").performTextInput("no-pager")
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Command ", substring = true)).fetchSemanticsNodes().size == 1 }

        // Save as snippet: the editor opens with the command as the body and a name made from it.
        val command = "journalctl -u caddy -n 4 --no-pager"
        compose.onNode(hasContentDescription("Command $command")).performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("Save as snippet")
        compose.onNodeWithText("Save as snippet").performClick()
        waitForText("New snippet")
        compose.onAllNodes(hasSetTextAction() and hasText(command)).assertCountEquals(2)
        settle(300)
        capture("terminal-history-snippet")
    }

    // ---- the app lock over the tools (spec C20) -------------------------------------------------------------

    /**
     * The tools through the app lock, the two windows stacked as AppRoot stacks them (spec C20): the
     * shell stays composed under the in-window cover and the lock window, so a selection, a search
     * stepped off its last match and a paste held for the preview are where they were when the lock
     * lifts, the way an edit in progress is. While the lock is up the cover takes the canvas' touches,
     * so a tap that would dismiss the selection does not reach it. The Copy that follows the unlock
     * is Berth's own copy, token and all, the way the security wave routes it.
     */
    @Test
    fun `a selection, a search and a pending paste stand through the lock`() {
        seedHomelab(withHistory = false)
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        graph.settings.security.value = SecuritySettings(appLock = true, lockTimeout = LockTimeout.IMMEDIATELY)
        // A launch with the lock on: the shell composes under the cover, its prompt passes at once.
        graph.appLock.onForeground()
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED)
        val tools = StageTools()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val lock by graph.appLock.state.collectAsState()
                    if (lock != LockState.UNKNOWN) Stage(session, tools)
                    if (lock == LockState.LOCKED) LockCover()
                }
            }
            LockWindow(graph.security, InterfaceTheme.DEFAULT)
        }
        compose.waitUntil(5_000) { graph.appLock.state.value == LockState.UNLOCKED }
        awaitGrid(session)
        val canvas = compose.onNodeWithContentDescription("Terminal")

        // A word selected, the search on `caddy` stepped back off its last match, and a paste held.
        val (row, col) = cellOf(session, "gitea/gitea:1.22")
        longPress(cellCenter(row, col + 3)) { tools.selection.active }
        canvas.performTouchInput { up() }
        waitForText("16 chars")
        // Opened from the test rather than a tap: the write reaches the canvas' search effect once the
        // looper has delivered it, which waitForIdle does and a bare waitUntil does not.
        compose.runOnIdle { tools.openSearch("caddy") }
        compose.waitForIdle()
        compose.waitUntil(5_000) { tools.search.matches.isNotEmpty() }
        val total = tools.search.matches.size
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Previous match")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Previous match").performClick()
        val stepped = "${total - 1}/$total"
        compose.waitUntil(5_000) { tools.search.countLabel == stepped }
        compose.runOnIdle { tools.pendingPaste = PasteClassifier.analyze("cd /srv\ndocker compose pull\n") }
        waitForText("Paste 2 lines")
        val selected = tools.selection.text(session.emulator)

        // Leaving and coming back with "Immediately": the lock falls over the Stage with all three up.
        graph.appLock.onBackground(changingConfigurations = false)
        graph.appLock.onForeground()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { graph.authenticator.pending }
        // The shell held its state under the cover: the reads survive, none cleared by the lock.
        assertTrue("the selection stands through the lock", tools.selection.active)
        assertEquals("the search keeps its match through the lock", stepped, tools.search.countLabel)
        assertNotNull("the paste is still held through the lock", tools.pendingPaste)
        // A tap where the canvas is is the cover's, not the terminal's, so the selection is not dismissed.
        canvas.performTouchInput { click(cellCenter(row + 4, 2)) }
        compose.waitForIdle()
        assertTrue("the cover took the tap that would have dismissed the selection", tools.selection.active)

        // Unlocked: the bar with its summary, the search at its match, the sheet with its lines, the
        // handles inside the canvas; then Copy is Berth's copy, token and all.
        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("16 chars").assertIsDisplayed()
        compose.onNodeWithContentDescription("Matches $stepped").assertIsDisplayed()
        compose.onNodeWithText("Paste 2 lines").assertIsDisplayed()
        assertEquals(selected, tools.selection.text(session.emulator))
        assertHandlesInside(session, tools)
        settle(200)
        capture("terminal-tools-survive-lock")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { tools.pendingPaste == null }
        compose.onNodeWithText("Copy").performClick()
        waitForText("Copied")
        assertBerthsClip(selected)
        assertFalse(tools.selection.active)
    }

    // ---- live flow against the local sshd ------------------------------------------------------------------

    @Test
    fun `terminal tools live flow`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = Host(
            id = "berth-test-box",
            name = "Berth test box",
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor("Berth test box"),
            address = sshHost,
            port = sshPort,
            user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("berth-test-box")),
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("New tab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New tab").performClick()
        waitForText("Berth test box")
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val session = graph.sessions.activeSession.value!!
        settle(1_200)
        val canvas = compose.onNodeWithContentDescription("Terminal")
        val barShown = { compose.onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }

        // Real output on the screen: a listing whose rows stay under the canvas's width (the root
        // directory's run to 68 columns and wrap), then a line to find again from the history sheet.
        session.sendText("mkdir -p ~/berth-demo && touch ~/berth-demo/{alpha,beta,gamma} && clear && ls --color=always -la ~/berth-demo\n")
        settle(1_500)
        session.sendText("echo re-run from history\n")
        settle(1_200)
        compose.waitUntil(5_000) { session.commands.value.any { it.text == "echo re-run from history" } }
        // The owner column of the listing is the test user, whatever the sshd calls it.
        val owner = " $sshUser "
        assertTrue(session.emulator.screenText().any { it.contains(owner) })

        // Long-press on the owner column: the word, then two more rows of the listing by dragging. On a
        // Live tab the bar offers Paste.
        val (row, col) = cellOf(session, owner)
        longPress(cellCenter(row, col + 2), barShown)
        waitForText("${sshUser.length} chars")
        compose.onNodeWithText("Paste").assertIsDisplayed()
        canvas.performTouchInput { moveTo(cellCenter(row + 2, col + 2)) }
        canvas.performTouchInput { up() }
        waitForText("3 lines")
        settle(300)
        capture("terminal-live-selection")
        compose.onNodeWithText("Copy").performClick()
        waitForText("Copied")
        val copied = clipboardText()!!
        assertBerthsClip(copied)
        assertEquals(3, copied.lines().size)
        assertTrue(copied.lines().all { it.contains(sshUser) })
        assertTrue(copied.lines().none { it.endsWith(" ") })
        capture("terminal-live-copied")
        waitForNoText("Copy")

        // Select a word and search for it: the bar opens with the word, every owner cell lights up.
        longPress(cellCenter(row, col + 2), barShown)
        canvas.performTouchInput { up() }
        waitForText("${sshUser.length} chars")
        compose.onNodeWithText("Search").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Matches ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Matches none")).fetchSemanticsNodes().isEmpty() }
        settle(400)
        capture("terminal-live-search")
        compose.onNodeWithContentDescription("Previous match").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Close search")).fetchSemanticsNodes().isEmpty() }

        // A multi-line clipboard pasted from the selection bar goes to the preview first; bash 5 has bracketed paste on.
        setClipboard("echo one\necho two\n")
        longPress(cellCenter(row, col + 2), barShown)
        canvas.performTouchInput { up() }
        compose.onNodeWithText("Paste").performClick()
        waitForText("Paste 2 lines")
        compose.onNodeWithText("The shell has bracketed paste on, so the text lands as one block.").assertIsDisplayed()
        settle(300)
        capture("terminal-live-paste-preview")
        compose.onNodeWithText("Cancel").performClick()
        waitForNoText("Paste 2 lines")

        // A clipboard carrying an escape and a ^C: the sheet is a warning that counts and names them, and
        // its filled button drops them. What lands at the prompt is the text alone; Enter runs it.
        setClipboard("echo pasted\u001b\u0003 clean")
        longPress(cellCenter(row, col + 2), barShown)
        canvas.performTouchInput { up() }
        compose.onNodeWithText("Paste").performClick()
        waitForText("Paste 19 characters")
        compose.onNodeWithText("Has 2 control characters (^[, ^C) the shell would act on.").assertIsDisplayed()
        compose.onNodeWithText("Paste as is").assertIsDisplayed()
        compose.onAllNodesWithText("Paste as one line").assertCountEquals(0)
        settle(300)
        capture("terminal-live-paste-control")
        compose.onNodeWithText("Paste without control characters").performClick()
        waitForNoText("Paste 19 characters")
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.contains("echo pasted clean") } }
        session.sendKey(TerminalKey.ENTER)
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.trim() == "pasted clean" } }
        settle(400)

        // History: the echoed command is there; Run sends it again and its output lands a second time.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("History").performClick()
        waitForText("echo re-run from history")
        settle(300)
        capture("terminal-live-history")
        val outputsBefore = session.emulator.screenText().count { it.trim() == "re-run from history" }
        compose.onNode(hasContentDescription("Command echo re-run from history")).performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("Run")
        compose.onNodeWithText("Run").performClick()
        compose.waitUntil(10_000) { session.emulator.screenText().count { it.trim() == "re-run from history" } == outputsBefore + 1 }
        settle(400)
        capture("terminal-live-history-ran")

        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private val homelab = Host(
        id = "homelab",
        name = "homelab",
        color = SwatchColor.VERDIGRIS,
        monogram = Host.monogramFor("homelab"),
        address = "192.168.1.20",
        user = "demo",
        auth = AuthMethod.Password("host-password:homelab"),
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18),
        createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun homelabRecord(state: SessionState, layer: PersistenceLayer) = SessionRecord(
        id = "s-homelab",
        workspaceId = Workspace.DEFAULT_ID,
        hostId = homelab.id,
        hostSnapshot = homelab,
        state = state,
        layer = layer,
        title = homelab.name,
        cwd = "~/srv",
        lastCommand = "journalctl -u caddy -n 4 --no-pager",
        sortOrder = 0,
        createdAt = now - TimeUnit.HOURS.toMillis(5),
        lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
    )

    private fun seedHomelab(withHistory: Boolean) = runBlocking {
        graph.hosts.upsert(homelab)
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.sessionRecords.upsert(homelabRecord(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME))
        val history = if (withHistory) HISTORY.map { (text, minutesAgo) -> text to now - TimeUnit.MINUTES.toMillis(minutesAgo) } else emptyList()
        graph.sessionRecords.saveFrame("s-homelab", frame(FRAME_LINES, history))
    }

    /** A version-2 frame: the text, then the history the session had. */
    private fun frame(lines: List<String>, history: List<Pair<String, Long>>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(2)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
            d.writeInt(history.size)
            for ((text, at) in history) {
                d.writeUTF(text)
                d.writeLong(at)
            }
        }
        return out.toByteArray()
    }

    /** The commands a version-2 frame carries. */
    private fun historyIn(frame: ByteArray): List<String> = DataInputStream(frame.inputStream()).use { d ->
        assertEquals(2, d.readInt())
        repeat(d.readInt()) { d.readUTF() }
        List(d.readInt()) { d.readUTF().also { d.readLong() } }
    }

    companion object {
        /** Eight commands, oldest first, as minutes ago: two days for the headings, and enough for the sheet's filter field. */
        private val HISTORY: List<Pair<String, Long>> = listOf(
            "cd ~/srv" to 26 * 60L,
            "docker compose pull" to 26 * 60L - 1,
            "docker compose up -d" to 25 * 60L,
            "cat compose.yaml" to 24 * 60L + 50,
            "journalctl -u caddy -f" to 50L,
            "ls -la" to 40L,
            "docker compose ps" to 30L,
            "journalctl -u caddy -n 4 --no-pager" to 2L,
        )

        /**
         * Enough for history behind the screen at any phone's row count, log lines long enough to
         * wrap at the canvas's width, wide (CJK) cells, and a link past a wrap. `caddy` appears in
         * every log line and in `Caddyfile` for the case toggle; `gitea/gitea:1.22` and
         * `postgres:16` are one word each. The rows the gestures land on stay under 46 columns.
         */
        private val FRAME_LINES: List<String> = buildList {
            add("demo@homelab:~/srv$ journalctl -u caddy -f")
            for (i in 0 until 48) {
                val sec = 10 + i
                val min = 41 + sec / 60
                val path = listOf("/repo/berth/commits", "/api/v1/user", "/assets/app.css", "/repo/berth/pulls/12", "/login", "/explore")[i % 6]
                val status = if (i % 11 == 7) 304 else 200
                add("Sep 19 19:%02d:%02d homelab caddy[812]: GET %s %d %dms".format(min, sec % 60, path, status, 3 + (i * 7) % 40))
            }
            add("^C")
            add("demo@homelab:~/srv$ ls -la")
            add("total 48")
            add("drwxr-xr-x  6 demo demo 4096 Sep 19 19:40 .")
            add("drwxr-xr-x 12 demo demo 4096 Sep 18 08:12 ..")
            add("drwxr-xr-x  3 demo demo 4096 Sep 19 19:40 caddy")
            add("-rw-r--r--  1 demo demo 2210 Sep 19 19:38 Caddyfile")
            add("-rw-r--r--  1 demo demo 1830 Sep 17 22:05 compose.yaml")
            add("drwxr-xr-x  4 demo demo 4096 Sep 16 10:20 gitea")
            add("drwxr-xr-x  2 demo demo 4096 Sep 16 10:20 postgres")
            add("demo@homelab:~/srv$ docker compose ps")
            add("NAME      IMAGE             STATUS")
            add("caddy     caddy:2           Up 3 days")
            add("gitea     gitea/gitea:1.22  Up 3 days")
            add("postgres  postgres:16       Up 3 days")
            add("demo@homelab:~/srv$ journalctl -u caddy -n 4 --no-pager")
            add("Sep 19 19:58:01 homelab caddy[812]: serving https://git.homelab.lan on :443 with automatic certificates from the internal CA")
            add("Sep 19 19:58:02 homelab caddy[812]: \u540d\u524d: gitea \u72b6\u614b: \u7a3c\u50cd\u4e2d \u30dd\u30fc\u30c8: 3000")
            add("Sep 19 19:58:03 homelab caddy[812]: reverse_proxy upstream gitea:3000 healthy")
            add("Sep 19 19:58:04 caddy[812]: docs at https://caddyserver.com/docs/")
            add("demo@homelab:~/srv$ ")
        }
    }
}
