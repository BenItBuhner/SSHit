package app.berth.android.screenshots

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.security.FakeKeystore
import app.berth.android.session.AuthResolver
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshKeys
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
 * The library's data surfaces of wave four (spec C9, C10, C12, C13) through Robolectric's native
 * graphics, each at the system's 1× and at 2×, where interface text stops at its 1.3× cap (A11):
 * the host editor's question when Back would drop edits. Every capture is the accessibility audit
 * too, and no text on these surfaces is cut at either size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class LibraryDataScreenshotTest(private val systemFontScale: Float) {
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
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph

    /** Captures at 2× carry the suffix the other suites' font-cap captures do. */
    private val suffix = if (systemFontScale > 1f) "-font-scale-2x" else ""

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        RuntimeEnvironment.setFontScale(systemFontScale)
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name$suffix.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun hasNoText(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)
    private fun waitForText(text: String, timeout: Long = 5_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForNoText(text: String, timeout: Long = 5_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun assertNoTextCut(where: String, within: SemanticsMatcher? = null) {
        val cut = compose.cutTexts(within)
        assertTrue("text cut on $where at ${systemFontScale}x: $cut", cut.isEmpty())
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The editor's text field holding [text], outside any sheet. */
    private fun editorField(text: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(hasSetTextAction() and hasText(text))
    }

    /**
     * The text field under the label [label], which BerthField sets in capitals over the field's
     * box: the screen's, or with [inDialog] the open sheet's.
     */
    private fun fieldUnder(label: String, inDialog: Boolean = false): SemanticsNodeInteraction {
        val where = if (inDialog) hasAnyAncestor(isDialog()) else SemanticsMatcher("anywhere") { true }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(label.uppercase()) and where).fetchSemanticsNodes().isNotEmpty() }
        val labelBounds = compose.onNode(hasText(label.uppercase()) and where).fetchSemanticsNode().boundsInRoot
        val under = compose.onAllNodes(hasSetTextAction() and where).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= labelBounds.bottom - 1f && it.boundsInRoot.left < labelBounds.right && it.boundsInRoot.right > labelBounds.left }
            .minByOrNull { it.boundsInRoot.top } ?: throw AssertionError("no text field under $label")
        return compose.onNode(SemanticsMatcher("the field under $label") { it.id == under.id })
    }

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    // ---- the host editor's unsaved-changes guard (C10) ----------------------------------------------

    /**
     * The editor as the app hosts it: system Back not taken by the editor falls through to the
     * navigator's own handler, registered first as NavDisplay's is, which pops the entry.
     */
    private fun editor(hostId: String?, onDone: () -> Unit, onPopped: () -> Unit) = themed {
        BackHandler(onBack = onPopped)
        HostEditorScreen(graph.viewModel, hostId = hostId, onDone = onDone)
    }

    /** Nothing changed, nothing to ask: system Back and the header's back each leave at once. */
    @Test
    fun `Back from an untouched host editor leaves at once`() {
        seedLibrary()
        var done = 0
        var popped = 0
        editor("homelab", onDone = { done++ }, onPopped = { popped++ })
        editorField("192.168.1.20")
        pressBack()
        compose.waitUntil(5_000) { popped == 1 }
        hasNoText("Discard changes?")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { done == 1 }
        hasNoText("Discard changes?")
    }

    /**
     * An edit, then Back: the prompt sheet asks once (spec C10), at its content's height, naming the
     * host; Keep editing puts it away with the edit where it was, and the header's back asks again;
     * Discard leaves, and the saved host is as it was.
     */
    @Test
    fun `Back with an edit asks Discard or Keep editing`() {
        seedLibrary()
        var done = 0
        var popped = 0
        editor("homelab", onDone = { done++ }, onPopped = { popped++ })
        editorField("ben").performTextReplacement("root")
        editorField("root")
        pressBack()
        waitForText("Discard changes?")
        assertEquals("Back with an edit does not leave", 0, done + popped)
        inSheet("The edits to homelab are not saved yet.").assertExists()
        compose.assertSheetAtContentHeight("Discard", "Keep editing")
        // Discard over Keep editing, both full width.
        val discard = inSheet("Discard").fetchSemanticsNode()
        val keep = inSheet("Keep editing").fetchSemanticsNode()
        assertTrue("Discard stands over Keep editing", discard.boundsInRoot.bottom <= keep.boundsInRoot.top)
        assertEquals("the two answers are one width", discard.size.width.toFloat(), keep.size.width.toFloat(), 1f)
        capture("host-editor-discard-changes")
        assertNoTextCut("the host editor's discard question", within = isDialog())

        inSheet("Keep editing").performClick()
        waitForNoText("Discard changes?")
        editorField("root")
        assertEquals(0, done + popped)

        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Discard changes?")
        inSheet("Discard").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertEquals("Discard leaves once, by the editor's own way out", 0, popped)
        assertEquals("the saved host keeps its user", "ben", graph.hosts.items.value.first { it.id == "homelab" }.user)
    }

    /** An edit put back as it was is no edit: Back leaves without asking. */
    @Test
    fun `an edit undone by hand leaves without asking`() {
        seedLibrary()
        var popped = 0
        editor("homelab", onDone = {}, onPopped = { popped++ })
        editorField("ben").performTextReplacement("root")
        editorField("root").performTextReplacement("ben")
        editorField("ben")
        pressBack()
        compose.waitUntil(5_000) { popped == 1 }
        hasNoText("Discard changes?")
    }

    /** A new host with anything typed asks too, and says it is the new host that would go. */
    @Test
    fun `a new host with an address typed asks before it is dropped`() {
        var done = 0
        var popped = 0
        editor(null, onDone = { done++ }, onPopped = { popped++ })
        fieldUnder("Address").performTextInput("10.0.0.12")
        editorField("10.0.0.12")
        pressBack()
        waitForText("Discard changes?")
        assertEquals(0, popped)
        inSheet("This new host is not saved yet.").assertExists()
        capture("host-editor-discard-new-host")
        assertNoTextCut("the new host's discard question", within = isDialog())
        inSheet("Discard").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertTrue("nothing was saved", graph.hosts.items.value.isEmpty())
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private val now = System.currentTimeMillis()

    private fun host(id: String, name: String, address: String, user: String, color: SwatchColor, auth: AuthMethod, tags: List<String> = emptyList(), lastConnectedAgoMinutes: Long? = null) = Host(
        id = id,
        name = name,
        color = color,
        monogram = Host.monogramFor(name),
        address = address,
        port = 22,
        user = user,
        auth = auth,
        tags = tags,
        lastConnectedAt = lastConnectedAgoMinutes?.let { now - TimeUnit.MINUTES.toMillis(it) },
        createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    /** Three hosts, a software key with a passphrase, a software key without one, a hardware key and three trusted server keys. */
    private fun seedLibrary() = runBlocking {
        val laptop = SshKeys.generate(KeyAlgorithm.ED25519)
        graph.identities.insert(
            Identity("id-laptop", "laptop ed25519", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(laptop.public, "ben@laptop"), SshKeys.fingerprintSha256(laptop.public), "ben@laptop", createdAt = now - TimeUnit.DAYS.toMillis(200)),
            SshKeys.openSshPrivate(laptop, "ben@laptop", "correct horse".toCharArray()).toByteArray(),
        )
        val ci = SshKeys.generate(KeyAlgorithm.ED25519)
        graph.identities.insert(
            Identity("id-ci", "ci deploy", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE, SshKeys.openSshPublic(ci.public, "ci@build"), SshKeys.fingerprintSha256(ci.public), "ci@build", createdAt = now - TimeUnit.DAYS.toMillis(40)),
            SshKeys.openSshPrivate(ci, "ci@build").toByteArray(),
        )
        val phone = FakeKeystore.newP256()
        graph.identities.insert(
            Identity("id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(phone.public, "berth@pixel"), SshKeys.fingerprintSha256(phone.public), "berth@pixel", keystoreAlias = "berth-id-phone", createdAt = now - TimeUnit.DAYS.toMillis(12)),
            null,
        )
        graph.secrets.put(AuthResolver.passwordSecretId("homelab"), "hunter2".toByteArray())
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, AuthMethod.Key("id-laptop"), tags = listOf("prod"), lastConnectedAgoMinutes = 130))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password(AuthResolver.passwordSecretId("homelab")), tags = listOf("lab"), lastConnectedAgoMinutes = 18))
        graph.hosts.upsert(host("build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, AuthMethod.Key("id-ci")))
        fun known(id: String, host: String, port: Int, daysAgo: Long): KnownHostKey {
            val server = SshKeys.generate(KeyAlgorithm.ED25519).public
            return KnownHostKey(id, host, port, "ssh-ed25519", SshKeys.openSshPublic(server).split(" ")[1], SshKeys.fingerprintSha256(server), now - TimeUnit.DAYS.toMillis(daysAgo), now - TimeUnit.HOURS.toMillis(daysAgo))
        }
        graph.knownHosts.upsert(known("kh-api", "203.0.113.10", 22, 90))
        graph.knownHosts.upsert(known("kh-lab", "192.168.1.20", 22, 30))
        graph.knownHosts.upsert(known("kh-build", "build.internal", 22, 5))
    }
}
