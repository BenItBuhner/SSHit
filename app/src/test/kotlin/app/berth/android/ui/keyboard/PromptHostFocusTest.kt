package app.berth.android.ui.keyboard

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.diagnostics.BerthLog
import app.berth.android.diagnostics.CrashReporter
import app.berth.android.diagnostics.ReportKind
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard's focus across the one case in which a sheet's host leaves the composition with its
 * sheet up: the prompt host is composed only while no crash report is unread, and the launch after
 * a crash reads its store off the startup path, so a password prompt a reconnecting tab raised can
 * have its sheet, and the keys in its field, when the store's word lands and the crash sheet takes
 * the prompt host's place. The prompt itself waits; nothing on the Stage may take the keys from
 * under the crash sheet or hold a requester into the sheet that went; and once the crash is kept
 * for later the prompt's sheet is back, and once it is answered the terminal has the keys again and
 * the chords work. Restored tabs only, so no sshd is needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class PromptHostFocusTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph
    private val bg = CoroutineScope(Dispatchers.Default + Job())

    @Before
    fun setUp() {
        graph = TestGraph(context)
        StageFixture.seed(graph)
    }

    @After
    fun tearDown() {
        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        bg.cancel()
        graph.close()
    }

    @Test
    fun `the prompt host leaving under an open prompt, and coming back, leaves the keys where the Stage can take them again`() {
        compose.setContent { WithHardwareKeyboard { AppRoot(graph.viewModel) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        awaitFocused(hasTestTag(TerminalTag), "the terminal, a keyboard attached and a shell tab on stage")

        // A password prompt rises, as a reconnect's would; its sheet is up and the field takes the keys.
        val homelab = graph.hosts.items.value.first { it.id == "homelab" }
        val asking = bg.launch { graph.prompts.password(homelab) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.Password }
        awaitText("Password for ")
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.waitForIdle()

        // The launch's store read lands: the last run's crash is unread, the prompt host leaves the composition
        // and the prompt's sheet with it, and the crash sheet stands in its place. The prompt itself waits.
        crashPreviousRun()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Berth crashed last time")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Password for ", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertTrue("the prompt waits behind the crash sheet", graph.prompts.current.value is Prompt.Password)
        compose.onNodeWithText("Keep for later").assertExists()

        // Kept for later: the prompt host is back, and the prompt's sheet with it.
        compose.onNodeWithText("Keep for later").performClick()
        compose.waitUntil(5_000) { graph.reports.unread.value == null }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Password for ", substring = true)).fetchSemanticsNodes().size == 1 }

        // Answered (cancelled, as its × would): the sheet goes and the terminal has the keys, as before the prompt;
        // Ctrl+Shift+S still moves them to the strip's active tab.
        (graph.prompts.current.value as Prompt.Password).cancel()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Password for ", substring = true)).fetchSemanticsNodes().isEmpty() }
        awaitFocused(hasTestTag(TerminalTag), "the terminal once the prompt was answered")
        chord(KEYCODE_S, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(role(Role.Tab), "the strip's tab after Ctrl+Shift+S")
        assertEquals("one control holds the focus", 1, compose.onAllNodes(isFocused()).fetchSemanticsNodes().size)
        asking.cancel()
    }

    /** The previous run's crash through the handler's path into this graph's store, then this launch's read of it. */
    private fun crashPreviousRun() {
        val previousRun = CrashReporter(graph.reportsDir, { CrashReporter.describeInstall(context) }, BerthLog.ring)
        previousRun.onCrash(Thread("main"), IllegalStateException("Frame 1 of 1 has no cells for row 24"))
        graph.reports.reload()
        assertEquals(ReportKind.CRASH, graph.reports.unread.value?.kind)
    }

    /** The configuration a hardware keyboard shows up in: a QWERTY keyboard, and not hidden. */
    @Composable
    private fun WithHardwareKeyboard(content: @Composable () -> Unit) {
        val current = LocalConfiguration.current
        val withKeyboard = remember(current) {
            Configuration(current).apply {
                keyboard = Configuration.KEYBOARD_QWERTY
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            }
        }
        CompositionLocalProvider(LocalConfiguration provides withKeyboard, content = content)
    }

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun awaitText(text: String) {
        compose.waitUntil("\"$text\" on screen", 10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for the one focused control to be [what], failing with [where] the focus was expected and where it is. */
    private fun awaitFocused(what: SemanticsMatcher, where: String) {
        try {
            compose.waitUntil("the focus on $where", 10_000) { compose.onAllNodes(isFocused() and what).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val holding = compose.onAllNodes(isFocused(), useUnmergedTree = true).printToString(maxDepth = 0)
            throw AssertionError("Expected the focus on $where; it is on:\n$holding", e)
        }
    }

    /** A chord's down, the way a hardware keyboard's arrives: through the focused control's ancestors, the Stage's preview first. */
    private fun chord(code: Int, meta: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.waitForIdle()
    }
}
