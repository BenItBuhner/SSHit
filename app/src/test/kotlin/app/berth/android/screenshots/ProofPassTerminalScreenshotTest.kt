package app.berth.android.screenshots

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_J
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.KeyEvent.META_SHIFT_ON
import android.view.MotionEvent
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshotDir
import app.berth.android.session.AuthResolver
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.components.LocalWallClock
import app.berth.android.ui.keyboard.KeyLayout
import app.berth.android.ui.keyboard.LocalVolumeKeys
import app.berth.android.ui.keyboard.ShadowKeyLayout
import app.berth.android.ui.keyboard.VolumeKeys
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalPaintsCache
import app.berth.android.ui.theme.berthColors
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.ClipboardClear
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.VolumeButtons
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.SshAuth
import app.berth.ssh.SshConnection
import app.berth.ssh.SshEndpoint
import app.berth.ssh.SshSecurity
import app.berth.terminal.Attr
import app.berth.terminal.MouseTracking
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The terminal rows of the v1 proof pass, from the real app against the local sshd, each a frame in
 * which the behaviour and what set it off are both on screen:
 *
 * - C5 and C6, CSI u and the mouse: `cat -v` shows what a chord and a click, a wheel notch and a
 *   trackpad swipe put on the wire once the program has asked for them.
 * - A55 and A56, mouse reporting and bracketed paste: a tap on htop's function bar opens its Sort
 *   panel; a paste under `?2004h` lands between `^[[200~` and `^[[201~`.
 * - A62 and A63, OSC 9, 777 and 99 and OSC 133: a `printf` into another tab's tty lights that tab;
 *   a prompt-marked command the typed-line fallback could not have read is in the history, and one
 *   that ran eleven seconds off stage lights its tab.
 * - A64 and A66, wide characters and ligatures: CJK and emoji over a ruler, two cells each, and one
 *   line of operators drawn with the font's ligatures and without, Settings' switch between.
 * - A72 to A74, the interface picks: the Stage drawn in Black and in Light, and a latched Ctrl in
 *   the Material You accent, each picked in Settings on the way from the tab.
 * - A24 and A25, the tmux helper and the Deck's tmux layer: a host on Attach or create keeps its
 *   shell through a dropped link, and the layer's keys drive tmux.
 * - A43, the volume buttons: Volume Up under Up and Down arrows recalls the last command.
 * - A41, the Deck's haptics, which no frame shows: what the Deck asks of the platform for a key
 *   tap and Ctrl's latches, as Settings' Haptics level moves from Full to Subtle to Off; the
 *   frames are the taps and the level they were made on.
 * - A76, clipboard auto-clear: a word copied off the screen pastes at once, and 30 s later the
 *   same paste gives nothing.
 *
 * Every case is skipped unless `SSH_TEST_*` is set; the tmux and htop cases also need those tools on
 * the sshd, which the local one has and CI's does not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ProofPassTerminalScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = screenshotDir
    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = FIXED_NOW

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    /** The activity's volume buttons, as MainActivity hands them to the Stage. */
    private val volumeKeys = VolumeKeys()

    /** What the tabs sent to the remote, in order, from each session's send hook. */
    private val sent = CopyOnWriteArrayList<String>()

    /** What the app asked of the platform's haptics, in order, each with the main clock's time then. */
    private val buzzes = CopyOnWriteArrayList<Pair<HapticFeedbackType, Long>>()

    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            buzzes += hapticFeedbackType to compose.mainClock.currentTime
        }
    }

    private val closeables = ArrayList<Closeable>()

    /** The tmux session a case made on the sshd, killed after it whether it passed or not. */
    private var tmuxSession: String? = null

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        graph.close()
        closeables.forEach { runCatching { it.close() } }
        tmuxSession?.let { name -> runCatching { exec("tmux kill-session -t '$name' 2>/dev/null; true") } }
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun waitForText(text: String, timeout: Long = 5_000) {
        try {
            compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { t -> t.text } ?: emptyList() }
            throw AssertionError("'$text' never showed; the texts on screen were $shown", e)
        }
    }

    private fun screen(session: TerminalSession): List<String> = synchronized(session.emulator.lock) { session.emulator.screenText() }

    private fun awaitScreen(session: TerminalSession, what: String, timeout: Long = 10_000, condition: (List<String>) -> Boolean) {
        try {
            compose.waitUntil(timeout) { condition(screen(session)) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what never showed in the terminal; its screen was ${screen(session).filter { it.isNotBlank() }}", e)
        }
    }

    private fun awaitOnScreen(session: TerminalSession, text: String, timeout: Long = 10_000) =
        awaitScreen(session, "'$text'", timeout) { rows -> rows.any { it.contains(text) } }

    /** A command run on the sshd over a login of the test's own, apart from the app's tabs. */
    private fun exec(command: String): String = runBlocking {
        val endpoint = SshEndpoint(host = sshHost, port = sshPort, user = sshUser, auth = listOf(SshAuth.Password { sshPassword.toCharArray() }), keepaliveSeconds = 5)
        val connection = SshConnection(endpoint, AcceptAllHostKeys)
        connection.connect()
        try {
            connection.exec(command)
        } finally {
            connection.close()
        }
    }

    private fun assumeRemote(tool: String) =
        assumeTrue("$tool is not installed on the test sshd", exec("command -v $tool >/dev/null && echo found || echo missing").trim() == "found")

    private fun testHost(name: String, address: String = sshHost, port: Int = sshPort, persistence: PersistencePolicy = PersistencePolicy()): Host {
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        return Host(
            id = id,
            name = name,
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor(name),
            address = address,
            port = port,
            user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId(id)),
            persistence = persistence,
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
    }

    /**
     * The real app with [box] saved and its password with it, the process in front (so the tab on
     * stage is on stage), the activity's volume buttons provided as MainActivity provides them and
     * the platform's haptics as [haptics], which keeps what is asked of it.
     */
    private fun mountApp(box: Host) {
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent {
            CompositionLocalProvider(LocalWallClock provides { now }, LocalVolumeKeys provides volumeKeys, LocalHapticFeedback provides haptics) {
                AppRoot(graph.viewModel)
            }
        }
        graph.process.start()
    }

    /**
     * A new tab on [box] through the strip's New tab, its host key trusted the first time it is
     * seen, and Live; the session's sends are copied into [sent].
     */
    private fun newTab(box: Host): TerminalSession {
        val before = graph.sessions.sessions.value.map { it.id }.toSet()
        fun opened() = graph.sessions.sessions.value.firstOrNull { it.id !in before }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("New tab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New tab").performClick()
        waitForText(box.name)
        // After the first tab the host is also a Recent swatch; both call the same open.
        compose.onAllNodes(hasText(box.name)).onFirst().performClick()
        compose.waitUntil(30_000) { graph.prompts.current.value is Prompt.TrustHostKey || opened()?.state == SessionState.LIVE }
        if (graph.prompts.current.value is Prompt.TrustHostKey) compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { opened()?.state == SessionState.LIVE }
        val session = opened()!!
        session.sendObserver = { sent += String(it, Charsets.UTF_8) }
        awaitGrid(session)
        compose.settle(1_000)
        return session
    }

    private fun paints(): TerminalPaints {
        val res = context.resources
        val font = runBlocking { graph.settings.terminalFont.first() }
        return TerminalPaintsCache.get(context, font, res.displayMetrics.density, if (font.followSystemScale) res.configuration.fontScale else 1f)
    }

    /** The centre of the view cell at [row], [col] in the canvas node's coordinates. */
    private fun cellCenter(row: Int, col: Int): Offset {
        val p = paints()
        return Offset((col + 0.5f) * p.cellWidth, (row + 0.5f) * p.cellHeight)
    }

    /** The view row and column where [token] first shows on the screen. */
    private fun cellOf(session: TerminalSession, token: String): Pair<Int, Int> {
        val rows = screen(session)
        val row = rows.indexOfFirst { it.contains(token) }
        assertTrue("'$token' is on screen in $rows", row >= 0)
        return row to rows[row].indexOf(token)
    }

    /** Waits for the canvas to size the grid to itself, so cells map to pixels. */
    private fun awaitGrid(session: TerminalSession) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) {
            val p = paints()
            val size = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().size
            session.emulator.cols == (size.width / p.cellWidth).toInt() && session.emulator.rows == (size.height / p.cellHeight).toInt()
        }
    }

    /** Runs [command] and waits for [until] on the screen. */
    private fun run(session: TerminalSession, command: String, until: String) {
        session.sendText("$command\n")
        awaitOnScreen(session, until)
        compose.settle(400)
    }

    /** Sends [command] without waiting on its echo, which may wrap; the caller waits on what it does. */
    private fun start(session: TerminalSession, command: String) = session.sendText("$command\n")

    /** Clears the screen and waits for the prompt to stand alone on it. */
    private fun clear(session: TerminalSession) {
        session.sendText("clear\n")
        awaitScreen(session, "a cleared screen") { rows -> rows.count { it.isNotBlank() } == 1 && rows.first().trimEnd().endsWith("$") }
    }

    private val canvas get() = compose.onNodeWithTag(TerminalTag)

    private fun Int.dpPx(): Float = with(compose.density) { this@dpPx.dp.toPx() }

    // ---- C6 and A55, mouse and trackpad; mouse reporting into a TUI ---------------------------------

    /**
     * Mouse reporting as `cat -v` reads it (vision §5.5, C6): with `?1000` and SGR `?1006` asked
     * for, a finger's tap, a mouse's left click, its wheel, its right button and a trackpad's
     * two-finger swipe each reach the program as a report of the cell under the pointer, echoed at
     * the line and printed again by `cat -v` on Enter.
     */
    @Test
    fun `C06 a tap, a click, the wheel, the right button and a trackpad swipe reach the program as mouse reports`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        clear(session)
        start(session, "printf '\\e[?1000h\\e[?1006h'; head -n1 | cat -v; printf '\\e[?1000l\\e[?1006l'")
        compose.waitUntil(5_000) { session.emulator.mouseTracking == MouseTracking.NORMAL }
        sent.clear()

        val at = cellCenter(6, 10)
        canvas.performTouchInput { click(at) }
        compose.settle(600)
        canvas.performMouseInput { click(at) }
        canvas.performMouseInput { moveTo(at); scroll(-1f) }
        canvas.performMouseInput { moveTo(at); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        trackpadSwipe(dy = paints().cellHeight * 1.2f)
        compose.waitUntil(5_000) { sent.size >= 8 }
        compose.settle(400)
        val reports = sent.joinToString("")
        assertTrue("a tap is a left press and release at row 7, column 11: $reports", reports.startsWith("\u001b[<0;11;7M\u001b[<0;11;7m\u001b[<0;11;7M\u001b[<0;11;7m"))
        assertTrue("the wheel up is button 64: $reports", "\u001b[<64;11;7M" in reports)
        assertTrue("the right button is 2: $reports", "\u001b[<2;11;7M\u001b[<2;11;7m" in reports)
        assertTrue("the swipe is wheel reports: $reports", reports.substringAfter("\u001b[<2;11;7m").startsWith("\u001b[<6"))
        session.sendKey(TerminalKey.ENTER)
        awaitScreen(session, "cat -v's copy of the reports", 10_000) { rows -> rows.count { it.contains("^[[<0;11;7M^[[<0;11;7m") } >= 2 }
        compose.settle(600)
        capture("C06-mouse-reports")
    }

    /** A trackpad's two-finger swipe down by [dy] pixels, as Android sends one: a finger classified as a swipe, the distance to scroll on its axis. */
    private fun trackpadSwipe(dy: Float, steps: Int = 6) {
        val root = compose.activity.window.decorView
        val node = canvas.fetchSemanticsNode()
        val at = node.positionInWindow + Offset(node.size.width / 2f, node.size.height / 2f)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val t0 = SystemClock.uptimeMillis()
        var y = at.y
        fun send(action: Int, t: Long, distance: Float) {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = at.x; this.y = y; setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, distance) })
            val ev = MotionEvent.obtain(t0, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0, 0, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)!!
            compose.runOnUiThread { root.dispatchTouchEvent(ev) }
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, t0, 0f)
        repeat(steps) { i ->
            y += dy / steps
            send(MotionEvent.ACTION_MOVE, t0 + 16L * (i + 1), -dy / steps)
        }
        send(MotionEvent.ACTION_UP, t0 + 16L * (steps + 1), 0f)
        compose.settle(200)
    }

    /**
     * A tap in htop (spec A55): htop asks for the mouse, and a finger's tap on `SortBy` in its
     * function bar is the click that opens its Sort by panel, whose bar then reads `EscCancel`;
     * the frame before shows the bar the tap lands on.
     */
    @Test
    fun `A55 a tap on htop's SortBy label is a click that opens its Sort by panel`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeRemote("htop")
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        session.sendText("htop\n")
        awaitOnScreen(session, "F6SortBy", 15_000)
        compose.waitUntil(5_000) { session.emulator.mouseTracking != MouseTracking.NONE }
        compose.settle(1_200)
        val (row, col) = cellOf(session, "SortBy")
        capture("A55-htop-before-tap")

        canvas.performTouchInput { click(cellCenter(row, col + 2)) }
        awaitOnScreen(session, "EscCancel", 10_000)
        assertTrue("the Sort by panel is open", screen(session).any { it.startsWith("Sort by") })
        compose.settle(800)
        capture("A55-htop-sortby-tap")

        session.sendKey(TerminalKey.ESCAPE)
        compose.settle(400)
        session.sendText("q")
        compose.waitUntil(10_000) { !session.emulator.isAlternateScreen }
    }

    // ---- A56, bracketed paste ----------------------------------------------------------------------

    /**
     * Bracketed paste made visible (spec A56): with `?2004` asked for, a two-finger tap pastes the
     * clipboard's line and it lands between `ESC [200~` and `ESC [201~`, which `cat -v` shows as
     * `^[[200~` and `^[[201~` on the echo and again on its own line after Enter.
     */
    @Test
    fun `A56 a paste under bracketed paste lands between the markers cat -v shows`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        clear(session)
        start(session, "printf '\\e[?2004h'; head -n1 | cat -v")
        compose.waitUntil(5_000) { session.emulator.bracketedPaste }
        sent.clear()

        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", "berth bracketed paste"))
        canvas.performTouchInput {
            down(0, cellCenter(8, 5))
            down(1, cellCenter(8, 25))
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals("\u001b[200~berth bracketed paste\u001b[201~", sent.joinToString(""))
        session.sendKey(TerminalKey.ENTER)
        awaitScreen(session, "cat -v's copy of the paste") { rows -> rows.count { it.contains("^[[200~berth bracketed paste^[[201~") } >= 2 }
        compose.settle(600)
        capture("A56-bracketed-paste-cat-v")
    }

    // ---- C5, CSI u --------------------------------------------------------------------------------

    /**
     * Ctrl+Shift+letter and Ctrl+Space told apart (vision §5.5, C5), from a hardware keyboard's
     * chords: under kitty's disambiguate flag (`CSI > 1 u`) Ctrl+Shift+J is `CSI 106;6u` and
     * Ctrl+Space `CSI 32;5u`; under xterm's modifyOtherKeys 2 (`CSI > 4;2 m`) Ctrl+Shift+J is
     * `CSI 27;6;74~`. Each shows in `cat -v`. (Ctrl+Shift+A is the tab switcher's; J is no chord's.)
     */
    @Test
    @Config(shadows = [ShadowKeyLayout::class])
    fun `C05 Ctrl+Shift+J and Ctrl+Space reach the program as CSI u under kitty's flags and as CSI 27 under modifyOtherKeys`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        ShadowKeyLayout.layout = KeyLayout.US
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        // A tap focuses the terminal, as a hardware keyboard's first key would find it.
        canvas.performTouchInput { click(cellCenter(4, 4)) }
        compose.settle(600)

        clear(session)
        start(session, "printf '\\e[>1u'; head -n1 | cat -v; printf '\\e[<u'")
        compose.waitUntil(5_000) { session.emulator.keyboardProtocol.kittyFlags == 1 }
        sent.clear()
        chord(KEYCODE_J, META_CTRL_ON or META_CTRL_LEFT_ON or META_SHIFT_ON or META_SHIFT_LEFT_ON)
        chord(KEYCODE_SPACE, META_CTRL_ON or META_CTRL_LEFT_ON)
        compose.waitUntil(5_000) { sent.size >= 2 }
        assertEquals(listOf("\u001b[106;6u", "\u001b[32;5u"), sent.toList())
        session.sendKey(TerminalKey.ENTER)
        awaitScreen(session, "cat -v's copy of the kitty chords") { rows -> rows.count { it.contains("^[[106;6u^[[32;5u") } >= 2 }
        compose.waitUntil(5_000) { session.emulator.keyboardProtocol.kittyFlags == 0 }

        session.sendText("printf '\\e[>4;2m'; head -n1 | cat -v; printf '\\e[>4;0m'\n")
        compose.waitUntil(5_000) { session.emulator.keyboardProtocol.modifyOtherKeys == 2 }
        sent.clear()
        chord(KEYCODE_J, META_CTRL_ON or META_CTRL_LEFT_ON or META_SHIFT_ON or META_SHIFT_LEFT_ON)
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals(listOf("\u001b[27;6;74~"), sent.toList())
        session.sendKey(TerminalKey.ENTER)
        awaitScreen(session, "cat -v's copy of the modifyOtherKeys chord") { rows -> rows.count { it.contains("^[[27;6;74~") } >= 2 }
        compose.settle(600)
        capture("C05-csi-u-cat-v")
    }

    private fun chord(code: Int, meta: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.waitForIdle()
    }

    // ---- A62, OSC 9, 777 and 99 -------------------------------------------------------------------

    /**
     * Desktop notifications from a program (spec A62): two tabs on the box; the second prints its
     * tty and goes off stage, and the first, on stage, `printf`s an OSC 9 into that tty. The frame
     * has the `printf` at the prompt and the ring it raised on the other tab; OSC 777 (`notify;title;body`)
     * and OSC 99 (kitty's) raise it the same way, each for its own reason.
     */
    @Test
    fun `A62 an OSC 9 printf into another tab's tty lights that tab, and OSC 777 and 99 do the same`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = testHost("Berth test box")
        mountApp(box)
        val sender = newTab(box)
        val receiver = newTab(box)
        run(receiver, "clear; tty", until = "/dev/pts/")
        val tty = screen(receiver).first { it.trim().startsWith("/dev/pts/") }.trim()

        graph.viewModel.setActive(sender.id)
        compose.waitUntil(5_000) { sender.onStage && !receiver.onStage }
        compose.settle(600)
        clear(sender)
        // Each printf wraps on a phone's width, so the wait is on the lit tab rather than the echo.
        start(sender, "printf '\\e]9;%s\\a' 'build finished' > $tty")
        compose.waitUntil(10_000) { receiver.record.value.needsAttention }
        awaitScreen(sender, "the prompt after the printf") { rows ->
            val shown = rows.filter { it.isNotBlank() }
            shown.size >= 2 && shown.first().contains("printf") && shown.last().trimEnd().endsWith("$")
        }
        assertEquals("build finished", receiver.record.value.attentionReason)
        assertFalse("the tab on stage is not lit", sender.record.value.needsAttention)
        val litTab = hasContentDescription("needs attention", substring = true) and hasContentDescription(", tab 2 of 2", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(litTab).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(1_200)
        capture("A62-osc9-printf-ring")

        start(sender, "printf '\\e]777;notify;%s;%s\\a' 'Deploy' 'staging is green' > $tty")
        compose.waitUntil(10_000) { receiver.record.value.attentionReason == "Deploy" }
        start(sender, "printf '\\e]99;;%s\\a' 'tests passed' > $tty")
        compose.waitUntil(10_000) { receiver.record.value.attentionReason == "tests passed" }
        assertTrue(receiver.record.value.needsAttention)
        compose.settle(800)
        capture("A62-osc777-osc99-ring")
    }

    // ---- A63, OSC 133 -----------------------------------------------------------------------------

    /**
     * Prompt marks decide (spec A63, C16): without them a command recalled with Up and edited is
     * not in the history, since the typed-line fallback cannot follow Up; with bash's A, B, C and
     * D marks the same kind of command is, read off the marks. That command sleeps eleven
     * seconds with its tab off stage, and D's arrival lights the tab (Command finished), the frame
     * taken in the switcher with the tab's own screen in its card. Back on the tab the History
     * sheet has the draft typed before the marks and the marked command, and not the edit between.
     */
    @Test
    fun `A63 a recalled command is history only through the OSC 133 marks, and a long one lights its tab`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = testHost("Berth test box")
        mountApp(box)
        val other = newTab(box)
        val marked = newTab(box)
        clear(marked)
        val history = { graph.commandHistory.items.value.map { it.text } }

        // Typed, no marks: the fallback reads the line off the screen, where its echo is by Enter.
        marked.sendText("echo draft")
        awaitScreen(marked, "the typed draft") { rows -> rows.first().trimEnd().endsWith("$ echo draft") }
        marked.sendKey(TerminalKey.ENTER)
        compose.waitUntil(10_000) { "echo draft" in history() }
        // Recalled and edited, no marks: not the fallback's to know.
        marked.sendKey(TerminalKey.UP)
        awaitScreen(marked, "the recalled draft") { rows -> rows.count { it.endsWith("echo draft") } >= 2 }
        repeat("draft".length) { marked.sendKey(TerminalKey.BACKSPACE) }
        marked.sendText("edited")
        marked.sendKey(TerminalKey.ENTER)
        awaitOnScreen(marked, "edited")
        compose.settle(2_000)
        assertFalse("an edit of a recalled line is not the fallback's: ${history()}", "echo edited" in history())

        // bash marks its prompt (A, B), the command's start (C) and its end with the status (D).
        marked.sendText("PS0='\\e]133;C\\a'; PROMPT_COMMAND='printf \"\\e]133;D;%s\\a\" \"\$?\"'; PS1='\\[\\e]133;A\\a\\]\\u@\\h:\\w\\\$ \\[\\e]133;B\\a\\]'\n")
        compose.settle(1_500)
        // Up twice is the edit again; edited once more it runs for eleven seconds, and the marks say what it was.
        marked.sendKey(TerminalKey.UP)
        marked.sendKey(TerminalKey.UP)
        awaitScreen(marked, "the recalled edit") { rows -> rows.last { it.isNotBlank() }.endsWith("echo edited") }
        repeat("edited".length) { marked.sendKey(TerminalKey.BACKSPACE) }
        marked.sendText("marked; sleep 11")
        marked.sendKey(TerminalKey.ENTER)
        compose.waitUntil(5_000) { "echo marked; sleep 11" in history() }

        graph.viewModel.setActive(other.id)
        compose.waitUntil(5_000) { !marked.onStage }
        compose.settle(2_000)
        assertFalse("not before the threshold", marked.record.value.needsAttention)
        compose.waitUntil(20_000) { marked.record.value.needsAttention }
        assertEquals("Command finished", marked.record.value.attentionReason)
        compose.onNode(hasContentDescription("open the tab switcher", substring = true)).performClick()
        val litCard = hasContentDescription("needs attention", substring = true) and hasAnyAncestor(isDialog())
        compose.waitUntil(5_000) { compose.onAllNodes(litCard).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(1_200)
        capture("A63-osc133-command-finished")

        compose.onNode(litCard).performClick()
        compose.waitUntil(5_000) { marked.onStage && !marked.record.value.needsAttention }
        compose.settle(600)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("History").performClick()
        waitForText("echo marked; sleep 11")
        waitForText("echo draft")
        assertTrue("the edit made without marks is nowhere", compose.onAllNodes(hasText("echo edited")).fetchSemanticsNodes().isEmpty())
        compose.settle(600)
        capture("A63-osc133-history")
    }

    // ---- A64 and A66, wide characters and ligatures ---------------------------------------------------

    /**
     * Wide characters (spec A64) and ligatures (A66, C19: on by default, a toggle): CJK and emoji
     * printed under a ruler take two cells each, head and tail, so the bar after them stands where
     * the ruler says; the line of operators is drawn with the font's ligatures, and with them off
     * (Settings' Ligatures switch, tapped) its pixels change while the CJK line's stay as they were.
     */
    @Test
    fun `A64 A66 CJK and emoji take two cells each, and the operator line changes with the ligature toggle`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val font = runBlocking { graph.settings.terminalFont.first() }
        assertTrue("ligatures are on by default", font.ligatures)
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        run(session, "export LC_ALL=C.UTF-8", until = "LC_ALL")
        clear(session)
        run(
            session,
            "printf '%s\\n' '0123456789ab' '\u6F22\u5B57\u304B\u306A\uD55C\uAE00|' '\uD83D\uDE42\uD83D\uDE80\uD83D\uDC4D\uD83C\uDF0A|' '=> -> != === <= >= |> ::'",
            until = "=> -> != === <= >= |> ::",
        )
        awaitScreen(session, "the printed lines") { rows -> rows.any { it == "=> -> != === <= >= |> ::" } }
        compose.settle(800)

        val rows = screen(session)
        val cjkRow = rows.indexOfFirst { it.startsWith("\u6F22") }
        val emojiRow = rows.indexOfFirst { it.startsWith("\uD83D\uDE42") }
        val ligRow = rows.indexOfFirst { it == "=> -> != === <= >= |> ::" }
        assertTrue("the three lines are on screen: $rows", cjkRow >= 0 && emojiRow >= 0 && ligRow >= 0)
        assertWide(session, cjkRow, count = 6)
        assertWide(session, emojiRow, count = 4)
        capture("A64-wide-characters")

        val on = canvas.captureToImage().toPixelMap()
        val switch = hasText("Ligatures") and isToggleable()
        val ligatures = { runBlocking { graph.settings.terminalFont.first().ligatures } }
        inSettings("A66-ligatures-switch-off", switch, done = { !ligatures() }) { compose.onNode(switch).assertIsOff() }
        compose.waitUntil(10_000) { band(canvas.captureToImage().toPixelMap(), on, ligRow) > 0 }
        compose.settle(600)
        val off = canvas.captureToImage().toPixelMap()
        assertTrue("the operator line is drawn differently without ligatures", band(on, off, ligRow) > 40)
        assertEquals("the CJK line is the same either way", 0, band(on, off, cjkRow))
        capture("A66-ligatures-off")
        inSettings(null, switch, done = { ligatures() }) { compose.onNode(switch).assertIsOn() }
        compose.waitUntil(10_000) { band(canvas.captureToImage().toPixelMap(), on, ligRow) == 0 }
        compose.settle(600)
        capture("A66-ligatures-on")
    }

    /**
     * Settings, reached from the tab as a user reaches it (More, Library, Settings): each of
     * [controls] tapped in turn until [done], [shown] checked and the page captured as [frame] as
     * the taps left it, and Back to the tab.
     */
    private fun inSettings(frame: String?, vararg controls: SemanticsMatcher, done: () -> Boolean, shown: () -> Unit = {}) {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Library").performClick()
        val settings = hasText("Settings") and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodes(settings).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(settings).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(controls.first()).fetchSemanticsNodes().isNotEmpty() }
        for (control in controls) compose.onNode(control).performScrollTo().performClick()
        compose.waitUntil(5_000) { done() }
        compose.settle(600)
        shown()
        frame?.let(::capture)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(600)
    }

    // ---- A72 to A74, the Stage in Black, in Light and in the Material You accent -------------------

    /**
     * The live Stage in each interface pick (spec A72 to A74, C19), each made in Settings on the way
     * from the tab and back, the page framed with the pick and the Stage after it. The Stage's chrome
     * is `surface.1`, so Black draws it from the true-black set (`surface.1 #0E0D0C` over
     * `surface.0 #000000`, spec Colour) where Dark draws graphite, Light from the light set around
     * the same terminal, and Material You fills a latched Ctrl with the system's wallpaper accent
     * where Copper filled it before.
     */
    @Test
    fun `A72 A73 A74 the live Stage is drawn in Black, in Light and in the Material You accent, each picked in Settings`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        clear(session)
        val theme = { runBlocking { graph.settings.interfaceTheme.first() } }
        val graphite = berthColors(InterfaceTheme.DEFAULT, dark = true).surface1.toArgb()
        assertEquals("Dark's graphite before any pick", graphite, chromeFill())
        val floor = Color.Black.toArgb()
        assertEquals("no pixel of Dark's Stage is #000000", 0, count(frame(), floor))
        val copper = InterfaceTheme.DEFAULT.accent.toColor().toArgb()
        val system = dynamicDarkColorScheme(context).primary.toArgb()
        assertNotEquals("the system accent is not Copper", copper, system)
        latchCtrl()
        val copperKey = count(frame(), copper)
        assertTrue("a latched Ctrl is filled with Copper", copperKey > 0)
        assertEquals("and nothing is the system accent yet", 0, count(frame(), system))

        inSettings("A72-stage-settings-black", variant("Black"), done = { theme().variant == InterfaceVariant.TRUE_BLACK }) {
            compose.onNode(variant("Black")).assertIsSelected()
        }
        val black = berthColors(theme(), dark = true)
        assertEquals("the true-black set's floor is black", floor, black.surface0.toArgb())
        assertNotEquals(graphite, black.surface1.toArgb())
        compose.waitUntil(5_000) { chromeFill() == black.surface1.toArgb() }
        compose.settle(600)
        assertTrue("the Stage's floor shows #000000 between its chrome and the terminal", count(frame(), floor) > 0)
        capture("A72-stage-black")

        inSettings("A74-stage-settings-light", variant("Light"), done = { theme().variant == InterfaceVariant.LIGHT }) {
            compose.onNode(variant("Light")).assertIsSelected()
        }
        val light = berthColors(theme(), dark = false)
        assertFalse(light.isDark)
        compose.waitUntil(5_000) { chromeFill() == light.surface1.toArgb() }
        compose.settle(600)
        capture("A74-stage-light")

        inSettings("A73-stage-settings-material-you", variant("Dark"), hasText("Material You"), done = { theme().let { it.variant == InterfaceVariant.DARK && it.materialYou } }) {
            compose.onNode(variant("Dark")).assertIsSelected()
            compose.onNodeWithText("Material You").assertIsSelected()
        }
        compose.waitUntil(5_000) { chromeFill() == graphite }
        latchCtrl()
        val after = frame()
        assertTrue("the latched Ctrl is filled with the system accent", count(after, system) > 0)
        assertTrue("where it was Copper: ${count(after, copper)} of $copperKey Copper pixels left", count(after, copper) < copperKey)
        capture("A73-stage-material-you")
    }

    /** An interface variant's segment in Settings' Appearance row. */
    private fun variant(label: String) = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun frame(): PixelMap = compose.onRoot().captureToImage().toPixelMap()

    /** The Stage's chrome: the flat strip's header, in its corner left of the first tab. */
    private fun chromeFill(): Int = frame()[2, 2].toArgb()

    private fun count(pixels: PixelMap, argb: Int): Int {
        var n = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].toArgb() == argb) n++
        return n
    }

    /** The Deck's Ctrl tapped once, a one-shot latch filled with the accent. */
    private fun latchCtrl() {
        compose.onNode(deckKey("Ctrl")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(deckKey("Ctrl, one-shot")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(600)
    }

    /** The first [count] characters of screen [row] are wide: a head cell then its tail, and the bar after them on the ruler's column. */
    private fun assertWide(session: TerminalSession, row: Int, count: Int) = synchronized(session.emulator.lock) {
        val line = session.emulator.line(row)
        for (i in 0 until count) {
            assertTrue("cell ${2 * i} of row $row is a wide head", line.attrs[2 * i] and Attr.WIDE != 0)
            assertTrue("cell ${2 * i + 1} of row $row is its tail", line.attrs[2 * i + 1] and Attr.WIDE_TAIL != 0)
        }
        assertEquals("the bar stands at the ruler's column ${2 * count}", '|'.code, line.chars[2 * count])
    }

    /** The pixels that differ between [a] and [b] in the band of text row [row]. */
    private fun band(a: PixelMap, b: PixelMap, row: Int): Int {
        val h = paints().cellHeight
        val top = (row * h).toInt().coerceIn(0, a.height - 1)
        val bottom = ((row + 1) * h).toInt().coerceIn(0, a.height)
        var n = 0
        for (y in top until bottom) for (x in 0 until minOf(a.width, b.width)) if (a[x, y] != b[x, y]) n++
        return n
    }

    // ---- A24 and A25, the tmux helper and the Deck's tmux layer --------------------------------------

    /**
     * The tmux helper (spec A24) and the Deck's tmux layer (A25). A host on Attach or create opens
     * inside `tmux new-session -A -s berth-tmux-box`, its status line at the foot and the Session
     * layer tmux. The relay in front of the sshd stops and starts, the reconnect attaches again, and
     * the shell is the one that was there before the drop (the same `$$`), with its screen. The
     * layer picker then puts the tmux layer on the Deck, and its keys drive tmux: split is the
     * prefix and `"`, new the prefix and `c`, each seen on the server.
     */
    @Test
    fun `A24 A25 a tmux host keeps its shell through a dropped link, and the tmux layer's keys split and open windows`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeRemote("tmux")
        val name = "berth-tmux-box"
        tmuxSession = name
        exec("tmux kill-session -t '$name' 2>/dev/null; true")
        val relay = StoppableRelay(sshHost, sshPort).also { closeables += it }
        val box = testHost("tmux box", address = "127.0.0.1", port = relay.port, persistence = PersistencePolicy(tmux = TmuxMode.ATTACH_OR_CREATE))
        graph.sessions.clock = ShiftedClock(now)
        mountApp(box)
        val session = newTab(box)
        assertEquals(PersistenceLayer.TMUX, session.record.value.layer)
        run(session, "clear; echo \"shell \$\$ in \$(tmux display -p '#S')\"", until = "in $name")
        val pid = Regex("shell (\\d+) in $name").find(screen(session).joinToString("\n"))!!.groupValues[1]
        compose.settle(600)
        capture("A24-tmux-attached")

        relay.stop()
        compose.waitUntil(15_000) { session.state == SessionState.RECONNECTING }
        relay.start()
        compose.waitUntil(45_000) { session.state == SessionState.LIVE }
        awaitOnScreen(session, "shell $pid in $name", 15_000)
        run(session, "echo \"still shell \$\$\"", until = "still shell $pid")
        assertEquals("one tmux session on the server, the same", name, exec("tmux list-sessions -F '#S' | grep -x '$name'").trim())
        compose.settle(600)
        capture("A24-tmux-reattached")

        compose.onAllNodes(hasContentDescription("Layer") and hasStateDescription("Base")).onFirst()
            .performTouchInput { down(center); moveBy(Offset(0f, -40.dpPx())); up() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("tmux") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("tmux") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(deckKey("split")).fetchSemanticsNodes().isNotEmpty() }
        for (label in listOf("Pfx", "<win", "win>", "new", "split", "zoom", "scroll")) compose.onNode(deckKey(label)).assertExists()
        compose.settle(800)
        capture("A25-tmux-layer")

        compose.onNode(deckKey("split")).performClick()
        compose.waitUntil(10_000) { exec("tmux list-panes -t '$name' | wc -l").trim() == "2" }
        compose.settle(1_200)
        capture("A25-tmux-split")
        compose.onNode(deckKey("new")).performClick()
        compose.waitUntil(10_000) { exec("tmux list-windows -t '$name' | wc -l").trim() == "2" }
        awaitScreen(session, "the second window on the status line") { rows -> rows.last { it.isNotBlank() }.contains("1:") }
        compose.settle(1_200)
        capture("A25-tmux-new-window")
    }

    private fun deckKey(label: String) = hasTestTag(DeckKeyTag) and hasContentDescription(label)

    // ---- A43, the volume buttons -------------------------------------------------------------------

    /**
     * The volume buttons as arrows (spec A43, C20): under Up and Down arrows, Volume Up, handed to
     * the Stage the way the activity hands every key event, sends Up, and bash puts the last
     * command back at the prompt, which no key of the Deck or the keyboard touched.
     */
    @Test
    fun `A43 Volume Up under Up and Down arrows recalls the last command`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        graph.viewModel.updateHardwareKeyboard { it.copy(volumeButtons = VolumeButtons.ARROWS) }
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        compose.waitUntil(5_000) { volumeKeys.handler != null }
        clear(session)
        run(session, "echo recalled by volume up", until = "recalled by volume up")
        awaitScreen(session, "the prompt after the echo") { rows ->
            val shown = rows.filter { it.isNotBlank() }
            shown.size == 3 && shown[1] == "recalled by volume up" && shown[2].trimEnd().endsWith("$")
        }
        sent.clear()

        var took = false
        compose.runOnUiThread { took = volumeKeys.dispatch(android.view.KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP)) }
        assertTrue("the Stage took the volume button", took)
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals("Volume Up is the Up arrow", String(session.emulator.encodeKey(TerminalKey.UP, 0), Charsets.UTF_8), sent.single())
        awaitScreen(session, "the recalled command at the prompt") { rows -> rows.last { it.isNotBlank() }.endsWith("echo recalled by volume up") }
        compose.settle(600)
        capture("A43-volume-up-recall")
    }

    // ---- A41, the Deck's haptics -------------------------------------------------------------------

    /**
     * The Deck's haptics (spec A41, D3), which no frame can show, read off what the app asks of the
     * platform. Under Full, the default, a tap on `-` is `KEYBOARD_TAP`, Ctrl's one-shot two light
     * ticks 40 ms apart, its lock one heavy tick and its release one light tick. Subtle, picked in
     * Settings, is taps only, so each of Ctrl's steps is a tap; Off asks for nothing. The frames are
     * what set each off: Ctrl locked over the `-` typed before it, Settings' Haptics row on each
     * level, and the prompt with a `-` for each level's tap.
     */
    @Test
    fun `A41 a Deck tap and Ctrl's latches ask for D3's patterns, only taps under Subtle and nothing under Off`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = testHost("Berth test box")
        mountApp(box)
        val session = newTab(box)
        clear(session)
        val level = { runBlocking { graph.settings.hapticLevel.first() } }
        assertEquals("Full is the default", HapticLevel.FULL, level())
        val tap = HapticFeedbackType.KeyboardTap
        val light = HapticFeedbackType.SegmentTick
        val heavy = HapticFeedbackType.LongPress

        buzzes.clear()
        tapDash(session, typed = 1)
        assertEquals("a key tap", listOf(tap), kinds())
        buzzes.clear()
        stepCtrl("Ctrl", "Ctrl, one-shot")
        compose.waitUntil(5_000) { buzzes.size >= 2 }
        assertEquals("a one-shot is two light ticks", listOf(light, light), kinds())
        assertTrue("40 ms apart: ${buzzes.map { it.second }}", buzzes[1].second - buzzes[0].second >= 40)
        buzzes.clear()
        stepCtrl("Ctrl, one-shot", "Ctrl, locked")
        assertEquals("a lock is one heavy tick", listOf(heavy), kinds())
        compose.settle(600)
        capture("A41-full-ctrl-locked")
        buzzes.clear()
        stepCtrl("Ctrl, locked", "Ctrl")
        assertEquals("a release is one light tick", listOf(light), kinds())

        val row = hasText("Haptics") and hasClickAction()
        val option = { name: String -> hasText(name) and hasAnyAncestor(isPopup()) }
        inSettings("A41-settings-haptics-subtle", row, option("Subtle"), done = { level() == HapticLevel.SUBTLE }) {
            compose.onNode(row).assertTextContains("Subtle")
        }
        buzzes.clear()
        tapDash(session, typed = 2)
        stepCtrl("Ctrl", "Ctrl, one-shot")
        stepCtrl("Ctrl, one-shot", "Ctrl, locked")
        stepCtrl("Ctrl, locked", "Ctrl")
        compose.settle(400)
        assertEquals("Subtle is taps only: the key's and each of Ctrl's steps", List(4) { tap }, kinds())

        inSettings("A41-settings-haptics-off", row, option("Off"), done = { level() == HapticLevel.OFF }) {
            compose.onNode(row).assertTextContains("Off")
        }
        buzzes.clear()
        tapDash(session, typed = 3)
        stepCtrl("Ctrl", "Ctrl, one-shot")
        stepCtrl("Ctrl, one-shot", "Ctrl, locked")
        stepCtrl("Ctrl, locked", "Ctrl")
        compose.settle(600)
        assertTrue("Off asks for nothing: ${kinds()}", buzzes.isEmpty())
        capture("A41-off-no-haptics")
    }

    private fun kinds() = buzzes.map { it.first }

    /** The Deck's `-` tapped, and the prompt waited on until it holds [typed] of them. */
    private fun tapDash(session: TerminalSession, typed: Int) {
        compose.onNode(deckKey("-")).performClick()
        awaitScreen(session, "$typed dashes at the prompt") { rows -> rows.first().trimEnd().endsWith("$ " + "-".repeat(typed)) }
    }

    /** The Deck's Ctrl, described as [from], tapped until it is described as [to]. */
    private fun stepCtrl(from: String, to: String) {
        compose.onNode(deckKey(from)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(deckKey(to)).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---- A76, clipboard auto-clear ------------------------------------------------------------------

    /**
     * Clipboard auto-clear (spec A76, C20): under Clear clipboard after 30 s, a word copied off the
     * terminal with the selection bar's Copy is what a two-finger-tap paste hands `read` at once,
     * and the same paste 30 s later hands it nothing, since Berth took its own copy back off the
     * clipboard. bash's `SECONDS`, zeroed at the copy, stamps each paste.
     */
    @Test
    fun `A76 a word copied off the terminal pastes at once and is off the clipboard 30 s later`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        runBlocking { graph.settings.updateSecuritySettings { it.copy(clipboardClear = ClipboardClear.THIRTY_SECONDS) } }
        val box = testHost("Berth test box")
        mountApp(box)
        graph.security.onActivityResumed()
        val session = newTab(box)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clear(session)
        session.sendText("p() { read -r x; echo \"pasted \$SECONDS s after the copy: [\$x]\"; }\n")
        awaitScreen(session, "the prompt after the probe's definition") { rows ->
            val shown = rows.filter { it.isNotBlank() }
            shown.size >= 2 && shown.last().trimEnd().endsWith("$")
        }
        session.sendText("echo berthclip4721\n")
        awaitScreen(session, "the word to copy") { rows -> rows.any { it == "berthclip4721" } }
        compose.settle(400)

        val row = screen(session).indexOfFirst { it == "berthclip4721" }
        canvas.performTouchInput { down(cellCenter(row, 4)) }
        compose.mainClock.advanceTimeBy(700)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        canvas.performTouchInput { up() }
        compose.onNodeWithText("Copy").performClick()
        waitForText("Copied")
        assertEquals("berthclip4721", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        run(session, "SECONDS=0; p", until = "SECONDS=0; p")
        sent.clear()
        twoFingerTap()
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertEquals("berthclip4721", sent.joinToString(""))
        session.sendKey(TerminalKey.ENTER)
        val stamped = Regex("""^pasted (\d+) s after the copy: \[(.*)]$""")
        awaitScreen(session, "the first paste's line") { rows -> rows.any { stamped.find(it)?.groupValues?.get(2) == "berthclip4721" } }
        compose.settle(600)
        capture("A76-paste-at-once")

        graph.clock.advance(30_000)
        compose.waitUntil(45_000) { !clipboard.hasPrimaryClip() }
        session.sendText("p\n")
        awaitScreen(session, "the second probe") { rows -> rows.any { it.trimEnd().endsWith("$ p") } }
        compose.settle(400)
        sent.clear()
        twoFingerTap()
        compose.settle(1_000)
        assertTrue("an empty clipboard pastes nothing: $sent", sent.isEmpty())
        session.sendKey(TerminalKey.ENTER)
        awaitScreen(session, "the second paste's line") { rows -> rows.any { stamped.find(it)?.groupValues?.get(2) == "" } }
        val seconds = screen(session).firstNotNullOf { stamped.find(it)?.takeIf { m -> m.groupValues[2].isEmpty() } }.groupValues[1].toInt()
        assertTrue("the clear waited out the 30 s: $seconds", seconds >= 30)
        compose.settle(600)
        capture("A76-clipboard-cleared")
    }

    /** Two fingers tapped together low on the terminal, clear of the lines a case reads. */
    private fun twoFingerTap() = canvas.performTouchInput {
        down(0, cellCenter(14, 5))
        down(1, cellCenter(14, 25))
        up(0)
        up(1)
    }
}
