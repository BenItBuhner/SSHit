package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.security.FakeKeystore
import app.berth.android.session.AgentAnswer
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.hosts.agentForwardingNote
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.theme.BerthTheme
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AgentKey
import app.berth.ssh.AgentSignPurpose
import app.berth.ssh.AgentSignRequest
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.KeyPair

/**
 * Agent forwarding's surfaces, each at 1x and at the interface's 1.3x font cap (A11): the host
 * editor's switch and Signatures picker in the Identity panel, the signature request sheet for
 * each thing a request can be (a login bound to the next server's key, one that is not, an
 * `ssh-keygen -Y` signature, data Berth cannot read) and the biometric sheet a forwarded
 * signature raises, each answered through its own buttons; and, against the two local sshds, a
 * tab off stage whose remote `ssh` asks: its ring lit in the strip, the sheet over the tab on
 * stage, Allow once letting the hop through and Deny letting it fall through.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class AgentForwardingScreenshotTest {
    @get:Rule(order = 0)
    val composeHost = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph
    private val bg = CoroutineScope(Dispatchers.IO + Job())

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull() ?: 0
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val p256KeyFile = System.getenv("SSH_TEST_P256_KEY_FILE").orEmpty()

    private val bastion = Host(
        id = "bastion", name = "bastion", color = SwatchColor.SLATE, monogram = "BA", address = "203.0.113.7", port = 22, user = "ben",
        auth = AuthMethod.Key("id-phone"), createdAt = 0L,
    )
    private val homelab = Host(
        id = "homelab", name = "homelab", color = SwatchColor.VERDIGRIS, monogram = "HL", address = "192.168.1.20", port = 22, user = "ben",
        auth = AuthMethod.Password("host-password:homelab"), createdAt = 0L,
    )

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(app)
    }

    @After
    fun tearDown() {
        bg.cancel()
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    /** No text on [where] is cut or broken inside a word; [within] as [cutTexts]'s. */
    private fun assertWhole(where: String, within: SemanticsMatcher? = null) {
        compose.assertNoTextCut(where, within)
        compose.assertNoBrokenWords(where, within)
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The system's largest font size, set before the first composition: interface text then sits at its 1.3x cap. */
    private fun atTheCap() = RuntimeEnvironment.setFontScale(2f)

    private fun waitForText(text: String, timeoutMs: Long = 5_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun security(): SecuritySettings = runBlocking { graph.settings.securitySettings.first() }

    /** The phone's Keystore key, and the two hosts: one that logs in with it, one with a password. */
    private fun seed(phoneKey: KeyPair = FakeKeystore.newP256(), hosts: List<Host> = listOf(bastion, homelab)) = runBlocking {
        graph.identities.insert(
            Identity(
                "id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC,
                SshKeys.openSshPublic(phoneKey.public, "berth@pixel"), SshKeys.fingerprintSha256(phoneKey.public), "berth@pixel",
                keystoreAlias = "berth-id-phone", createdAt = 0L,
            ),
            null,
        )
        hosts.forEach { graph.hosts.upsert(it) }
    }

    // ---- the host editor (C10) --------------------------------------------------------------------

    @Test
    fun `host editor agent forwarding`() = editor("host-editor-agent-forwarding")

    @Test
    fun `host editor agent forwarding at the font cap`() {
        atTheCap()
        editor("host-editor-agent-forwarding-font-cap")
    }

    /**
     * Off by default, under Key where spec C10 draws it. Switched on, Signatures and the note that
     * names the one key offered; Always allow says who can then sign. Save keeps both.
     */
    private fun editor(name: String) {
        seed()
        var done = 0
        themed { HostEditorScreen(graph.viewModel, hostId = bastion.id, onDone = { done++ }) }
        waitForText("Agent forwarding")
        compose.onNodeWithText("Agent forwarding").performScrollTo()
        compose.onNodeWithText("Programs on the host may ask to sign").assertExists()
        compose.onAllNodesWithText("Signatures").assertCountEquals(0)

        compose.onNodeWithText("Agent forwarding").performClick()
        waitForText("Signatures")
        val asking = agentForwardingNote("this phone", keyAuth = true, silent = false, tunnelsOnly = false)
        assertEquals(
            "Only the key \u201Cthis phone\u201D is offered, and only its signatures leave this phone. Each request asks you first, naming the host and what it is for.",
            asking,
        )
        compose.onNodeWithText(asking).performScrollTo()
        compose.onNodeWithText("Ask each time").assertExists()
        compose.settle(300)
        capture(name)
        assertWhole("the Identity panel with agent forwarding on")

        compose.onNodeWithText("Signatures").performClick()
        waitForText("Always allow")
        compose.onNodeWithText("Always allow").performClick()
        val silent = agentForwardingNote("this phone", keyAuth = true, silent = true, tunnelsOnly = false)
        waitForText(silent)
        compose.onNodeWithText(silent).performScrollTo()
        compose.settle(300)
        capture("$name-silent")
        assertWhole("the Identity panel signing without asking")

        assertFalse("nothing is kept before Save", graph.hosts.items.value.first { it.id == bastion.id }.agentForwarding)
        assertFalse(security().signsAgentSilently(bastion.id))
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertTrue(graph.hosts.items.value.first { it.id == bastion.id }.agentForwarding)
        compose.waitUntil(5_000) { security().signsAgentSilently(bastion.id) }
    }

    @Test
    fun `switching forwarding off forgets silent signing on Save`() {
        seed(hosts = listOf(bastion.copy(agentForwarding = true), homelab))
        graph.security.setHostAgentSilent(bastion.id, true)
        var done = 0
        themed { HostEditorScreen(graph.viewModel, hostId = bastion.id, onDone = { done++ }) }
        waitForText("Always allow")
        compose.onNodeWithText("Agent forwarding").performScrollTo().performClick()
        waitForNoText("Signatures")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertFalse(graph.hosts.items.value.first { it.id == bastion.id }.agentForwarding)
        compose.waitUntil(5_000) { !security().signsAgentSilently(bastion.id) }
    }

    @Test
    fun `a host that logs in with a password is told its agent has nothing to offer`() {
        seed()
        themed { HostEditorScreen(graph.viewModel, hostId = homelab.id, onDone = {}) }
        waitForText("Agent forwarding")
        compose.onNodeWithText("Agent forwarding").performScrollTo().performClick()
        val note = agentForwardingNote(null, keyAuth = false, silent = false, tunnelsOnly = false)
        waitForText(note)
        assertTrue(note, note.endsWith("so the agent has nothing to offer."))
    }

    // ---- the signature request sheet ----------------------------------------------------------------

    /** A host key for the server a login goes on to, fixed so the fingerprint in the frame is the same on every run. */
    private val nextServerKey: ByteArray =
        Buffer.PlainBuffer().putString("ssh-ed25519").putBytes(ByteArray(32) { (it * 7 + 3).toByte() }).compactData

    private val heldKey by lazy { AgentKey(FakeKeystore.newP256().public, "berth@pixel", "this phone") { _, _ -> ByteArray(0) } }

    private fun ask(purpose: AgentSignPurpose): Deferred<AgentAnswer> {
        val answer = bg.async { graph.prompts.agentRequest(bastion, AgentSignRequest(heldKey, purpose, 0)) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.AgentRequest }
        waitForText("Signature request")
        return answer
    }

    private fun answered(answer: Deferred<AgentAnswer>): AgentAnswer {
        compose.waitUntil(5_000) { answer.isCompleted }
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
        compose.settle(400)
        return runBlocking { answer.await() }
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `signature request sheets`() = sheets("")

    @Test
    fun `signature request sheets at the font cap`() {
        atTheCap()
        sheets("-font-cap")
    }

    private fun sheets(suffix: String) {
        seed()
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }

        // A login onward, bound to the next server's key: the fingerprint is the one word on where it goes.
        val login = ask(AgentSignPurpose.Login("git", "ssh-connection", nextServerKey))
        compose.onNodeWithText("A program on bastion asked to sign with the key \u201Cthis phone\u201D.").assertExists()
        compose.onNodeWithText("LOGIN AS").assertExists()
        compose.onNodeWithText("It is logging in to the server whose key is above.").assertExists()
        // The remote caused it, so nothing is primary: Deny rests first, the allows under it.
        assertTrue(top("Deny") < top("Allow once") && top("Allow once") < top("Allow for this session"))
        compose.settle(400)
        capture("agent-request-login$suffix")
        assertWhole("the signature request sheet for a login")
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        assertEquals(AgentAnswer.ALLOW_ONCE, answered(login))

        // Unbound, and a user name with a right-to-left override in it: quoted cleaned.
        val unbound = ask(AgentSignPurpose.Login("ci\u202Erunner", "ssh-connection", null))
        compose.onNodeWithText("cirunner").assertExists()
        compose.onNodeWithText("It is logging in to another server; the request does not say which.").assertExists()
        compose.settle(400)
        capture("agent-request-login-unbound$suffix")
        assertWhole("the signature request sheet for an unbound login")
        compose.onNodeWithText("Allow for this session").performScrollTo().performClick()
        assertEquals(AgentAnswer.ALLOW_FOR_SESSION, answered(unbound))

        val sshSig = ask(AgentSignPurpose.SshSig("git"))
        compose.onNodeWithText("NAMESPACE").assertExists()
        compose.onNodeWithText("It is signing for git, as a signed commit or tag does.").assertExists()
        compose.settle(400)
        capture("agent-request-sshsig$suffix")
        assertWhole("the signature request sheet for an SSHSIG")
        compose.onNodeWithText("Deny").performScrollTo().performClick()
        assertEquals(AgentAnswer.DENY, answered(sshSig))

        // Taken down without an answer: this request is refused, and nothing more is decided.
        val unknown = ask(AgentSignPurpose.Unknown(1234))
        compose.onNodeWithText("It sent 1,234 bytes in no form Berth reads, so what they are for cannot be said.").assertExists()
        compose.settle(400)
        capture("agent-request-unknown$suffix")
        assertWhole("the signature request sheet for unread data")
        compose.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(width / 2f, 60f)) }
        assertEquals(AgentAnswer.DENY, answered(unknown))

        // A biometric key's own prompt for a forwarded signature: says a program asked, and that the host stays up.
        val unlock = bg.async { graph.prompts.unlockKey(bastion, "this phone", forwarded = true) { it.cancelled.await() } }
        waitForText("Sign for bastion")
        compose.onNodeWithText(
            "A program on bastion asked for this signature. Confirm with your fingerprint, face or screen lock when the system asks; cancelling refuses it, and bastion stays connected.",
        ).assertExists()
        compose.settle(400)
        capture("agent-unlock-forwarded$suffix")
        assertWhole("the forwarded unlock sheet")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { unlock.isCompleted && graph.prompts.current.value == null }
    }

    // ---- live: the ring and the sheet against the local sshds ---------------------------------------

    @Test
    fun `live signature request lights the ring and the sheet answers it`() = live("agent-request-live", deny = true)

    @Test
    fun `live signature request at the font cap`() {
        atTheCap()
        live("agent-request-live-font-cap", deny = false)
    }

    private fun hop(word: String): String =
        "ssh -n -p $jumpPort -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes " +
            "-o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o IdentityFile=none -o LogLevel=ERROR " +
            "$sshUser@$sshHost 'echo ${word.take(3)}\"\"${word.drop(3)} on \$SSH_CONNECTION'"

    private fun screen(session: TerminalSession) = session.emulator.screenText().joinToString("\n") { it.trimEnd() }

    /** Runs [command] on a cleared screen in [session], followed by its status split as typed, so only the output matches. */
    private fun send(session: TerminalSession, command: String, id: Int) =
        session.sendText("clear; $command 2>&1; echo \"__st\"\"atus_$id:\$?\"\n")

    /**
     * A tab on the first sshd without forwarding, in front, and a second one with forwarding on beside
     * it: the second is off stage when its remote `ssh` asks the agent to sign the hop to the second
     * sshd, so its ring lights in the strip and the sheet comes up over the tab on stage. Allow once, tapped on the sheet, lets
     * the hop through; with [deny], a second hop is refused from the sheet and falls through.
     */
    private fun live(name: String, deny: Boolean) {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_JUMP_PORT not set", jumpPort > 0)
        assumeTrue("SSH_TEST_P256_KEY_FILE not set", p256KeyFile.isNotBlank())
        val loaded = SshKeys.load(File(p256KeyFile).readText())
        val keystore = FakeKeystore(KeyAuthModel.NONE, KeyPair(loaded.public, loaded.private))
        graph.keystore = keystore
        val box = Host(
            id = "agent-box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = sshHost, port = sshPort, user = sshUser,
            auth = AuthMethod.Key("id-phone"), agentForwarding = true, createdAt = 0L,
        )
        val plain = box.copy(id = "plain-box", name = "plain box", color = SwatchColor.COPPER, monogram = "PB", agentForwarding = false)
        seed(keystore.pair, listOf(box, plain))
        graph.process.start()

        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { graph.sessions.restored.value }
        graph.viewModel.open(plain)
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val onStage = graph.sessions.activeSession.value!!

        graph.viewModel.open(box)
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.let { it.id != onStage.id && it.state == SessionState.LIVE } == true }
        val forwarded = graph.sessions.activeSession.value!!
        graph.sessions.setActive(onStage.id)
        compose.waitUntil(5_000) { !forwarded.onStage && onStage.onStage }
        forwarded.resize(400, 40)
        onStage.sendText("clear\n")

        forwarded.sendText("stty -echo\n")
        send(forwarded, hop("hop-ok"), 0)
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.AgentRequest }
        waitForText("Signature request")
        waitForText("A program on Berth test box asked to sign with the key \u201Cthis phone\u201D.")
        assertTrue(forwarded.record.value.needsAttention)
        assertEquals(TerminalSession.AGENT_REQUEST_REASON, forwarded.record.value.attentionReason)
        assertFalse("the tab in front is not lit", onStage.record.value.needsAttention)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("needs attention", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(800)
        capture(name)
        assertWhole("the live signature request", isDialog())

        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        compose.waitUntil(30_000) { screen(forwarded).contains("__status_0:") }
        assertTrue(screen(forwarded), screen(forwarded).contains("__status_0:0"))
        assertTrue(screen(forwarded), screen(forwarded).contains("hop-ok on") && screen(forwarded).contains(" $jumpPort"))
        compose.waitUntil(5_000) { !forwarded.record.value.needsAttention }

        if (deny) {
            send(forwarded, hop("not-run"), 1)
            compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.AgentRequest }
            waitForText("Deny")
            compose.onNodeWithText("Deny").performScrollTo().performClick()
            compose.waitUntil(30_000) { screen(forwarded).contains("__status_1:") }
            val said = screen(forwarded)
            assertTrue(said, said.contains("__status_1:255"))
            assertTrue(said, said.contains("Permission denied"))
            assertFalse(said, said.contains("not-run"))
            compose.waitUntil(5_000) { !forwarded.record.value.needsAttention }
            assertEquals("the tab stays up", SessionState.LIVE, forwarded.state)
        }
        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
    }
}
