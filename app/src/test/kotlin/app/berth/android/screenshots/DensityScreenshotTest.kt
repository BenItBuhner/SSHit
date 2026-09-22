package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Density
import app.berth.domain.model.Host
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Density (spec A12) on the surfaces its tokens reach, each at Comfortable and then Compact, at the
 * system's 1× and at 2×, where interface text stops at its 1.3× cap: the Interface editor, whose
 * Density control is the switch, Settings' panels and rows, the Hosts list and the host editor's
 * fields. The interface theme comes from the view model as the shell's does, so the pick in the
 * editor is the density every screen draws at. Written to `build/outputs/roborazzi` in pairs,
 * `density-<surface>-comfortable` and `density-<surface>-compact`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DensityScreenshotTest(private val systemFontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "system font scale {0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf(1f), arrayOf(2f))
    }

    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val now = System.currentTimeMillis()
    private val suffix = if (systemFontScale > 1f) "-font-scale-2x" else ""

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        RuntimeEnvironment.setFontScale(systemFontScale)
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        seed()
    }

    @After
    fun tearDown() {
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name$suffix.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            val theme by graph.viewModel.interfaceTheme.collectAsState()
            BerthTheme(theme) { Box(Modifier.fillMaxSize()) { content() } }
        }
    }

    private fun setDensity(density: Density) {
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = density))
        awaitDensity(density)
    }

    private fun awaitDensity(density: Density) {
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == density }
        compose.waitForIdle()
    }

    private fun bounds(text: String): Rect = compose.onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot

    private fun Float.inDp(): Float = this / compose.density.density

    @Test
    fun `compact picked in the interface editor tightens its panels`() {
        themed { AppearanceScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        capture("density-appearance-comfortable")
        val comfortable = bounds("Variant")

        compose.onNodeWithText("Compact").performScrollTo().performClick()
        awaitDensity(Density.COMPACT)
        compose.onNodeWithContentDescription("Interface preview").performScrollTo()
        compose.waitForIdle()
        capture("density-appearance-compact")
        assertEquals("the Look panel's padding is 12 dp, not 16", 4f, (comfortable.left - bounds("Variant").left).inDp(), 0.5f)
    }

    @Test
    fun `settings panels and rows`() {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.waitForIdle()
        capture("density-settings-comfortable")
        val comfortable = bounds("System font")

        setDensity(Density.COMPACT)
        capture("density-settings-compact")
        val compact = bounds("System font")
        assertEquals("the panel's padding is 12 dp, not 16", 4f, (comfortable.left - compact.left).inDp(), 0.5f)
        // At least 8: the row is 8 dp wider as well, so its caption may take a line fewer.
        assertTrue("a captioned row loses its 8 dp of padding", (comfortable.height - compact.height).inDp() >= 7.5f)
    }

    @Test
    fun `hosts list rows`() {
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("prod-api").fetchSemanticsNodes().isNotEmpty() }
        capture("density-hosts-comfortable")
        val comfortable = bounds("prod-api")

        setDensity(Density.COMPACT)
        capture("density-hosts-compact")
        assertEquals("a row with its address under the name is 8 dp shorter", 8f, (comfortable.height - bounds("prod-api").height).inDp(), 0.5f)
    }

    @Test
    fun `host editor fields`() {
        themed { HostEditorScreen(graph.viewModel, hostId = "prod-api", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("203.0.113.10").fetchSemanticsNodes().isNotEmpty() }
        capture("density-host-editor-comfortable")
        val address = bounds("203.0.113.10")
        val user = bounds("deploy")

        setDensity(Density.COMPACT)
        capture("density-host-editor-compact")
        val compactAddress = bounds("203.0.113.10")
        val compactUser = bounds("deploy")
        assertEquals("the Address field is 40 dp, not 44, so User stands 4 dp nearer", 4f, ((user.top - address.top) - (compactUser.top - compactAddress.top)).inDp(), 0.5f)
        assertEquals("inside the Connection panel's 12 dp", 4f, (address.left - compactAddress.left).inDp(), 0.5f)
    }

    private fun seed() = runBlocking {
        fun host(id: String, name: String, address: String, user: String, color: SwatchColor, lastConnectedAgoMinutes: Long?) = Host(
            id = id,
            name = name,
            color = color,
            monogram = Host.monogramFor(name),
            address = address,
            port = 22,
            user = user,
            auth = AuthMethod.AskEachTime,
            lastConnectedAt = lastConnectedAgoMinutes?.let { now - TimeUnit.MINUTES.toMillis(it) },
            createdAt = now - TimeUnit.DAYS.toMillis(30),
        )
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, 130))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, 18))
        graph.hosts.upsert(host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, 60 * 26))
        graph.hosts.upsert(host("build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, null))
    }
}
