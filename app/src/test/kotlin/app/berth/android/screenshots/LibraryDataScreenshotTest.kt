package app.berth.android.screenshots

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.security.FakeKeystore
import app.berth.android.session.AuthResolver
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.keys.KeysScreen
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
import app.berth.ssh.SshCiphers
import app.berth.ssh.SshKeys
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The library's data surfaces of wave four (spec C9, C10, C12, C13) through Robolectric's native
 * graphics, each at the system's 1× and at 2×, where interface text stops at its 1.3× cap (A11):
 * the host editor's question when Back would drop edits and its Advanced › Scrollback and
 * Ciphers rows, and Rename and Change protection on the Keys screen. Every capture is the accessibility audit
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
