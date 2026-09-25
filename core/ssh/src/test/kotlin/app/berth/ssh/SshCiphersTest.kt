package app.berth.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.TransportException
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An sshj client that loses the connect's race every time its key exchange fails: sshj's bare "Not connected",
 * thrown once the transport is down, as when the reader dies before connect's own check. A key exchange that
 * settles is left alone, so a hop the test means to pass logs in as any other.
 */
internal fun racingClient(config: DefaultConfig): SSHClient = object : SSHClient(config) {
    override fun onConnect() {
        try {
            super.onConnect()
        } catch (e: Exception) {
            val deadline = System.nanoTime() + 5_000_000_000
            while (transport.isRunning && System.nanoTime() < deadline) Thread.sleep(5)
            throw IllegalStateException("Not connected")
        }
    }
}

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

    @Test
    fun `a server that shares no cipher fails the connect as NoCommonCipher with what was offered`() = runBlocking {
        kexServer().use { server ->
            val endpoint = SshEndpoint(host = "127.0.0.1", port = server.localPort, user = "nobody", auth = listOf(SshAuth.Password { CharArray(0) }), connectTimeoutMillis = 5_000)
            val error = assertFailsWith<SshError.NoCommonCipher> {
                SshConnection(endpoint, AcceptAllHostKeys).use { it.connect() }
            }
            assertEquals(offered(emptyList()), error.offered)
            assertTrue(!error.isTransientSshFailure())
        }
    }

    /**
     * The race the connect loses now and then: sshj's reader fails the key exchange and dies
     * before connect's own check, which throws a bare "Not connected". The block stands in for
     * that check once the transport is down, so the race is taken every time.
     */
    @Test
    fun `a key exchange that dies before connect checks for it still reports the settlement it failed`() {
        kexServer().use { server ->
            val client = SSHClient(DefaultConfig()).apply { connectTimeout = 5_000 }
            try {
                val death = assertFailsWith<TransportException> {
                    client.connectOrCauseOfDeath {
                        runCatching { client.connect("127.0.0.1", server.localPort) }
                        val deadline = System.nanoTime() + 5_000_000_000
                        while (client.transport.isRunning && System.nanoTime() < deadline) Thread.sleep(5)
                        throw IllegalStateException("Not connected")
                    }
                }
                assertTrue(death.message.orEmpty().contains("settlement of Client2ServerCipherAlgorithms"), "${death.message}")
            } finally {
                runCatching { client.disconnect() }
            }
        }
        // A client whose transport never started has no cause to give: its own error stands.
        val bare = IllegalStateException("Not connected")
        assertSame(bare, assertFailsWith<IllegalStateException> { SSHClient(DefaultConfig()).connectOrCauseOfDeath { throw bare } })
    }

    /** The same race taken inside [SshConnection.connect] on the target, which reports the settlement only if it connects through [connectOrCauseOfDeath]. */
    @Test
    fun `a target whose key exchange dies before connect checks for it fails the connect as NoCommonCipher`() = runBlocking {
        kexServer().use { server ->
            val endpoint = SshEndpoint(host = "127.0.0.1", port = server.localPort, user = "nobody", auth = listOf(SshAuth.Password { CharArray(0) }), connectTimeoutMillis = 5_000)
            val error = assertFailsWith<SshError.NoCommonCipher> {
                SshConnection(endpoint, AcceptAllHostKeys).apply { clientFactory = ::racingClient }.use { it.connect() }
            }
            assertEquals(offered(emptyList()), error.offered)
        }
    }

    /** And on a hop, whose failure is named for the hop with the settlement as its reason. */
    @Test
    fun `a hop whose key exchange dies before connect checks for it fails naming the hop, as NoCommonCipher`() = runBlocking {
        kexServer().use { server ->
            val hop = SshEndpoint(host = "127.0.0.1", port = server.localPort, user = "nobody", auth = listOf(SshAuth.Password { CharArray(0) }), connectTimeoutMillis = 5_000)
            val target = hop.copy(host = "target.berth.test", port = 22)
            val error = assertFailsWith<SshError.JumpHopFailed> {
                SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(hop, AcceptAllHostKeys))).apply { clientFactory = ::racingClient }.use { it.connect() }
            }
            assertEquals(0, error.hop)
            assertEquals(offered(emptyList()), assertIs<SshError.NoCommonCipher>(error.reason).offered)
            assertTrue(!error.isTransientSshFailure())
        }
    }

    /** One connection's worth of a server that greets, then offers only a cipher no client has. */
    private fun kexServer(): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    output.write("SSH-2.0-BerthTest\r\n".toByteArray())
                    output.flush()
                    while (input.read().let { it != -1 && it != '\n'.code }) Unit
                    output.write(kexInit(cipher = "no-such-cipher@berth.test"))
                    output.flush()
                    input.readBytes()
                }
            }
        }
        return server
    }

    /** A KEXINIT any client can settle up to [cipher], framed as a packet before any cipher is in use. */
    private fun kexInit(cipher: String): ByteArray {
        val names = listOf(
            "curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group14-sha256",
            "ssh-ed25519,rsa-sha2-256,ssh-rsa",
            cipher, cipher,
            "hmac-sha2-256", "hmac-sha2-256",
            "none", "none",
            "", "",
        )
        val payload = SSHPacket(Message.KEXINIT).apply {
            putRawBytes(ByteArray(16))
            names.forEach { putString(it) }
            putBoolean(false)
            putUInt32(0)
        }.compactData
        var padding = 8 - (5 + payload.size) % 8
        if (padding < 4) padding += 8
        return Buffer.PlainBuffer()
            .putUInt32((1 + payload.size + padding).toLong())
            .putByte(padding.toByte())
            .putRawBytes(payload)
            .putRawBytes(ByteArray(padding))
            .compactData
    }
}
