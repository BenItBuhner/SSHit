package app.berth.android.ui.a11y

import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.captureAudited
import app.berth.android.session.AuthResolver
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Reduced motion (spec A7): the system's Remove animations is read from the animator duration
 * scale and followed as it changes; under it a Stage whose strip has a connecting tab and a tab
 * with attention shows the arc held still and the ring simply there, the capture of that state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ReducedMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private val now = System.currentTimeMillis()
    private lateinit var graph: TestGraph

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

    @Test
    fun `the setting is reduced motion at a zero animator scale and nothing else`() {
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        assertFalse("an unset scale is the platform's 1x", readReducedMotion(resolver))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        assertFalse("a slower clock is still motion", readReducedMotion(resolver))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue("Remove animations zeroes the scale", readReducedMotion(resolver))
    }

    @Test
    fun `the theme follows the setting as it changes`() {
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Text(if (LocalReducedMotion.current) "reduced" else "full")
            }
        }
        compose.onNodeWithText("full").assertExists()
        // The system writes the setting and tells its observers; the theme hears it on the main looper.
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("reduced")).fetchSemanticsNodes().isNotEmpty() }
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("full")).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * A tab connecting to a host that accepts and never speaks stays Connecting, so its arc is on
     * the strip for as long as the capture needs; a bell on a detached tab beside it sets its ring.
     * Under reduced motion neither moves: the arc rests with its open quarter at the top, and the
     * ring is at its held width from the first frame, with no pulse to settle.
     */
    @Test
    fun `stage under reduced motion, the arc still and the ring simply there`() {
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val homelab = host("homelab", "homelab", "127.0.0.1", silent.localPort, SwatchColor.VERDIGRIS)
            val pihole = host("pi-hole", "pi-hole", "192.168.1.2", 22, SwatchColor.MOSS)
            runBlocking {
                graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now))
                graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
                graph.secrets.put(AuthResolver.passwordSecretId("homelab"), "not-reached".toByteArray())
                listOf(homelab, pihole).forEach { graph.hosts.upsert(it) }
                graph.sessionRecords.upsert(record("s-homelab", homelab, 0, "~/srv", "docker compose ps"))
                graph.sessionRecords.upsert(record("s-pihole", pihole, 1, "/etc/pihole", "tail -f pihole.log"))
                graph.sessions.restore()
            }
            graph.sessions.setActive("s-homelab")
            compose.setContent {
                BerthTheme(InterfaceTheme.DEFAULT, reducedMotion = true) {
                    Box(Modifier.fillMaxSize()) {
                        val tab by graph.viewModel.activeTab.collectAsState()
                        val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                        StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                    }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 2 open")).fetchSemanticsNodes().isNotEmpty() }
            val session = graph.sessions.get("s-homelab")!!
            session.connect()
            compose.waitUntil(10_000) { session.state == SessionState.CONNECTING }
            graph.sessions.get("s-pihole")!!.emulator.write("\u0007")
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription("homelab, connecting", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasContentDescription("pi-hole, detached", substring = true) and hasContentDescription("needs attention", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            // Real time passes on the compose clock: a pulse or a spin would have moved by now; here nothing has a clock to move on.
            settle(700)
            compose.captureAudited(File(outDir, "stage-reduced-motion.png"))
            assertEquals(SessionState.CONNECTING, session.state)
        } finally {
            silent.close()
        }
    }

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun host(id: String, name: String, address: String, port: Int, color: SwatchColor) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = port, user = "ben",
        auth = AuthMethod.Password(AuthResolver.passwordSecretId(id)),
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun record(id: String, host: Host, order: Int, cwd: String, lastCommand: String) = SessionRecord(
        id = id, workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME,
        title = host.name, cwd = cwd, lastCommand = lastCommand, sortOrder = order, createdAt = now - TimeUnit.HOURS.toMillis(5),
        lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
    )
}
