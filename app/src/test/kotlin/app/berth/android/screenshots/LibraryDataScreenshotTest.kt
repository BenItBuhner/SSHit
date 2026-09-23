package app.berth.android.screenshots

import android.app.Application
import android.content.Context
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.security.FakeKeystore
import app.berth.android.session.AuthResolver
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.settings.HOSTS_EXPORT_NOTE
import app.berth.android.ui.settings.ImportBundleSheet
import app.berth.android.ui.settings.KnownHostsScreen
import app.berth.android.ui.settings.PickedFile
import app.berth.android.ui.settings.UNSAVED_HOST_REMOTE_CLIPBOARD_CAPTION
import app.berth.android.ui.settings.hardwareNote
import app.berth.android.ui.settings.keysLine
import app.berth.android.ui.settings.leftBehindNote
import app.berth.android.ui.settings.namedKeyLine
import app.berth.android.ui.settings.recreateNote
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.NOTICE_BAR_MS
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.data.bundle.BerthBundles
import app.berth.data.bundle.BundleCodec
import app.berth.data.bundle.BundleKdf
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.ssh.SshCiphers
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The library's data surfaces of wave four (spec C9, C10, C12, C13) through Robolectric's native
 * graphics, each at the system's 1× and at 2×, where interface text stops at its 1.3× cap (A11):
 * the host editor's question when Back would drop edits and its Advanced › Scrollback and
 * Ciphers rows, with the Stage's failure when a server takes none of a host's ciphers (against
 * the local sshd), Rename and Change protection on the Keys screen, the Hosts screen's Export hosts
 * and the `+` long-press to Quick connect, with a hosts-only file's import, and Known hosts' swipe
 * to forget with Undo. Every capture is the accessibility audit too, and no text on these
 * surfaces is cut at either size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class LibraryDataScreenshotTest(private val systemFontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "system font scale {0}")
        fun scales(): List<Array<Any>> = listOf(arrayOf(1f), arrayOf(2f))

        private const val OTHER_PASSPHRASE = "moving day 2026"
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

    /** [text] laid out on one line: a one-word title its row's value squeezes breaks mid-word, which no ellipsis marks. */
    private fun assertOneLine(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$text on one line at ${systemFontScale}x", 1, layouts.single().lineCount)
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Back as the system hands it to an open sheet: to the sheet window's own dispatcher, where M3's sheet listens. */
    private fun pressBackOnSheet() {
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Taps the modal sheet's scrim near the top of the screen, where the sheet itself is not. */
    private fun tapSheetScrim() {
        compose.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(width / 2f, 60f)) }
        compose.waitForIdle()
    }

    /** Drags the open sheet down by [title], further than the sheet is tall. */
    private fun swipeSheetDown(title: String) {
        inSheet(title).performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 250) }
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

    private fun sheetFieldUnder(label: String) = fieldUnder(label, inDialog = true)

    private fun inSheet(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun waitForTextContaining(text: String, timeout: Long = 5_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

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

    /**
     * The sheet's own ways out keep editing (spec C10): Back while it is up, a tap on the scrim and a
     * swipe down each put it away with the editor open and the edit standing, and the next Back asks again.
     */
    @Test
    fun `Back, the scrim and a swipe on the discard question each keep editing`() {
        seedLibrary()
        var done = 0
        var popped = 0
        editor("homelab", onDone = { done++ }, onPopped = { popped++ })
        editorField("ben").performTextReplacement("root")
        editorField("root")
        val ways = listOf<Pair<String, () -> Unit>>(
            "Back" to { pressBackOnSheet() },
            "the scrim" to { tapSheetScrim() },
            "a swipe down" to { swipeSheetDown("Discard changes?") },
        )
        for ((way, putAway) in ways) {
            pressBack()
            waitForText("Discard changes?")
            putAway()
            waitForNoText("Discard changes?")
            assertEquals("$way keeps the editor open", 0, done + popped)
            editorField("root")
        }
        pressBack()
        waitForText("Discard changes?")
        assertEquals(0, done + popped)
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

    /**
     * Agent forwarding switched on is an edit Back asks about. Always allow chosen under it and then
     * hidden by switching forwarding off again saves nothing, as Save keeps it, so Back leaves.
     */
    @Test
    fun `Agent forwarding alone asks, and a Signatures choice it hides again does not`() {
        seedLibrary()
        var done = 0
        var popped = 0
        editor("build-box", onDone = { done++ }, onPopped = { popped++ })
        waitForText("Agent forwarding")
        compose.onNodeWithText("Agent forwarding").performScrollTo().performClick()
        waitForText("Signatures")
        pressBack()
        waitForText("Discard changes?")
        inSheet("Keep editing").performClick()
        waitForNoText("Discard changes?")

        compose.onNodeWithText("Signatures").performScrollTo().performClick()
        waitForText("Always allow")
        compose.onNodeWithText("Always allow").performClick()
        waitForTextContaining("Anything on build box, root included, can sign as you without asking")
        compose.onNodeWithText("Agent forwarding").performScrollTo().performClick()
        waitForNoText("Signatures")
        pressBack()
        compose.waitUntil(5_000) { popped == 1 }
        hasNoText("Discard changes?")
        assertEquals(0, done)
    }

    /** On a host that forwards its agent, the Signatures choice alone asks, and Discard keeps it as it was. */
    @Test
    fun `a Signatures choice alone asks before it is dropped`() {
        seedLibrary()
        runBlocking { graph.hosts.upsert(graph.hosts.items.value.first { it.id == "prod-api" }.copy(agentForwarding = true)) }
        var done = 0
        editor("prod-api", onDone = { done++ }, onPopped = {})
        waitForText("Signatures")
        compose.onNodeWithText("Signatures").performScrollTo().performClick()
        waitForText("Always allow")
        compose.onNodeWithText("Always allow").performClick()
        waitForTextContaining("Anything on prod-api, root included, can sign as you without asking")
        pressBack()
        waitForText("Discard changes?")
        inSheet("The edits to prod-api are not saved yet.").assertExists()
        inSheet("Discard").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertFalse(runBlocking { graph.settings.securitySettings.first() }.signsAgentSilently("prod-api"))
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

    /** A new host's Remote clipboard row waits for the host to be saved and says so in its caption, which is whole at the font cap. */
    @Test
    fun `a new host's Remote clipboard row says in its caption that the host is saved first`() {
        editor(null, onDone = {}, onPopped = {})
        waitForText("Remote clipboard")
        compose.onNodeWithText("Remote clipboard").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText(UNSAVED_HOST_REMOTE_CLIPBOARD_CAPTION).assertIsDisplayed()
        hasNoText("Clipboard writes from this server (OSC 52)")
        capture("host-editor-new-remote-clipboard")
        assertNoTextCut("a new host's Remote clipboard row")
    }

    // ---- the host editor's Scrollback and Ciphers (C2, C10 Advanced) ------------------------------

    /**
     * Advanced › Scrollback on a host that has none of its own: Inherit, saying what Settings ›
     * Terminal holds now; Ciphers on Default, with what that offers. The menu lists Settings' own
     * choices under Inherit, and one picked is the host's, saved with it and asked about on Back.
     */
    @Test
    fun `Scrollback says what Inherit is and saves the host's own, beside Ciphers on Default`() {
        seedLibrary()
        runBlocking { graph.settings.updateTerminalSettings { it.copy(scrollbackLines = 20_000) } }
        var done = 0
        editor("homelab", onDone = { done++ }, onPopped = {})
        waitForText("Ciphers")
        compose.onNodeWithText("Ciphers").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Scrollback").assertIsDisplayed()
        compose.onNodeWithText("Inherit (20,000)").assertIsDisplayed()
        compose.onNodeWithText("History kept above the screen").assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("Every cipher Berth knows, old ones too").assertIsDisplayed()
        capture("host-editor-advanced-scrollback-ciphers")
        assertNoTextCut("the host editor's Advanced panel")
        assertOneLine("Scrollback")
        assertOneLine("Ciphers")

        compose.onNodeWithText("Scrollback").performClick()
        waitForText("50,000 lines")
        for (lines in listOf("1,000 lines", "10,000 lines", "100,000 lines")) compose.onAllNodesWithText(lines).assertCountEquals(1)
        capture("host-editor-scrollback-menu")
        compose.onNodeWithText("50,000 lines").performClick()
        waitForNoText("Inherit (20,000)")
        compose.onNodeWithText("50,000 lines").assertIsDisplayed()

        pressBack()
        waitForText("Discard changes?")
        inSheet("Keep editing").performClick()
        waitForNoText("Discard changes?")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { done == 1 }
        val saved = graph.hosts.items.value.first { it.id == "homelab" }
        assertEquals(50_000, saved.scrollbackLines)
        assertEquals("Ciphers left on Default", emptyList<String>(), saved.ciphers)
    }

    /**
     * A cipher list neither choice names, as a bundle from another phone may carry, reads as
     * Custom with the ciphers it offers; opening the editor on it is no edit, the menu keeps it
     * beside Default and Modern only, and Modern only picked is what Save keeps.
     */
    @Test
    fun `Ciphers shows a list from elsewhere as Custom, keeps it on offer, and saves Modern only`() {
        seedLibrary()
        val custom = listOf("aes256-gcm@openssh.com", "aes256-ctr")
        runBlocking { graph.hosts.upsert(graph.hosts.items.value.first { it.id == "build-box" }.copy(ciphers = custom)) }
        var done = 0
        var popped = 0
        editor("build-box", onDone = { done++ }, onPopped = { popped++ })
        waitForText("Ciphers")
        compose.onNodeWithText("Ciphers").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Custom").assertIsDisplayed()
        compose.onNodeWithText("Offers aes256-gcm, aes256-ctr").assertIsDisplayed()
        capture("host-editor-ciphers-custom")
        assertNoTextCut("the host editor's Ciphers row on a custom list")
        assertOneLine("Scrollback")
        assertOneLine("Ciphers")

        compose.onNodeWithText("Ciphers").performClick()
        waitForText("Modern only")
        compose.onAllNodesWithText("Custom").assertCountEquals(2)
        capture("host-editor-ciphers-menu")
        compose.onNodeWithText("Modern only").performClick()
        waitForText("ChaCha20, AES-GCM and AES-CTR only")
        compose.onNodeWithText("Ciphers").performScrollTo()
        compose.waitForIdle()
        capture("host-editor-ciphers-modern")
        assertNoTextCut("the host editor's Ciphers row on Modern only")

        // The list the editor opened with is still one pick away.
        compose.onNodeWithText("Ciphers").performClick()
        waitForText("Custom")
        compose.onNodeWithText("Custom").performClick()
        waitForText("Offers aes256-gcm, aes256-ctr")
        pressBack()
        compose.waitUntil(5_000) { popped == 1 }
        hasNoText("Discard changes?")

        compose.onNodeWithText("Ciphers").performClick()
        waitForText("Modern only")
        compose.onNodeWithText("Modern only").performClick()
        waitForText("ChaCha20, AES-GCM and AES-CTR only")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertEquals(SshCiphers.MODERN, graph.hosts.items.value.first { it.id == "build-box" }.ciphers)
    }

    /**
     * Against the local sshd, when `SSH_TEST_*` is set: a host offering only 3des-cbc, which the
     * server does not take, fails on the Stage before any login, in words that name the host
     * editor's row, and Edit host beside Retry goes to it. The mismatch comes before the host key,
     * so no trust sheet stands in front of the failure.
     */
    @Test
    fun `a host offering only ciphers the server lacks fails on the Stage naming the Ciphers row`() {
        val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val legacy = host("legacy-nas", "legacy nas", sshHost, System.getenv("SSH_TEST_USER").orEmpty(), SwatchColor.MOSS, AuthMethod.Password(AuthResolver.passwordSecretId("legacy-nas")))
            .copy(port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22, ciphers = listOf("3des-cbc"))
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(legacy.id), "never sent".toByteArray())
            graph.hosts.upsert(legacy)
            graph.sessions.restore()
        }
        val edited = ArrayList<String>()
        themed {
            val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
            StageScreen(graph.viewModel, graph.viewModel.activeTab.collectAsState().value, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = { edited += it })
        }
        val tab = runBlocking { graph.sessions.connect(legacy) }
        compose.waitUntil(20_000) { tab.state == SessionState.FAILED }
        waitForText("The server accepts none of the ciphers set under Advanced \u203A Ciphers.")
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("Edit host").assertIsDisplayed()
        capture("stage-failed-no-common-cipher")
        // The strip above holds tab titles to its own height at the cap, so the check is the panel's.
        val panel = hasAnyChild(hasText("Couldn't connect"))
        assertTrue(
            "the check reaches the panel's text",
            compose.onAllNodes(hasText("The server accepts none", substring = true) and hasAnyAncestor(panel), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertNoTextCut("the Stage's failure on a cipher mismatch", within = panel)
        compose.onNodeWithText("Edit host").performClick()
        assertEquals(listOf("legacy-nas"), edited)
    }

    // ---- Keys: Rename and Change protection (C12) ------------------------------------------------

    private fun keyMenu(name: String, item: String) {
        waitForText(name)
        compose.onNodeWithText(name).performTouchInput { longClick() }
        waitForText(item)
        compose.onNodeWithText(item).performClick()
    }

    private fun storedKey(id: String) = runBlocking { String(requireNotNull(graph.identities.privateKey(id)), Charsets.UTF_8) }

    /** The row's menu in the spec's order, Save public key beside Share as it was, the two new entries before Delete. */
    @Test
    fun `the key row's menu has Rename and Change protection before Delete`() {
        seedLibrary()
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        waitForText("laptop ed25519")
        compose.onNodeWithText("laptop ed25519").performTouchInput { longClick() }
        waitForText("Change protection")
        val order = listOf("Copy public key", "Share public key", "Save public key\u2026", "Show QR", "Install on host", "Rename", "Change protection", "Delete")
        val tops = order.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("the menu reads in the spec's order", tops.sorted(), tops)
        capture("keys-row-menu-rename-protection")
        assertNoTextCut("the key row's menu")
    }

    /** Rename changes the name Berth shows and nothing else: the key pair, its fingerprint and the host that uses it stay. */
    @Test
    fun `Rename changes the key's name and nothing else`() {
        seedLibrary()
        val before = graph.identities.items.value.first { it.id == "id-ci" }
        val pem = storedKey("id-ci")
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        keyMenu("ci deploy", "Rename")
        waitForText("Rename key")
        inSheet("Only the name changes. The key, its fingerprint and the hosts that use it stay as they are.").assertExists()
        inSheet("Rename").assertIsNotEnabled()
        compose.assertSheetAtContentHeight("Rename", "Cancel")
        capture("key-rename")
        assertNoTextCut("the Rename key sheet")

        compose.onNode(hasSetTextAction() and hasText("ci deploy") and hasAnyAncestor(isDialog())).performTextReplacement("   ")
        inSheet("Rename").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("  ci deploy 2026 ")
        inSheet("Rename").assertIsEnabled().performClick()
        waitForNoText("Rename key")
        waitForText("ci deploy 2026")
        val after = graph.identities.items.value.first { it.id == "id-ci" }
        assertEquals(before.copy(name = "ci deploy 2026"), after)
        assertEquals("the private key is untouched", pem, storedKey("id-ci"))
        assertEquals(listOf("build-box"), runBlocking { graph.identities.hostsUsing("id-ci") }.map { it.id })
    }

    /**
     * A key with a passphrase: the current one opens it, a wrong one is named on its field and
     * changes nothing, and None writes the same key pair back with no passphrase.
     */
    @Test
    fun `a passphrase is taken off after the current one opens the key`() {
        seedLibrary()
        val before = graph.identities.items.value.first { it.id == "id-laptop" }
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        keyMenu("laptop ed25519", "Change protection")
        waitForText("Current passphrase".uppercase())
        inSheet("laptop ed25519 \u00B7 Ed25519").assertExists()
        inSheet("The new passphrase replaces the old one and is asked for on each connection.").assertExists()
        inSheet("New passphrase".uppercase()).assertExists()
        inSheet("Save").assertIsNotEnabled()
        compose.assertSheetAtContentHeight("Save", "Cancel")
        capture("key-change-protection-passphrase")
        assertNoTextCut("the Change protection sheet on a passphrase key")

        inSheet("None").performClick()
        waitForText("Signs in without asking. The key file stays encrypted at rest under this phone's Keystore.")
        hasNoText("New passphrase".uppercase())
        sheetFieldUnder("Current passphrase").performTextInput("wrong horse")
        inSheet("Save").assertIsEnabled().performClick()
        waitForText("That passphrase didn't unlock the key.", timeout = 15_000)
        assertEquals("a wrong passphrase changes nothing", before, graph.identities.items.value.first { it.id == "id-laptop" })
        assertTrue(SshKeys.isEncrypted(storedKey("id-laptop")))
        capture("key-change-protection-wrong-passphrase")
        assertNoTextCut("the Change protection sheet with a wrong passphrase")

        sheetFieldUnder("Current passphrase").performTextReplacement("correct horse")
        hasNoText("That passphrase didn't unlock the key.")
        inSheet("Save").performClick()
        waitForNoText("Change protection", timeout = 15_000)
        val after = graph.identities.items.value.first { it.id == "id-laptop" }
        assertEquals(before.copy(protection = KeyProtection.NONE), after)
        val pem = storedKey("id-laptop")
        assertFalse("the key file has no passphrase now", SshKeys.isEncrypted(pem))
        assertEquals("the same key pair", before.fingerprintSha256, SshKeys.fingerprintSha256(SshKeys.importPrivate(pem).pair.public))
    }

    /** A key without one gets a passphrase typed twice; until the two agree, Save waits and the second field says why. */
    @Test
    fun `a passphrase is put on a key that had none, typed twice`() {
        seedLibrary()
        val before = graph.identities.items.value.first { it.id == "id-ci" }
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        keyMenu("ci deploy", "Change protection")
        waitForText("Signs in without asking. The key file is encrypted at rest under this phone's Keystore.")
        hasNoText("Current passphrase".uppercase())
        inSheet("Save").assertIsNotEnabled()
        capture("key-change-protection-none")
        assertNoTextCut("the Change protection sheet on a key without a passphrase")

        inSheet("Passphrase").performClick()
        waitForText("Asked for on each connection; the key file is also encrypted at rest.")
        sheetFieldUnder("New passphrase").performTextInput("tr0ub4dor")
        sheetFieldUnder("Confirm new passphrase").performTextInput("tr0ub4dor!")
        waitForText("The two passphrases don't match.")
        inSheet("Save").assertIsNotEnabled()
        compose.assertSheetAtContentHeight("Save", "Cancel")
        capture("key-change-protection-mismatch")
        assertNoTextCut("the Change protection sheet with passphrases that differ")

        sheetFieldUnder("Confirm new passphrase").performTextReplacement("tr0ub4dor")
        hasNoText("The two passphrases don't match.")
        inSheet("Save").assertIsEnabled().performClick()
        waitForNoText("Change protection", timeout = 15_000)
        assertEquals(before.copy(protection = KeyProtection.PASSPHRASE), graph.identities.items.value.first { it.id == "id-ci" })
        val pem = storedKey("id-ci")
        assertTrue(SshKeys.isEncrypted(pem))
        assertTrue(runCatching { SshKeys.importPrivate(pem) }.exceptionOrNull() is SshKeys.ImportError.PassphraseNeeded)
        assertEquals(before.fingerprintSha256, SshKeys.fingerprintSha256(SshKeys.importPrivate(pem, "tr0ub4dor".toCharArray()).pair.public))
        waitForTextContaining("Passphrase \u00B7 asked for on each connection")
    }

    /**
     * The two ways a software key cannot be written again name it as the key “name”: no stored file
     * at all, and a stored file that is not the key the row describes, which the sheet shows under its
     * notes and which changes nothing.
     */
    @Test
    fun `a key that cannot be written again is named as the key in its reason`() {
        seedLibrary()
        val ci = graph.identities.items.value.first { it.id == "id-ci" }
        assertEquals(
            AppViewModel.KeyChangeResult.Failed("The key \u201Cwork\u201D has no stored private key."),
            runBlocking { graph.viewModel.changeProtection(ci.copy(id = "id-gone", name = "work"), null, "tr0ub4dor".toCharArray()) },
        )

        val laptop = graph.identities.items.value.first { it.id == "id-laptop" }
        val drifted = ci.copy(fingerprintSha256 = laptop.fingerprintSha256)
        runBlocking { graph.identities.update(drifted) }
        val pem = storedKey("id-ci")
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        keyMenu("ci deploy", "Change protection")
        inSheet("Passphrase").performClick()
        sheetFieldUnder("New passphrase").performTextInput("tr0ub4dor")
        sheetFieldUnder("Confirm new passphrase").performTextInput("tr0ub4dor")
        inSheet("Save").assertIsEnabled().performClick()
        waitForText("The stored file for the key \u201Cci deploy\u201D does not match its fingerprint, so it was left as it is.", timeout = 15_000)
        assertNoTextCut("the Change protection sheet with a key file that is not the key")
        assertEquals(drifted, graph.identities.items.value.first { it.id == "id-ci" })
        assertEquals("the key file is left as it is", pem, storedKey("id-ci"))
    }

    /** A hardware key's protection is the secure hardware's: the sheet says so, changes nothing, and offers a new hardware key. */
    @Test
    fun `a hardware key's protection is explained and left as it is`() {
        seedLibrary()
        val before = graph.identities.items.value.first { it.id == "id-phone" }
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        keyMenu("this phone", "Change protection")
        waitForText("this phone \u00B7 hardware-backed")
        inSheet("Protect").assertExists()
        hasNoText("Current passphrase".uppercase())
        hasNoText("Save")
        // "Close" alone would also find the sheet's own "Close sheet"; the button is the one with the role.
        compose.assertSheetAtContentHeight("New hardware key")
        compose.onNode(hasText("Close") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        capture("key-change-protection-hardware")
        assertNoTextCut("the Change protection sheet on a hardware key")

        inSheet("New hardware key").performClick()
        waitForText("New key")
        waitForTextContaining("Hardware-backed keys never leave this phone's secure hardware")
        assertEquals("the hardware key is as it was", before, graph.identities.items.value.first { it.id == "id-phone" })
    }

    // ---- Hosts › Export and the header's + (C9) --------------------------------------------------

    private fun hostsScreen(onAddHost: () -> Unit = {}) = themed {
        HostsScreen(graph.viewModel, onConnect = {}, onAddHost = onAddHost, onEditHost = {}, onBack = null, onOpenDrawer = null, onKnownHosts = {})
    }

    /**
     * The overflow has Export hosts after the imports and before Known hosts, as C9 orders it, and
     * its sheet is the bundle export's: the contents line counts the hosts, their saved passwords
     * and their tunnels, the line under it says the tunnels go with them, the keys stay and what a
     * key-login host does on the other phone, no hardware key is named as staying since every key
     * does, and Export waits on two matching passphrases of length.
     */
    @Test
    fun `the Hosts overflow has Export hosts, whose sheet seals the hosts alone`() {
        seedLibrary()
        runBlocking { graph.tunnels.upsert(Tunnel(id = "t-lab", hostId = "homelab", type = TunnelType.LOCAL, bindPort = 8080, destinationPort = 80)) }
        hostsScreen()
        waitForText("prod-api")
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Export hosts")
        val tops = listOf("Import known_hosts", "Export hosts", "Known hosts").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("C9's order: the imports, Export, Known hosts", tops.sorted(), tops)
        capture("hosts-overflow-export")
        assertNoTextCut("the Hosts overflow", within = isPopup())

        compose.onNodeWithText("Export hosts").performClick()
        waitForText("The host list alone, in one .berth file")
        inSheet("Export hosts").assertExists()
        waitForText("3 hosts \u00B7 1 saved password \u00B7 1 tunnel")
        waitForText(HOSTS_EXPORT_NOTE)
        compose.onAllNodes(hasText("is hardware-backed and stays on this phone", substring = true)).assertCountEquals(0)
        inSheet("Export").assertIsNotEnabled()
        sheetFieldUnder("Passphrase").performTextInput("moving day 2026")
        sheetFieldUnder("Once more").performTextInput("moving day 2026")
        inSheet("Export").assertIsEnabled()
        capture("hosts-export-sheet")
        assertNoTextCut("the Export hosts sheet")
        compose.assertSheetAtContentHeight("Export")
    }

    /** With no host there is nothing to export: the overflow leaves the row out rather than open a sheet for none. */
    @Test
    fun `with no host saved the overflow offers no Export hosts`() {
        hostsScreen()
        waitForText("Nothing here yet.")
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Known hosts")
        hasNoText("Export hosts")
    }

    /** The header's `+` (C9): a tap adds a host, a long-press opens Quick connect over the library and adds nothing. */
    @Test
    fun `the header's plus adds a host on a tap and opens Quick connect on a long-press`() {
        seedLibrary()
        var added = 0
        hostsScreen(onAddHost = { added++ })
        waitForText("prod-api")
        val plus = compose.onNodeWithContentDescription("Add host")
        plus.performClick()
        compose.waitForIdle()
        assertEquals(1, added)
        hasNoText("user@host:port")

        plus.performTouchInput { longClick() }
        waitForText("user@host:port")
        inSheet("Quick connect").assertExists()
        assertEquals("the long-press added no host", 1, added)
        capture("hosts-plus-long-press-quick-connect")
        assertNoTextCut("Quick connect from the header's plus")
    }

    /** A screen reader cannot guess a long-press: the `+` names it, and the action opens Quick connect as the finger does. */
    @Test
    fun `a screen reader is offered the plus's long-press as Quick connect`() {
        seedLibrary()
        var added = 0
        hostsScreen(onAddHost = { added++ })
        waitForText("prod-api")
        val plus = compose.onNodeWithContentDescription("Add host")
        plus.assert(SemanticsMatcher("a long-press named Quick connect") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Quick connect" })
        plus.performSemanticsAction(SemanticsActions.OnLongClick)
        waitForText("user@host:port")
        inSheet("Quick connect").assertExists()
        assertEquals(0, added)
    }

    /**
     * Another phone's hosts-only file, opened here: this phone holds one of its software keys (the
     * same laptop key, under its own name) and not the other, and none of the other phone's
     * hardware key. The panel counts the hosts, names the hardware key to make again, and gives
     * each key the file names without carrying a row saying whether this phone has it and so what
     * its host does; the import points the host on the laptop key at this phone's copy, leaves the
     * other two asking each time, writes no key, and ends on the two lists, the software key's
     * saying where it can come from.
     */
    @Test
    fun `a hosts-only bundle's import says which of its keys this phone has, and what the hosts on the rest do`() {
        seedLibrary()
        val blob = runBlocking { hostsOnlyFromAnotherPhone() }
        val keysBefore = graph.identities.items.value
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = null, onKnownHosts = {})
            ImportBundleSheet(graph.viewModel, onDismiss = {}, onNotice = {}, initialFile = PickedFile("berth-hosts-2026-09-21.berth", blob), onMakeKey = {})
        }
        waitForTextContaining("berth-hosts-2026-09-21.berth \u00B7 ")
        sheetFieldUnder("Passphrase").performTextInput(OTHER_PASSPHRASE)
        inSheet("Open").performClick()
        waitForText("IN THIS BUNDLE")
        waitForText("4 hosts")
        waitForText(keysLine(carried = 0, hardware = 1))
        waitForText("old laptop")
        waitForText(namedKeyLine("old laptop", KeyAlgorithm.ED25519.displayName, hereAs = "laptop ed25519", hosts = listOf("prod-web")))
        waitForText(namedKeyLine("deploy bot", KeyAlgorithm.ED25519.displayName, hereAs = null, hosts = listOf("ci-runner")))
        waitForText(recreateNote(listOf("Phone key"), listOf("db-primary")))
        capture("hosts-bundle-import-contents")
        assertNoTextCut("a hosts-only bundle, opened")

        inSheet("Import").performScrollTo().performClick()
        waitForText("Imported 4 hosts.")
        waitForText("MAKE AGAIN IN KEYS")
        waitForText("NOT ON THIS PHONE")
        waitForText(hardwareNote(1))
        waitForText(leftBehindNote(1))
        waitForTextContaining("ci-runner asks each time until you pick a key")
        capture("hosts-bundle-import-report")
        assertNoTextCut("a hosts-only bundle's report")

        val hosts = graph.hosts.items.value.associateBy { it.id }
        assertEquals("the host on the laptop key logs in with this phone's copy", AuthMethod.Key("id-laptop"), hosts.getValue("h-web").auth)
        assertEquals(AuthMethod.AskEachTime, hosts.getValue("h-ci").auth)
        assertEquals(AuthMethod.AskEachTime, hosts.getValue("h-db").auth)
        assertEquals(AuthMethod.Password("host-password:h-nas"), hosts.getValue("h-nas").auth)
        assertEquals("hunter2", runBlocking { graph.secrets.get("host-password:h-nas") }!!.toString(Charsets.UTF_8))
        assertEquals("no key was written", keysBefore, graph.identities.items.value)
        assertEquals(7, hosts.size)
    }

    /**
     * A bundle opened on the phone that made it: its hardware key is this phone's own, so the sheet
     * names it as a key this phone has, on which its host logs in, and counts no key to make again
     * and says nothing of making one; the import leaves the host on that key.
     */
    @Test
    fun `a bundle opened on the phone that made it lists no hardware key to make again`() {
        seedLibrary()
        runBlocking { graph.hosts.upsert(host("db-primary", "db-primary", "db.internal", "postgres", SwatchColor.SLATE, AuthMethod.Key("id-phone"))) }
        val blob = runBlocking {
            BerthBundles(graph.hosts, graph.identities, graph.workspaces, graph.snippets, graph.tunnels, graph.knownHosts, graph.settings, graph.secrets, BundleCodec())
                .export(OTHER_PASSPHRASE.toCharArray(), exportedAt = now - TimeUnit.DAYS.toMillis(1), appVersion = "0.2.0", cost = BundleKdf(memoryKiB = 1024, iterations = 1, parallelism = 1))
        }
        var notice: String? = null
        themed {
            ImportBundleSheet(graph.viewModel, onDismiss = {}, onNotice = { notice = it }, initialFile = PickedFile("berth-2026-09-21.berth", blob), onMakeKey = {})
        }
        waitForTextContaining("berth-2026-09-21.berth \u00B7 ")
        sheetFieldUnder("Passphrase").performTextInput(OTHER_PASSPHRASE)
        inSheet("Open").performClick()
        waitForText("IN THIS BUNDLE")
        waitForText(keysLine(carried = 2, hardware = 0))
        waitForText(namedKeyLine("this phone", KeyAlgorithm.ECDSA_P256.displayName, hereAs = "this phone", hosts = listOf("db-primary")))
        compose.onAllNodes(hasText("to make again", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("hardware-backed on the other phone", substring = true)).assertCountEquals(0)
        capture("bundle-import-same-phone")
        assertNoTextCut("a bundle opened on the phone that made it")

        // With no key to make again the sheet closes on a notice rather than a list.
        inSheet("Import").performScrollTo().performClick()
        compose.waitUntil(5_000) { notice != null }
        assertTrue("$notice", notice!!.startsWith("Imported"))
        assertEquals(AuthMethod.Key("id-phone"), graph.hosts.items.value.single { it.id == "db-primary" }.auth)
    }

    // ---- Known hosts: swipe to forget, with Undo (C13) ------------------------------------------------

    private fun knownHostsScreen() = themed { KnownHostsScreen(graph.viewModel, onBack = {}) }

    private fun knownRow(title: String, algorithm: String = "ED25519") =
        compose.onNode(hasText(title) and hasText("$algorithm \u00B7", substring = true) and hasClickAction())

    /**
     * A swipe to the left over an unpinned row shows Forget behind it, quiet until letting go would
     * forget and filled with danger after; let go past that, the key is forgotten, and the foot says
     * so for six seconds with Undo, which puts the key back as it was, with its dates. The pinned
     * build box stays in the frames as it is.
     */
    @Test
    fun `a swipe to the left forgets a known host, and Undo puts it back as it was`() {
        seedLibrary()
        runBlocking { graph.knownHosts.setPinned("kh-build", true) }
        val before = graph.knownHosts.items.value.single { it.id == "kh-lab" }
        knownHostsScreen()
        val lab = "192.168.1.20  \u00B7  homelab"
        waitForText(lab)
        val row = knownRow(lab)
        val width = row.fetchSemanticsNode().size.width.toFloat()
        row.performTouchInput {
            down(Offset(width - 24f, centerY))
            repeat(10) { moveBy(Offset(-width * 0.03f, 0f)) }
        }
        waitForText("Forget")
        capture("known-hosts-swipe")
        row.performTouchInput { repeat(10) { moveBy(Offset(-width * 0.03f, 0f)) } }
        compose.waitForIdle()
        capture("known-hosts-swipe-armed")
        row.performTouchInput { up() }

        compose.waitUntil(5_000) { graph.knownHosts.items.value.none { it.id == "kh-lab" } }
        waitForText("Forgot the key for 192.168.1.20")
        waitForNoText(lab)
        compose.onNodeWithText("Undo").assertExists()
        capture("known-hosts-forgot-undo")
        assertNoTextCut("Known hosts with a key just forgotten")

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.knownHosts.items.value.any { it.id == "kh-lab" } }
        assertEquals("the key is back as it was, with its own dates", before, graph.knownHosts.items.value.single { it.id == "kh-lab" })
        waitForText(lab)
        waitForNoText("Forgot the key for 192.168.1.20")
        knownRow("build.internal  \u00B7  build box").assert(hasText("pinned", substring = true))
        hasNoText("Forget")
        assertEquals(3, graph.knownHosts.items.value.size)
    }

    /**
     * A pinned key is the one trust nothing light may take away: its row does not move under a
     * drag or a swipe and offers a screen reader no Forget, and it is forgotten only from its
     * detail sheet, with the same Undo, which puts it back pinned.
     */
    @Test
    fun `a pinned key does not swipe and is forgotten from its sheet`() {
        seedLibrary()
        runBlocking { graph.knownHosts.setPinned("kh-build", true) }
        val before = graph.knownHosts.items.value.single { it.id == "kh-build" }
        knownHostsScreen()
        val build = "build.internal  \u00B7  build box"
        waitForText(build)
        val row = knownRow(build)
        assertTrue(
            "a pinned row offers no Forget action",
            row.fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.none { it.label == "Forget" },
        )

        val left = row.fetchSemanticsNode().boundsInRoot.left
        row.performTouchInput {
            down(Offset(width - 24f, centerY))
            repeat(20) { moveBy(Offset(-width * 0.03f, 0f)) }
        }
        compose.waitForIdle()
        assertEquals("the row stays where it is under a drag", left, row.fetchSemanticsNode().boundsInRoot.left)
        hasNoText("Forget")
        row.performTouchInput { up() }
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(3, graph.knownHosts.items.value.size)
        compose.onAllNodes(hasText("Forgot", substring = true)).assertCountEquals(0)

        row.performClick()
        waitForText("Pin this key")
        inSheet("Forget").performScrollTo().performClick()
        waitForNoText("Pin this key")
        compose.waitUntil(5_000) { graph.knownHosts.items.value.none { it.id == "kh-build" } }
        waitForText("Forgot the key for build.internal")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.knownHosts.items.value.any { it.id == "kh-build" } }
        assertEquals("the key is back pinned, with its own dates", before, graph.knownHosts.items.value.single { it.id == "kh-build" })
        waitForText(build)
        knownRow(build).assert(hasText("pinned", substring = true))
    }

    /** A swipe that stops short and comes back slowly, and one to the right, forget nothing: the row settles, and a tap still opens it. */
    @Test
    fun `a short swipe or one to the right forgets nothing`() {
        seedLibrary()
        knownHostsScreen()
        val build = "build.internal  \u00B7  build box"
        waitForText(build)
        val row = knownRow(build)
        row.performTouchInput { swipeLeft(startX = right - 24f, endX = right - 24f - width * 0.25f, durationMillis = 2_000) }
        compose.waitForIdle()
        row.performTouchInput { swipeRight(startX = left + 24f, endX = right - 24f, durationMillis = 300) }
        compose.waitForIdle()
        assertEquals(3, graph.knownHosts.items.value.size)
        hasNoText("Forget")
        compose.onAllNodes(hasText("Forgot", substring = true)).assertCountEquals(0)
        row.performClick()
        waitForText("Pin this key")
    }

    /**
     * Each key forgotten while the notice is up counts into it and restarts its six seconds, and
     * Undo puts every one of them back; left alone, the notice goes and the key stays forgotten.
     */
    @Test
    fun `keys forgotten while the notice is up count into it and come back together, and left alone the forget stands`() {
        seedLibrary()
        val before = graph.knownHosts.items.value.toSet()
        knownHostsScreen()
        for (title in listOf("203.0.113.10  \u00B7  prod-api", "build.internal  \u00B7  build box")) {
            waitForText(title)
            knownRow(title).performTouchInput { swipeLeft() }
            waitForNoText(title)
        }
        waitForText("Forgot 2 keys")
        assertEquals(listOf("kh-lab"), graph.knownHosts.items.value.map { it.id })
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.knownHosts.items.value.size == 3 }
        assertEquals(before, graph.knownHosts.items.value.toSet())

        val lab = "192.168.1.20  \u00B7  homelab"
        waitForText(lab)
        knownRow(lab).performTouchInput { swipeLeft() }
        waitForText("Forgot the key for 192.168.1.20")
        compose.mainClock.advanceTimeBy(NOTICE_BAR_MS + 100)
        waitForNoText("Forgot the key for 192.168.1.20")
        assertEquals(before.filter { it.id != "kh-lab" }.toSet(), graph.knownHosts.items.value.toSet())
    }

    /**
     * A screen reader cannot swipe: the row offers Forget as an action, and it forgets with the
     * same Undo. So does the detail sheet's Forget, whose notice names the algorithm when the
     * endpoint keeps another key on the list.
     */
    @Test
    fun `a screen reader forgets through the row's action, and the detail sheet's Forget offers the same Undo`() {
        seedLibrary()
        val other = SshKeys.generate(KeyAlgorithm.ECDSA_P256).public
        runBlocking {
            graph.knownHosts.upsert(KnownHostKey("kh-build-ecdsa", "build.internal", 22, "ecdsa-sha2-nistp256", SshKeys.openSshPublic(other).split(" ")[1], SshKeys.fingerprintSha256(other), now, now))
        }
        knownHostsScreen()
        val lab = "192.168.1.20  \u00B7  homelab"
        waitForText(lab)
        val forget = knownRow(lab).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Forget" }
        compose.runOnUiThread { forget.action() }
        compose.waitUntil(5_000) { graph.knownHosts.items.value.none { it.id == "kh-lab" } }
        waitForText("Forgot the key for 192.168.1.20")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.knownHosts.items.value.any { it.id == "kh-lab" } }
        waitForText(lab)

        val build = "build.internal  \u00B7  build box"
        knownRow(build).performClick()
        waitForText("Pin this key")
        inSheet("Forget").performScrollTo().performClick()
        waitForNoText("Pin this key")
        compose.waitUntil(5_000) { graph.knownHosts.items.value.none { it.id == "kh-build" } }
        waitForText("Forgot the ED25519 key for build.internal")
        knownRow(build, algorithm = "ECDSA P-256").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.knownHosts.items.value.any { it.id == "kh-build" } }
        assertEquals(4, graph.knownHosts.items.value.size)
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

    /**
     * Another phone's Hosts › Export: four hosts, on this phone's laptop key held there under
     * another id and name, on a software key this phone does not have, on a hardware key, and on a
     * saved password. A light key derivation keeps the test quick; the container is the same.
     */
    private suspend fun hostsOnlyFromAnotherPhone(): ByteArray {
        val other = TestStorage()
        val laptop = graph.identities.items.value.first { it.id == "id-laptop" }
        other.identities.insert(laptop.copy(id = "id-laptop-other", name = "old laptop"), graph.identities.privateKey("id-laptop"))
        val bot = SshKeys.generate(KeyAlgorithm.ED25519)
        other.identities.insert(
            Identity("id-bot", "deploy bot", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE, SshKeys.openSshPublic(bot.public, "bot@ci"), SshKeys.fingerprintSha256(bot.public), "bot@ci", createdAt = 1),
            SshKeys.openSshPrivate(bot, "bot@ci").toByteArray(),
        )
        val phone = FakeKeystore.newP256()
        other.identities.insert(
            Identity("id-phone-other", "Phone key", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(phone.public, "berth@old-phone"), SshKeys.fingerprintSha256(phone.public), "berth@old-phone", keystoreAlias = "berth-id-phone-other", createdAt = 2),
            null,
        )
        other.hosts.upsert(host("h-web", "prod-web", "203.0.113.20", "deploy", SwatchColor.TEAL, AuthMethod.Key("id-laptop-other")))
        other.hosts.upsert(host("h-ci", "ci-runner", "ci.internal", "ci", SwatchColor.OCHRE, AuthMethod.Key("id-bot")))
        other.hosts.upsert(host("h-db", "db-primary", "db.internal", "postgres", SwatchColor.SLATE, AuthMethod.Key("id-phone-other")))
        other.hosts.upsert(host("h-nas", "nas", "10.0.0.5", "admin", SwatchColor.MOSS, AuthMethod.Password("host-password:h-nas")))
        other.secrets.put("host-password:h-nas", "hunter2".toByteArray())
        val bundles = BerthBundles(other.hosts, other.identities, other.workspaces, other.snippets, other.tunnels, other.knownHosts, other.settings, other.secrets, BundleCodec())
        return bundles.exportHosts(OTHER_PASSPHRASE.toCharArray(), exportedAt = now - TimeUnit.DAYS.toMillis(1), appVersion = "0.2.0", cost = BundleKdf(memoryKiB = 1024, iterations = 1, parallelism = 1))
    }
}
