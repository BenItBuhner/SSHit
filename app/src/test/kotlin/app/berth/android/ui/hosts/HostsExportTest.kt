package app.berth.android.ui.hosts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.data.bundle.BundleException
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Base64Codec
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hosts › Export (spec C9) as the sheet calls it, through the view model: the file is the bundle's
 * own container at the bundle's own cost, opens with its passphrase and no other, and holds the
 * hosts, the passwords they log in with and the public record of each key they log in with, with
 * nothing of the rest of the library and no private half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HostsExportTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = graph.close()

    @Test
    fun `the hosts export opens with its passphrase alone and holds the hosts, their passwords and their keys' public records`(): Unit = runBlocking {
        val pair = SshKeys.generate(KeyAlgorithm.ED25519)
        val pem = SshKeys.openSshPrivate(pair, "ben@laptop").toByteArray()
        val laptop = Identity(
            "id-laptop", "laptop", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE,
            SshKeys.openSshPublic(pair.public, "ben@laptop"), SshKeys.fingerprintSha256(pair.public), "ben@laptop", createdAt = 1,
        )
        graph.identities.insert(laptop, pem)
        val web = Host(id = "web", name = "prod-web", color = SwatchColor.COPPER, monogram = "PW", address = "203.0.113.10", user = "deploy", auth = AuthMethod.Key("id-laptop"), createdAt = 2)
        val nas = Host(id = "nas", name = "nas", color = SwatchColor.MOSS, monogram = "NA", address = "10.0.0.5", user = "admin", auth = AuthMethod.Password(AuthResolver.passwordSecretId("nas")), createdAt = 3)
        graph.hosts.upsert(web)
        graph.hosts.upsert(nas)
        graph.secrets.put(AuthResolver.passwordSecretId("nas"), "hunter2".toByteArray())
        graph.snippets.upsert(Snippet("s-disk", "disk", "df -h"))
        graph.knownHosts.upsert(KnownHostKey("kh-web", "203.0.113.10", 22, "ssh-ed25519", "AAAAweb", "SHA256:web", 4, 5))

        val blob = graph.viewModel.exportHosts(PASSPHRASE.toCharArray(), "0.2.0")
        try {
            graph.viewModel.openBundle(blob, "not the passphrase".toCharArray())
            fail("a hosts export opened under another passphrase")
        } catch (_: BundleException) {
        }
        val bundle = graph.viewModel.openBundle(blob, PASSPHRASE.toCharArray())

        assertEquals("0.2.0", bundle.appVersion)
        assertEquals(setOf(web, nas), bundle.hosts.toSet())
        assertArrayEquals("hunter2".toByteArray(), bundle.passwords.single { it.id == AuthResolver.passwordSecretId("nas") }.bytes())
        val named = bundle.identities.single()
        assertEquals(laptop.fingerprintSha256, named.identity.fingerprintSha256)
        assertNull("the private half stays on this phone", named.privateKey)
        assertFalse("the key file is nowhere in the document", Base64Codec.encode(pem) in bundle.toJson())
        assertTrue(bundle.snippets.isEmpty())
        assertTrue(bundle.knownHosts.isEmpty())
        assertTrue(bundle.workspaces.isEmpty())
        assertNull(bundle.deck)
        assertNull(bundle.interfaceTheme)
    }

    private companion object {
        const val PASSPHRASE = "moving day 2026"
    }
}
