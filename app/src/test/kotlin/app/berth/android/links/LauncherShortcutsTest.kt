package app.berth.android.links

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.MainActivity
import app.berth.android.screenshots.InMemoryHosts
import app.berth.android.session.SessionNotifier
import app.berth.domain.model.Host
import app.berth.domain.model.SwatchColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the launcher and other apps hand the activity, read back as arrivals (spec Part B: App
 * shortcuts, Deep links; C24, the share sheet), and the shortcuts Berth publishes: the four hosts
 * connected most recently, newest first, following the host list as connections are made and
 * hosts deleted, each opening as its host would. Quick connect is the manifest's and is not
 * published from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val hosts = InMemoryHosts()
    private val shortcuts = LauncherShortcuts(context, hosts)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- what an intent is read as -----------------------------------------------------------------

    @Test
    fun `a VIEW of a link, a shortcut's host, Quick connect and a share are each read as their arrival`() {
        assertEquals(Arrival.Link("ssh://ben@10.0.0.7:22"), Arrival.of(Intent(Intent.ACTION_VIEW, Uri.parse("ssh://ben@10.0.0.7:22"))))
        assertEquals(Arrival.OpenHost("h1"), Arrival.of(LauncherShortcuts.openHost(context, "h1")))
        assertEquals(Arrival.QuickConnect, Arrival.of(Intent(LauncherShortcuts.ACTION_QUICK_CONNECT)))
        val one = Uri.parse("content://media/external/file/12")
        val two = Uri.parse("content://media/external/file/13")
        assertEquals(Arrival.Files(listOf(one)), Arrival.of(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, one)))
        assertEquals(
            Arrival.Files(listOf(one, two)),
            Arrival.of(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))),
        )
        assertEquals("text with no stream is pasted as text", Arrival.Text("tail -f /var/log/syslog"), Arrival.of(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "tail -f /var/log/syslog")))
        assertEquals("a stream wins over the text beside it", Arrival.Files(listOf(one)), Arrival.of(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, one).putExtra(Intent.EXTRA_TEXT, "caption")))
    }

    @Test
    fun `a notification's tap, the plain launch, a host shortcut without its host and an empty share are not arrivals`() {
        assertNull(Arrival.of(Intent(Intent.ACTION_MAIN)))
        assertNull(Arrival.of(Intent(SessionNotifier.ACTION_OPEN_TAB).putExtra(SessionNotifier.EXTRA_TAB_ID, "t1")))
        assertNull(Arrival.of(Intent(SessionNotifier.ACTION_OPEN_FILES).putExtra(SessionNotifier.EXTRA_TAB_ID, "t1")))
        assertNull(Arrival.of(Intent(LauncherShortcuts.ACTION_OPEN_HOST)))
        assertNull(Arrival.of(Intent(Intent.ACTION_VIEW)))
        assertNull(Arrival.of(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(Arrival.of(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "")))
        assertNull(Arrival.of(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>())))
    }

    @Test
    fun `the inbox hands each arrival on once, in order, and holds one that comes before anyone listens`() {
        val inbox = IntentInbox()
        inbox.offer(Arrival.QuickConnect)
        inbox.offer("sftp://ops@files.example.net/var/log")
        val seen = runBlocking { withTimeout(5_000) { inbox.arrivals.take(2).toList() } }
        assertEquals(listOf(Arrival.QuickConnect, Arrival.Link("sftp://ops@files.example.net/var/log")), seen)
        // Delivered once: a second collector starts with nothing.
        assertNull(runBlocking { withTimeoutOrNull(200) { inbox.arrivals.first() } })
    }

    // ---- the recent hosts ------------------------------------------------------------------------

    @Test
    fun `recent is the four hosts connected most recently, newest first, and a host never connected is not among them`() {
        val all = listOf(
            host("never", connectedAt = null),
            host("a", connectedAt = 100),
            host("b", connectedAt = 500),
            host("c", connectedAt = 300),
            host("d", connectedAt = 400),
            host("e", connectedAt = 200),
        )
        assertEquals(listOf("b", "d", "c", "e"), shortcuts.recent(all).map { it.id })
        assertEquals(emptyList<Host>(), shortcuts.recent(listOf(host("never", connectedAt = null))))
    }

    @Test
    fun `the dynamic shortcuts are the recent hosts, each opening its host, and follow the list as connections are made and hosts deleted`() {
        shortcuts.publishFrom(scope)
        runBlocking {
            hosts.upsert(host("web", connectedAt = 300, name = "prod-web", user = "deploy", address = "web.example.net"))
            hosts.upsert(host("db", connectedAt = 200))
            hosts.upsert(host("spare", connectedAt = null))
        }
        // The launcher orders by rank; the manager hands them back in no particular order.
        var published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
        assertEquals(listOf("host:web", "host:db"), published.map { it.id })
        val web = published.first()
        assertEquals("prod-web", web.shortLabel)
        assertEquals("the long label is the name too; the launcher never learns the address", "prod-web", web.longLabel)
        assertEquals(0, web.rank)
        assertEquals(LauncherShortcuts.ACTION_OPEN_HOST, web.intent.action)
        assertEquals("web", web.intent.getStringExtra(LauncherShortcuts.EXTRA_HOST_ID))
        assertEquals(MainActivity::class.java.name, web.intent.component?.className)
        assertEquals("the intent the launcher fires comes back as the host's arrival", Arrival.OpenHost("web"), Arrival.of(web.intent))

        // A connection to the spare host makes it the newest; the four are capped, so the oldest of five falls off.
        runBlocking {
            hosts.upsert(host("cache", connectedAt = 250))
            hosts.upsert(host("mail", connectedAt = 100))
            hosts.markConnected("spare", 900)
        }
        published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
        assertEquals(listOf("host:spare", "host:web", "host:cache", "host:db"), published.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), published.map { it.rank })

        // A deleted host leaves the launcher at once, and the one it pushed out comes back.
        runBlocking { hosts.delete("web") }
        published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
        assertEquals(listOf("host:spare", "host:cache", "host:db", "host:mail"), published.map { it.id })

        // Reporting a use, of a host still there or one gone, is never an error.
        shortcuts.used("spare")
        shortcuts.used("web")
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == "host:web" })
    }

    private fun host(id: String, connectedAt: Long?, name: String = id, user: String = "ben", address: String = "$id.example.net") = Host(
        id = id, name = name, color = SwatchColor.MOSS, monogram = Host.monogramFor(name), address = address, port = 22, user = user,
        createdAt = 1L, lastConnectedAt = connectedAt,
    )
}
