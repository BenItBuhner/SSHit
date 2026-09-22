package app.berth.android.screenshots

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.security.FakeKeystore
import app.berth.android.session.AuthResolver
import app.berth.android.session.HostKeyChangedDecision
import app.berth.android.session.Prompt
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.android.ui.components.HOLD_TO_CONFIRM_MS
import app.berth.android.ui.groups.GroupsScreen
import app.berth.android.ui.hosts.HostEditorFields
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.hosts.HostsScreen
import app.berth.android.ui.keys.KeyInstall
import app.berth.android.ui.keys.KeysScreen
import app.berth.android.ui.settings.KnownHostsScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.prompts.PromptHost
import app.berth.android.ui.prompts.formatDate
import app.berth.android.ui.rail.Drawer
import app.berth.android.ui.stage.SessionSheet
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.GroupEditorSheet
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.MonoFontFeatures
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.HostKeyRequest
import app.berth.ssh.KnownHostsFile
import app.berth.ssh.Randomart
import app.berth.ssh.SshAuth
import app.berth.ssh.SshKeys
import app.berth.ssh.SshLink
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The library's third wave (spec C8, C9, C10, C11, C12, C13, A16) through Robolectric's native
 * graphics, each surface at the system's 1× and at 2×, where interface text stops at its 1.3× cap
 * (A11): the Hosts screen's search, tag chips and sort with the row menu's Connect in new group,
 * Duplicate and Share as `ssh://` link; the host editor's Tags beside the name and Environment
 * and Mute bell under Advanced; Save as host from Quick connect and from the Session sheet of a
 * quick-connected tab; a key's detail with its randomart and the `ssh-keygen -lf` line, its QR
 * and Install on host; the trust sheet's visual fingerprint and compare-on-the-server command,
 * and the changed-key sheet's Replace held to confirm; the known_hosts import beside the ssh
 * config import, from the Hosts overflow, Settings › Data and the Known hosts screen; the groups
 * overview with its Edit mode and the group editor's Reconnect tabs at launch. Every capture is the accessibility audit too, and no text on a new surface is cut at
 * either size except the one line that elides by design. `install on host over the local sshd`
 * types the command into a real shell and logs in with the key it installed when the
 * `SSH_TEST_*` variables are set.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class LibraryScreenshotTest(private val systemFontScale: Float) {
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

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    /** Captures at 2× carry the suffix the other suites' font-cap captures do. */
    private val suffix = if (systemFontScale > 1f) "-font-scale-2x" else ""

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        // Set before the first composition reads the configuration, as the system's setting would be.
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

    private val clipboard: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)
    private val clipText: String? get() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private fun hasNoText(text: String) = compose.onAllNodesWithText(text).assertCountEquals(0)
    private fun waitForText(text: String, timeout: Long = 5_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForNoText(text: String, timeout: Long = 5_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    /** The one text field in the sheet's own window, whatever fields the screen under it has. */
    private val sheetField get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    /** The sheet's text field under the label [label], which BerthField sets in capitals over the field's box. */
    private fun field(label: String): SemanticsNodeInteraction {
        val labelBottom = compose.onNode(hasText(label.uppercase()) and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot.bottom
        val under = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= labelBottom - 1f }
            .minByOrNull { it.boundsInRoot.top } ?: throw AssertionError("no text field under $label")
        return compose.onNode(SemanticsMatcher("the field under $label") { it.id == under.id })
    }

    /** A tag chip: the selectable with that label, as the section label of the same name is not. */
    private fun chip(text: String) = compose.onNode(hasText(text) and isSelectable())

    /** A section label, which [SectionLabel] sets in capitals. */
    private fun section(text: String) = compose.onNodeWithText(text.uppercase())

    /** Real time passes while the compose clock keeps ticking. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    /**
     * No text on [where] is cut at this size, except one a caller names as eliding by design (the
     * QR sheet's key line stops at three lines, since its base64 middle says nothing).
     */
    private fun assertNoTextCutBut(where: String, vararg allowed: String) {
        val cut = compose.cutTexts().filter { text -> allowed.none { text.startsWith(it) } }
        assertTrue("text cut on $where at ${systemFontScale}x: $cut", cut.isEmpty())
    }

    private fun assertNoTextCut(where: String) = assertNoTextCutBut(where)

    /**
     * The sheet's buttons, [labels] among them, are measured to a size and stand whole in the
     * window as the sheet opened (#15's B8 fault, seen again on the group editor): a sheet that
     * opened at half the window left its buttons below the fold, one a sliver a few dp tall. Each
     * label's bounds in the window must be its whole measured size, and nothing is scrolled first,
     * since a scroll inside a half-open sheet expands it and would hide the fault the check is for.
     */
    private fun assertSheetButtonsReachable(vararg labels: String) {
        val buttons = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasAnyAncestor(isDialog())).fetchSemanticsNodes()
        assertTrue("the sheet's buttons, ${labels.size} named, were on it: ${buttons.size} found", buttons.size >= labels.size)
        val flat = buttons.filter { it.size.height <= 0 || it.size.width <= 0 }
        assertTrue("buttons measured to no size: ${flat.map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }}", flat.isEmpty())
        for (label in labels) {
            val node = compose.onNodeWithText(label).fetchSemanticsNode()
            val shown = node.boundsInWindow
            assertTrue("'$label' is measured to no size: ${node.size}", node.size.height > 0 && node.size.width > 0)
            assertTrue(
                "'$label' is cut by the window at ${systemFontScale}x: ${shown.height.toInt()} of ${node.size.height} px tall, ${shown.width.toInt()} of ${node.size.width} wide shown",
                shown.height >= node.size.height - 1 && shown.width >= node.size.width - 1,
            )
        }
    }

    /**
     * [node]'s text is drawn glyph for glyph as its ASCII: the layout it was measured with has
     * JetBrains Mono's ligatures and contextual alternates off ([MonoFontFeatures]), so a randomart
     * row's `.=` and `|=` and a fingerprint's `//` are the glyphs `ssh-keygen` prints, and the
     * picture on the sheet can be held against the server's. The text is asserted beside it; the
     * glyphs themselves are the capture's.
     */
    private fun assertMonoGlyphsExact(node: SemanticsNode, what: String) {
        val results = ArrayList<TextLayoutResult>()
        assertTrue("$what has no text layout to read", node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results) == true)
        val style = results.single().layoutInput.style
        assertEquals("$what is drawn with the font's ligatures on", MonoFontFeatures, style.fontFeatureSettings)
    }

    /** The one node whose text is exactly [text], read unmerged so a row's own layout is the one measured. */
    private fun textNode(text: String): SemanticsNode = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()

    // ---- hosts (C9) -------------------------------------------------------------------------------

    /**
     * With no host saved, the empty state (spec C1) offers Add host with Import a bundle beside it
     * on one line, Quick connect and Import ssh config under them a line each, and the second
     * opens Settings › Data's bundle import (spec C20) as it is there, under the one title, its
     * buttons whole in the window as the sheet opens.
     */
    @Test
    fun `the empty Hosts library offers a bundle import that opens the import sheet`() {
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        waitForText("Nothing here yet.")
        val add = compose.onNodeWithText("Add host").fetchSemanticsNode()
        val import = compose.onNodeWithText("Import a bundle").fetchSemanticsNode()
        assertEquals("the secondary stands on the primary's line", add.positionInRoot.y, import.positionInRoot.y, 1f)
        assertTrue("the secondary stands beside the primary, after it", import.positionInRoot.x >= add.positionInRoot.x + add.size.width)
        val quick = compose.onNodeWithText("Quick connect").fetchSemanticsNode()
        assertTrue("the text actions take their own lines under the pair", quick.positionInRoot.y >= add.positionInRoot.y + add.size.height - 1)
        assertTrue(compose.onNodeWithText("Import ssh config").fetchSemanticsNode().positionInRoot.y > quick.positionInRoot.y)
        capture("hosts-empty-restore")
        assertNoTextCut("the empty Hosts library")

        compose.onNodeWithText("Import a bundle").performClick()
        waitForText("Import bundle")
        compose.onNodeWithText("A .berth file Berth exported, here or on another phone").assertExists()
        compose.onNodeWithText("Open").assertIsNotEnabled()
        assertSheetButtonsReachable("Choose file", "Open", "Cancel")
        capture("hosts-empty-restore-sheet")
        assertNoTextCut("the bundle import opened from the empty Hosts library")
    }

    /**
     * The pair keeps to the margin where the line is short for it: on a 300 dp window (a phone
     * narrower than this fixture's, or a longer translation) Add host and Import a bundle share the
     * line at 1×, and at the font cap, where the two run past the margin, the secondary wraps under
     * the primary flush with it, 8 dp below, in place of being cut at the edge; the text actions
     * keep their lines under the pair either way. Geometry, not a frame.
     */
    @Test
    fun `the empty Hosts library's pair wraps under itself on a narrow window rather than past the margin`() {
        themed { Box(Modifier.width(300.dp)) { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) } }
        waitForText("Nothing here yet.")
        val density = compose.density.density
        val add = compose.onNodeWithText("Add host").fetchSemanticsNode()
        val import = compose.onNodeWithText("Import a bundle").fetchSemanticsNode()
        assertTrue("the secondary is whole within the 300 dp", import.positionInRoot.x + import.size.width <= 300 * density + 0.5f)
        if (systemFontScale > 1f) {
            assertEquals("wrapped flush with the primary", add.positionInRoot.x, import.positionInRoot.x, 1f)
            assertTrue("wrapped under the primary, 8 dp below it", import.positionInRoot.y >= add.positionInRoot.y + add.size.height + 8 * density - 1f)
        } else {
            assertEquals("on the primary's line", add.positionInRoot.y, import.positionInRoot.y, 1f)
            assertTrue("beside the primary, after it", import.positionInRoot.x >= add.positionInRoot.x + add.size.width)
        }
        val quick = compose.onNodeWithText("Quick connect").fetchSemanticsNode()
        assertTrue("the text actions take their lines under the pair", quick.positionInRoot.y >= import.positionInRoot.y + import.size.height - 1f)
        assertNoTextCut("the empty Hosts library on a 300 dp window")
    }

    @Test
    fun `hosts search, tag chips and sort`() {
        seedLibrary()
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        waitForText("homelab")
        // Every tag once, alphabetically, All first and chosen; the hosts under Recent then All.
        chip("All").assertIsSelected()
        chip("dns").assertExists()
        chip("lab").assertExists()
        chip("prod").assertExists()
        section("Recent").assertExists()
        // Recent is the three last connected, the latest first; All is the rest in the same order, here
        // the one host that has never connected. Each host is on the screen once (nit 1).
        val standing = listOf("RECENT", "homelab", "prod-api", "pi-hole", "ALL", "build box").map { it to compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(standing.map { it.first }, standing.sortedBy { it.second }.map { it.first })
        capture("hosts-library")
        assertNoTextCut("the Hosts library")

        // Search reads name, address, user and tags: `hole` leaves pi-hole by its name (`pi` would keep
        // prod-api too), `deploy` prod-api by its user, `dns` pi-hole by its tag. A narrowed list is one
        // list: no Recent over it, so a match connected lately is not said twice.
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("hole")
        waitForNoText("prod-api")
        compose.onAllNodesWithText("pi-hole").assertCountEquals(1)
        compose.onNodeWithText("pi-hole").assertIsDisplayed()
        hasNoText("homelab")
        section("Recent").assertDoesNotExist()
        capture("hosts-search")
        search.performTextReplacement("deploy")
        waitForText("prod-api")
        hasNoText("pi-hole")
        search.performTextReplacement("dns")
        waitForText("pi-hole")
        hasNoText("prod-api")
        search.performTextReplacement("nowhere")
        waitForText("No host matches.")
        search.performTextClearance()
        waitForText("homelab")

        // A chip narrows to its tag, one list again; a second tap on it lets go, and Recent is back.
        chip("lab").performClick()
        waitForNoText("prod-api")
        compose.onNodeWithText("homelab").assertIsDisplayed()
        compose.onNodeWithText("pi-hole").assertIsDisplayed()
        hasNoText("build box")
        section("Recent").assertDoesNotExist()
        // The one list keeps the sort's order: homelab connected after pi-hole did.
        assertTrue(compose.onNodeWithText("homelab").fetchSemanticsNode().positionInRoot.y < compose.onNodeWithText("pi-hole").fetchSemanticsNode().positionInRoot.y)
        chip("lab").assertIsSelected()
        capture("hosts-tag-chip")
        search.performTextInput("zzz")
        waitForText("No host is tagged lab and matches.")
        search.performTextClearance()
        chip("lab").performClick()
        waitForText("prod-api")
        chip("All").assertIsSelected()
        section("Recent").assertExists()

        // Sort from the overflow: the row names the sort in force, the submenu the three orders.
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Sort \u00B7 Recent")
        compose.onNodeWithText("Import ssh config").assertExists()
        compose.onNodeWithText("Import known_hosts").assertExists()
        capture("hosts-more-menu")
        compose.onNodeWithText("Sort \u00B7 Recent").performClick()
        waitForText("Sort by tag")
        compose.onNodeWithText("Sort by name").assertExists()
        capture("hosts-sort-menu")
        compose.onNodeWithText("Sort by tag").performClick()
        waitForText("DNS")
        // A section per tag, alphabetically, the untagged last; no Recent section in this order.
        section("Recent").assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("UNTAGGED"))
        section("Untagged").assertIsDisplayed()
        capture("hosts-sorted-by-tag")
        assertNoTextCut("the Hosts library sorted by tag")

        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Sort \u00B7 Tag")
        compose.onNodeWithText("Sort \u00B7 Tag").performClick()
        waitForText("Sort by name")
        compose.onNodeWithText("Sort by name").performClick()
        waitForNoText("UNTAGGED")
        section("Recent").assertDoesNotExist()
    }

    @Test
    fun `host row menu connects in a new group, duplicates, and shares an ssh link`() {
        seedLibrary()
        val inNewGroup = ArrayList<String>()
        val edited = ArrayList<String>()
        val tunnels = ArrayList<String>()
        val files = ArrayList<String>()
        // Wired as AppRoot wires the screen, every door open, so the capture and its audit are of the menu the app shows (nit 8).
        themed {
            HostsScreen(
                graph.viewModel,
                onConnect = {},
                onAddHost = {},
                onEditHost = { edited += it },
                onBack = null,
                onOpenDrawer = {},
                onKnownHosts = {},
                onFiles = { files += it.id },
                onTunnels = { tunnels += it.id },
                onConnectInNewGroup = { inNewGroup += it.id },
            )
        }
        waitForText("homelab")

        // The row's menu: C9's list with Files after the tunnel row, in that order, Delete last and apart.
        compose.onAllNodesWithText("homelab")[0].performTouchInput { longClick() }
        waitForText("Connect in new group")
        val rows = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Edit", "Connect in new group", "Connect as tunnel only", "Files", "Duplicate", "Share as ssh:// link", "Delete"), rows)
        capture("hosts-row-menu-library")
        assertNoTextCut("the host row's menu")
        compose.onNodeWithText("Connect as tunnel only").performClick()
        waitForNoText("Connect as tunnel only")
        assertEquals(listOf("homelab"), tunnels)
        compose.onAllNodesWithText("homelab")[0].performTouchInput { longClick() }
        waitForText("Files")
        compose.onNodeWithText("Files").performClick()
        waitForNoText("Files")
        assertEquals(listOf("homelab"), files)
        compose.onAllNodesWithText("homelab")[0].performTouchInput { longClick() }
        waitForText("Connect in new group")
        compose.onNodeWithText("Connect in new group").performClick()
        waitForNoText("Connect in new group")
        assertEquals(listOf("homelab"), inNewGroup)

        // Duplicate: a copy named after the original, its password under a secret of its own, opened in the editor to be named.
        compose.onAllNodesWithText("homelab")[0].performTouchInput { longClick() }
        waitForText("Duplicate")
        compose.onNodeWithText("Duplicate").performClick()
        compose.waitUntil(5_000) { graph.hosts.items.value.any { it.name == "homelab copy" } }
        val original = graph.hosts.items.value.first { it.id == "homelab" }
        val copy = graph.hosts.items.value.first { it.name == "homelab copy" }
        assertEquals(listOf(copy.id), edited)
        assertNotEquals(original.id, copy.id)
        assertEquals(original.tags, copy.tags)
        assertEquals(original.address, copy.address)
        assertNull("a copy has never connected", copy.lastConnectedAt)
        val copyAuth = copy.auth as AuthMethod.Password
        assertNotEquals((original.auth as AuthMethod.Password).secretId, copyAuth.secretId)
        assertEquals("hunter2", String(runBlocking { graph.secrets.get(copyAuth.secretId!!) }!!))
        waitForText("homelab copy")
        capture("hosts-duplicated")

        // Share as ssh:// link: the system share sheet with the link as its text, the trusted key's fingerprint in it.
        compose.onAllNodesWithText("prod-api")[0].performTouchInput { longClick() }
        waitForText("Share as ssh:// link")
        compose.onNodeWithText("Share as ssh:// link").performClick()
        compose.waitUntil(5_000) { shadowOf(context.applicationContext as Application).peekNextStartedActivity() != null }
        val chooser = shadowOf(context.applicationContext as Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("prod-api", send.getStringExtra(Intent.EXTRA_SUBJECT))
        val link = send.getStringExtra(Intent.EXTRA_TEXT)!!
        // The plain `ssh://user@host` for any client, the fingerprint percent-encoded (its base64 may hold a `/`), the name in the fragment.
        assertTrue(link, link.startsWith("ssh://deploy;fingerprint=SHA256%3A") && link.endsWith("@203.0.113.10#prod-api"))
        assertFalse(link, link.substringAfter(";fingerprint=").substringBefore('@').any { it == '/' || it == '+' || it == ':' })
        val parsed = (SshLink.parse(link) as SshLink.Result.Parsed).link
        assertEquals("deploy", parsed.user)
        assertEquals("203.0.113.10", parsed.host)
        assertEquals(22, parsed.port)
        assertEquals(graph.knownHosts.items.value.first { it.host == "203.0.113.10" }.fingerprintSha256, parsed.fingerprint)
        assertEquals("prod-api", parsed.name)
    }

    // ---- host editor (C10) ------------------------------------------------------------------------

    @Test
    fun `host editor has tags beside the name, environment and mute bell under Advanced`() {
        seedLibrary()
        var done = 0
        themed { HostEditorScreen(graph.viewModel, hostId = "pi-hole", onDone = { done++ }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText("lab, dns")).fetchSemanticsNodes().isNotEmpty() }
        section("Tags").assertExists() // a field's label, set in capitals as a section label is
        capture("host-editor-tags")
        assertNoTextCut("the host editor's top")

        // Advanced: the environment as NAME=value lines, its helper, and Mute bell with its caption.
        compose.onNodeWithText("Mute bell").performScrollTo()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("LANG=C.UTF-8")).assertExists()
        compose.onNodeWithText("One NAME=value per line; the server's AcceptEnv decides which arrive.").assertExists()
        compose.onNodeWithText("No buzz when the shell rings; off stage the tab still lights.").assertExists()
        capture("host-editor-advanced")
        assertNoTextCut("the host editor's Advanced panel")
        // The helper keeps a field's gap to the label under it (nit 2): in the panel a field's text
        // stands 15 dp over the next label (its box's unseen bottom half and the panel's 4 dp), and the
        // helper stood 4 dp over TERMINAL TYPE.
        val helperBottom = compose.onNodeWithText("One NAME=value per line; the server's AcceptEnv decides which arrive.").fetchSemanticsNode().boundsInRoot.bottom
        val nextLabelTop = compose.onNodeWithText("TERMINAL TYPE").fetchSemanticsNode().boundsInRoot.top
        val helperGap = (nextLabelTop - helperBottom) / compose.density.density
        assertTrue("the Environment helper stands $helperGap dp over TERMINAL TYPE, a field's text stands 15", helperGap >= 14f)

        // The panel's end: the Alt key's caption whole beside the widest value the editor has, then Delete host.
        compose.onNodeWithText("Alt key").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("For a hardware keyboard").assertExists()
        compose.onNodeWithText("Inherit (escape prefix)").assertExists()
        capture("host-editor-advanced-end")

        // A line that is not NAME=value stops Save and says which line, then what a line is (nit 2).
        compose.onNode(hasSetTextAction() and hasText("LANG=C.UTF-8")).performTextReplacement("LANG=C.UTF-8\n1BAD=x")
        val environmentHelp = "Line 2: 1BAD is not a name a shell takes. " + HostEditorFields.ENVIRONMENT_HELP
        waitForText(environmentHelp)
        compose.onNodeWithText("Save").assertIsNotEnabled()
        capture("host-editor-environment-error")
        assertNoTextCut("the host editor with an environment error")

        // Fixed, tagged and muted: Save writes all three.
        compose.onNode(hasSetTextAction() and hasText("LANG=C.UTF-8\n1BAD=x")).performTextReplacement("LANG=C.UTF-8\nTERM_PROGRAM=berth")
        waitForNoText(environmentHelp)
        compose.onNodeWithText("Mute bell").performClick()
        section("Tags").performScrollTo()
        compose.onNode(hasSetTextAction() and hasText("lab, dns")).performTextReplacement("lab, dns, Home, dns")
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { done == 1 }
        val saved = graph.hosts.items.value.first { it.id == "pi-hole" }
        assertEquals(listOf("lab", "dns", "Home"), saved.tags)
        assertEquals(mapOf("LANG" to "C.UTF-8", "TERM_PROGRAM" to "berth"), saved.environment)
        assertTrue(saved.muteBell)
        assertFalse("agent forwarding stays off unless it is switched on", saved.agentForwarding)
    }

    // ---- save as host (C11) -----------------------------------------------------------------------

    @Test
    fun `quick connect saves the address as a host`() {
        seedLibrary()
        // The login was quick-connected before: its commands are kept under the login (spec C16) and move to the host it is saved as.
        val quickKey = Host.quickCommandHistoryKey("deploy", "203.0.113.99", 2200)
        runBlocking {
            graph.commandHistory.record(quickKey, "uptime", now - TimeUnit.HOURS.toMillis(2))
            graph.commandHistory.record(quickKey, "df -h", now - TimeUnit.HOURS.toMillis(1))
        }
        val edited = ArrayList<String>()
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = { edited += it }, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        waitForText("homelab")
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Quick connect")
        compose.onNodeWithText("Quick connect").performClick()
        waitForText("Save as host")
        compose.onNodeWithText("Save as host").assertIsNotEnabled()
        sheetField.performTextInput("deploy@203.0.113.99:2200")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Save as host").fetchSemanticsNodes().isNotEmpty() && runCatching { compose.onNodeWithText("Save as host").assertIsEnabled() }.isSuccess }
        capture("quick-connect-save-as-host")
        assertNoTextCut("the Quick connect sheet")

        // Saved under the address's name with the identity picked (none: ask on connect), and handed to the editor; no tab opens.
        compose.onNodeWithText("Save as host").performClick()
        compose.waitUntil(5_000) { graph.hosts.items.value.any { it.address == "203.0.113.99" } }
        val saved = graph.hosts.items.value.first { it.address == "203.0.113.99" }
        assertEquals("203.0.113.99", saved.name)
        assertEquals(2200, saved.port)
        assertEquals("deploy", saved.user)
        assertEquals(AuthMethod.AskEachTime, saved.auth)
        assertEquals(listOf(saved.id), edited)
        assertTrue("nothing connected", graph.sessions.sessions.value.isEmpty())
        // The saved host's id is its history key, and the login's two commands are under it now, oldest first, none left under the login.
        assertEquals(saved.id, saved.commandHistoryKey)
        compose.waitUntil(5_000) { graph.commandHistory.items.value.any { it.hostId == saved.id } }
        assertEquals(listOf("uptime", "df -h"), graph.commandHistory.items.value.filter { it.hostId == saved.id }.map { it.text })
        assertTrue(graph.commandHistory.items.value.none { it.hostId == quickKey })
        waitForNoText("Quick connect")

        // A spec that does not parse says why under the field and saves nothing.
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Quick connect")
        compose.onNodeWithText("Quick connect").performClick()
        waitForText("Save as host")
        sheetField.performTextInput("deploy@[::1")
        compose.onNodeWithText("Save as host").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("bracket", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, graph.hosts.items.value.count { it.user == "deploy" && it.id != "prod-api" })
    }

    @Test
    fun `the session sheet of a quick-connected tab offers Save as host`() {
        seedLibrary()
        val edited = ArrayList<String>()
        var dismissed = 0
        val tab = quickTab()
        themed {
            SessionSheet(graph.viewModel, tab, onDismiss = { dismissed++ }, onSwitch = {}, onEditHost = { edited += it }, onNewSession = {})
        }
        waitForText("Save as host")
        // The tab's host is nobody's in the library: no Host button, no Tunnels, the offer to save instead.
        hasNoText("Host")
        hasNoText("Tunnels")
        compose.onNodeWithText("root@198.51.100.7:2202").assertExists()
        // The sheet opens whole: the offer and Close are on screen at the cap as well, no drag first
        // (half-open, its second row of pills stood below the fold at that size).
        compose.onNodeWithText("Save as host").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
        capture("session-sheet-save-as-host")
        assertNoTextCut("the Session sheet of a quick-connected tab")

        // Saved under the id the tab carries, so the tab is the host's from here on, and opened in the editor.
        compose.onNodeWithText("Save as host").performClick()
        compose.waitUntil(5_000) { graph.hosts.items.value.any { it.id == tab.record.value.hostId } }
        val saved = graph.hosts.items.value.first { it.id == tab.record.value.hostId }
        assertEquals("198.51.100.7", saved.address)
        assertEquals(2202, saved.port)
        assertEquals("root", saved.user)
        compose.waitUntil(5_000) { edited == listOf(saved.id) }
        assertEquals(1, dismissed)
    }

    // ---- keys (C12) -------------------------------------------------------------------------------

    @Test
    fun `key detail shows the fingerprint, the randomart and the ssh-keygen line`() {
        seedLibrary()
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        waitForText("laptop ed25519")
        compose.onNodeWithText("laptop ed25519").performClick()
        waitForText("Show visual fingerprint")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ed25519 \u00B7 passphrase \u00B7 used by prod-api").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("As ssh-keygen prints it".uppercase()).assertExists()
        val keygenLine = SshKeys.keygenLine(laptopKey.public, "ben@laptop")
        compose.onNodeWithText(keygenLine).assertExists()
        compose.onNodeWithText("Show QR").assertExists()
        compose.onNodeWithText("Install on host").assertExists()
        capture("key-detail")
        assertNoTextCut("the key detail sheet")

        // The randomart behind the reveal, drawn as ssh-keygen -lv draws it, and the way back.
        compose.onNodeWithText("Show visual fingerprint").performClick()
        waitForText("Hide visual fingerprint")
        val art = compose.onNode(hasContentDescription("Visual fingerprint", substring = true), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(Randomart.of(laptopKey.public), art.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        assertMonoGlyphsExact(art, "the randomart")
        assertMonoGlyphsExact(textNode(keygenLine), "the ssh-keygen line")
        compose.onNodeWithText("Install on host").performScrollTo()
        compose.waitForIdle()
        capture("key-detail-randomart")
        assertNoTextCut("the key detail sheet with its randomart")

        // The line is copied whole, the fingerprint the tool would print for the file.
        compose.onNodeWithContentDescription("Copy As ssh-keygen prints it").performClick()
        compose.waitUntil(5_000) { clipText == keygenLine }
        assertTrue(keygenLine.startsWith("256 ${SshKeys.fingerprintSha256(laptopKey.public)} ben@laptop (ED25519)"))
    }

    @Test
    fun `a key's public line as a QR code`() {
        seedLibrary()
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        waitForText("this phone")
        // The row's menu, every action of it (C12); the private key is offered nowhere.
        compose.onNodeWithText("this phone").performTouchInput { longClick() }
        waitForText("Show QR")
        for (item in listOf("Copy public key", "Share public key", "Save public key\u2026", "Install on host", "Delete")) compose.onNodeWithText(item).assertExists()
        hasNoText("Export private key")
        capture("keys-row-menu")
        assertNoTextCut("the key row's menu")

        compose.onNodeWithText("Show QR").performClick()
        waitForText("Public key as QR")
        val qr = compose.onNodeWithContentDescription("QR code of this phone's public key").fetchSemanticsNode()
        assertTrue("the code is square, ${qr.size}", qr.size.width == qr.size.height && qr.size.width > 0)
        compose.onNodeWithText("this phone \u00B7 scan it into another device's authorized_keys or key list. The private key is not in it.").assertExists()
        capture("key-qr")
        // The key line under the code is the one text that elides by design: three lines of base64, its whole copied.
        val line = graph.identities.items.value.first { it.id == "id-phone" }.publicKeyOpenSsh.trim()
        assertNoTextCutBut("the QR sheet", line)
        compose.onNodeWithContentDescription("Copy Public key").performClick()
        compose.waitUntil(5_000) { clipText == line }
        compose.onNodeWithText("Done").performClick()
        waitForNoText("Public key as QR")
    }

    @Test
    fun `install on host picks a saved host and previews the command`() {
        seedLibrary()
        themed { KeysScreen(graph.viewModel, onBack = {}) }
        waitForText("laptop ed25519")
        compose.onNodeWithText("laptop ed25519").performTouchInput { longClick() }
        waitForText("Install on host")
        compose.onNodeWithText("Install on host").performClick()
        waitForText("Adds laptop ed25519's public key to ~/.ssh/authorized_keys on the host you pick, typed into its shell.")
        // No shell is up: every saved host under SAVED, none under CONNECTED, and the button waits for a pick.
        compose.onNodeWithText("Saved".uppercase()).assertExists()
        hasNoText("Connected".uppercase())
        compose.onNodeWithText("Install").assertIsNotEnabled()
        compose.onNodeWithText("Runs in the shell".uppercase()).assertExists()
        capture("key-install")
        assertNoTextCut("the Install on host sheet")

        // A saved host without a shell: the button says it will connect first.
        compose.onNode(hasText("homelab") and hasAnyAncestor(isDialog())).performClick()
        waitForText("Connect and install")
        compose.onNodeWithText("Connect and install").assertIsEnabled()
        capture("key-install-picked")
        assertNoTextCut("the Install on host sheet with a host picked")

        // The preview elides the key's base64 middle; what is copied, and typed, is the whole command.
        val publicLine = graph.identities.items.value.first { it.id == "id-laptop" }.publicKeyOpenSsh
        compose.onNodeWithContentDescription("Copy Runs in the shell").performClick()
        compose.waitUntil(5_000) { clipText == KeyInstall.command(publicLine) }
        assertTrue(clipText!!.contains(publicLine.trim()))
        compose.onNodeWithText("Cancel").performClick()
        waitForNoText("Connect and install")
    }

    // ---- trust sheets (C13) -----------------------------------------------------------------------

    @Test
    fun `the trust sheet has a visual fingerprint and the compare-on-the-server command`() {
        seedLibrary()
        val prodApi = graph.hosts.items.value.first { it.id == "prod-api" }
        val key = SshKeys.generate(KeyAlgorithm.ED25519).public
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }
        val asking = CoroutineScope(Dispatchers.IO).async { graph.prompts.trustHostKey(prodApi, request(prodApi, key), emptyList()) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        waitForText("Show visual fingerprint")
        compose.onNodeWithText("Compare on the server".uppercase()).assertExists()
        compose.onNodeWithText("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub").assertExists()
        capture("trust-sheet")
        assertNoTextCut("the trust sheet")

        compose.onNodeWithText("Show visual fingerprint").performClick()
        waitForText("Hide visual fingerprint")
        val art = compose.onNode(hasContentDescription("Visual fingerprint", substring = true), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(Randomart.of(key), art.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        assertMonoGlyphsExact(art, "the trust sheet's randomart")
        assertMonoGlyphsExact(textNode("ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"), "the server's ssh-keygen command")
        compose.onNodeWithText("Trust and connect").performScrollTo()
        compose.waitForIdle()
        capture("trust-sheet-randomart")
        assertNoTextCut("the trust sheet with its randomart")

        compose.onNodeWithContentDescription("Copy Compare on the server").performClick()
        compose.waitUntil(5_000) { clipText == "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub" }
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(runBlocking { asking.await() })
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
    }

    @Test
    fun `the changed-key sheet's Replace is held to confirm`() {
        seedLibrary()
        val prodApi = graph.hosts.items.value.first { it.id == "prod-api" }
        val offered = SshKeys.generate(KeyAlgorithm.ED25519).public
        val saved = graph.knownHosts.items.value.first { it.host == prodApi.address }
        themed {
            HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {})
            PromptHost(graph.prompts)
        }
        val asking = CoroutineScope(Dispatchers.IO).async { graph.prompts.hostKeyChanged(prodApi, request(prodApi, offered), saved) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.HostKeyChanged }
        val label = "Replace saved key \u2014 hold to confirm"
        waitForText(label)
        compose.onNodeWithText(label).performScrollTo()
        settle(400)
        capture("changed-key-sheet")
        assertNoTextCut("the changed-key sheet")

        // Under the test clock: a tap, or a hold let go early, replaces nothing.
        compose.mainClock.autoAdvance = false
        val button = compose.onNodeWithText(label)
        button.performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        button.performTouchInput { up() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertTrue("let go at 400 ms of ${HOLD_TO_CONFIRM_MS}: still asking", graph.prompts.current.value is Prompt.HostKeyChanged)
        assertTrue(asking.isActive)

        // Held: the progress fills under the label, half way at half the hold; at the end the saved key is replaced.
        button.performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(HOLD_TO_CONFIRM_MS / 2L)
        compose.waitForIdle()
        capture("changed-key-sheet-holding")
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        assertEquals(HostKeyChangedDecision.REPLACE_SAVED, runBlocking { asking.await() })
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
    }

    // ---- known_hosts import (A16) -----------------------------------------------------------------

    @Test
    fun `known_hosts import beside the ssh config import`() {
        seedLibrary()
        themed { HostsScreen(graph.viewModel, onConnect = {}, onAddHost = {}, onEditHost = {}, onBack = null, onOpenDrawer = {}, onKnownHosts = {}) }
        waitForText("homelab")
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Import known_hosts")
        compose.onNodeWithText("Import ssh config").assertExists()
        compose.onNodeWithText("Import known_hosts").performClick()
        waitForText("Import known hosts")
        compose.onNodeWithText("Paste your ~/.ssh/known_hosts, or choose the file").assertExists()
        compose.onNodeWithText("Choose file").assertExists()
        compose.onNodeWithText("Import 0 keys").assertIsNotEnabled()
        capture("import-known-hosts-empty")
        assertNoTextCut("the empty known_hosts import sheet")

        // A file as a laptop writes one: plain names, a bracketed port, two hashed names (one a saved
        // host's, one nobody's), a key Berth already trusts, a CA line and a wildcard.
        val git = SshKeys.generate(KeyAlgorithm.ED25519).public
        val nas = SshKeys.generate(KeyAlgorithm.ECDSA_P256).public
        val prodRsa = SshKeys.generate(KeyAlgorithm.RSA_3072).public
        val stranger = SshKeys.generate(KeyAlgorithm.ED25519).public
        val trusted = graph.knownHosts.items.value.first { it.host == "203.0.113.10" }
        val text = listOf(
            "# from ben@laptop",
            "git.example.com ${SshKeys.openSshPublic(git)}",
            "[10.0.0.12]:2222 ${SshKeys.openSshPublic(nas)}",
            "${hashed("203.0.113.10")} ${SshKeys.openSshPublic(prodRsa)}",
            "203.0.113.10 ssh-ed25519 ${trusted.publicKeyBase64}",
            "${hashed("unknown.example.net")} ${SshKeys.openSshPublic(stranger)}",
            "@cert-authority *.example.com ${SshKeys.openSshPublic(git)}",
            "*.example.org ${SshKeys.openSshPublic(git)}",
        ).joinToString("\n")
        sheetField.performTextInput(text)
        waitForText("Import 3 keys")
        compose.onNodeWithText("4 keys".uppercase()).assertExists()
        compose.onNodeWithText("git.example.com").assertExists()
        compose.onNodeWithText("10.0.0.12:2222").assertExists()
        compose.onNode(hasText("matched by hash", substring = true)).assertExists()
        // Each row is a checkbox to a reader (`checked, git.example.com`): the trusted one off, the three new ones on.
        compose.onNode(hasText("trusted already", substring = true) and isToggleable()).assertIsOff()
        compose.onAllNodes(isToggleable() and isOff()).assertCountEquals(1)
        compose.onAllNodes(isToggleable() and isOn()).assertCountEquals(3)
        compose.onNodeWithText("1 hashed name matches no saved host and is left out; save the host first, then import again.").assertExists()
        compose.onNodeWithText("Line 7: a certificate authority, not a host key.\nLine 8: a wildcard pattern \u201C*.example.org\u201D.").assertExists()
        compose.onNodeWithText("Import 3 keys").performScrollTo()
        compose.waitForIdle()
        capture("import-known-hosts")
        assertNoTextCut("the known_hosts import sheet")

        // Imported: three new keys, the RSA one for prod-api beside its ed25519, the trusted one left as it was.
        compose.onNodeWithText("Import 3 keys").performClick()
        compose.waitUntil(5_000) { graph.knownHosts.items.value.size == 4 }
        waitForNoText("Import known hosts")
        val keys = graph.knownHosts.items.value
        assertEquals(setOf("203.0.113.10" to 22, "git.example.com" to 22, "10.0.0.12" to 2222), keys.map { it.host to it.port }.toSet())
        assertEquals(listOf("ssh-ed25519", "ssh-rsa"), keys.filter { it.host == "203.0.113.10" }.map { it.keyType }.sorted())
        assertEquals(trusted, keys.first { it.id == trusted.id })
        assertEquals(SshKeys.fingerprintSha256(prodRsa), keys.first { it.keyType == "ssh-rsa" }.fingerprintSha256)
    }

    /**
     * A file that carries a different key of a type Berth already trusts for an address is the
     * changed-key case (C13) in a file: the row starts unticked with the saved key and its date
     * under it in the danger tint, the button counts it apart once ticked, and the import then
     * replaces the saved key rather than adding a second beside it. An address with a pinned key
     * takes no other key: its row is read, says where the pin is undone, and is not offered.
     */
    @Test
    fun `known_hosts import holds a conflicting key unticked and a pinned endpoint's key back`() {
        seedLibrary()
        val rotated = SshKeys.generate(KeyAlgorithm.ED25519).public
        val nasPinned = SshKeys.generate(KeyAlgorithm.ED25519).public
        val nasOther = SshKeys.generate(KeyAlgorithm.ED25519).public
        val fresh = SshKeys.generate(KeyAlgorithm.ED25519).public
        val trusted = graph.knownHosts.items.value.first { it.host == "203.0.113.10" }
        val pin = KnownHostKey("kh-nas", "10.0.0.12", 2222, "ssh-ed25519", SshKeys.openSshPublic(nasPinned).split(" ")[1], SshKeys.fingerprintSha256(nasPinned), now - TimeUnit.DAYS.toMillis(30), now - TimeUnit.DAYS.toMillis(1), pinned = true)
        runBlocking { graph.knownHosts.upsert(pin) }
        themed { KnownHostsScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("203.0.113.10", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Import known_hosts")
        compose.onNodeWithText("Import known_hosts").performClick()
        waitForText("Import known hosts")

        // A rotated key for prod-api (conflicting), another key for the pinned NAS (held back), a new host's key (plainly new).
        sheetField.performTextInput(
            listOf(
                "203.0.113.10 ${SshKeys.openSshPublic(rotated)}",
                "[10.0.0.12]:2222 ${SshKeys.openSshPublic(nasOther)}",
                "git.example.com ${SshKeys.openSshPublic(fresh)}",
            ).joinToString("\n"),
        )
        waitForText("Import 1 key")
        compose.onNodeWithText("3 keys".uppercase()).assertExists()
        // The conflicting row: a checkbox, off, the saved key's fingerprint and date under it; the pinned one is no checkbox at all.
        fun start(fingerprint: String) = "SHA256:" + fingerprint.removePrefix("SHA256:").take(4)
        val conflict = compose.onNode(hasText("Differs from the saved ED25519 key ${start(trusted.fingerprintSha256)}", substring = true) and isToggleable())
        conflict.assertIsOff()
        conflict.assert(hasText("(trusted ${formatDate(trusted.firstSeenAt)}). Ticked, it replaces that key.", substring = true))
        compose.onNode(hasText("Pinned to ED25519 ${start(pin.fingerprintSha256)}", substring = true)).assert(hasText("unpin it under Known hosts first.", substring = true))
        compose.onNode(hasText("10.0.0.12:2222") and isToggleable()).assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onNode(hasText("git.example.com") and isToggleable()).assertIsOn()
        compose.onNodeWithText("Import 1 key").performScrollTo()
        compose.waitForIdle()
        capture("import-known-hosts-conflict")
        assertNoTextCut("the known_hosts import sheet with a conflicting and a pinned key")

        // Ticking the conflict is the Replace decision, and the button says so before it is taken.
        conflict.performClick()
        waitForText("Import 1 key, replace 1")
        conflict.assertIsOn()
        compose.onNodeWithText("git.example.com").performClick()
        waitForText("Replace 1 key")
        compose.onNodeWithText("git.example.com").performClick()
        waitForText("Import 1 key, replace 1")
        capture("import-known-hosts-replace")
        assertNoTextCut("the known_hosts import sheet with a conflict ticked")
        compose.onNodeWithText("Import 1 key, replace 1").performClick()
        waitForNoText("Import known hosts")
        compose.waitUntil(5_000) { graph.knownHosts.items.value.none { it.id == trusted.id } }
        val keys = graph.knownHosts.items.value
        // The saved prod-api key is gone and the rotated one stands alone for the address; the pin is untouched; git is new.
        assertEquals(setOf("203.0.113.10" to 22, "10.0.0.12" to 2222, "git.example.com" to 22), keys.map { it.host to it.port }.toSet())
        assertEquals(3, keys.size)
        assertEquals(SshKeys.fingerprintSha256(rotated), keys.single { it.host == "203.0.113.10" }.fingerprintSha256)
        assertEquals(pin, keys.single { it.host == "10.0.0.12" })
        assertEquals(SshKeys.fingerprintSha256(fresh), keys.single { it.host == "git.example.com" }.fingerprintSha256)
    }

    /** Settings › Data has the same import as a row beside the config import's, opening the same sheet. */
    @Test
    fun `known_hosts import from Settings Data`() {
        seedLibrary()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        // The panel's last row brings the whole of Data into the frame.
        compose.onNodeWithText("Import private key").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Import ssh config").assertExists()
        compose.onNodeWithText("Import known_hosts").assertExists()
        compose.onNodeWithText("Server keys from ~/.ssh/known_hosts").assertExists()
        capture("settings-data-imports")
        assertNoTextCut("Settings › Data")
        compose.onNodeWithText("Import known_hosts").performClick()
        waitForText("Import known hosts")
        compose.onNodeWithText("Choose file").assertExists()
    }

    /** The Known hosts screen's overflow holds the import too, so a key can be added where the keys are read. */
    @Test
    fun `known_hosts import from the Known hosts screen`() {
        seedLibrary()
        themed { KnownHostsScreen(graph.viewModel, onBack = {}) }
        // The row's title is the endpoint and the host's name in one line.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("203.0.113.10", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Import known_hosts")
        capture("known-hosts-more-menu")
        assertNoTextCut("the Known hosts overflow")
        compose.onNodeWithText("Import known_hosts").performClick()
        waitForText("Import known hosts")
        compose.onNodeWithText("Choose file").assertExists()
    }

    /** With no key saved yet, the empty screen offers the import itself, as the empty Hosts library offers the config import. */
    @Test
    fun `the empty Known hosts screen offers the import`() {
        seedLibrary()
        runBlocking { graph.knownHosts.delete("kh-1") }
        themed { KnownHostsScreen(graph.viewModel, onBack = {}) }
        waitForText("No saved server keys.")
        compose.onNodeWithText("Import known_hosts").assertExists()
        capture("known-hosts-empty")
        assertNoTextCut("the empty Known hosts screen")
        compose.onNodeWithText("Import known_hosts").performClick()
        waitForText("Import known hosts")
    }

    // ---- groups overview (C8) ---------------------------------------------------------------------

    @Test
    fun `groups overview cards, their edit mode and the menu`() {
        seedLibrary()
        seedTabs()
        // A bell in a tab off stage: the tab needs the user, and its group's card says so.
        graph.sessions.get("s-pihole")!!.emulator.write("\u0007")
        compose.waitUntil(5_000) { graph.sessions.records.value.any { it.id == "s-pihole" && it.needsAttention } }
        val actions = RecordingTabActions()
        val opened = ArrayList<String>()
        var newGroup = 0
        themed { GroupsScreen(graph.viewModel, actions, onBack = {}, onOpenGroup = { opened += it }, onNewGroup = { newGroup++ }) }
        waitForText("Groups")
        waitForText("2 tabs \u00B7 1 needs you")
        compose.onNodeWithText("Work").assertExists()
        compose.onNodeWithText("1 tab").assertExists()
        compose.onNodeWithText("New group").assertExists()
        compose.onNodeWithContentDescription("needs you").assertExists()
        compose.onNodeWithContentDescription("current group").assertExists()
        // The tab lines: the one needing the user first, then the rest in strip order with their ages.
        compose.onNodeWithText("pi-hole").assertExists()
        compose.onNodeWithText("homelab").assertExists()
        compose.onNodeWithText("build box").assertExists()
        compose.onNodeWithText("12m").assertExists()
        capture("groups")
        assertNoTextCut("the groups overview")

        // A tap switches to the group; New group asks for one.
        compose.onNodeWithText("Work").performClick()
        assertEquals(listOf("ws-work"), opened)
        compose.onNodeWithText("New group").performClick()
        assertEquals(1, newGroup)

        // Edit: every card wears the menu glyph and a tap opens its menu; the last group cannot move later.
        // The order's words, earlier and later, since on the grid the card before Work is to its left (nit 5).
        compose.onNodeWithText("Edit").performClick()
        waitForText("Done")
        capture("groups-edit")
        compose.onNodeWithText("Work").performClick()
        waitForText("Move earlier")
        for (item in listOf("Rename", "Colour", "New tab here", "Close group", "Delete group")) compose.onNodeWithText(item).assertExists()
        hasNoText("Move later")
        hasNoText("Move up")
        capture("groups-card-menu")
        assertNoTextCut("a group card's menu")
        compose.onNodeWithText("Move earlier").performClick()
        waitForNoText("Move earlier")
        assertEquals(listOf("ws-work" to 0), actions.moved)
        assertEquals(listOf("ws-work"), opened)

        // The first group's menu has Move later and no Move earlier; a long-press opens it in either mode.
        compose.onNodeWithText("Done").performClick()
        waitForText("Edit")
        compose.onNodeWithText("Home").performTouchInput { longClick() }
        waitForText("Move later")
        hasNoText("Move earlier")
        compose.onNodeWithText("Rename").performClick()
        assertEquals(listOf(Workspace.DEFAULT_ID), actions.edited)
    }

    /**
     * The overview's second door (C8, "from the rail ... and from the Overflow"): the Stage's ⋮ has
     * Groups, after Tabs and before Library, with a tab on stage and without one, so a window whose
     * drawer stands as a column with no GROUPS label (C7, C23) still reaches the screen.
     */
    @Test
    fun `the groups overview opens from the Stage's overflow`() {
        seedLibrary()
        seedTabs()
        val tab = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(tab.id)
        var groups = 0
        themed {
            val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
            StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, onGroups = { groups++ })
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Groups")
        val rows = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals("Groups follows Tabs and leads Library", listOf("Tabs", "Groups", "Library"), rows.dropWhile { it != "Tabs" }.take(3))
        capture("stage-overflow-groups")
        compose.assertNoTextCut("the Stage's overflow at ${systemFontScale}x", within = isPopup())
        compose.onNodeWithText("Groups").performClick()
        waitForNoText("Groups")
        assertEquals("the row opens the overview", 1, groups)
    }

    @Test
    fun `the empty Stage's overflow has Groups too`() {
        seedLibrary()
        var groups = 0
        themed {
            val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
            StageScreen(graph.viewModel, tab = null, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, onGroups = { groups++ })
        }
        waitForText("No tabs")
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Groups")
        val rows = compose.onAllNodes(hasAnyAncestor(isPopup()) and hasClickAction()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("New tab", "Groups", "Library"), rows)
        compose.onNodeWithText("Groups").performClick()
        waitForNoText("Groups")
        assertEquals(1, groups)
    }

    @Test
    fun `the group editor has Reconnect tabs at launch for a saved group only`() {
        seedLibrary()
        val work = graph.workspaces.items.value.first { it.id == "ws-work" }
        val reconnect = ArrayList<Boolean>()
        themed {
            GroupEditorSheet(group = work, onCreate = { _, _, _ -> }, onRename = { _, _ -> }, onRecolor = {}, onDismiss = {}, onReconnectAtLaunch = { reconnect += it })
        }
        waitForText("Reconnect tabs at launch")
        compose.onNodeWithText("Off, its tabs come back as saved frames and reconnect when tapped.").assertExists()
        // C8's controls in its order: Name, Colour, Monogram, then the switch; Work's saved monogram is its own letter, not the name's two.
        section("Monogram").assertExists()
        field("Monogram").assert(hasText("W"))
        capture("group-editor-reconnect")
        assertNoTextCut("the group editor")
        // The sheet opens whole: Done and Cancel are measured and on screen at the cap, not below a half-open sheet's fold.
        assertSheetButtonsReachable("Done", "Cancel")
        compose.onNodeWithText("Reconnect tabs at launch").performClick()
        compose.waitUntil(5_000) { reconnect == listOf(true) }
        compose.onNodeWithText("Reconnect tabs at launch").performClick()
        compose.waitUntil(5_000) { reconnect == listOf(true, false) }
    }

    @Test
    fun `a new group's editor has no Reconnect row`() {
        seedLibrary()
        themed { GroupEditorSheet(group = null, onCreate = { _, _, _ -> }, onRename = { _, _ -> }, onRecolor = {}, onDismiss = {}) }
        waitForText("Create")
        hasNoText("Reconnect tabs at launch")
        assertSheetButtonsReachable("Create", "Cancel")
    }

    /**
     * Monogram (auto, editable), the editor's third control in C8 (nit 5): the field carries the
     * name's own two letters (A8) and follows the name as it is typed; typing into the field ends
     * that, and an emptied field falls back to the name's own rather than saving nothing. Create
     * and Done hand the monogram over beside the name, blank while it is the name's own, and the
     * model keeps a typed monogram through a rename, in one write with the name.
     */
    @Test
    fun `the group editor's monogram follows the name until it is typed over`() {
        seedLibrary()
        val created = ArrayList<Triple<String, SwatchColor, String>>()
        var dismissed = 0
        themed { GroupEditorSheet(group = null, onCreate = { n, c, m -> created += Triple(n, c, m) }, onRename = { _, _ -> }, onRecolor = {}, onDismiss = { dismissed++ }) }
        waitForText("Create")
        field("Monogram").assert(hasText("GR"))
        field("Name").performTextInput("Home lab")
        compose.waitUntil(5_000) { runCatching { field("Monogram").assert(hasText("HL")) }.isSuccess }
        // Typed over: two characters, set in capitals, kept as the name goes on changing.
        field("Monogram").performTextReplacement("h9")
        compose.waitUntil(5_000) { runCatching { field("Monogram").assert(hasText("H9")) }.isSuccess }
        field("Name").performTextReplacement("Lab rack")
        field("Monogram").assert(hasText("H9"))
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { created.isNotEmpty() }
        assertEquals("Lab rack", created.single().first)
        assertEquals("H9", created.single().third)
        assertEquals(1, dismissed)

        // The model: Done's one write carries both, a blank monogram is the name's own, and Create keeps a typed one.
        compose.waitUntil(5_000) { graph.sessions.workspaces.value.any { it.id == "ws-work" } }
        graph.viewModel.renameWorkspace("ws-work", "Work bench", "WB")
        compose.waitUntil(5_000) { graph.workspaces.items.value.first { it.id == "ws-work" }.let { it.name == "Work bench" && it.monogram == "WB" } }
        graph.viewModel.renameWorkspace("ws-work", "Ops")
        compose.waitUntil(5_000) { graph.workspaces.items.value.first { it.id == "ws-work" }.let { it.name == "Ops" && it.monogram == "OP" } }
        var made: Workspace? = null
        graph.viewModel.createWorkspace("Night shift", switchTo = false, monogram = "N1") { made = it }
        compose.waitUntil(5_000) { made != null && graph.workspaces.items.value.any { it.id == made!!.id && it.monogram == "N1" && it.name == "Night shift" } }
        assertEquals("N1", made!!.monogram)
    }

    /** An existing group whose monogram is the name's own: the field follows a rename, and Done hands the name over with the monogram blank; emptied, the field means the name's own too. */
    @Test
    fun `an existing group's monogram follows its rename while it is the name's own`() {
        seedLibrary()
        val renamed = ArrayList<Pair<String, String>>()
        val plain = Workspace("ws-plain", "Homelab", SwatchColor.MOSS, Host.monogramFor("Homelab"), createdAt = 0)
        themed { GroupEditorSheet(group = plain, onCreate = { _, _, _ -> }, onRename = { n, m -> renamed += n to m }, onRecolor = {}, onDismiss = {}) }
        waitForText("Done")
        field("Monogram").assert(hasText("HO"))
        field("Name").performTextReplacement("Rack")
        compose.waitUntil(5_000) { runCatching { field("Monogram").assert(hasText("RA")) }.isSuccess }
        field("Monogram").performTextClearance()
        compose.waitUntil(5_000) { runCatching { field("Monogram").assert(hasText("")) }.isSuccess }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { renamed.isNotEmpty() }
        assertEquals(listOf("Rack" to ""), renamed)
    }

    @Test
    fun `the drawer's Groups label opens the overview`() {
        seedLibrary()
        seedTabs()
        var groups = 0
        themed { Drawer(graph.viewModel, RecordingTabActions(), onGroupTap = {}, onNewGroup = {}, onGroups = { groups++ }, onLibrary = {}) }
        waitForText("GROUPS")
        section("Groups").performClick()
        assertEquals(1, groups)
        capture("drawer-groups-label")
    }

    // ---- live against the local sshd --------------------------------------------------------------

    /**
     * Install on host end to end: the key typed into the demo user's shell through the session's
     * own send path, its answer read off the screen, and then a login with that very key, which the
     * sshd accepts only if the line landed in `authorized_keys`. The line is removed again through
     * the same shell, so the sshd's file is as it was.
     */
    @Test
    fun `install on host over the local sshd, then the key signs in`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        seedLibrary()
        val marker = "berth-install-" + java.util.UUID.randomUUID().toString().take(8)
        val pair = SshKeys.generate(KeyAlgorithm.ED25519)
        runBlocking {
            graph.identities.insert(
                Identity("id-install", "install test", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE, SshKeys.openSshPublic(pair.public, marker), SshKeys.fingerprintSha256(pair.public), marker, createdAt = now),
                SshKeys.openSshPrivate(pair, marker).toByteArray(),
            )
            graph.secrets.put(AuthResolver.passwordSecretId("berth-test-box"), sshPassword.toByteArray())
            graph.hosts.upsert(
                Host(
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
                ),
            )
            graph.sessions.restore()
        }
        val opened = ArrayList<String>()
        themed {
            KeysScreen(graph.viewModel, onBack = {}, onOpenTab = { opened += it })
            PromptHost(graph.prompts)
        }
        waitForText("install test")
        compose.onNodeWithText("install test").performTouchInput { longClick() }
        waitForText("Install on host")
        compose.onNodeWithText("Install on host").performClick()
        waitForText("Berth test box")
        compose.onNode(hasText("Berth test box") and hasAnyAncestor(isDialog())).performClick()
        waitForText("Connect and install")
        compose.onNodeWithText("Connect and install").performScrollTo().performClick()

        // The login's prompts come up over the sheet: the server's key first. Under it the sheet offers
        // Stop waiting, not Cancel, since the connection goes on as a tab whatever the sheet does (nit 9).
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        waitForText("Trust and connect")
        compose.onNodeWithText("Stop waiting").assertExists()
        compose.onAllNodesWithText("Cancel").assertCountEquals(1) // the trust sheet's own
        settle(300)
        capture("key-install-connecting-live")
        compose.onNodeWithText("Trust and connect").performScrollTo().performClick()
        var session: TerminalSession? = null
        try {
            compose.waitUntil(90_000) { compose.onAllNodesWithText("Installed").fetchSemanticsNodes().isNotEmpty() }
            session = graph.sessions.sessions.value.filterIsInstance<TerminalSession>().single { it.host.id == "berth-test-box" }
            assertEquals(SessionState.LIVE, session.state)
            compose.onNodeWithText("install test is in ~/.ssh/authorized_keys on Berth test box. Logging in there as $sshUser with this key works from the next connection.").assertExists()
            settle(300)
            capture("key-install-installed-live")
            assertNoTextCut("the Installed sheet")
            // The shell printed the answer, not the typed line: the words are on screen once, put together by printf.
            val rows = KeyInstall.rowsFrom(session.emulator, 0)
            assertEquals(1, rows.count { it.contains(KeyInstall.INSTALLED) })
            assertEquals(0, rows.count { it.contains(KeyInstall.NOT_INSTALLED) })
            compose.onNodeWithText("Open tab").performScrollTo().performClick()
            assertEquals(listOf(session.id), opened)

            // The proof: the same host with the key just installed logs in, no password asked.
            val withKey = graph.hosts.items.value.first { it.id == "berth-test-box" }.copy(auth = AuthMethod.Key("id-install"))
            runBlocking { graph.hosts.upsert(withKey) }
            val second = runBlocking { graph.sessions.open(withKey) }
            compose.waitUntil(45_000) { second.state == SessionState.LIVE || second.state == SessionState.FAILED }
            assertEquals(second.failure.value?.plain ?: "live", SessionState.LIVE, second.state)
            assertNull("no prompt stood in the way", graph.prompts.current.value)
            settle(800)
            second.sendText("echo signed in with $marker\n")
            compose.waitUntil(15_000) { KeyInstall.rowsFrom(second.emulator, 0).any { it.contains("signed in with $marker") && !it.contains("echo") } }
        } finally {
            // Leave the sshd's authorized_keys as it was: the line this test added goes, through the shell that wrote it.
            val shell = session ?: graph.sessions.sessions.value.filterIsInstance<TerminalSession>().firstOrNull { it.host.id == "berth-test-box" && it.state == SessionState.LIVE }
            if (shell != null) {
                shell.sendText("grep -v '$marker' ~/.ssh/authorized_keys > ~/.ssh/.ak.tmp; cat ~/.ssh/.ak.tmp > ~/.ssh/authorized_keys; rm ~/.ssh/.ak.tmp; grep -c '$marker' ~/.ssh/authorized_keys; echo berth-cleanup-done\n")
                compose.waitUntil(15_000) { KeyInstall.rowsFrom(shell.emulator, 0).any { it.trim() == "berth-cleanup-done" } }
                val after = KeyInstall.rowsFrom(shell.emulator, 0)
                val done = after.indexOfLast { it.trim() == "berth-cleanup-done" }
                assertEquals("the installed line is gone again", "0", after[done - 1].trim())
            }
            graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        }
    }

    /**
     * A connected tab that is in a program is not typed into: with the demo user's shell on the
     * alternate screen (as `vim` or `less` would put it) its row under CONNECTED says so in place of
     * its live dot, the button for it opens the tab, and nothing reaches the shell; once the program
     * is quit the same row is a shell again and the button says Install.
     */
    @Test
    fun `install on host refuses a live tab that is in a program`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        seedLibrary()
        val box = Host(
            id = "berth-test-box", name = "Berth test box", color = SwatchColor.TEAL, monogram = Host.monogramFor("Berth test box"),
            address = sshHost, port = sshPort, user = sshUser, auth = AuthMethod.Password(AuthResolver.passwordSecretId("berth-test-box")),
            tags = listOf("local"), createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        val opened = ArrayList<String>()
        themed {
            KeysScreen(graph.viewModel, onBack = {}, onOpenTab = { opened += it })
            PromptHost(graph.prompts)
        }
        val session = runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.sessions.restore()
            graph.sessions.open(box)
        }
        try {
            compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
            waitForText("Trust and connect")
            compose.onNodeWithText("Trust and connect").performScrollTo().performClick()
            compose.waitUntil(45_000) { session.state == SessionState.LIVE }
            settle(800)
            // The shell itself switches the tab to the alternate screen, as a full-screen program does on its way in.
            session.sendText("printf '\\033[?1049h'\n")
            compose.waitUntil(15_000) { session.emulator.isAlternateScreen }
            assertTrue(KeyInstall.runningProgram(session))

            waitForText("laptop ed25519")
            compose.onNodeWithText("laptop ed25519").performTouchInput { longClick() }
            waitForText("Install on host")
            compose.onNodeWithText("Install on host").performClick()
            waitForText("Berth test box")
            compose.onNodeWithText("Connected".uppercase()).assertExists()
            // The state is the line under the address, and the address is whole on its own line at both sizes: never broken inside an octet.
            val endpoint = "$sshUser@$sshHost" + if (sshPort != 22) ":$sshPort" else ""
            val stateLine = compose.onNode(hasText("$endpoint\nrunning a program"), useUnmergedTree = true).fetchSemanticsNode().textLayout()!!
            assertEquals("the address is not whole on its line", endpoint.length, stateLine.getLineEnd(0, visibleEnd = true))
            assertEquals(2, stateLine.lineCount)
            compose.onNode(hasText("Berth test box") and hasAnyAncestor(isDialog())).performClick()
            waitForText("Open tab")
            compose.onNodeWithText("Berth test box's tab is in a program, not at a shell, so the command is not typed there. Quit what is running there, or paste the command from the copy button.").assertExists()
            hasNoText("Install")
            settle(300)
            capture("key-install-program-running")
            assertNoTextCut("the Install on host sheet with the picked tab in a program")

            // The program quits while the sheet is up: the tab is a shell again, and the row and the button say so at once.
            session.sendText("printf '\\033[?1049l'\n")
            compose.waitUntil(15_000) { !session.emulator.isAlternateScreen }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Install").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText("running a program", substring = true)).assertCountEquals(0)
            compose.onNodeWithText(endpoint).assertExists()
            hasNoText("Open tab")

            // Back in a program, Open tab leads to the tab and closes the sheet, and nothing has been typed into the program.
            session.sendText("printf '\\033[?1049h'\n")
            compose.waitUntil(15_000) { session.emulator.isAlternateScreen }
            waitForText("Open tab")
            compose.onNodeWithText("Open tab").performScrollTo().performClick()
            compose.waitUntil(5_000) { opened == listOf(session.id) }
            waitForNoText("Runs in the shell")
            // Had the command been typed, the shell at the alternate screen would have echoed it there by now.
            settle(500)
            assertTrue("nothing was typed into the program", KeyInstall.rowsFrom(session.emulator, 0).none { it.contains("authorized_keys") })
        } finally {
            graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private val now = System.currentTimeMillis()
    private lateinit var laptopKey: KeyPair

    private fun host(id: String, name: String, address: String, user: String, color: SwatchColor, auth: AuthMethod, tags: List<String>, lastConnectedAgoMinutes: Long? = null, environment: Map<String, String> = emptyMap()) = Host(
        id = id,
        name = name,
        color = color,
        monogram = Host.monogramFor(name),
        address = address,
        port = 22,
        user = user,
        auth = auth,
        tags = tags,
        environment = environment,
        lastConnectedAt = lastConnectedAgoMinutes?.let { now - TimeUnit.MINUTES.toMillis(it) },
        createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    /** Four hosts tagged three ways with one untagged, two keys, two groups and one trusted server key. */
    private fun seedLibrary() = runBlocking {
        laptopKey = SshKeys.generate(KeyAlgorithm.ED25519)
        graph.identities.insert(
            Identity("id-laptop", "laptop ed25519", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(laptopKey.public, "ben@laptop"), SshKeys.fingerprintSha256(laptopKey.public), "ben@laptop", createdAt = now - TimeUnit.DAYS.toMillis(200)),
            SshKeys.openSshPrivate(laptopKey, "ben@laptop", "correct horse".toCharArray()).toByteArray(),
        )
        val phone = FakeKeystore.newP256()
        graph.identities.insert(
            Identity("id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(phone.public, "berth@pixel"), SshKeys.fingerprintSha256(phone.public), "berth@pixel", keystoreAlias = "berth-id-phone", createdAt = now - TimeUnit.DAYS.toMillis(12)),
            null,
        )
        graph.secrets.put(AuthResolver.passwordSecretId("homelab"), "hunter2".toByteArray())
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, AuthMethod.Key("id-laptop"), tags = listOf("prod"), lastConnectedAgoMinutes = 130))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password(AuthResolver.passwordSecretId("homelab")), tags = listOf("lab"), lastConnectedAgoMinutes = 18))
        graph.hosts.upsert(host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, AuthMethod.Key("id-phone"), tags = listOf("lab", "dns"), lastConnectedAgoMinutes = 60 * 26, environment = mapOf("LANG" to "C.UTF-8")))
        graph.hosts.upsert(host("build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, AuthMethod.AskEachTime, tags = emptyList()))
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        val server = SshKeys.generate(KeyAlgorithm.ED25519).public
        graph.knownHosts.upsert(KnownHostKey("kh-1", "203.0.113.10", 22, "ssh-ed25519", SshKeys.openSshPublic(server).split(" ")[1], SshKeys.fingerprintSha256(server), now - TimeUnit.DAYS.toMillis(90), now - TimeUnit.HOURS.toMillis(2)))
    }

    /** Three detached tabs, two in Home and one in Work, restored so the manager holds them. */
    private fun seedTabs() = runBlocking {
        val hosts = graph.hosts.items.value.associateBy { it.id }
        fun record(id: String, hostId: String, ws: String, order: Int, lastLiveMinutesAgo: Long, cwd: String, lastCommand: String) = SessionRecord(
            id = id, workspaceId = ws, hostId = hostId, hostSnapshot = hosts.getValue(hostId), state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME, title = hosts.getValue(hostId).name,
            cwd = cwd, lastCommand = lastCommand, sortOrder = order, createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(lastLiveMinutesAgo),
        )
        graph.sessionRecords.upsert(record("s-homelab", "homelab", Workspace.DEFAULT_ID, 0, 12, "~/srv", "docker compose ps"))
        graph.sessionRecords.upsert(record("s-pihole", "pi-hole", Workspace.DEFAULT_ID, 1, 95, "/etc/pihole", "tail -f pihole.log"))
        graph.sessionRecords.upsert(record("s-build", "build-box", "ws-work", 0, 400, "~/work/berth", "./gradlew assembleDebug"))
        graph.sessionRecords.saveFrame("s-homelab", frame(listOf("ben@homelab:~/srv$ docker compose ps", "ben@homelab:~/srv$ ")))
        graph.sessionRecords.saveFrame("s-pihole", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log")))
        graph.sessionRecords.saveFrame("s-build", frame(listOf("ci@build:~/work/berth$ ./gradlew assembleDebug", "BUILD SUCCESSFUL in 1m 12s")))
        graph.sessions.restore()
        compose.waitUntil(10_000) { graph.sessions.restored.value && graph.sessions.records.value.size == 3 }
    }

    /**
     * A tab as Quick connect opens one: its host under a `quick-` id no library holds, Live by its
     * record with no socket behind it, since the sheet reads the record and the emulator only.
     */
    private fun quickTab(): TerminalSession {
        val quick = Host(
            id = "quick-" + java.util.UUID.randomUUID(),
            name = "198.51.100.7",
            color = SwatchColor.forName("198.51.100.7"),
            monogram = Host.monogramFor("198.51.100.7"),
            address = "198.51.100.7",
            port = 2202,
            user = "root",
            auth = AuthMethod.AskEachTime,
            createdAt = now,
        )
        val record = SessionRecord(
            id = "s-quick", workspaceId = Workspace.DEFAULT_ID, hostId = quick.id, hostSnapshot = quick, state = SessionState.LIVE, layer = PersistenceLayer.IN_APP, title = quick.name,
            cwd = "~", lastCommand = null, sortOrder = 0, createdAt = now - TimeUnit.MINUTES.toMillis(3), lastLiveAt = now,
        )
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = emptyFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        return TerminalSession(record, CoroutineScope(SupervisorJob() + Dispatchers.Default), env) {}
    }

    private fun request(host: Host, key: java.security.PublicKey) = HostKeyRequest(
        host = host.address,
        port = host.port,
        keyType = SshKeys.keyTypeName(key),
        publicKey = key,
        publicKeyBase64 = SshKeys.openSshPublic(key).split(" ")[1],
        fingerprintSha256 = SshKeys.fingerprintSha256(key),
    )

    /** A host name hashed as `ssh-keygen -H` writes it: `|1|salt|HMAC-SHA1(salt, name)`, both base64. */
    private fun hashed(name: String): String {
        val salt = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) }
        val b64 = Base64.getEncoder()
        return "|1|${b64.encodeToString(salt)}|${b64.encodeToString(mac.doFinal(KnownHostsFile.patternFor(name, 22).toByteArray()))}"
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

    /** The overview's actions as the Stage would take them, recorded. */
    private class RecordingTabActions : TabActions {
        val moved = ArrayList<Pair<String, Int>>()
        val edited = ArrayList<String>()
        override fun moveGroup(groupId: String, toIndex: Int) { moved += groupId to toIndex }
        override fun editGroup(groupId: String) { edited += groupId }
    }
}
