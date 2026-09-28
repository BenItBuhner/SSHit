package app.berth.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the transport against a live sshd. Opt in with environment variables:
 * `SSH_TEST_HOST`, `SSH_TEST_PORT`, `SSH_TEST_USER`, `SSH_TEST_PASSWORD`, and optionally
 * `SSH_TEST_KEY_FILE` (an unencrypted private key authorised for that user). The jump chain
 * tests need a second sshd on `SSH_TEST_JUMP_PORT` (same user and password, its own host keys):
 * the first instance is the hop, the second the target reached through it. The keyboard-interactive
 * case needs `SSH_TEST_KBD_PORT`, an sshd that takes that method alone through PAM with a pam_echo
 * line ahead of the password (`.github/scripts/test-sshd.sh` makes all of them).
 */
class SshIntegrationTest {
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val keyFile = System.getenv("SSH_TEST_KEY_FILE").orEmpty()
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull()
    private val kbdPort = System.getenv("SSH_TEST_KBD_PORT").orEmpty().toIntOrNull()

    @Before
    fun requireServer() {
        assumeTrue("set SSH_TEST_HOST/PORT/USER/PASSWORD to run", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        SshSecurity.ensureProviders()
    }

    private fun passwordEndpoint(pw: String = password, onPort: Int = port) =
        SshEndpoint(host = host, port = onPort, user = user, auth = listOf(SshAuth.Password { pw.toCharArray() }), keepaliveSeconds = 5)

    /** The second sshd, reached through the first: the chain's target. */
    private fun targetBeyondJump(): SshEndpoint {
        assumeTrue("set SSH_TEST_JUMP_PORT to a second sshd to run", jumpPort != null)
        return passwordEndpoint(onPort = jumpPort!!)
    }

    /** A policy that records the host key requests it accepted, so a test can tell which endpoint was checked. */
    private class RecordingPolicy : HostKeyPolicy by AcceptAllHostKeys {
        val seen = ArrayList<HostKeyRequest>()
        override fun onUnknownHost(request: HostKeyRequest): Boolean { seen += request; return true }
    }

    @Test
    fun `a jump chain logs in through the first sshd into the second, each hop checked on its own host key`() = runBlocking {
        val target = targetBeyondJump()
        val direct = RecordingPolicy()
        SshConnection(target, direct).use { it.connect() }
        val targetKey = assertNotNull(direct.seen.singleOrNull()).fingerprintSha256

        val hopPolicy = RecordingPolicy()
        val targetPolicy = RecordingPolicy()
        SshConnection(target, targetPolicy, jumpHosts = listOf(SshHop(passwordEndpoint(), hopPolicy))).use { connection ->
            connection.connect()
            assertTrue(connection.isConnected)
            assertEquals(SshConnectionState.Connected, connection.state.value)
            assertEquals(user, connection.exec("printf %s \"\$USER\""))
            // The target is the second instance: its key, seen through the tunnel, is the one a direct login saw.
            assertEquals(targetKey, connection.serverHostKeyFingerprint)
        }
        val hop = assertNotNull(hopPolicy.seen.singleOrNull(), "the hop's policy checks the hop")
        assertEquals(port, hop.port)
        val end = assertNotNull(targetPolicy.seen.singleOrNull(), "the target's policy checks the target")
        assertEquals(jumpPort, end.port)
        assertEquals(targetKey, end.fingerprintSha256)
        assertTrue(hop.fingerprintSha256 != end.fingerprintSha256, "two instances, two host keys")
    }

    @Test
    fun `a hop whose host key is refused fails naming that hop, and the target is never asked`() = runBlocking {
        val target = targetBeyondJump()
        val refusing = object : HostKeyPolicy {
            override fun trustedKeys(host: String, port: Int) = emptyList<TrustedHostKey>()
            override fun onUnknownHost(request: HostKeyRequest) = false
            override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>) = false
        }
        val targetPolicy = RecordingPolicy()
        val error = assertFailsWith<SshError.JumpHopFailed> {
            SshConnection(target, targetPolicy, jumpHosts = listOf(SshHop(passwordEndpoint(), refusing))).use { it.connect() }
        }
        assertEquals(0, error.hop)
        assertEquals(1, error.hopCount)
        assertEquals(port, error.port)
        assertTrue(error.reason is SshError.HostKeyRejected, "reason was ${error.reason}")
        assertTrue(!error.isTransientSshFailure(), "a refused key is not something a retry fixes")
        assertTrue(targetPolicy.seen.isEmpty())
    }

