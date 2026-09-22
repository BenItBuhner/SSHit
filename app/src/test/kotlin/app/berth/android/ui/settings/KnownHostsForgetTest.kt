package app.berth.android.ui.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.KnownHostKey
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Known hosts' forget and its Undo (spec C13) as the screen calls them: the notice names the one
 * key by its endpoint, with the algorithm where the endpoint keeps another key, or counts the keys
 * forgotten while it was up; Undo puts each key back as it was and never makes a second row of a
 * key this phone has trusted again since.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KnownHostsForgetTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = graph.close()

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private val api = KnownHostKey("kh-api", "203.0.113.10", 22, "ssh-ed25519", "AAAAapi", "SHA256:api", firstSeenAt = 10, lastSeenAt = 20, pinned = true)
    private val apiRsa = KnownHostKey("kh-api-rsa", "203.0.113.10", 22, "ssh-rsa", "AAAArsa", "SHA256:rsa", firstSeenAt = 11, lastSeenAt = 21)
    private val nas = KnownHostKey("kh-nas", "10.0.0.12", 2222, "ssh-ed25519", "AAAAnas", "SHA256:nas", firstSeenAt = 12, lastSeenAt = 22)

    @Test
    fun `the notice names the key by its endpoint, adds the algorithm when another key stays beside it, and counts several`() {
        assertEquals("Forgot the key for 203.0.113.10", forgotLine(listOf(api), beside = false))
        assertEquals("Forgot the ED25519 key for 203.0.113.10", forgotLine(listOf(api), beside = true))
        assertEquals("Forgot the key for 10.0.0.12:2222", forgotLine(listOf(nas), beside = false))
        assertEquals("Forgot 2 keys", forgotLine(listOf(api, nas), beside = false))
        assertEquals("Forgot 3 keys", forgotLine(listOf(api, apiRsa, nas), beside = true))
    }

    @Test
    fun `Undo puts forgotten keys back as they were, pin, dates and id`(): Unit = runBlocking {
        for (key in listOf(api, apiRsa, nas)) graph.knownHosts.upsert(key)
        graph.viewModel.forgetKnownHost(api.id)
        graph.viewModel.forgetKnownHost(nas.id)
        settle()
        assertEquals(listOf(apiRsa), graph.knownHosts.items.value)

        graph.viewModel.restoreKnownHosts(listOf(api, nas))
        settle()
        assertEquals(setOf(api, apiRsa, nas), graph.knownHosts.items.value.toSet())
    }

    @Test
    fun `Undo leaves out a key trusted again since it was forgotten, and still puts back one that differs`(): Unit = runBlocking {
        graph.knownHosts.upsert(api)
        graph.knownHosts.upsert(nas)
        graph.viewModel.forgetKnownHost(api.id)
        graph.viewModel.forgetKnownHost(nas.id)
        settle()
        // The next connection to 203.0.113.10 asked and the same key was trusted again, as a new row; the NAS came back with another key.
        val again = api.copy(id = "kh-api-again", firstSeenAt = 30, lastSeenAt = 30, pinned = false)
        val rotated = nas.copy(id = "kh-nas-rotated", publicKeyBase64 = "AAAAnas2", fingerprintSha256 = "SHA256:nas2", firstSeenAt = 31, lastSeenAt = 31)
        graph.knownHosts.upsert(again)
        graph.knownHosts.upsert(rotated)

        graph.viewModel.restoreKnownHosts(listOf(api, nas))
        settle()
        assertEquals("one row for the one key", setOf(again, rotated, nas), graph.knownHosts.items.value.toSet())
    }
}
