package app.berth.android.ui.stage

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.android.ui.security.BerthClipboardLocals
import app.berth.android.ui.terminal.LinkTap
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import app.berth.terminal.PasteClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * The link sheet's answers, posture by posture (spec A60; the scheme table of review #16): a web
 * page or a mail address has Open filled and Open hands the address to the system as a plain view;
 * a `file://` link, what `ls --hyperlink` prints, has no Open at all, its Copy path puts the
 * decoded path on the clipboard and its Paste path, on a live tab only, hands the path to the
 * paste route quoted for the shell; an `ssh://` link says Berth is what opens it; any other scheme
 * is another app's, with Cancel carrying the weight, and when nothing on the phone takes it the
 * sheet says so in the notice pill rather than doing nothing; and a link dressed as another site
 * is a warning whatever its scheme. The rule that decides the caption and the posture is pinned
 * in [LinkLookTest]; this is the sheet laid out over it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class LinkOpenSheetTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val application: Application get() = context as Application
    private val sessions = ArrayList<TerminalSession>()
    private var dismissed = 0

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(context)
        mounted = false
        mount.value = null
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        graph.close()
        shadowOf(application).checkActivities(false)
    }

    private fun live(): TerminalSession = StageFixture.liveHomelab().also { sessions += it }

    /** A detached tab with a frame and no shell, on a host nothing answers for. */
    private fun detached(): TerminalSession {
        val now = System.currentTimeMillis()
        val box = Host(
            id = "box", name = "box", color = SwatchColor.SLATE, monogram = Host.monogramFor("box"), address = "127.0.0.1", port = 1, user = "demo",
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), lastConnectedAt = now - TimeUnit.MINUTES.toMillis(5), createdAt = now - TimeUnit.DAYS.toMillis(1),
        )
        val record = SessionRecord(
            id = "s-box", workspaceId = Workspace.DEFAULT_ID, hostId = box.id, hostSnapshot = box, state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME,
            title = box.name, cwd = "~", lastCommand = "ls", sortOrder = 0, createdAt = now - TimeUnit.HOURS.toMillis(1), lastLiveAt = now - TimeUnit.MINUTES.toMillis(5),
        )
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = MutableSharedFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        return TerminalSession(record, CoroutineScope(SupervisorJob() + Dispatchers.Default), env) {}.also { sessions += it }
    }

    /** What the sheet stands over: a link, the tab it was tapped on and the Stage's tools; null between sheets. */
    private class Mount(val link: LinkTap, val session: TerminalSession, val tools: StageTools)

    private val mount = mutableStateOf<Mount?>(null)
    private var mounted = false

    /**
     * The sheet over [link] on [session], as the Stage mounts it: inside the clipboard locals that
     * make every Copy Berth's own, with the Stage's tools. The content is set once a test and the
     * sheet keyed on what it stands over, so a test raises it again after an answer has taken it down.
     */
    private fun sheet(link: LinkTap, session: TerminalSession, tools: StageTools = StageTools()): StageTools {
        dismissed = 0
        if (!mounted) {
            mounted = true
            compose.setContent {
                BerthTheme(InterfaceTheme.DEFAULT) {
                    BerthClipboardLocals(graph.clipboard) {
                        val scope = rememberCoroutineScope()
                        val feedback = LocalHapticFeedback.current
                        val haptics = remember { DeckHaptics(feedback, scope, HapticLevel.OFF) }
                        mount.value?.let { m ->
                            key(m) { LinkOpenSheet(m.link, m.session, m.tools, haptics, onDismiss = { mount.value = null; dismissed++ }) }
                        }
                    }
                }
            }
        }
        mount.value = Mount(link, session, tools)
        waitForText("Open link")
        compose.onNodeWithContentDescription("Link address, ${link.url}").assertIsDisplayed()
        return tools
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = compose.waitUntil(5_000) { shown(text) }

    private fun waitDismissed() {
        compose.waitUntil(5_000) { dismissed == 1 }
        compose.waitUntil(5_000) { !shown("Open link") }
    }

    private fun clipboardText(): String? = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun buttons(vararg present: String, absent: List<String>) {
        for (label in present) compose.onNodeWithText(label).assertIsDisplayed()
        for (label in absent) compose.onAllNodesWithText(label).assertCountEquals(0)
    }

    private fun opened(): Intent? = shadowOf(application).nextStartedActivity

    // ---- web and mail: Open filled -------------------------------------------------------------------------

    @Test
    fun `a web link has Open filled, and Open hands the address to the system as a plain view`() {
        val url = "https://caddyserver.com/docs/"
        val tools = sheet(LinkTap(url, "the docs"), live())
        compose.onNodeWithText("Shown as \u201Cthe docs\u201D, goes to caddyserver.com").assertIsDisplayed()
        buttons("Open", "Copy", "Cancel", absent = listOf("Open anyway", "Copy path", "Paste path"))
        compose.onNodeWithText("Open").performClick()
        waitDismissed()
        val intent = opened()
        assertNotNull("Open started an activity", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(url, intent.dataString)
        assertNull("nothing to say: the system took it", tools.notice)
    }

    @Test
    fun `a mail link opens the same way, and Copy puts the address on the clipboard`() {
        val url = "mailto:ben@homelab.lan"
        var tools = sheet(LinkTap(url, "Ben"), live())
        compose.onNodeWithText("Shown as \u201CBen\u201D, mails ben@homelab.lan").assertIsDisplayed()
        buttons("Open", "Copy", "Cancel", absent = listOf("Open anyway"))
        compose.onNodeWithText("Open").performClick()
        waitDismissed()
        assertEquals(url, opened()?.dataString)

        tools = sheet(LinkTap(url, "Ben"), live())
        compose.onNodeWithText("Copy").performClick()
        waitDismissed()
        assertEquals(url, clipboardText())
        assertEquals("Copied", tools.notice)
        assertNull("Copy opens nothing", opened())
    }

    // ---- file: nothing here opens it -----------------------------------------------------------------------

    @Test
    fun `a file link offers Copy path and Paste path and no Open, the path decoded, and Paste path quotes it for the shell`() {
        val url = "file://homelab/home/ben/my%20notes.txt"
        val text = "'my notes.txt'"
        var tools = sheet(LinkTap(url, text), live())
        compose.onNodeWithText("A file on homelab: /home/ben/my notes.txt").assertIsDisplayed()
        buttons("Copy path", "Paste path", "Cancel", absent = listOf("Open", "Open anyway", "Copy"))

        // Copy path: the path as a path, decoded and unquoted, on the clipboard.
        compose.onNodeWithText("Copy path").performClick()
        waitDismissed()
        assertEquals("/home/ben/my notes.txt", clipboardText())
        assertEquals("Copied", tools.notice)
        assertNull("nothing was opened", opened())

        // Paste path: one word for the shell through the paste route, which on a short plain line goes
        // straight to the session with no preview and nothing to say.
        tools = sheet(LinkTap(url, text), live())
        compose.onNodeWithText("Paste path").performClick()
        waitDismissed()
        assertNull(tools.pendingPaste)
        assertNull(tools.notice)
        assertNull(opened())

        // A path long enough for the paste route to hold it for a look shows what Paste path handed over: the path quoted whole.
        val deep = "/srv/" + "a".repeat(PasteClassifier.PREVIEW_CHARS) + "/my notes.txt"
        tools = sheet(LinkTap("file://homelab$deep".replace(" ", "%20"), "my notes.txt"), live())
        compose.onNodeWithText("Paste path").performClick()
        waitDismissed()
        val held = tools.pendingPaste
        assertNotNull("a long path is looked at before it lands, as any long paste is", held)
        assertEquals(LinkLook.shellQuote(deep), held!!.text)
        assertEquals("'$deep'", held.text)
    }

    @Test
    fun `on a tab that is not live a file link has Copy path alone, since there is no shell to paste into`() {
        val url = "file:///etc/hosts"
        val tools = sheet(LinkTap(url, "hosts"), detached())
        compose.onNodeWithText("A file: /etc/hosts").assertIsDisplayed()
        buttons("Copy path", "Cancel", absent = listOf("Paste path", "Open", "Open anyway", "Copy"))
        compose.onNodeWithText("Copy path").performClick()
        waitDismissed()
        assertEquals("/etc/hosts", clipboardText())
        assertEquals("Copied", tools.notice)
    }

    // ---- ssh and sftp: Berth is what opens it -------------------------------------------------------------

    @Test
    fun `an ssh link says Berth opens it, and Open hands it to Berth's own link handler`() {
        val url = "ssh://root@homelab:2222/"
        // With the phone's activities checked, Berth's own handler is what resolves it: no notice, an intent.
        shadowOf(application).checkActivities(true)
        val tools = sheet(LinkTap(url, "prod box"), live())
        compose.onNodeWithText("Shown as \u201Cprod box\u201D, opens in Berth: root@homelab:2222").assertIsDisplayed()
        buttons("Open", "Copy", "Cancel", absent = listOf("Open anyway"))
        compose.onNodeWithText("Open").performClick()
        waitDismissed()
        val intent = opened()
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(url, intent.dataString)
        assertNull(tools.notice)
    }

    // ---- any other scheme: another app's, Cancel carries the weight -----------------------------------------

    @Test
    fun `another app's scheme has Cancel filled and Open anyway plain, and when nothing on the phone takes it the pill says so`() {
        val url = "upi://pay?pa=attacker@bank&am=5000"
        // Nothing on this phone handles upi: Open anyway is tried and the sheet says what happened rather than nothing.
        shadowOf(application).checkActivities(true)
        var tools = sheet(LinkTap(url, "invoice"), live())
        compose.onNodeWithText("Shown as \u201Cinvoice\u201D, another app on this phone would open this upi link").assertIsDisplayed()
        buttons("Cancel", "Open anyway", "Copy", absent = listOf("Open", "Copy path", "Paste path"))
        compose.onNodeWithText("Open anyway").performClick()
        waitDismissed()
        assertEquals(StageTools.NOTHING_OPENS, tools.notice)
        assertNull("nothing started", opened())

        // With an app for it, Open anyway is the user's call and opens the address as it is.
        shadowOf(application).checkActivities(false)
        tools = sheet(LinkTap(url, "invoice"), live())
        compose.onNodeWithText("Open anyway").performClick()
        waitDismissed()
        assertEquals(url, opened()?.dataString)
        assertNull(tools.notice)

        // Cancel opens nothing and says nothing.
        tools = sheet(LinkTap("market://details?id=com.evil.app", "market://details?id=com.evil.app"), live())
        compose.onNodeWithText("Another app on this phone would open this market link").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        waitDismissed()
        assertNull(opened())
        assertNull(tools.notice)
    }

    // ---- dressed as another site: a warning whatever the scheme ---------------------------------------------

    @Test
    fun `a link dressed as another site is a warning, with Cancel filled and Open anyway opening the address it carries`() {
        val url = "https://evil.example/login"
        val text = "https://github.com/berth/releases"
        var tools = sheet(LinkTap(url, text), live())
        compose.onNodeWithText("Shown as \u201C$text\u201D, but goes to evil.example").assertIsDisplayed()
        buttons("Cancel", "Open anyway", "Copy", absent = listOf("Open"))
        compose.onNodeWithText("Open anyway").performClick()
        waitDismissed()
        assertEquals("the address the link carries, not the one it wore", url, opened()?.dataString)

        // Over a file link the warning stands too, in a sheet that opens nothing.
        tools = sheet(LinkTap("file://homelab/home/ben/notes.txt", "google.com"), live())
        compose.onNodeWithText("Shown as \u201Cgoogle.com\u201D, but a file on homelab: /home/ben/notes.txt").assertIsDisplayed()
        buttons("Copy path", "Paste path", "Cancel", absent = listOf("Open", "Open anyway"))
        compose.onNodeWithText("Cancel").performClick()
        waitDismissed()
        assertNull(opened())
        assertTrue(tools.notice == null)
    }
}