    @Test
    fun `a hop that rejects the login fails naming that hop`() = runBlocking {
        val target = targetBeyondJump()
        val error = assertFailsWith<SshError.JumpHopFailed> {
            SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint(pw = "definitely-wrong"), AcceptAllHostKeys))).use { it.connect() }
        }
        assertEquals(0, error.hop)
        assertTrue(error.reason is SshError.AuthenticationFailed, "reason was ${error.reason}")
        assertTrue(error.message!!.contains("hop 1 of 1"), error.message!!)
        assertTrue(!error.isTransientSshFailure())
    }

    @Test
    fun `an unreachable hop fails naming that hop and stays transient`() = runBlocking {
        val target = targetBeyondJump()
        val dead = SshEndpoint(host = "127.0.0.1", port = 1, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }), connectTimeoutMillis = 2_000)
        val error = assertFailsWith<SshError.JumpHopFailed> {
            SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint(), AcceptAllHostKeys), SshHop(dead, AcceptAllHostKeys))).use { it.connect() }
        }
        assertEquals(1, error.hop)
        assertEquals(2, error.hopCount)
        assertEquals(1, error.port)
        assertTrue(error.reason is SshError.ConnectFailed, "reason was ${error.reason}")
        assertTrue(error.isTransientSshFailure())
    }

    /**
     * A hop that accepts the socket and never speaks holds the login at its greeting; the state
     * names the hop the whole while, and closing the connection then drops that socket and ends
     * the attempt, rather than leaving it blocked on a hop no tab wants any more.
     */
    @Test
    fun `closing while a hop hangs at its greeting drops its socket and ends the connect`() = runBlocking {
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val hop = SshEndpoint(host = "127.0.0.1", port = silent.localPort, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }))
            val connection = SshConnection(passwordEndpoint(), AcceptAllHostKeys, jumpHosts = listOf(SshHop(hop, AcceptAllHostKeys)))
            val attempt = async(Dispatchers.IO) { runCatching { connection.connect() } }
            val accepted = withTimeout(5_000) { withContext(Dispatchers.IO) { silent.accept() } }
            withTimeout(5_000) { connection.state.first { it is SshConnectionState.ConnectingVia } }
            delay(300)
            assertTrue(attempt.isActive, "the greeting never comes, so the connect is still waiting for it")
            assertEquals(SshConnectionState.ConnectingVia(0, 1, "127.0.0.1"), connection.state.value)

            connection.close()
            val outcome = withTimeout(5_000) { attempt.await() }
            assertTrue(outcome.isFailure, "the attempt ends with the close")
            assertTrue(connection.state.value is SshConnectionState.Disconnected, "state was ${connection.state.value}")
            // The client greets first and then waits; what the hop hears is that greeting, then the socket closing under it.
            accepted.soTimeout = 5_000
            val heard = String(accepted.getInputStream().readBytes(), Charsets.ISO_8859_1)
            assertTrue(heard.startsWith("SSH-2.0"), "the hop heard the greeting and then the close: $heard")
            accepted.close()
        } finally {
            silent.close()
        }
    }

    @Test
    fun `forwards through a jump chain carry traffic and count it`() = runBlocking {
        val target = targetBeyondJump()
        SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint(), AcceptAllHostKeys))).use { connection ->
            connection.connect()
            val forward = connection.startLocalForward("127.0.0.1", 0, "127.0.0.1", jumpPort!!)
            try {
                val banner = withTimeout(10_000) {
                    Socket("127.0.0.1", forward.localPort).use { socket ->
                        socket.soTimeout = 8_000
                        socket.getOutputStream().write("SSH-2.0-berth-probe\r\n".toByteArray())
                        socket.getOutputStream().flush()
                        socket.getInputStream().bufferedReader().readLine()
                    }
                }
                assertTrue(banner.startsWith("SSH-2.0"), "got banner: $banner")
                awaitTraffic(forward.traffic) { it.bytesDown >= banner.length && it.bytesUp >= 21 && it.connections == 1 }
            } finally {
                forward.close()
            }
        }
    }

    @Test
    fun `password auth, interactive shell, resize and clean exit`() = runBlocking {
        val connection = SshConnection(passwordEndpoint(), AcceptAllHostKeys)
        connection.connect()
        assertTrue(connection.isConnected)
        assertEquals(SshConnectionState.Connected, connection.state.value)
        assertNotNull(connection.serverHostKeyFingerprint)
        assertTrue(connection.serverHostKeyFingerprint!!.startsWith("SHA256:"))

        val shell = connection.openShell(80, 24, environment = mapOf("LANG" to "C.UTF-8"))
        val collector = OutputCollector(shell)
        try {
            shell.write("echo BERTH-\$((40+2))\n")
            collector.awaitContains("BERTH-42")

            shell.resize(100, 30)
            shell.write("stty size\n")
            collector.awaitContains("30 100")

            shell.write("printf '%s\\n' \"\$TERM\"\n")
            collector.awaitContains("xterm-256color")

            shell.write("exit\n")
            collector.awaitEof()
            assertTrue(shell.awaitClose(5_000), "channel should close after exit")
            assertEquals(0, shell.exitStatus)
        } finally {
            collector.close()
            shell.close()
            connection.close()
        }
        assertTrue(connection.state.value is SshConnectionState.Disconnected)
    }

    @Test
    fun `exec without a PTY returns stdout`() = runBlocking {
        SshConnection(passwordEndpoint(), AcceptAllHostKeys).use { connection ->
            connection.connect()
            assertEquals("ok", connection.exec("printf ok"))
        }
    }

    @Test
    fun `public key auth with an OpenSSH private key`() = runBlocking {
        assumeTrue("set SSH_TEST_KEY_FILE to run", keyFile.isNotEmpty() && File(keyFile).exists())
        val provider = SshKeys.load(File(keyFile).readText())
        val endpoint = SshEndpoint(host = host, port = port, user = user, auth = listOf(SshAuth.PublicKey(provider)))
        SshConnection(endpoint, AcceptAllHostKeys).use { connection ->
            connection.connect()
            assertEquals(user, connection.exec("printf %s \"\$USER\""))
        }
    }

    /**
     * PAM sends its pam_echo line in a request with no prompt, then "Password: " in the next: the
     * line reaches that prompt as its instruction, and the request with none is answered with none.
     */
    @Test
    fun `keyboard-interactive carries a request with no prompt's instruction to the next prompt, and logs in`() = runBlocking {
        assumeTrue("set SSH_TEST_KBD_PORT to a keyboard-interactive sshd to run", kbdPort != null)
        val asked = ArrayList<List<Any>>()
        val endpoint = SshEndpoint(
            host = host, port = kbdPort!!, user = user,
            auth = listOf(SshAuth.KeyboardInteractive { name, instruction, prompt, echo -> asked += listOf(name, instruction, prompt, echo); password.toCharArray() }),
        )
        SshConnection(endpoint, AcceptAllHostKeys).use { connection ->
            connection.connect()
            assertEquals(user, connection.exec("printf %s \"\$USER\""))
        }
        assertEquals(listOf(listOf<Any>("", "Berth test server: keyboard-interactive login through PAM", "Password: ", false)), asked)
    }

    @Test
    fun `closing while a password prompt waits on the user ends the wait, and the login fails`() = runBlocking {
        closeWhileAsking(port) { ask -> SshAuth.Password { ask() } }
    }

    @Test
    fun `closing while a keyboard-interactive prompt waits on the user ends the wait, and the login fails`() = runBlocking {
        assumeTrue("set SSH_TEST_KBD_PORT to a keyboard-interactive sshd to run", kbdPort != null)
        closeWhileAsking(kbdPort!!) { ask -> SshAuth.KeyboardInteractive { _, _, _, _ -> ask() } }
    }

    /**
     * Logs in to [port] with the method [auth] makes around an answer that waits on the user until
     * it is released, and closes from another thread while it waits: the close returns without the
     * answer and the login fails. The answer is released at the end either way, so a close that
     * waits for it fails the test instead of hanging it.
     */
    private suspend fun closeWhileAsking(port: Int, auth: (ask: () -> CharArray?) -> SshAuth) = coroutineScope {
        val asking = CompletableDeferred<Unit>()
        val answer = CountDownLatch(1)
        val endpoint = SshEndpoint(host = host, port = port, user = user, auth = listOf(auth { asking.complete(Unit); answer.await(); null }))
        val connection = SshConnection(endpoint, AcceptAllHostKeys)
        val login = async(Dispatchers.IO) { runCatching { connection.connect() } }
        try {
            withTimeout(15_000) { asking.await() }
            val closer = thread { connection.close() }
            closer.join(5_000)
            assertFalse(closer.isAlive, "close waited for the user's answer")
            assertTrue(withTimeout(5_000) { login.await() }.isFailure)
        } finally {
            answer.countDown()
        }
    }

    @Test
    fun `wrong password fails with AuthenticationFailed and falls through methods in order`() = runBlocking {
        val tried = ArrayList<String>()
        val endpoint = SshEndpoint(
            host = host, port = port, user = user,
            auth = listOf(
                SshAuth.Password { tried += "first"; "definitely-wrong".toCharArray() },
                SshAuth.Password { tried += "second"; null },
            ),
        )
        val error = assertFailsWith<SshError.AuthenticationFailed> {
            SshConnection(endpoint, AcceptAllHostKeys).use { it.connect() }
        }
        assertTrue(error.message!!.contains(user))
        assertEquals(listOf("first", "second"), tried)
    }

    @Test
    fun `host key policy can refuse and gets the fingerprint it needs to ask`() = runBlocking {
        var seen: HostKeyRequest? = null
        val refusing = object : HostKeyPolicy {
            override fun trustedKeys(host: String, port: Int) = emptyList<TrustedHostKey>()
            override fun onUnknownHost(request: HostKeyRequest): Boolean { seen = request; return false }
            override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>) = false
        }
        assertFailsWith<SshError.HostKeyRejected> {
            SshConnection(passwordEndpoint(), refusing).use { it.connect() }
        }
        val request = assertNotNull(seen)
        assertEquals(host, request.host)
        assertEquals(port, request.port)
        assertTrue(request.fingerprintSha256.startsWith("SHA256:"))
        assertEquals(SshKeys.fingerprintSha256(request.publicKey), request.fingerprintSha256)
    }

    @Test
    fun `a trusted key is recognised and a changed key is flagged`() = runBlocking {
        var firstSeen: HostKeyRequest? = null
        SshConnection(passwordEndpoint(), object : HostKeyPolicy by AcceptAllHostKeys {
            override fun onUnknownHost(request: HostKeyRequest): Boolean { firstSeen = request; return true }
        }).use { it.connect() }
        val trusted = assertNotNull(firstSeen).let { TrustedHostKey(it.keyType, it.publicKeyBase64, it.fingerprintSha256) }

        var recognised = false
        SshConnection(passwordEndpoint(), object : HostKeyPolicy {
            override fun trustedKeys(host: String, port: Int) = listOf(trusted)
            override fun onUnknownHost(request: HostKeyRequest) = error("should have matched the trusted key")
            override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>) = error("should not be flagged as changed")
            override fun onKnownHostSeen(request: HostKeyRequest) { recognised = true }
        }).use { it.connect() }
        assertTrue(recognised)

        var changed = false
        val bogus = trusted.copy(publicKeyBase64 = trusted.publicKeyBase64.dropLast(4) + "ZZZZ", fingerprintSha256 = "SHA256:bogus")
        assertFailsWith<SshError.HostKeyRejected> {
            SshConnection(passwordEndpoint(), object : HostKeyPolicy {
                override fun trustedKeys(host: String, port: Int) = listOf(bogus)
                override fun onUnknownHost(request: HostKeyRequest) = error("a same-type key mismatch is a change, not first use")
                override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>): Boolean { changed = true; return false }
            }).use { it.connect() }
        }
        assertTrue(changed)
    }

    @Test
    fun `local port forward reaches the server through the tunnel`() = runBlocking {
        SshConnection(passwordEndpoint(), AcceptAllHostKeys).use { connection ->
            connection.connect()
            val forward = connection.startLocalForward("127.0.0.1", 0, "127.0.0.1", port)
            try {
                assertTrue(forward.localPort > 0)
                val banner = withTimeout(10_000) {
                    Socket("127.0.0.1", forward.localPort).use { socket ->
                        socket.soTimeout = 8_000
                        socket.getInputStream().bufferedReader().readLine()
                    }
                }
                assertTrue(banner.startsWith("SSH-2.0"), "got banner: $banner")
                // The banner came down the tunnel; nothing went up yet, and one connection was carried.
                awaitTraffic(forward.traffic) { it.bytesDown >= banner.length && it.connections == 1 }
                assertEquals(0L, forward.traffic.bytesUp)
            } finally {
                forward.close()
            }
        }
    }

    @Test
    fun `dynamic forward proxies SOCKS5 connections out through the server`() = runBlocking {
        SshConnection(passwordEndpoint(), AcceptAllHostKeys).use { connection ->
            connection.connect()
            val forward = connection.startDynamicForward("127.0.0.1", 0)
            try {
                assertTrue(forward.localPort > 0)
                val banner = withTimeout(10_000) {
                    Socket("127.0.0.1", forward.localPort).use { socket ->
                        socket.soTimeout = 8_000
                        val out = socket.getOutputStream()
                        val input = DataInputStream(socket.getInputStream())
                        out.write(byteArrayOf(5, 1, 0))
                        out.flush()
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        val name = "localhost".toByteArray()
                        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
                        out.flush()
                        val reply = ByteArray(10).also { input.readFully(it) }
                        assertEquals(0, reply[1].toInt(), "SOCKS5 reply should be succeeded")
                        input.bufferedReader().readLine()
                    }
                }
                assertTrue(banner.startsWith("SSH-2.0"), "got banner: $banner")
                // The SOCKS handshake itself is not counted, only what the proxied connection carried.
                awaitTraffic(forward.traffic) { it.bytesDown >= banner.length && it.connections == 1 }

                // A port nobody listens on comes back as "connection refused" rather than a hang.
                withTimeout(10_000) {
                    Socket("127.0.0.1", forward.localPort).use { socket ->
                        socket.soTimeout = 8_000
                        val out = socket.getOutputStream()
                        val input = DataInputStream(socket.getInputStream())
                        out.write(byteArrayOf(5, 1, 0))
                        out.flush()
                        input.readFully(ByteArray(2))
                        out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 1))
                        out.flush()
                        val reply = ByteArray(10).also { input.readFully(it) }
                        assertEquals(5, reply[1].toInt(), "SOCKS5 reply should be connection refused")
                    }
                }
            } finally {
                forward.close()
            }
        }
    }

    @Test
    fun `remote forward delivers server-side connections back to this side`() = runBlocking {
        val echo = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val server = thread(isDaemon = true) {
            runCatching {
                echo.accept().use { s ->
                    val line = s.getInputStream().bufferedReader().readLine()
                    s.getOutputStream().write("echo:$line\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
        try {
            SshConnection(passwordEndpoint(), AcceptAllHostKeys).use { connection ->
                connection.connect()
                val forward = connection.startRemoteForward("127.0.0.1", 0, "127.0.0.1", echo.localPort)
                try {
                    assertTrue(forward.localPort > 0, "server should pick a port")
                    val reply = withTimeout(10_000) {
                        Socket("127.0.0.1", forward.localPort).use { socket ->
                            socket.soTimeout = 8_000
                            socket.getOutputStream().write("ping\n".toByteArray())
                            socket.getOutputStream().flush()
                            socket.getInputStream().bufferedReader().readLine()
                        }
                    }
                    assertEquals("echo:ping", reply)
                    // "ping" came down from the server to the echo listener; its answer went back up.
                    awaitTraffic(forward.traffic) { it.bytesDown >= 5 && it.bytesUp >= 10 && it.connections == 1 }
                } finally {
                    forward.close()
                }
            }
        } finally {
            echo.close()
            server.join(2_000)
        }
    }

    /** The server settles on the first cipher of the host's list it also speaks; the test sshd speaks OpenSSH's defaults. */
    @Test
    fun `the host's cipher list is what the login offers, and the server takes the first it speaks`() = runBlocking {
        suspend fun negotiated(ciphers: List<String>): String? = SshConnection(passwordEndpoint().copy(ciphers = ciphers), AcceptAllHostKeys).use { connection ->
            connection.connect()
            assertEquals("ok", connection.exec("printf ok"))
            connection.negotiatedCipher
        }
        assertEquals("chacha20-poly1305@openssh.com", negotiated(emptyList()))
        assertEquals("aes256-ctr", negotiated(listOf("aes256-ctr")))
        assertEquals("aes128-gcm@openssh.com", negotiated(listOf("3des-cbc", "aes128-gcm@openssh.com", "aes256-ctr")))
        assertEquals("chacha20-poly1305@openssh.com", negotiated(SshCiphers.MODERN))
    }

    @Test
    fun `a cipher list the server shares nothing with fails as NoCommonCipher, which no retry fixes`() = runBlocking {
        val error = assertFailsWith<SshError.NoCommonCipher> {
            SshConnection(passwordEndpoint().copy(ciphers = listOf("3des-cbc")), AcceptAllHostKeys).use { it.connect() }
        }
        assertEquals(listOf("3des-cbc"), error.offered)
        assertTrue(error.message!!.contains("3des-cbc"), error.message!!)
        assertTrue(!error.isTransientSshFailure())
    }

    @Test
    fun `a hop's own cipher list applies to the hop, and a hop that shares none fails naming that hop`() = runBlocking {
        val target = targetBeyondJump()
        val error = assertFailsWith<SshError.JumpHopFailed> {
            SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint().copy(ciphers = listOf("3des-cbc")), AcceptAllHostKeys))).use { it.connect() }
        }
        assertEquals(0, error.hop)
        assertTrue(error.reason is SshError.NoCommonCipher, "reason was ${error.reason}")
        assertTrue(!error.isTransientSshFailure())
        // The target's own list is its own: through a hop on the default list, the target settles on its one cipher.
        SshConnection(target.copy(ciphers = listOf("aes192-ctr")), AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint(), AcceptAllHostKeys))).use { connection ->
            connection.connect()
            assertEquals("aes192-ctr", connection.negotiatedCipher)
        }
    }

    /** The connect's race ([racingClient]) on a target reached through a hop, whose client connects over the hop's channel. */
    @Test
    fun `a target beyond a hop whose key exchange dies before connect checks for it fails as NoCommonCipher, not as the hop`() = runBlocking {
        val target = targetBeyondJump().copy(ciphers = listOf("3des-cbc"))
        val error = assertFailsWith<SshError.NoCommonCipher> {
            SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint(), AcceptAllHostKeys))).apply { clientFactory = ::racingClient }.use { it.connect() }
        }
        assertEquals(listOf("3des-cbc"), error.offered)
    }

    @Test
    fun `unreachable host fails fast with ConnectFailed`() = runBlocking {
        val endpoint = SshEndpoint(host = "127.0.0.1", port = 1, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }), connectTimeoutMillis = 2_000)
        val error = assertFailsWith<SshError.ConnectFailed> {
            SshConnection(endpoint, AcceptAllHostKeys).use { it.connect() }
        }
        assertTrue(error.isTransientSshFailure())
    }

    /**
     * The test sshd sends nothing of its own to an idle login (no `ClientAliveInterval`), so what
     * the client writes on one is its keepalive: once a second at 1 s, nothing at 0.
     */
    @Test
    fun `an idle login sends a keepalive every interval, and none with the keepalive off`() = runBlocking {
        suspend fun writesWhileIdle(keepaliveSeconds: Int, idleMillis: Long): Int = Relay(host, port).use { relay ->
            SshConnection(passwordEndpoint(onPort = relay.port).copy(host = "127.0.0.1", keepaliveSeconds = keepaliveSeconds), AcceptAllHostKeys).use { connection ->
                connection.connect()
                delay(300)
                val before = relay.writesUp.get()
                delay(idleMillis)
                assertTrue(connection.isConnected)
                relay.writesUp.get() - before
            }
        }
        val every = writesWhileIdle(keepaliveSeconds = 1, idleMillis = 4_500)
        assertTrue(every >= 3, "an idle 4.5 s under a 1 s keepalive carried $every writes from the client")
        assertEquals(0, writesWhileIdle(keepaliveSeconds = 0, idleMillis = 2_500), "a login with the keepalive off writes nothing while idle")
    }

    @Test
    fun `a jump host keeps its own login alive on the link to it`() = runBlocking {
        val target = targetBeyondJump()
        Relay(host, port).use { relay ->
            val hop = passwordEndpoint(onPort = relay.port).copy(host = "127.0.0.1", keepaliveSeconds = 1)
            SshConnection(target.copy(keepaliveSeconds = 0), AcceptAllHostKeys, jumpHosts = listOf(SshHop(hop, AcceptAllHostKeys))).use { connection ->
                connection.connect()
                delay(300)
                val before = relay.writesUp.get()
                delay(4_500)
                assertTrue(connection.isConnected)
                val writes = relay.writesUp.get() - before
                assertTrue(writes >= 3, "an idle 4.5 s under the hop's 1 s keepalive, the target's off, carried $writes writes to the hop")
            }
        }
    }

    /** sshj's keepalive gives up after five requests go unanswered and drops the transport as lost. */
    @Test
    fun `a login whose link goes silent is dropped once its keepalives go unanswered`() = runBlocking {
        Relay(host, port).use { relay ->
            val connection = SshConnection(passwordEndpoint(onPort = relay.port).copy(host = "127.0.0.1", keepaliveSeconds = 1), AcceptAllHostKeys)
            val dropped = CompletableDeferred<SshError.Disconnected>()
            connection.onDisconnected = { dropped.complete(it) }
            connection.use {
                it.connect()
                relay.silent = true
                val error = withTimeout(15_000) { dropped.await() }
                assertTrue(error.message!!.contains("keep-alive response"), error.message!!)
                // sshj tells the listener before it marks its transport closed.
                withTimeout(2_000) { while (connection.isConnected) delay(20) }
                val state = connection.state.value
                assertTrue(state is SshConnectionState.Disconnected && state.error === error, "state was $state")
                assertTrue(error.isTransientSshFailure(), "a lost link is one the reconnect loop retries")
            }
        }
    }

    /**
     * sshj's channel close sends CHANNEL_CLOSE and waits 30 s for a reply that a link gone silent
     * never carries, so a caller closing the shell first sat out the whole of it. In the background
     * the caller has its thread back at once, and the socket is shut once the grace is up.
     */
    @Test
    fun `a close in the background over a silent link returns at once and shuts the socket after the grace`() = runBlocking {
        Relay(host, port).use { relay ->
            val connection = SshConnection(passwordEndpoint(onPort = relay.port).copy(host = "127.0.0.1", keepaliveSeconds = 0), AcceptAllHostKeys)
            connection.connect()
            val shell = connection.openShell(80, 24)
            relay.silent = true
            val started = System.nanoTime()
            val closing = connection.closeInBackground(listOf(shell), graceMillis = 1_000)
            val returned = (System.nanoTime() - started) / 1_000_000
            assertTrue(returned < 200, "closeInBackground held its caller $returned ms")
            assertTrue(relay.clientClosed.await(4_000, TimeUnit.MILLISECONDS), "the client's socket was still open 4 s after a close with a 1 s grace")
            closing.join(2_000)
            val took = (System.nanoTime() - started) / 1_000_000
            assertFalse(closing.isAlive, "the close was still running after $took ms")
            assertTrue(took < 4_000, "the close took $took ms with a 1 s grace; the reply it no longer waits for takes 30 s")
            assertFalse(shell.isOpen)
            assertFalse(connection.isConnected)
            assertTrue(connection.state.value is SshConnectionState.Disconnected)
        }
    }

    /** A login already dropped has nobody to answer a close: what it hands over is not waited on. */
    @Test
    fun `a close in the background of a login already dropped waits on nothing it hands over`() = runBlocking {
        Relay(host, port).use { relay ->
            val connection = SshConnection(passwordEndpoint(onPort = relay.port).copy(host = "127.0.0.1", keepaliveSeconds = 0), AcceptAllHostKeys)
            connection.connect()
            relay.silent = true
            connection.dropAsLost("the network changed")
            withTimeout(2_000) { while (connection.isConnected) delay(20) }
            val never = CountDownLatch(1)
            try {
                val started = System.nanoTime()
                val closing = connection.closeInBackground(listOf(Closeable { never.await() }), graceMillis = 10_000)
                closing.join(10_000)
                val took = (System.nanoTime() - started) / 1_000_000
                assertFalse(closing.isAlive, "the close was still running after $took ms")
                assertTrue(took < 2_000, "the close of a dropped login took $took ms; its 10 s grace is for a login that still looks up")
            } finally {
                never.countDown()
            }
        }
    }

    /**
     * A writer stuck on a full send buffer holds sshj's write lock, so the disconnect's own write
     * waits behind it for as long as the path stays stalled. Once the grace is up the socket is
     * closed under both, and both end.
     */
    @Test
    fun `a close in the background whose disconnect cannot write closes the socket under it`() = runBlocking {
        Relay(host, port, bufferBytes = 4_096).use { relay ->
            val connection = SshConnection(passwordEndpoint(onPort = relay.port).copy(host = "127.0.0.1", keepaliveSeconds = 0), AcceptAllHostKeys)
            connection.clientFactory = { config -> SSHClient(config).apply { socketFactory = SmallSendBuffer(4_096) } }
            connection.connect()
            val shell = connection.openShell(80, 24)
            relay.stalled = true
            val writerEnded = CountDownLatch(1)
            thread(isDaemon = true) {
                runCatching { shell.write(ByteArray(1_024 * 1_024) { 'x'.code.toByte() }) }
                writerEnded.countDown()
            }
            assertFalse(writerEnded.await(1_000, TimeUnit.MILLISECONDS), "the writer should be stuck on the stalled path")
            val started = System.nanoTime()
            val closing = connection.closeInBackground(listOf(shell), graceMillis = 500)
            closing.join(5_000)
            val took = (System.nanoTime() - started) / 1_000_000
            assertFalse(closing.isAlive, "the close was still running after $took ms")
            assertTrue(took < 2_500, "the close took $took ms with a 500 ms grace")
            assertTrue(writerEnded.await(2_000, TimeUnit.MILLISECONDS), "the stuck writer should end once its socket is closed")
            assertFalse(connection.isConnected)
        }
    }

    /**
     * A close during a login through two hops ends the target's wait for its greeting, and the
     * connect then tears down too, on its own thread, beside the close's. Each disconnect on the
     * close's thread is slowed, so the two teardowns overlap as they would over a real link, where
     * a hop's disconnect writes through the hop before it. Each client is disconnected once, by
     * whichever teardown takes it, and nothing reaches the default handler, which on Android ends
     * the process and every session in it.
     */
    @Test
    fun `a close racing a login through two hops disconnects each client once and throws nothing uncaught`() = runBlocking {
        val secondHop = targetBeyondJump()
        val rounds = 10
        val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = 10_000 }
        val uncaught = CopyOnWriteArrayList<String>()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += "${t.name}: $e" }
        val failures = ArrayList<String>()
        try {
            repeat(rounds) { round ->
                val made = CopyOnWriteArrayList<CountingClient>()
                val target = SshEndpoint(host = "127.0.0.1", port = silent.localPort, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }))
                val connection = SshConnection(target, AcceptAllHostKeys, jumpHosts = listOf(SshHop(passwordEndpoint(), AcceptAllHostKeys), SshHop(secondHop, AcceptAllHostKeys)))
                connection.clientFactory = { config -> CountingClient(config, slowOn = "berth-disconnect").also { made += it } }
                val attempt = async(Dispatchers.IO) { runCatching { connection.connect() } }
                // The target is reached through both hops and never greets, so the connect waits on it with both hops logged in.
                val greeted = withContext(Dispatchers.IO) { silent.accept() }
                delay(round * 3L)
                val before = uncaught.size
                val closing = connection.closeInBackground()
                val outcome = withTimeout(10_000) { attempt.await() }
                withContext(Dispatchers.IO) { closing.join(10_000) }
                greeted.close()
                val problems = buildList {
                    if (closing.isAlive) add("the close was still running")
                    outcome.exceptionOrNull().let { if (it !is SshError) add("the connect ended with $it, not an SshError") }
                    uncaught.drop(before).forEach { add("uncaught on $it") }
                    val counts = made.map { it.disconnects.get() }
                    if (counts != listOf(1, 1, 1)) add("disconnects per client, the hops then the target: $counts")
                }
                if (problems.isNotEmpty()) failures += "round ${round + 1}: ${problems.joinToString("; ")}"
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(handler)
            silent.close()
        }
        assertTrue(failures.isEmpty(), "${failures.size} of $rounds rounds failed:\n${failures.joinToString("\n")}")
    }

    /** A client that counts its disconnects and, on a thread named [slowOn]..., waits 40 ms before each. */
    private class CountingClient(config: DefaultConfig, private val slowOn: String) : SSHClient(config) {
        val disconnects = AtomicInteger()

        override fun disconnect() {
            disconnects.incrementAndGet()
            if (Thread.currentThread().name.startsWith(slowOn)) Thread.sleep(40)
            super.disconnect()
        }
    }

    /** Sockets whose send buffer is [bytes], so a stalled path fills it within a small write. */
    private class SmallSendBuffer(private val bytes: Int) : SocketFactory() {
        override fun createSocket(): Socket = Socket().apply { sendBufferSize = bytes }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    /**
     * A TCP relay to the sshd that counts the client's writes and can go [silent]: from then on
     * it reads both ways and passes nothing on, a path that lost the network with both sockets
     * still open, so only a keepalive can tell. [stalled] stops it reading from the client at all,
     * so the client's writes back up into its send buffer; [bufferBytes] makes that buffer's far
     * end small. [clientClosed] opens once the client has closed its end.
     */
    private class Relay(host: String, port: Int, bufferBytes: Int? = null) : Closeable {
        private val listener = ServerSocket().apply {
            bufferBytes?.let { receiveBufferSize = it }
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
        }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val port: Int get() = listener.localPort
        val writesUp = AtomicInteger()
        val clientClosed = CountDownLatch(1)
        @Volatile var silent = false
        @Volatile var stalled = false

        init {
            thread(isDaemon = true) {
                runCatching {
                    val client = listener.accept().also { sockets += it }
                    val server = Socket(host, port).also { sockets += it }
                    thread(isDaemon = true) {
                        pump(client, server, writesUp)
                        clientClosed.countDown()
                    }
                    pump(server, client, null)
                }
            }
        }

        private fun pump(from: Socket, to: Socket, writes: AtomicInteger?) = runCatching {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                while (writes != null && stalled && !listener.isClosed) Thread.sleep(10)
                val n = input.read(buffer)
                if (n < 0) break
                writes?.incrementAndGet()
                if (!silent) {
                    output.write(buffer, 0, n)
                    output.flush()
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }

    /** Counters are bumped on the pump threads a moment after the bytes land, so a check waits for them. */
    private suspend fun awaitTraffic(traffic: ForwardTraffic, timeoutMillis: Long = 5_000, ready: (ForwardTraffic) -> Boolean) = withTimeout(timeoutMillis) {
        while (!ready(traffic)) delay(20)
        assertTrue(ready(traffic), "traffic: up=${traffic.bytesUp} down=${traffic.bytesDown} connections=${traffic.connections}")
    }

    /** Single consumer of the shell's output; tests wait on it rather than racing reads. */
    private class OutputCollector(shell: ShellChannel) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val text = StringBuilder()
        @Volatile private var eof = false
        private val job: Job = scope.launch {
            shell.output().collect { chunk -> synchronized(text) { text.append(chunk.toString(Charsets.UTF_8)) } }
            eof = true
        }

        suspend fun awaitContains(marker: String, timeoutMillis: Long = 15_000) = withTimeout(timeoutMillis) {
            while (!synchronized(text) { text.contains(marker) }) {
                check(!eof) { "channel closed before '$marker' appeared; output so far:\n$text" }
                delay(20)
            }
        }

        suspend fun awaitEof(timeoutMillis: Long = 15_000) = withTimeout(timeoutMillis) { job.join() }

        fun close() = scope.cancel()
    }
}
