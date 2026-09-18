package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.session.AuthResolver
import app.berth.android.session.HostKeyChangedDecision
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.rail.Rail
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.theme.BerthTheme
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
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Workspace
import app.berth.ssh.HostKeyRequest
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    @get:Rule
    val compose = createComposeRule()

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

    // ---- offline screens ------------------------------------------------------------------------

    @Test
    fun `hosts library`() {
        seedLibrary()
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenRail = {}, onKnownHosts = {})
        }
        capture("hosts")
    }

    @Test
    fun `hosts library empty`() {
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenRail = {}, onKnownHosts = {})
        }
        capture("hosts-empty")
    }

    @Test
    fun `host editor`() {
        seedLibrary()
        themed { HostEditorScreen(graph.viewModel, hostId = "build-box", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("build box")).fetchSemanticsNodes().isNotEmpty() }
        capture("host-editor")
    }

    @Test
    fun identities() {
        seedLibrary()
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        capture("identities")
    }

    @Test
    fun settings() {
        seedLibrary()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        capture("settings")
    }

    @Test
    fun `rail with workspaces and detached sessions`() {
        seedLibrary()
        seedDetachedSessions()
        themed {
            Rail(graph.viewModel, onSessionTap = {}, onNewSession = {}, onLibrary = {})
        }
        runBlocking { graph.sessions.restore() }
        compose.waitUntil(10_000) { graph.viewModel.workspaceSessions.value.size >= 2 }
        capture("rail-detached-sessions")
    }

    @Test
    fun `stage with a detached frame`() {
        seedLibrary()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed {
            StageScreen(graph.viewModel, session, onOpenRail = {}, onOpenSessionSheet = {}, onEditHost = {}, onNextSession = {}, onPreviousSession = {})
        }
        capture("stage-detached")
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
        themed { PromptHost(graph.prompts) }
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
        }

        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { graph.viewModel.workspaces.value.isNotEmpty() }
        capture("app-hosts-picker")

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

        // One tap arms Ctrl for the next key; a second tap locks it, a third releases it.
        compose.onNode(hasContentDescription("Ctrl", substring = true)).performClick()
        capture("stage-live-ctrl-latched")
        compose.onNode(hasContentDescription("Ctrl", substring = true)).performClick()
        compose.onNode(hasContentDescription("Ctrl", substring = true)).performClick()

        session.sendText("clear && htop\n")
        settle(3_000)
        capture("stage-live-htop")
        session.sendText("q")
        settle(600)

        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Detach")).fetchSemanticsNodes().isNotEmpty() }
        capture("session-sheet")
        dismissSheet()

        // Second session on the ask-each-time host: the password prompt comes from the transport.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Open the rail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open the rail").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New session")).fetchSemanticsNodes().isNotEmpty() }
        capture("rail-live")
        compose.onNodeWithText("New session").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Same box, ask each time")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Same box, ask each time").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.Password }
        capture("prompt-password-live")
        (graph.prompts.current.value as Prompt.Password).submit(sshPassword.toCharArray())
        compose.waitUntil(45_000) { graph.sessions.sessions.value.count { it.state == SessionState.LIVE } == 2 }
        settle(800)
        compose.onNodeWithContentDescription("Open the rail").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New session")).fetchSemanticsNodes().isNotEmpty() }
        capture("rail-two-live-sessions")

        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
    }

    /** Taps the modal sheet's scrim near the top of the screen, where the sheet itself is not. */
    private fun dismissSheet() {
        compose.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(width / 2f, 60f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
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
