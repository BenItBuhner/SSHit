package app.berth.android.ui.stage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.components.CoachMarkId
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Nub's one-time coach mark (product vision, the Nub; spec A9). On a first run the Stage hangs
 * it over the Nub, Got it puts it away and the settings keep it seen, and a mark seen before never
 * shows. The Deck hangs it only from a Nub in reach: one mark for two rows of Nubs, none while the
 * Deck is out of reach or being edited, none where the arrows are four keys.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class NubCoachMarkTest {
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

    private fun stage() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, live, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(nub()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun nub() = hasTestTag(DeckKeyTag) and hasContentDescription("Nub, the arrow keys")

    private fun marks() = compose.onAllNodes(hasText(NubCoachMarkText) and hasAnyAncestor(isPopup())).fetchSemanticsNodes().size

    private fun awaitMark(up: Boolean) {
        compose.waitUntil(5_000) { (marks() > 0) == up }
        compose.waitForIdle()
    }

    @Test
    fun `on a first run the Stage hangs the Nub's mark, and Got it puts it away for good`() {
        graph.settings.coachMarks.value = emptySet()
        stage()
        awaitMark(up = true)
        assertEquals(1, marks())

        compose.onNode(hasText("Got it") and hasAnyAncestor(isPopup())).performClick()
        awaitMark(up = false)
        compose.waitUntil(5_000) { graph.settings.coachMarks.value.isNotEmpty() }
        assertEquals("kept seen in the settings", setOf(CoachMarkId.NUB.key), graph.settings.coachMarks.value)
    }

    @Test
    fun `a mark seen before never shows`() {
        graph.settings.coachMarks.value = setOf(CoachMarkId.NUB.key)
        stage()
        compose.waitForIdle()
        assertEquals(0, marks())
    }

    @Test
    fun `the Deck hangs the mark from its first Nub in reach, and from none while edited, out of reach or four keys`() {
        val layers = listOf(
            DeckLayer("Base", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)), DeckKey(nub = true))),
            DeckLayer("Nav", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.HOME)), DeckKey(nub = true))),
        )
        var layout by mutableStateOf(DeckLayout(rows = 2, layers = layers))
        var enabled by mutableStateOf(true)
        var editing by mutableStateOf<DeckEditing?>(null)
        var dismissed = 0
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
                Deck(layout = layout, layerIndex = 0, onLayerIndexChange = {}, input = input, enabled = enabled, editing = editing, nubCoachMark = { dismissed++ })
            }
        }
        awaitMark(up = true)
        assertEquals("both rows have a Nub", 2, compose.onAllNodes(nub()).fetchSemanticsNodes().size)
        assertEquals("one mark for the two", 1, marks())

        enabled = false
        awaitMark(up = false)
        enabled = true
        awaitMark(up = true)

        editing = DeckEditing(selectedSlot = null, onSelectSlot = {}, onMoveKey = { _, _ -> })
        awaitMark(up = false)
        editing = null
        awaitMark(up = true)

        layout = layout.copy(arrows = DeckArrows.FOUR_KEYS)
        awaitMark(up = false)
        layout = layout.copy(arrows = DeckArrows.BOTH)
        awaitMark(up = true)

        compose.onNode(hasText("Got it") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
