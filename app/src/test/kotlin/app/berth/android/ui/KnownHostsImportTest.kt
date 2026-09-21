package app.berth.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KnownHostKey
import app.berth.ssh.SshKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `known_hosts` import's write against the store (spec A16, C13): what the sheet showed as a
 * conflict is what the import replaces, whatever case the saved address was typed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KnownHostsImportTest {
    private val graph = TestGraph(ApplicationProvider.getApplicationContext())

    /**
     * A host saved as `Prod-API.example.com` (the address as typed into the editor, which the live
     * path keys on) has its key in a `known_hosts` file as `prod-api.example.com` (OpenSSH lowercases
     * before it writes). The sheet judges the rotated key a conflict case-blind; the import judges it
     * the same way at write time, so the saved key goes and the rotated one stands in its place —
     * under the saved spelling, the one the live lookup reads — rather than a dead row beside it.
     */
    @Test
    fun `a conflict against a capitalised saved address is replaced, not added beside`() = runBlocking {
        val saved = SshKeys.generate(KeyAlgorithm.ED25519).public
        val rotated = SshKeys.generate(KeyAlgorithm.ED25519).public
        val trusted = KnownHostKey(
            "kh-prod", "Prod-API.example.com", 22, "ssh-ed25519",
            SshKeys.openSshPublic(saved).split(" ")[1], SshKeys.fingerprintSha256(saved),
            firstSeenAt = 1_000L, lastSeenAt = 2_000L,
        )
        graph.knownHosts.upsert(trusted)

        val read = graph.viewModel.parseKnownHosts("prod-api.example.com ${SshKeys.openSshPublic(rotated)}")
        val candidate = read.candidates.single()
        assertEquals(KnownHostsCandidate.Standing.Conflicting(trusted), candidate.standing)
        assertTrue(!candidate.tickedByDefault)

        assertEquals(KnownHostsImported(added = 0, replaced = 1), graph.viewModel.importKnownHosts(listOf(candidate.entry)))
        val key = graph.knownHosts.items.value.single()
        assertEquals("Prod-API.example.com", key.host)
        assertEquals(22, key.port)
        assertEquals(SshKeys.fingerprintSha256(rotated), key.fingerprintSha256)
        assertTrue(!key.pinned)

        // Read again, the same line is the very key now held: nothing to import, and the store is left as it is.
        val again = graph.viewModel.parseKnownHosts("prod-api.example.com ${SshKeys.openSshPublic(rotated)}")
        assertEquals(KnownHostsCandidate.Standing.EXISTING, again.candidates.single().standing)
        assertEquals(KnownHostsImported(added = 0, replaced = 0), graph.viewModel.importKnownHosts(listOf(again.candidates.single().entry)))
        assertEquals(listOf(key), graph.knownHosts.items.value)
    }
}
