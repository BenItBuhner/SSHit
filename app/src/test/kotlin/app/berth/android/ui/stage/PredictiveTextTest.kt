package app.berth.android.ui.stage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Predictive text as a flag of the tab's (spec C6, C4): the Deck key spec C5 lets a user give the
 * action to flips the same flag the Session sheet's row does, on the tab on stage and no other,
 * and a tab that closes takes its flag with it, so nothing about a shell outlives the shell. Where
 * a tab starts is Settings' (C20): off as the app comes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class PredictiveTextTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        graph.close()
    }

    private fun stage(session: TerminalSession) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, session, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun flags(): Set<String> = graph.viewModel.predictiveTextTabIds.value

    @Test
    fun `a Deck key given Toggle predictive text flips the tab on stage, and only that tab`() {
        StageFixture.seed(graph)
        // The user's Deck (spec C5): one key given the app action, beside Esc.
        runBlocking {
            graph.settings.setDeckLayout(
                DeckLayout(
                    layers = listOf(
                        DeckLayer(
                            "Base",
                            listOf(
                                DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)),
                                DeckKey(tap = DeckAction.App(DeckAppAction.TOGGLE_PREDICTIVE_TEXT), display = "Aa"),
                                DeckKey(nub = true),
                            ),
                        ),
                    ),
                ),
            )
        }
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live)
        assertTrue(flags().isEmpty())

        compose.onNodeWithContentDescription("Aa").performClick()
        compose.waitUntil(5_000) { live.id in flags() }
        assertEquals("the tab on stage alone", setOf(live.id), flags())

        compose.onNodeWithContentDescription("Aa").performClick()
        compose.waitUntil(5_000) { live.id !in flags() }
        assertTrue(flags().isEmpty())
    }

    @Test
    fun `the flag is the tab's and goes with it, and a tab never asked stays off`() {
        StageFixture.seed(graph)
        val vm = graph.viewModel
        // The Stage up on homelab while the other tabs come and go behind it.
        stage(StageFixture.liveHomelab().also { sessions += it })
        compose.waitUntil(5_000) { vm.tabs.value.any { it.id == "s-pihole" } }
        vm.setPredictiveText("s-pihole", true)
        vm.setPredictiveText("s-build", true)
        assertEquals(setOf("s-pihole", "s-build"), flags())
        // Off is a word too, for a tab that was never on.
        vm.setPredictiveText("s-homelab", false)
        assertEquals(setOf("s-pihole", "s-build"), flags())

        vm.close("s-pihole")
        // The manager's strip changes off the main thread and the view model hears of it on it,
        // which under Robolectric runs only when idled: waitUntil alone never idles the looper.
        compose.waitUntil(5_000) { compose.waitForIdle(); "s-pihole" !in flags() }
        assertEquals("the closed tab's flag went with it; the other's stands", setOf("s-build"), flags())
        assertFalse("s-homelab" in flags())
    }

    /**
     * Settings › Predictive text on (spec C20): every terminal tab this process sees starts with
     * suggestions, those restored at launch as well, since the stored default is read before any
     * tab is decided; a Files tab has no keyboard to tell and is left off.
     */
    @Test
    fun `with the default on, the terminal tabs restored at launch start with suggestions and a Files tab does not`() {
        runBlocking { graph.settings.setPredictiveTextDefault(true) }
        StageFixture.seed(graph)
        val vm = graph.viewModel
        compose.waitUntil(5_000) { compose.waitForIdle(); flags().size == 3 }
        assertEquals(setOf("s-homelab", "s-pihole", "s-build"), flags())

        val files = runBlocking { graph.sessions.openFiles("s-homelab") }!!
        compose.waitUntil(5_000) { compose.waitForIdle(); vm.tabs.value.any { it.id == files.id } }
        compose.waitForIdle()
        assertEquals("no keyboard on a Files tab to tell", setOf("s-homelab", "s-pihole", "s-build"), flags())
    }

    /**
     * The default is where a tab starts, not a switch over the open ones: turned on, it leaves the
     * tabs already open as they are and the next tab starts on; turned off again, that tab keeps
     * its suggestions until its own toggle says otherwise.
     */
    @Test
    fun `a change of the default leaves the open tabs as they are and the next tab starts from it`() {
        StageFixture.seed(graph)
        val vm = graph.viewModel
        compose.waitUntil(5_000) { compose.waitForIdle(); vm.tabs.value.size == 3 }
        vm.setPredictiveTextDefault(true)
        compose.waitUntil(5_000) { compose.waitForIdle(); vm.predictiveTextDefault.value }
        assertTrue("the open tabs keep theirs", flags().isEmpty())

        val twin = runBlocking { graph.sessions.duplicate("s-pihole") }!!.also { sessions += it as TerminalSession }
        compose.waitUntil(5_000) { compose.waitForIdle(); twin.id in flags() }
        assertEquals(setOf(twin.id), flags())

        vm.setPredictiveTextDefault(false)
        compose.waitUntil(5_000) { compose.waitForIdle(); !vm.predictiveTextDefault.value }
        assertEquals("the new tab keeps what it started with", setOf(twin.id), flags())
    }

    /** The row itself (spec C20, Input): off as the app comes, and a tap stores the default. */
    @Test
    fun `the Settings row is off as the app comes and a tap turns the default on`() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        }
        compose.onNodeWithText("On for new tabs").performScrollTo().assertIsOff()
        compose.onNodeWithText("On for new tabs").performClick()
        compose.waitUntil(5_000) { graph.settings.predictiveDefault.value }
        compose.onNodeWithText("On for new tabs").assertIsOn()
    }
}
