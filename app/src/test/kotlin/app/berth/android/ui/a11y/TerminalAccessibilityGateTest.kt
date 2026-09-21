package app.berth.android.ui.a11y

import android.app.Application
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.terminal.TerminalFrame
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screen reader's copy of each frame is made only while a service is on (review #15): the
 * canvas reads [AccessibilityManager.isEnabled] and hands it to [TerminalAccessibility], which
 * copies nothing while it is off, so a `cat` of a large file costs the reader nothing while no
 * reader runs. A service turned on mid-session gets the frame on screen at once, not at the next
 * output; turned off, the copying stops and the node keeps the last screen it was given.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalAccessibilityGateTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val manager: AccessibilityManager get() = ApplicationProvider.getApplicationContext<Application>().getSystemService(AccessibilityManager::class.java)

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        shadowOf(manager).setEnabled(false)
    }

    @After
    fun tearDown() {
        shadowOf(manager).setEnabled(false)
        graph.close()
    }

    // ---- the object ------------------------------------------------------------------------------------

    private fun frameOf(vararg lines: String): TerminalFrame {
        val t = TerminalEmulator(40, 6, 100, TerminalListenerAdapter())
        t.write(lines.joinToString("\r\n"))
        return TerminalFrame().also { it.capture(t, scrollOffset = 0) }
    }

    @Test
    fun `off, a frame is not copied and the screen stays unread, and on, it is`() {
        val a11y = TerminalAccessibility()
        a11y.enabled = false
        a11y.onFrame(frameOf("ben@homelab:~$ ls", "srv  work"))
        assertEquals("", a11y.screenText())
        assertEquals(0, a11y.version)
        a11y.enabled = true
        a11y.onFrame(frameOf("ben@homelab:~$ ls", "srv  work"))
        assertEquals("ben@homelab:~$ ls\nsrv  work", a11y.screenText())
        assertEquals(1, a11y.version)
        // Off again: the copy stops where it is; the next frame does not replace it.
        a11y.enabled = false
        a11y.onFrame(frameOf("something else"))
        assertEquals("ben@homelab:~$ ls\nsrv  work", a11y.screenText())
        assertEquals(1, a11y.version)
    }

    @Test
    fun `the read of the cursor line and the announcements read the copy, so off they have nothing new`() {
        val announcer = Announcer()
        val a11y = TerminalAccessibility(announcer)
        a11y.enabled = false
        a11y.onFrame(frameOf("ben@homelab:~$ ls"))
        a11y.announceNewOutput()
        assertEquals("nothing was copied, so there is no new output to say", "", announcer.spoken)
        a11y.readCursorLine()
        assertEquals("Nothing on screen", announcer.spoken)
        // On, the same frame is copied and the line is read from the copy.
        a11y.enabled = true
        a11y.onFrame(frameOf("ben@homelab:~$ ls"))
        a11y.readCursorLine()
        assertEquals("ben@homelab:~$ ls", announcer.spoken)
    }

    // ---- the canvas on stage -----------------------------------------------------------------------------

    private fun stageText(): String? = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    private fun stageDescription(): String? = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `with no service on the canvas node says Terminal blank whatever is on screen, and a service turned on gets the screen at once`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, live, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { live.emulator.screenText().any { it.contains("docker compose ps") } }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // The screen has the frame; the reader's copy was never made.
        assertNull("no rows are copied for a reader that is not there", stageText())
        assertEquals("Terminal, blank", stageDescription())

        // TalkBack comes on: the manager's listener fires, the frame on screen is copied then and there.
        shadowOf(manager).setEnabled(true)
        compose.waitUntil(5_000) { stageText()?.contains("docker compose ps") == true }
        assertTrue(stageText()!!.contains("gitea/gitea:1.22"))
        assertNull(stageDescription())

        // New output while it is on reaches the node; turned off, the node keeps what it last had.
        live.emulator.write("\r\nuptime")
        compose.waitUntil(5_000) { stageText()?.contains("uptime") == true }
        shadowOf(manager).setEnabled(false)
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        live.emulator.write("\r\nnot copied")
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue(live.emulator.screenText().any { it.contains("not copied") })
        assertTrue(stageText()?.contains("not copied") != true)
        live.close()
    }
}
