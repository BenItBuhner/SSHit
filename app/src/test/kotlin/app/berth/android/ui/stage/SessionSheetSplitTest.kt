package app.berth.android.ui.stage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.assertNoBrokenWords
import app.berth.android.screenshots.assertNoTextCut
import app.berth.android.screenshots.assertSheetAtContentHeight
import app.berth.android.session.PaneSide
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Session sheet's Split (spec C6): the first of the tab's actions on a window with room for two
 * panes, Unsplit once the Stage is split, and absent on a phone upright ("Split is hidden in
 * portrait on phones"), where the Stage has one pane whatever it holds. At a medium width and the
 * interface's font cap the pills wrap whole and the sheet stands at its content height (A9).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class SessionSheetSplitTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private val onSheet = hasAnyAncestor(isDialog())

    private fun pills(label: String) = compose.onAllNodes(hasText(label) and onSheet).fetchSemanticsNodes().size

    /** Homelab's tab on stage, and its sheet up until a pill puts it away; [open] brings it back. */
    private var open by mutableStateOf(true)

    private fun sheet() {
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        val tab = StageFixture.liveHomelab()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    if (open) SessionSheet(graph.viewModel, tab, onDismiss = { open = false }, onSwitch = {}, onEditHost = {}, onNewSession = {})
                }
            }
        }
        compose.waitUntil(5_000) { pills("Close") == 1 }
    }

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun `on a medium width Split comes first and splits, and Unsplit puts the Stage back to one`() {
        sheet()
        assertEquals(1, pills("Split"))
        assertEquals(0, pills("Unsplit"))

        compose.onNode(hasText("Split") and onSheet).performClick()
        compose.waitUntil(5_000) { graph.sessions.split.value != null }
        compose.waitUntil(5_000) { !open }

        open = true
        compose.waitUntil(5_000) { pills("Unsplit") == 1 }
        assertEquals(0, pills("Split"))
        compose.onNode(hasText("Unsplit") and onSheet).performClick()
        compose.waitUntil(5_000) { graph.sessions.split.value == null }
    }

    @Test
    fun `on a phone upright the sheet offers neither, split or not`() {
        sheet()
        assertEquals(0, pills("Split"))
        assertEquals(0, pills("Unsplit"))

        // A split made on a wider window stays when the phone is upright again (C23); still nothing to offer here.
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        compose.waitUntil(5_000) { graph.viewModel.panes.value != null }
        compose.waitForIdle()
        assertEquals(0, pills("Split"))
        assertEquals(0, pills("Unsplit"))
    }

    /** A phone on its side is the medium width the sheet still rises from the bottom on, over a window 360 dp tall. */
    @Test
    @Config(qualifiers = PHONE_ON_ITS_SIDE)
    fun `on a phone on its side at the font cap the pills wrap whole and the sheet stands at its content height`() {
        RuntimeEnvironment.setFontScale(2f)
        sheet()
        assertEquals(1, pills("Split"))
        compose.assertSheetAtContentHeight("Split", "Detach", "History", "Tunnels")
        compose.assertNoTextCut("the Session sheet with Split on a phone on its side at the interface's font cap", within = isDialog())
        compose.assertNoBrokenWords("the Session sheet with Split on a phone on its side at the interface's font cap", within = isDialog())
    }

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun `on a tablet upright at the font cap the dialog keeps every pill whole`() {
        RuntimeEnvironment.setFontScale(2f)
        sheet()
        assertEquals(1, pills("Split"))
        compose.assertNoTextCut("the Session sheet with Split on a tablet at the interface's font cap", within = isDialog())
        compose.assertNoBrokenWords("the Session sheet with Split on a tablet at the interface's font cap", within = isDialog())
    }
}

private const val TABLET_PORTRAIT = "w800dp-h1280dp-port-320dpi"
private const val PHONE_ON_ITS_SIDE = "w740dp-h360dp-land-420dpi"
