package app.berth.ssh

import net.schmizz.sshj.DefaultConfig
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A host's cipher list against the ciphers this client has (spec C10, Advanced › Ciphers). */
class SshCiphersTest {
    private lateinit var available: List<String>

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        available = DefaultConfig().cipherFactories.map { it.name }
    }

    private fun offered(names: List<String>) = SshCiphers.select(DefaultConfig().cipherFactories, names).map { it.name }

    @Test
    fun `every cipher of the modern list is one this client has`() {
        val missing = SshCiphers.MODERN.filter { it !in available }
        assertTrue(missing.isEmpty(), "missing $missing from $available")
        assertEquals(SshCiphers.MODERN, offered(SshCiphers.MODERN))
    }

    @Test
    fun `the default list offers every modern cipher before any older one, and keeps the older ones for older servers`() {
        val default = offered(emptyList())
        val lastModern = SshCiphers.MODERN.maxOf { default.indexOf(it) }
        val firstOlder = default.indexOfFirst { it !in SshCiphers.MODERN }
        assertTrue(lastModern < firstOlder, "an older cipher comes before a modern one in $default")
        assertEquals(SshCiphers.MODERN, default.take(SshCiphers.MODERN.size))
        assertEquals(available.filter { it !in SshCiphers.MODERN }, default.drop(SshCiphers.MODERN.size), "the older ones keep sshj's order")
        assertTrue("aes128-cbc" in default && "3des-cbc" in default, "$default")
        assertTrue(SshCiphers.MODERN.none { it.endsWith("-cbc") || it.startsWith("3des") || it.startsWith("arcfour") || it.startsWith("blowfish") })
    }

    @Test
    fun `a list is offered in its own order, once each, and a name this client lacks is passed over`() {
        assertEquals(listOf("aes256-gcm@openssh.com", "aes128-ctr"), offered(listOf("aes256-gcm@openssh.com", "no-such-cipher", "aes128-ctr", "aes256-gcm@openssh.com")))
    }

    @Test
    fun `no list, or none this client knows, offers the whole default list`() {
        val default = SshCiphers.MODERN + available.filter { it !in SshCiphers.MODERN }
        assertEquals(default, offered(emptyList()))
        assertEquals(default, offered(listOf("no-such-cipher", "rot13@example.com")))
        assertEquals(available.toSet(), default.toSet())
    }
}
