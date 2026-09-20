package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.R
import app.berth.android.createBerthComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.session.AuthResolver
import app.berth.android.session.HostKeyChangedDecision
import app.berth.android.session.LinkFingerprint
import app.berth.android.session.ManagedTab
import app.berth.android.session.Prompt
import app.berth.android.session.TunnelStatus
import app.berth.android.ui.AppRoot
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.importer.ImportHostsSheet
import app.berth.android.ui.importer.ImportKeySheet
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.rail.Drawer
import app.berth.android.ui.settings.KnownHostSheet
import app.berth.android.ui.settings.KnownHostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.snippets.PendingSnippet
import app.berth.android.ui.snippets.SnippetEditorSheet
import app.berth.android.ui.snippets.SnippetRunSheet
import app.berth.android.ui.snippets.SnippetsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.NewTabSheet
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabSwitcher
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.tunnels.TunnelEditorSheet
import app.berth.android.ui.tunnels.TunnelsScreen
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Snippet
import app.berth.domain.model.SnippetAction
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.ssh.HostKeyFingerprints
import app.berth.ssh.HostKeyRequest
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Renders the real screens, in Berth Dark on a Pixel-class phone, through Robolectric's native
 * graphics and writes PNGs to `build/outputs/roborazzi`. Offline cases seed in-memory storage;
 * `live session on stage` drives a real sshj connection when the `SSH_TEST_*` variables are set,
 * so the Stage, the Deck and the trust prompt in those frames come from an actual session.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class BerthScreenshotTest {
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

    /** Tab actions without the shell: data changes reach the manager, sheet requests land in a state nobody renders. */
    @Composable
    private fun tabActions(): TabActions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }

    /** The Stage as the shell mounts it, minus navigation: a terminal's body or a Files tab's browser under the strip. */
    @Composable
    private fun Stage(tab: ManagedTab?) {
        StageScreen(graph.viewModel, tab, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
    }

    // ---- offline screens ------------------------------------------------------------------------

    @Test
    fun `hosts library`() {
        seedLibrary()
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
        }
        capture("hosts")
    }

    @Test
    fun `hosts library empty`() {
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
        }
        capture("hosts-empty")
    }

    @Test
    fun `host editor`() {
        seedLibrary()
        seedTunnels()
        themed { HostEditorScreen(graph.viewModel, hostId = "build-box", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("build box")).fetchSemanticsNodes().isNotEmpty() }
        capture("host-editor")
        compose.onNodeWithText("Add tunnel").performScrollTo()
        compose.waitForIdle()
        capture("host-editor-tunnels")
    }

    // ---- tunnels ------------------------------------------------------------------------------

    @Test
    fun `tunnels for one host`() {
        seedLibrary()
        seedTunnels()
        themed { TunnelsScreen(graph.viewModel, hostId = "prod-api", onBack = {}) }
        capture("tunnels-host")
    }

    @Test
    fun `tunnels for every host`() {
        seedLibrary()
        seedTunnels()
        themed { TunnelsScreen(graph.viewModel, hostId = null, onBack = {}) }
        capture("tunnels-all")
    }

    @Test
    fun `tunnel editor`() {
        seedLibrary()
        seedTunnels()
        val tunnel = graph.tunnels.items.value.first { it.id == "tn-web" }
        themed {
            TunnelsScreen(graph.viewModel, hostId = "prod-api", onBack = {})
            TunnelEditorSheet(graph.viewModel, hostId = "prod-api", existing = tunnel, onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Edit tunnel")).fetchSemanticsNodes().isNotEmpty() }
        capture("tunnel-editor")
    }

    // ---- snippets -----------------------------------------------------------------------------

    @Test
    fun `snippets library`() {
        seedLibrary()
        seedSnippets()
        themed { SnippetsScreen(graph.viewModel, onBack = {}) }
        capture("snippets")
    }

    @Test
    fun `snippet editor`() {
        seedLibrary()
        seedSnippets()
        val snippet = graph.snippets.items.value.first { it.id == "sn-tail" }
        themed {
            SnippetsScreen(graph.viewModel, onBack = {})
            SnippetEditorSheet(graph.viewModel, existing = snippet, onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Edit snippet")).fetchSemanticsNodes().isNotEmpty() }
        capture("snippet-editor")
    }

    @Test
    fun `snippet run sheet asks for placeholders`() {
        seedLibrary()
        seedSnippets()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        val snippet = graph.snippets.items.value.first { it.id == "sn-tail" }
        themed {
            Stage(session)
            SnippetRunSheet(graph.viewModel, session, PendingSnippet(snippet, SnippetAction.RUN), onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("tail app log")).fetchSemanticsNodes().isNotEmpty() }
        capture("snippet-run")
    }

    // ---- known hosts --------------------------------------------------------------------------

    @Test
    fun `known hosts`() {
        seedLibrary()
        themed { KnownHostsScreen(graph.viewModel, onBack = {}) }
        capture("known-hosts")
    }

    @Test
    fun `known host detail`() {
        seedLibrary()
        val key = graph.knownHosts.items.value.first { it.id == "kh-3" }
        themed {
            KnownHostsScreen(graph.viewModel, onBack = {})
            KnownHostSheet(graph.viewModel, key, hostNames = listOf("build box"), onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Forget")).fetchSemanticsNodes().isNotEmpty() }
        capture("known-host-detail")
    }

    @Test
    fun `pinned key refused notice`() {
        seedLibrary()
        val host = graph.hosts.items.value.first { it.id == "build-box" }
        val pinned = graph.knownHosts.items.value.first { it.id == "kh-3" }
        val offered = SshKeys.generate(KeyAlgorithm.ED25519).public
        val request = HostKeyRequest(host.address, host.port, "ssh-ed25519", offered, SshKeys.openSshPublic(offered).split(" ")[1], SshKeys.fingerprintSha256(offered))
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }
        CoroutineScope(Dispatchers.IO).launch { graph.prompts.pinnedKeyRefused(host, request, pinned) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.PinnedKeyRefused }
        capture("prompt-pinned-key-refused")
        (graph.prompts.current.value as Prompt.PinnedKeyRefused).acknowledge()
    }

    // ---- import -------------------------------------------------------------------------------

    @Test
    fun `import hosts from an ssh config`() {
        seedLibrary()
        themed {
            SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {})
            ImportHostsSheet(graph.viewModel, onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput(SAMPLE_SSH_CONFIG)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Import 2 hosts")).fetchSemanticsNodes().isNotEmpty() }
        capture("import-hosts")
    }

    @Test
    fun `import a passphrase protected key`() {
        seedLibrary()
        val pem = SshKeys.openSshPrivate(SshKeys.generate(KeyAlgorithm.ED25519), "ben@old-laptop", "correct horse".toCharArray())
        themed {
            KeysScreen(graph.viewModel, onBack = {})
            ImportKeySheet(graph.viewModel, onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(pem)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("old laptop")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("PASSPHRASE")).fetchSemanticsNodes().isNotEmpty() }
        capture("import-key")
    }

    @Test
    fun identities() {
        seedLibrary()
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        capture("identities")
    }

    @Test
    fun `identities empty`() {
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        capture("identities-empty")
    }

    @Test
    fun settings() {
        seedLibrary()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        capture("settings")
        compose.onNodeWithText("Import private key").performScrollTo()
        compose.waitForIdle()
        capture("settings-trust-data")
    }

    /**
     * The adaptive icon as launchers mask it (the inner 72 dp of the 108 dp canvas, circle and
     * squircle), the themed monochrome layer and the 24 dp notification glyph, on surface.0.
     */
    @Test
    fun `launcher icon`() {
        themed {
            val c = Berth.colors
            val bg = colorResource(R.color.ic_launcher_background)
            Row(
                Modifier.fillMaxSize().background(c.surface0).padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
                }
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
                }
                Box(Modifier.size(72.dp).clip(CircleShape).background(c.surface3), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null, tint = c.text1, modifier = Modifier.requiredSize(108.dp))
                }
                Icon(painterResource(R.drawable.ic_notification), contentDescription = null, tint = c.text1, modifier = Modifier.size(24.dp))
            }
        }
        capture("launcher-icon")
    }

    // ---- tabs (spec C3) -----------------------------------------------------------------------

    /** Three detached tabs across two groups: the strip with chips, the active tab, the count tile. */
    @Test
    fun `stage with a detached frame`() {
        seedLibrary()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed { Stage(session) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        capture("stage-detached")
    }

    /** The Stage with no tab: the empty state under a strip that is only the plus tab. */
    @Test
    fun `stage with no tabs`() {
        seedLibrary()
        runBlocking { graph.sessions.restore() }
        themed { Stage(null) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No tabs")).fetchSemanticsNodes().isNotEmpty() }
        capture("stage-empty")
    }

    /** A tab's long-press menu over the strip. */
    @Test
    fun `tab menu`() {
        seedLibrary()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed { Stage(session) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("pi-hole, detached", substring = true)).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Close others")).fetchSemanticsNodes().isNotEmpty() }
        capture("tab-menu")
    }

    /** The switcher: a grid of frozen frames, grouped, the active card marked; a Files tab is a folder card. */
    @Test
    fun `tab switcher`() {
        seedLibrary()
        seedDetachedSessions()
        seedFilesTab()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed {
            Stage(session)
            TabSwitcher(graph.viewModel, tabActions(), onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Frame of homelab")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Files \u00B7 berth", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("tab-switcher")
    }

    /**
     * A Files tab on the Stage (spec C3, tab kinds): the browser under the strip with no header of
     * its own, the tab titled for the folder it shows with the host's monogram and its ride's state.
     * A selection swaps the breadcrumb row for the selection bar at the same height, so the strip
     * keeps the top to itself and the listing does not move; the selection and the folder survive
     * a switch to a terminal tab and back.
     */
    @Test
    fun `files tab on stage`() {
        seedLibrary()
        seedDetachedSessions()
        seedFilesTab()
        val fs = FakeSftpFileSystem.demoTree(now)
        graph.files.channelFor = { fs }
        runBlocking { graph.sessions.restore() }
        val tab = graph.sessions.filesTab("f-homelab")!!
        graph.sessions.setActive(tab.id)
        // The body follows the active tab, as the shell's does, so a tap on the strip swaps bodies.
        themed { Stage(graph.viewModel.activeTab.collectAsState().value) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("settings.gradle.kts")).fetchSemanticsNodes().isNotEmpty() }
        // The folder listed names the tab, and the tab rides the host's detached terminal.
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Files \u00B7 berth, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("s-homelab", tab.ride.value?.id)
        assertEquals("/home/demo/projects/berth", tab.folder)
        compose.onAllNodesWithText("Files \u00B7 homelab").assertCountEquals(0)
        compose.onNodeWithContentDescription("Tabs, 4 open").assertExists()
        capture("stage-files")

        // One ⋮ on the screen: the Stage's overflow opens on the folder rows, then the tab's own; the pane has none.
        compose.onAllNodesWithContentDescription("Folder options").assertCountEquals(0)
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New folder")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Terminal directory").assertExists()
        compose.onNodeWithText("Terminal").assertExists()
        compose.onNodeWithText("Close").assertExists()
        val newFolderRow = compose.onNodeWithText("New folder").fetchSemanticsNode().boundsInRoot
        val terminalRow = compose.onNodeWithText("Terminal").fetchSemanticsNode().boundsInRoot
        assertTrue("folder rows lead the tab's", newFolderRow.bottom <= terminalRow.top)
        capture("stage-files-overflow")
        // A lent row closes the Stage's menu before it acts.
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New folder")).fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithContentDescription("Select settings.gradle.kts").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("1 selected")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Edit path").assertCountEquals(0)
        capture("stage-files-selected")

        // Over to the terminal tab and back: the selection is still there, then a folder deeper renames the tab.
        compose.onNode(hasContentDescription("homelab, detached", substring = true)).performClick()
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == "s-homelab" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("1 selected")).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasContentDescription("Files \u00B7 berth, detached", substring = true)).performClick()
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == tab.id }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("1 selected")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Edit path")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("app").performClick()
        compose.waitUntil(10_000) { tab.folder == "/home/demo/projects/berth/app" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Files \u00B7 app, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Empty folder.")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The Files tab's long-press menu: Terminal where a terminal tab offers Files, and no Detach. */
    @Test
    fun `files tab menu`() {
        seedLibrary()
        seedDetachedSessions()
        seedFilesTab()
        val fs = FakeSftpFileSystem.demoTree(now)
        graph.files.channelFor = { fs }
        runBlocking { graph.sessions.restore() }
        val tab = graph.sessions.filesTab("f-homelab")!!
        graph.sessions.setActive(tab.id)
        themed { Stage(tab) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Files \u00B7 berth, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Files \u00B7 berth, detached", substring = true)).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Terminal")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Detach").assertCountEquals(0)
        compose.onAllNodesWithText("Files").assertCountEquals(0)
        capture("tab-menu-files")
    }

    /** The New tab sheet: quick connect, recent hosts, then the library. */
    @Test
    fun `new tab sheet`() {
        seedLibrary()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed {
            Stage(session)
            NewTabSheet(graph.viewModel, groupId = null, onDismiss = {}, onAddHost = {})
        }
        // The Recent row exposes its swatches as "Open …"; the library below is rows by name.
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Open homelab")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("build box")).fetchSemanticsNodes().isNotEmpty() }
        capture("new-tab-sheet")
    }

    /** The drawer (spec C7): the groups with their tab counts, then the library. */
    @Test
    fun `drawer with groups and detached tabs`() {
        seedLibrary()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        themed {
            Drawer(graph.viewModel, tabActions(), onGroupTap = {}, onNewGroup = {}, onLibrary = {})
        }
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 3 }
        capture("drawer-groups")
    }

    @Test
    fun `host key and secret prompts`() {
        seedLibrary()
        val host = graph.hosts.items.value.first { it.id == "prod-api" }
        val key = SshKeys.generate(KeyAlgorithm.ED25519).public
        val request = HostKeyRequest(
            host = host.address,
            port = host.port,
            keyType = "ssh-ed25519",
            publicKey = key,
            publicKeyBase64 = SshKeys.openSshPublic(key).split(" ")[1],
            fingerprintSha256 = SshKeys.fingerprintSha256(key),
        )
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }
        val bg = CoroutineScope(Dispatchers.IO)

        bg.launch { graph.prompts.trustHostKey(host, request, emptyList()) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        capture("prompt-trust-host-key")
        (graph.prompts.current.value as Prompt.TrustHostKey).trust()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        val saved = KnownHostKey("k1", host.address, host.port, "ssh-ed25519", request.publicKeyBase64, "SHA256:2b0dNsF7TTa7iNZbFqWlV1nSTR3a6i2E1p1bY8ahbVQ", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        bg.launch { graph.prompts.hostKeyChanged(host, request, saved) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.HostKeyChanged }
        capture("prompt-host-key-changed")
        (graph.prompts.current.value as Prompt.HostKeyChanged).decide(HostKeyChangedDecision.DISCONNECT)
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        val homelab = graph.hosts.items.value.first { it.id == "homelab" }
        bg.launch { graph.prompts.password(homelab) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.Password }
        capture("prompt-password")
        (graph.prompts.current.value as Prompt.Password).cancel()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        bg.launch { graph.prompts.passphrase(host, "laptop ed25519") }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.Passphrase }
        capture("prompt-passphrase")
        (graph.prompts.current.value as Prompt.Passphrase).cancel()
    }

    /**
     * The trust sheets when the login was opened by a link carrying `;fingerprint=`: a match is one
     * line under the fingerprint and the sheet is otherwise the first-connection sheet; a mismatch
     * leads the sheet in danger, said once, with the offered key and the link's fingerprint as two
     * labelled rows of one size and no primary action; a value Berth cannot read is said to be that,
     * quoted clean; and the changed-key sheet says whose fingerprint the link carried, the offered
     * key's, the saved key's (a link from before a rotation) or neither's, with the link's as a third
     * row for that one. The comparison decides nothing: the actions are the same.
     */
    @Test
    fun `trust sheets with the fingerprint a link carried`() {
        seedLibrary()
        val host = graph.hosts.items.value.first { it.id == "prod-api" }
        val key = SshKeys.generate(KeyAlgorithm.ED25519).public
        val other = SshKeys.generate(KeyAlgorithm.ED25519).public
        val request = HostKeyRequest(
            host = host.address,
            port = host.port,
            keyType = "ssh-ed25519",
            publicKey = key,
            publicKeyBase64 = SshKeys.openSshPublic(key).split(" ")[1],
            fingerprintSha256 = SshKeys.fingerprintSha256(key),
        )
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }
        val bg = CoroutineScope(Dispatchers.IO)

        // The link named this key: the sheet is the first-connection sheet with one more line, and Trust stays primary.
        bg.launch { graph.prompts.trustHostKey(host, request, emptyList(), link = LinkFingerprint.of(SshKeys.fingerprintSha256(key), key)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("First connection").assertExists()
        compose.onNodeWithText("The link that opened this connection carried the same fingerprint as this key's.").assertExists()
        compose.onNodeWithText("Trust and connect").assertExists()
        capture("prompt-trust-host-key-link-match")
        (graph.prompts.current.value as Prompt.TrustHostKey).cancel()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        // The link named another key: danger leads, once, in the caption; the offered key and the link's fingerprint
        // are two labelled rows of one size (C13), and trusting is the destructive action at the bottom.
        bg.launch { graph.prompts.trustHostKey(host, request, emptyList(), link = LinkFingerprint.of(SshKeys.fingerprintSha256(other), key)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Key does not match the link").assertExists()
        compose.onNodeWithText("The link that opened this connection carried a different fingerprint for this server.").assertExists()
        compose.onAllNodes(hasText("carried a different fingerprint", substring = true)).assertCountEquals(1)
        compose.onAllNodes(hasText("OFFERED   ssh-ed25519 \u00B7 SHA256")).assertCountEquals(1)
        compose.onAllNodes(hasText("LINK   SHA256")).assertCountEquals(1)
        compose.onNodeWithText(SshKeys.groupedFingerprint(SshKeys.fingerprintSha256(other))).assertExists()
        compose.onNodeWithText(SshKeys.groupedFingerprint(SshKeys.fingerprintSha256(key))).assertExists()
        compose.onNodeWithText("Trust and connect anyway").assertExists()
        compose.onAllNodesWithText("Trust and connect").assertCountEquals(0)
        capture("prompt-trust-host-key-link-mismatch")
        (graph.prompts.current.value as Prompt.TrustHostKey).cancel()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        // A fingerprint in no form Berth reads: said so, the link's text quoted short and clean; nothing is compared and nothing alarms.
        bg.launch { graph.prompts.trustHostKey(host, request, emptyList(), link = LinkFingerprint.of("not-a-fingerprint\u202e", key)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("First connection").assertExists()
        compose.onNodeWithText("The link that opened this connection carried a fingerprint Berth cannot read (\u201Cnot-a-fingerprint\u201D), so there is nothing to compare here.").assertExists()
        capture("prompt-trust-host-key-link-unreadable")
        (graph.prompts.current.value as Prompt.TrustHostKey).cancel()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        // The saved key changed and the link carried the offered key's fingerprint: said in the changed-key sheet, which stays as alarming as it is.
        val saved = KnownHostKey("k1", host.address, host.port, "ssh-ed25519", SshKeys.openSshPublic(other).split(" ")[1], SshKeys.fingerprintSha256(other), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        bg.launch { graph.prompts.hostKeyChanged(host, request, saved, link = LinkFingerprint.of(SshKeys.fingerprintSha256(key), key, saved)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.HostKeyChanged }
        compose.onNodeWithText("Host key changed").assertExists()
        compose.onNodeWithText("The link that opened this connection carried the offered key's fingerprint.").assertExists()
        compose.onAllNodes(hasText("LINK", substring = true)).assertCountEquals(0)
        capture("prompt-host-key-changed-link-offered")
        (graph.prompts.current.value as Prompt.HostKeyChanged).decide(HostKeyChangedDecision.DISCONNECT)
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        // The link carried the saved key's fingerprint, written as MD5 hex: a link from before the rotation, which is
        // what a rotation looks like and is said so, in the sheet's own tone, not as a fingerprint that is neither key's.
        val savedMd5 = HostKeyFingerprints.md5(other).removePrefix("MD5:")
        bg.launch { graph.prompts.hostKeyChanged(host, request, saved, link = LinkFingerprint.of(savedMd5, key, saved)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.HostKeyChanged }
        compose.onNodeWithText("The link that opened this connection carried the saved key's fingerprint.").assertExists()
        compose.onAllNodes(hasText("neither key's", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("LINK", substring = true)).assertCountEquals(0)
        capture("prompt-host-key-changed-link-saved")
        (graph.prompts.current.value as Prompt.HostKeyChanged).decide(HostKeyChangedDecision.DISCONNECT)
        compose.waitUntil(5_000) { graph.prompts.current.value == null }

        // The link carried a third key's fingerprint: neither key's, said in danger, with the link's as a third row.
        val third = SshKeys.generate(KeyAlgorithm.ED25519).public
        bg.launch { graph.prompts.hostKeyChanged(host, request, saved, link = LinkFingerprint.of(SshKeys.fingerprintSha256(third), key, saved)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.HostKeyChanged }
        compose.onNodeWithText("The link that opened this connection carried a fingerprint that is neither key's.").assertExists()
        compose.onAllNodes(hasText("SAVED   ssh-ed25519 \u00B7 SHA256")).assertCountEquals(1)
        compose.onAllNodes(hasText("OFFERED   ssh-ed25519 \u00B7 SHA256")).assertCountEquals(1)
        compose.onAllNodes(hasText("LINK   SHA256")).assertCountEquals(1)
        compose.onNodeWithText(SshKeys.groupedFingerprint(SshKeys.fingerprintSha256(third))).assertExists()
        capture("prompt-host-key-changed-link-neither")
        (graph.prompts.current.value as Prompt.HostKeyChanged).decide(HostKeyChangedDecision.DISCONNECT)
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
    }

    // ---- live session against the local sshd -------------------------------------------------

    @Test
    fun `live session on stage`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        seedLibrary()
        seedDetachedSessions()
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
        val askBox = box.copy(id = "berth-test-box-ask", name = "Same box, ask each time", color = SwatchColor.OCHRE, monogram = "SB", auth = AuthMethod.AskEachTime)
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.hosts.upsert(askBox)
            // The tab that was on stage when the process died, as the phone has it.
            graph.settings.setLastActiveSessionId("s-homelab")
        }

        compose.setContent { AppRoot(graph.viewModel) }
        // A cold start: the detached tabs come back onto the strip and the last active one is on stage with its
        // frozen frame before anything connects (spec C3, Persistence). A missing or stale id lands on the same
        // tab through the manager's fallback (SessionManagerTest); "No tabs" is only ever the zero-tab state.
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 3 && graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("No tabs").assertCountEquals(0)
        settle(300)
        // The strip opens at its start, Home's chip heading the run: the groups can land a frame after the tabs, and
        // a chip that arrives ahead of the first tab must not be left behind the edge.
        compose.onNode(hasContentDescription("Group Home, 2 tabs")).assertIsDisplayed()
        capture("app-cold-start")

        openNewTabSheet()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth test box")).fetchSemanticsNodes().isNotEmpty() }
        capture("new-tab-sheet-live")
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        capture("prompt-trust-host-key-live")
        compose.onNodeWithText("Trust and connect").performClick()

        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val session = graph.sessions.activeSession.value!!
        settle(1_200)
        session.sendText("export PS1='\\[\\e[38;5;108m\\]\\u@berth\\[\\e[0m\\]:\\[\\e[38;5;179m\\]\\w\\[\\e[0m\\]\\$ ' && clear && ls --color=always -la /\n")
        settle(1_500)
        capture("stage-live-ls-color")

        // One tap arms Ctrl for the next key; a second tap locks it (the bar under the label), a third releases it.
        compose.onNode(hasContentDescription("Ctrl", substring = true)).performClick()
        capture("stage-live-ctrl-latched")
        compose.onNode(hasContentDescription("Ctrl", substring = true)).performClick()
        capture("stage-live-ctrl-locked")
        compose.onNode(hasContentDescription("Ctrl", substring = true)).performClick()

        session.sendText("clear && htop\n")
        settle(3_000)
        capture("stage-live-htop")
        session.sendText("q")
        settle(600)

        // A local forward to an HTTP server in this process: the request leaves through the sshd and comes back.
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val bytes = "berth tunnel ok".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        val forwardPort = ServerSocket(0).use { it.localPort }
        runBlocking {
            graph.tunnels.upsert(Tunnel("tn-live", box.id, TunnelType.LOCAL, "127.0.0.1", forwardPort, "127.0.0.1", http.address.port))
            graph.snippets.upsert(Snippet("sn-live", "uptime", "uptime", pinnedToDeck = true))
        }
        compose.waitUntil(15_000) { graph.sessions.tunnelStatuses.value["tn-live"] is TunnelStatus.Up }
        val fetched = URL("http://127.0.0.1:$forwardPort/").openConnection().run {
            connectTimeout = 5_000
            readTimeout = 5_000
            getInputStream().bufferedReader().readText()
        }
        assertEquals("berth tunnel ok", fetched)

        // Pinned snippets are a Deck layer; tapping one types it into the shell. The layer key is the
        // `Layer` button to a reader, in the state of the layer it is on.
        repeat(4) { compose.onNode(hasContentDescription("Layer")).performClick() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("uptime")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Layer") and hasStateDescription("Snippets")).assertExists()
        capture("stage-live-snippets-layer")
        compose.onNode(hasContentDescription("uptime")).performClick()
        settle(1_200)
        capture("stage-live-snippet-ran")
        // Back to Base (the fifth tap wraps), so the captures that follow show the Deck as a launch does, not the layer this test stepped to.
        compose.onNode(hasContentDescription("Layer")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("uptime")).fetchSemanticsNodes().isEmpty() }

        // The Session sheet sits behind the overflow now that the header row is the strip.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Session").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Detach")).fetchSemanticsNodes().isNotEmpty() }
        capture("session-sheet")
        compose.onNodeWithText("Tunnels 1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Local \u00B7 Up", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("tunnels-live")
        compose.onNodeWithContentDescription("Back").performClick()

        // Second tab on the ask-each-time host from the plus tab: the password prompt comes from the transport.
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        openNewTabSheet()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search hosts")).fetchSemanticsNodes().isNotEmpty() }
        // The host sits low in the half-height sheet; the search field brings it up.
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("same box")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Same box, ask each time")).fetchSemanticsNodes().isNotEmpty() }
        capture("new-tab-sheet-search")
        compose.onNodeWithText("Same box, ask each time").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.Password }
        capture("prompt-password-live")
        (graph.prompts.current.value as Prompt.Password).submit(sshPassword.toCharArray())
        compose.waitUntil(45_000) { graph.sessions.sessions.value.count { it.state == SessionState.LIVE } == 2 }
        settle(800)
        // The new tab landed after the one it was opened from and is active; the strip shows both live dots.
        assertEquals(listOf(box.id, askBox.id), graph.sessions.sessions.value.filter { it.state == SessionState.LIVE }.map { it.record.value.hostId })
        capture("stage-live-two-tabs")

        compose.onNode(hasContentDescription("open the tab switcher", substring = true)).performClick()
        // The shell sets the tab's title over OSC 0 at its first prompt, so the card is found by the title the session has now.
        val second = graph.sessions.sessions.value.first { it.record.value.hostId == askBox.id }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Frame of ${second.record.value.displayTitle}")).fetchSemanticsNodes().isNotEmpty() }
        settle(400)
        capture("tab-switcher-live")
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Library").performClick()
        // Section labels draw in capitals.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("GROUPS")).fetchSemanticsNodes().isNotEmpty() }
        settle(400)
        capture("drawer-live")

        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        http.stop(0)
    }

    /** The plus tab is the strip's last item; with tabs before it the lazy row has not composed it until it scrolls there. */
    private fun openNewTabSheet() {
        compose.onNode(hasContentDescription("Tabs, ", substring = true)).performScrollToNode(hasContentDescription("New tab"))
        compose.onNodeWithContentDescription("New tab").performClick()
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

    // ---- fixtures -------------------------------------------------------------------------------

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
        val rsa = SshKeys.generate(KeyAlgorithm.ED25519)
        graph.identities.insert(
            Identity("id-laptop", "laptop ed25519", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(ed.public, "ben@laptop"), SshKeys.fingerprintSha256(ed.public), "ben@laptop", createdAt = now - TimeUnit.DAYS.toMillis(200)),
            SshKeys.openSshPrivate(ed, "ben@laptop", "correct horse".toCharArray()).toByteArray(),
        )
        graph.identities.insert(
            Identity("id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(ec.public, "berth@pixel"), SshKeys.fingerprintSha256(ec.public), "berth@pixel", keystoreAlias = "berth-id-phone", createdAt = now - TimeUnit.DAYS.toMillis(12)),
            null,
        )
        graph.identities.insert(
            Identity("id-deploy", "deploy key", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE, SshKeys.openSshPublic(rsa.public, "deploy"), SshKeys.fingerprintSha256(rsa.public), "deploy", createdAt = now - TimeUnit.DAYS.toMillis(3)),
            SshKeys.openSshPrivate(rsa, "deploy").toByteArray(),
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

        graph.knownHosts.upsert(KnownHostKey("kh-1", "203.0.113.10", 22, "ssh-ed25519", SshKeys.openSshPublic(ed.public).split(" ")[1], SshKeys.fingerprintSha256(ed.public), now - TimeUnit.DAYS.toMillis(90), now - TimeUnit.HOURS.toMillis(2)))
        graph.knownHosts.upsert(KnownHostKey("kh-2", "192.168.1.20", 22, "ecdsa-sha2-nistp256", SshKeys.openSshPublic(ec.public).split(" ")[1], SshKeys.fingerprintSha256(ec.public), now - TimeUnit.DAYS.toMillis(40), now - TimeUnit.MINUTES.toMillis(18)))
        val build = SshKeys.generate(KeyAlgorithm.ED25519).public
        graph.knownHosts.upsert(KnownHostKey("kh-3", "build.internal", 22, "ssh-ed25519", SshKeys.openSshPublic(build).split(" ")[1], SshKeys.fingerprintSha256(build), now - TimeUnit.DAYS.toMillis(200), now - TimeUnit.DAYS.toMillis(1), pinned = true))
        val rsaHost = SshKeys.generate(KeyAlgorithm.RSA_4096).public
        graph.knownHosts.upsert(KnownHostKey("kh-4", "db-staging.example.net", 2200, "rsa-sha2-512", SshKeys.openSshPublic(rsaHost).split(" ")[1], SshKeys.fingerprintSha256(rsaHost), now - TimeUnit.DAYS.toMillis(12), now - TimeUnit.DAYS.toMillis(12)))
    }

    private fun seedTunnels() = runBlocking {
        graph.tunnels.upsert(Tunnel("tn-web", "prod-api", TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80))
        graph.tunnels.upsert(Tunnel("tn-db", "prod-api", TunnelType.LOCAL, "127.0.0.1", 5433, "db.internal", 5432, enabled = false))
        graph.tunnels.upsert(Tunnel("tn-socks", "prod-api", TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0))
        graph.tunnels.upsert(Tunnel("tn-gitea", "homelab", TunnelType.LOCAL, "127.0.0.1", 3000, "localhost", 3000))
        graph.tunnels.upsert(Tunnel("tn-webhook", "homelab", TunnelType.REMOTE, "", 9000, "127.0.0.1", 3000))
        graph.tunnels.upsert(Tunnel("tn-ci", "build-box", TunnelType.LOCAL, "127.0.0.1", 8081, "localhost", 8080))
        graph.tunnels.upsert(Tunnel("tn-ci-socks", "build-box", TunnelType.DYNAMIC, "0.0.0.0", 1081, "", 0, enabled = false))
    }

    private fun seedSnippets() = runBlocking {
        graph.snippets.upsert(Snippet("sn-restart", "restart nginx", "sudo systemctl restart nginx && systemctl status nginx --no-pager", pinnedToDeck = true))
        graph.snippets.upsert(Snippet("sn-tail", "tail app log", "tail -n {{lines:200}} -f /var/log/{{service}}/app.log", hostId = "prod-api", pinnedToDeck = true, tags = listOf("logs")))
        graph.snippets.upsert(Snippet("sn-df", "df -h", "df -h", pinnedToDeck = true))
        graph.snippets.upsert(Snippet("sn-dc", "compose up", "docker compose up -d {{service}}", hostId = "homelab", workspaceId = "ws-lab", defaultAction = SnippetAction.PASTE))
        graph.snippets.upsert(Snippet("sn-gradle", "gradle build", "./gradlew assembleDebug --console=plain", workspaceId = "ws-work", tags = listOf("ci")))
        graph.snippets.upsert(Snippet("sn-motd", "show motd", "cat /etc/motd", hostId = "pi-hole", runOnConnect = true))
    }

    companion object {
        private val SAMPLE_SSH_CONFIG = """
            Host *
              ServerAliveInterval 30
              IdentityFile ~/.ssh/laptop_ed25519

            Host bastion
              HostName bastion.example.net
              User ops
              Port 2222

            Host prod-web
              HostName 10.0.4.12
              User deploy
              ProxyJump bastion
              LocalForward 8443 localhost:443
              IdentityFile ~/.ssh/id_rsa_old

            Host homelab
              HostName 192.168.1.20
              User ben
        """.trimIndent()
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
