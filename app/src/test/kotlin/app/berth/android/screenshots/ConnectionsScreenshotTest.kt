package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import app.berth.android.session.AuthResolver
import app.berth.android.session.ManagedTab
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.session.TunnelStatus
import app.berth.android.ui.AppRoot
import app.berth.android.ui.LinkOutcome
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabSwitcher
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Connection depth (gap wave two): the host editor's jump chain and its Add menu, the editor
 * prefilled from an `ssh://` link, the Tunnels tab detached and in the switcher with its menu, the
 * notice a malformed link earns, and, against the two local sshds, a hop's failure on the Stage,
 * the pill naming the hop under its trust sheet, and a Live Tunnels tab counting its traffic
 * through the bastion. Berth Dark on a Pixel-class phone; PNGs land in `build/outputs/roborazzi`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ConnectionsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val now = System.currentTimeMillis()

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull() ?: 0
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

    @After
    fun tearDown() {
        graph.close()
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

    @Composable
    private fun tabActions(): TabActions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }

    @Composable
    private fun Stage(tab: ManagedTab?, onEditHost: (String) -> Unit = {}) {
        StageScreen(graph.viewModel, tab, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = onEditHost)
    }

    /** Real time passes while the compose clock keeps ticking, so a pulse, a sheet's entrance or the traffic clock's tick lands. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    // ---- the host editor -------------------------------------------------------------------------

    /**
     * prod-db logs in through bastion and then relay: the Jump hosts panel lists the two hops in
     * order, numbered, each with its swatch and user@address:port, and the Tunnels panel starts with
     * Tunnels only, on for this host, over its forwards. Add jump host offers only what can be
     * added: homelab, not the host itself and not the hops already in the chain.
     */
    @Test
    fun `host editor with a jump chain, and the add menu`() {
        seed()
        themed { HostEditorScreen(graph.viewModel, hostId = "prod-db", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("prod-db")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add jump host").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("bastion").assertExists()
        compose.onNodeWithText("ops@bastion.example.net:2200").assertExists()
        compose.onNodeWithText("relay").assertExists()
        compose.onNodeWithText("The login goes through each hop in order, top first, each with its own key and its own known-host check.").assertExists()
        capture("host-editor-jump-hosts")

        // The panel's last row at the bottom edge puts the whole panel, toggle over forwards, in the frame.
        compose.onNodeWithText("Add tunnel").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Tunnels only").assertExists()
        compose.onNodeWithText("127.0.0.1:5433 \u2192 localhost:5432").assertExists()
        capture("host-editor-tunnels-only")

        // Back up to the chain, its first hop at the top edge, so the menu opens under Add jump host with the hops above it.
        compose.onNodeWithText("bastion").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Add jump host").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("homelab")).fetchSemanticsNodes().isNotEmpty() }
        // The title (the Name field aside): a host cannot jump through itself, so the menu leaves it out.
        compose.onAllNodes(hasText("prod-db") and !hasSetTextAction()).assertCountEquals(1)
        compose.onAllNodesWithText("bastion").assertCountEquals(1) // its row only; a hop is offered once
        settle(300)
        capture("host-editor-jump-add-menu")
    }

    /** Editing bastion: relay and prod-db both reach here through their own chains, so neither is offered; homelab is. */
    @Test
    fun `a host whose chain leads back is not offered as a hop`() {
        seed()
        themed { HostEditorScreen(graph.viewModel, hostId = "bastion", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("bastion")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Direct login. Add a host to log in through it first; each hop uses its own key and its own known-host check.").assertExists()
        compose.onNodeWithText("Add jump host").performScrollTo()
        compose.onNodeWithText("Add jump host").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("homelab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("relay").assertCountEquals(0)
        compose.onAllNodesWithText("prod-db").assertCountEquals(0)
    }

    /**
     * An `ssh://` link no saved host answers to: the editor opens with the name from the fragment,
     * the address, port and user from the link, Tunnels only on because the link asks only for
     * forwards, a line at the top saying where the fields came from and that its two forwards are
     * saved with the host, and the header's button doing both: Save and open tunnels.
     */
    @Test
    fun `host editor prefilled from a link`() {
        seed()
        val link = "ssh://ops@edge.example.net:2200/?L=8443:localhost:443&D=1080#edge"
        themed { HostEditorScreen(graph.viewModel, hostId = null, onDone = {}, link = link) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save and open tunnels")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("From the link ops@edge.example.net:2200: no saved host has this address, port and user. Its 2 forwards are saved as tunnels with the host.").assertExists()
        compose.onNodeWithText("edge").assertExists()
        compose.onNodeWithText("edge.example.net").assertExists()
        compose.onNodeWithText("2200").assertExists()
        compose.onNodeWithText("ops").assertExists()
        capture("host-editor-from-link")
        compose.onNodeWithText("Save the host to add tunnels.").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Tunnels only").assertExists()
        capture("host-editor-from-link-tunnels")
    }

    // ---- links ---------------------------------------------------------------------------------

    /** A link that cannot be read: the shell's notice bar names what was wrong, with OK to put it away; nothing else moves. */
    @Test
    fun `a malformed link's notice`() {
        seed()
        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("No tabs")).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { graph.viewModel.openLink("ssh://ben@[fe80::1") }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("The IPv6 address is missing its closing bracket.")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("nothing opened", graph.sessions.tabs.value.isEmpty())
        settle(400)
        capture("link-malformed-notice")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("closing bracket", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    // ---- the Tunnels tab -------------------------------------------------------------------------

    /**
     * The Tunnels tab after the process came back: its forwards as rows that say Not connected (Off
     * for the disabled one), the caption saying the tunnels run while the login is up and that this
     * login opens no shell, and the same Detached pill a terminal has. Its overflow offers Terminal
     * and Files where a terminal's has the Deck, Find and History. In the switcher it is a card with the link
     * glyph, next to homelab's frame.
     */
    @Test
    fun `tunnels tab detached, its overflow, and the switcher`() {
        seed()
        seedTabs()
        runBlocking { graph.sessions.restore() }
        val tab = graph.sessions.get("s-tunnels")!!
        assertEquals(TabKind.Tunnels, tab.kind)
        graph.sessions.setActive(tab.id)
        val switcher = mutableStateOf(false)
        themed {
            Stage(graph.viewModel.activeTab.collectAsState().value)
            if (switcher.value) TabSwitcher(graph.viewModel, tabActions(), onDismiss = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("127.0.0.1:5433 \u2192 localhost:5432")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Detached \u00B7 7 min ago").assertExists()
        compose.onAllNodes(hasText("Not connected", substring = true)).assertCountEquals(2)
        compose.onAllNodes(hasText("Dynamic \u00B7 Off", substring = true)).assertCountEquals(1)
        compose.onNodeWithText("Tunnels run while this login is up \u00B7 this login opens no shell; Terminal and Files are in the menu.").assertExists()
        compose.onNodeWithContentDescription("Tunnels \u00B7 prod-db, detached", substring = true).assertExists()
        capture("tunnels-tab-detached")

        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Terminal")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Files").assertCountEquals(1)
        compose.onAllNodesWithText("Find").assertCountEquals(0)
        compose.onAllNodesWithText("History").assertCountEquals(0)
        compose.onAllNodesWithText("Show Deck").assertCountEquals(0)
        compose.onAllNodesWithText("Reconnect").assertCountEquals(2) // the pill's button, and the menu's row
        settle(300)
        capture("tunnels-tab-overflow")
        // A row closes the menu before it acts; Session's action is a no-op here.
        compose.onNodeWithText("Session").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Terminal")).fetchSemanticsNodes().isEmpty() }

        switcher.value = true
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Frame of homelab")).fetchSemanticsNodes().isNotEmpty() }
        settle(400)
        capture("tab-switcher-tunnels")
    }

    // ---- live, against the two local sshds --------------------------------------------------------

    /**
     * Through the production stack against the two sshds, when `SSH_TEST_*` and `SSH_TEST_JUMP_PORT`
     * are set. First a Tunnels tab whose hop accepts the socket and never greets: the login is held
     * at the hop, and the pill under the waiting forwards reads "Connecting via gateway…" for as long
     * as that takes; Close drops it. Then a Tunnels tab on the target through the bastion: the
     * bastion's key comes up for trust under its own swatch while the pill still names the hop;
     * trusted twice, the tab is Live with the target's forwards up, Open on the web port, and after
     * three requests through the local forward its row counts them and their bytes. Last, a terminal
     * whose bastion (the same sshd, now trusted, under a host with the wrong secret) refuses the
     * login fails on the Stage naming the hop.
     */
    @Test
    fun `the pill naming the hop, the hop's trust sheet, a live Tunnels tab through the bastion, and a hop failure on the stage`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_JUMP_PORT not set", jumpPort > 0)
        val body = "served through the chain"
        val http = webServer().apply {
            createContext("/") { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        // Open shows for a destination that looks like a web port (Host.openUrl); with every such port taken here, the row has no Open to show.
        val webLike = http.address.port in WEB_PORTS
        // A hop that accepts and never speaks: the login waits at its greeting until the tab is closed.
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val bastion = Host(
                id = "bastion", name = "bastion", color = SwatchColor.SLATE, monogram = "BA", address = sshHost, port = jumpPort, user = sshUser,
                auth = AuthMethod.Password(AuthResolver.passwordSecretId("bastion")), createdAt = now - TimeUnit.DAYS.toMillis(3),
            )
            val locked = bastion.copy(id = "locked-bastion", name = "old bastion", color = SwatchColor.RUST, monogram = "OB", auth = AuthMethod.Password(AuthResolver.passwordSecretId("locked-bastion")))
            val gateway = bastion.copy(id = "gateway", name = "gateway", color = SwatchColor.OCHRE, monogram = "GA", address = "127.0.0.1", port = silent.localPort, auth = AuthMethod.Password(AuthResolver.passwordSecretId("gateway")))
            val target = Host(
                id = "target", name = "prod-db", color = SwatchColor.VERDIGRIS, monogram = "PD", address = sshHost, port = sshPort, user = sshUser,
                auth = AuthMethod.Password(AuthResolver.passwordSecretId("target")), jumpHostIds = listOf("bastion"), createdAt = now - TimeUnit.DAYS.toMillis(2),
            )
            val behindLocked = target.copy(id = "behind-locked", name = "prod-web", color = SwatchColor.COPPER, monogram = "PW", jumpHostIds = listOf("locked-bastion"))
            val behindGateway = target.copy(id = "behind-gateway", name = "prod-cache", color = SwatchColor.PLUM, monogram = "PC", jumpHostIds = listOf("gateway"))
            val webPort = freePort()
            runBlocking {
                graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now))
                graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
                for (id in listOf("bastion", "target", "gateway")) graph.secrets.put(AuthResolver.passwordSecretId(id), sshPassword.toByteArray())
                graph.secrets.put(AuthResolver.passwordSecretId("locked-bastion"), "not-the-password".toByteArray())
                listOf(bastion, locked, gateway, target, behindLocked, behindGateway).forEach { graph.hosts.upsert(it) }
                graph.tunnels.upsert(Tunnel("t-web", target.id, TunnelType.LOCAL, "127.0.0.1", webPort, "127.0.0.1", http.address.port))
                graph.tunnels.upsert(Tunnel("t-socks", target.id, TunnelType.DYNAMIC, "127.0.0.1", freePort(), "", 0))
                graph.tunnels.upsert(Tunnel("t-redis", behindGateway.id, TunnelType.LOCAL, "127.0.0.1", 16379, "localhost", 6379))
                graph.tunnels.upsert(Tunnel("t-metrics", behindGateway.id, TunnelType.LOCAL, "127.0.0.1", 19090, "localhost", 9090))
                graph.sessions.restore()
            }
            val edited = ArrayList<String>()
            themed {
                Stage(graph.viewModel.activeTab.collectAsState().value, onEditHost = { edited += it })
                PromptHost(graph.prompts)
            }

            // Held at the hop: the forwards wait for the login, and the pill says which hop it is waiting on.
            val held = runBlocking { graph.sessions.openTunnels(behindGateway) }
            compose.waitUntil(10_000) { held.via.value == "via gateway" }
            waitForText("Connecting via gateway\u2026", 5_000, held)
            compose.onAllNodes(hasText("Waiting for the login", substring = true)).assertCountEquals(2)
            compose.onNodeWithText("Tunnels start when the login is up \u00B7 this login opens no shell; Terminal and Files are in the menu.").assertExists()
            settle(500)
            capture("stage-connecting-via")
            runBlocking { graph.sessions.close(held.id) }

            // A Tunnels tab through the bastion: its key comes up for trust under the hop's own swatch and endpoint, the pill still naming the hop.
            val tunnels = runBlocking { graph.sessions.openTunnels(target) }
            waitFor("the bastion's trust prompt", 20_000, tunnels) { (graph.prompts.current.value as? Prompt.TrustHostKey)?.host?.id == "bastion" }
            waitFor("via bastion", 5_000, tunnels) { tunnels.via.value == "via bastion" }
            waitForText("Connecting via bastion\u2026", 5_000, tunnels)
            // The sheet is the hop's: it says so, names the host beside its endpoint, and says where the chain is going.
            compose.onNodeWithText("Berth has not seen this jump host before. It is hop 1 of 1 on the way to prod-db.").assertExists()
            compose.onNodeWithText("bastion").assertExists()
            compose.onNodeWithText("$sshUser@$sshHost:$jumpPort").assertExists()
            settle(500)
            capture("stage-jump-trust-sheet")
            compose.onNodeWithText("Trust and connect").performClick()
            waitFor("the target's trust prompt", 20_000, tunnels) { (graph.prompts.current.value as? Prompt.TrustHostKey)?.host?.id == "target" }
            compose.onNodeWithText("Berth has not seen this server before.").assertExists()
            compose.onNodeWithText("prod-db").assertExists()
            compose.onNodeWithText("Trust and connect").performClick()
            waitFor("the Tunnels tab Live", 45_000, tunnels) { tunnels.state == SessionState.LIVE }
            waitFor("both forwards up", 15_000, tunnels) { tunnels.tunnels.value.values.count { it is TunnelStatus.Up } == 2 }
            repeat(3) { assertEquals(body, get("http://127.0.0.1:$webPort/")) }
            val traffic = (tunnels.tunnels.value["t-web"] as TunnelStatus.Up).traffic!!
            compose.waitUntil(5_000) { traffic.connections == 3 && traffic.openConnections == 0 }
            // The rows read the counters on the 1 Hz clock; a tick and a half lands the count.
            settle(1_600)
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("3 served", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            if (webLike) compose.onNodeWithText("Open").assertExists()
            compose.onNodeWithText("2 of 2 up \u00B7 this login opens no shell; Terminal and Files are in the menu.").assertExists()
            compose.onNodeWithContentDescription("Tunnels \u00B7 prod-db, live", substring = true).assertExists()
            capture("tunnels-tab-live")

            // The hop refuses the login: its key is trusted by now, so no sheet; the failure names the hop and where it sits, not the target.
            val failed = runBlocking { graph.sessions.open(behindLocked) }
            compose.waitUntil(45_000) { failed.state == SessionState.FAILED }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("old bastion (jump host) did not accept the credentials for $sshUser.")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("the Tunnels tab keeps running behind it", tunnels.state == SessionState.LIVE)
            // The action beside Retry is the hop's, since the credentials that failed are old bastion's; the target's editor stays a text action.
            compose.onNodeWithText("Edit old bastion").assertExists()
            compose.onNodeWithText("Edit host").assertExists()
            settle(400)
            capture("stage-jump-hop-failed")
            compose.onNodeWithText("Edit old bastion").performClick()
            assertEquals(listOf("locked-bastion"), edited)
            compose.onNodeWithText("Edit host").performClick()
            assertEquals(listOf("locked-bastion", "behind-locked"), edited)
        } finally {
            http.stop(0)
            silent.close()
        }
    }

    // ---- fixture ---------------------------------------------------------------------------------

    /** Home: bastion (direct), relay (through bastion), prod-db (through bastion, then relay; tunnels only) and homelab. */
    private fun seed() = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.hosts.upsert(host("bastion", "bastion", "bastion.example.net", "ops", SwatchColor.SLATE, port = 2200))
        graph.hosts.upsert(host("relay", "relay", "10.0.0.2", "ops", SwatchColor.COPPER, jumpHostIds = listOf("bastion")))
        graph.hosts.upsert(host("prod-db", "prod-db", "10.0.4.12", "deploy", SwatchColor.VERDIGRIS, jumpHostIds = listOf("bastion", "relay"), tunnelsOnly = true))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.MOSS))
        graph.tunnels.upsert(Tunnel("t-pg", "prod-db", TunnelType.LOCAL, "127.0.0.1", 5433, "localhost", 5432))
        graph.tunnels.upsert(Tunnel("t-web", "prod-db", TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80))
        graph.tunnels.upsert(Tunnel("t-socks", "prod-db", TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0, enabled = false))
    }

    /** A Tunnels tab on prod-db, detached seven minutes ago, beside a detached homelab terminal with a frame. */
    private fun seedTabs() = runBlocking {
        val hosts = graph.hosts.items.value.associateBy { it.id }
        val prodDb = hosts.getValue("prod-db")
        val homelab = hosts.getValue("homelab")
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "s-homelab", workspaceId = Workspace.DEFAULT_ID, hostId = homelab.id, hostSnapshot = homelab, state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME, title = homelab.name, cwd = "~/srv", lastCommand = "docker compose ps", sortOrder = 0,
                createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
            ),
        )
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "s-tunnels", workspaceId = Workspace.DEFAULT_ID, hostId = prodDb.id, hostSnapshot = prodDb, state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME, title = "Tunnels \u00B7 ${prodDb.name}", sortOrder = 1,
                createdAt = now - TimeUnit.HOURS.toMillis(2), lastLiveAt = now - TimeUnit.MINUTES.toMillis(7), kind = TabKind.Tunnels,
            ),
        )
        graph.sessionRecords.saveFrame(
            "s-homelab",
            frame(
                listOf(
                    "ben@homelab:~/srv$ docker compose ps",
                    "NAME        IMAGE               STATUS        PORTS",
                    "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
                    "postgres    postgres:16         Up 3 days     5432/tcp",
                    "ben@homelab:~/srv$ ",
                ),
            ),
        )
    }

    private fun host(
        id: String,
        name: String,
        address: String,
        user: String,
        color: SwatchColor,
        port: Int = 22,
        jumpHostIds: List<String> = emptyList(),
        tunnelsOnly: Boolean = false,
    ) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = port, user = user, auth = AuthMethod.AskEachTime,
        jumpHostIds = jumpHostIds, tunnelsOnly = tunnelsOnly, lastConnectedAt = now - TimeUnit.MINUTES.toMillis(40), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun frame(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(1)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
        }
        return out.toByteArray()
    }

    /** Waits for [text] on screen; a miss names the session's state and hop and prints every root, so what showed instead is in the failure. */
    private fun waitForText(text: String, timeoutMs: Long, session: TerminalSession) =
        waitFor("\u201C$text\u201D on screen", timeoutMs, session) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** Waits for [condition]; a miss says what was waited for, where the session and the prompt stood, and what was on screen. */
    private fun waitFor(what: String, timeoutMs: Long, session: TerminalSession, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            val roots = compose.onAllNodes(isRoot())
            val trees = List(roots.fetchSemanticsNodes().size) { i -> roots[i].printToString(maxDepth = Int.MAX_VALUE) }
            val prompt = graph.prompts.current.value?.let { "${it.javaClass.simpleName} for ${it.host.name}" }
            throw AssertionError(
                "no $what after $timeoutMs ms; state=${session.state} via=${session.via.value} failure=${session.failure.value} prompt=$prompt\n${trees.joinToString("\n----\n")}",
                e,
            )
        }
    }

    /** One request over one connection: keep-alive would leave it pooled, and open, past the response. */
    private fun get(url: String): String {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Connection", "close")
        return conn.getInputStream().bufferedReader().readText()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A local web server on the first free port of [WEB_PORTS], so the forward to it earns Open; on any port when all are taken. */
    private fun webServer(): HttpServer {
        for (port in WEB_PORTS) {
            try {
                return HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
            } catch (_: IOException) {
                // taken; the next
            }
        }
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    private companion object {
        /** Destination ports [app.berth.domain.model.Tunnel.openUrl] reads as a web server's. */
        val WEB_PORTS = listOf(8080, 8000, 8008, 8888, 3000, 4200, 5000, 5173, 8081, 9000, 9090)
    }
}
