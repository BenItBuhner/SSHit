package app.berth.android.screenshots

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshotDir
import app.berth.android.session.AuthResolver
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.components.LocalWallClock
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A login by keyboard-interactive from the real app, against the sshd on `SSH_TEST_KBD_PORT` that
 * takes that method alone through PAM (`.github/scripts/test-sshd.sh`); skipped without it. PAM's
 * pam_echo line comes in a request with no prompt ahead of the one asking `Password: `, and the
 * sheet carries that line as its caption.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class KeyboardInteractiveScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = screenshotDir
    private lateinit var graph: TestGraph
    private val now = FIXED_NOW

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val kbdPort = System.getenv("SSH_TEST_KBD_PORT").orEmpty().toIntOrNull()

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
    fun tearDown() = graph.close()

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun waitForText(text: String) {
        try {
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val texts = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { t -> t.text } ?: emptyList() }
            throw AssertionError("'$text' never showed; the texts on screen were $texts", e)
        }
    }

    /**
     * The password saved for the host goes first and is turned down, the server taking no
     * `password` method; keyboard-interactive follows, and its sheet is the server's.
     */
    @Test
    fun `the pam_echo line PAM sends ahead of the password is the sheet's caption, and the answer logs in`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_KBD_PORT not set: no keyboard-interactive sshd to log in to", kbdPort != null)
        val box = Host(
            id = "pam-box",
            name = "PAM box",
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor("PAM box"),
            address = sshHost,
            port = kbdPort!!,
            user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("pam-box")),
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent { CompositionLocalProvider(LocalWallClock provides { now }) { AppRoot(graph.viewModel) } }
        graph.process.start()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("New tab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New tab").performClick()
        waitForText(box.name)
        compose.onNodeWithText(box.name).performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()

        compose.waitUntil(30_000) { graph.prompts.current.value is Prompt.Password }
        val prompt = graph.prompts.current.value as Prompt.Password
        assertEquals("Password: ", prompt.serverPrompt)
        assertEquals("Berth test server: keyboard-interactive login through PAM", prompt.instruction)
        assertNull("PAM names none of its requests", prompt.name)
        waitForText("Password for ${box.userAtHost}")
        waitForText("Berth test server: keyboard-interactive login through PAM")
        compose.settle(600)
        capture("prompt-password-keyboard-interactive-live")

        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(sshPassword)
        compose.onNodeWithText("Connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
    }
}
