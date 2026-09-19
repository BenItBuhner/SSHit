package app.berth.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the transport against a live sshd. Opt in with environment variables:
 * `SSH_TEST_HOST`, `SSH_TEST_PORT`, `SSH_TEST_USER`, `SSH_TEST_PASSWORD`, and optionally
 * `SSH_TEST_KEY_FILE` (an unencrypted private key authorised for that user).
 */
class SshIntegrationTest {
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val keyFile = System.getenv("SSH_TEST_KEY_FILE").orEmpty()

    @Before
    fun requireServer() {
        assumeTrue("set SSH_TEST_HOST/PORT/USER/PASSWORD to run", host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty())
        SshSecurity.ensureProviders()
    }

    private fun passwordEndpoint(pw: String = password) =
        SshEndpoint(host = host, port = port, user = user, auth = listOf(SshAuth.Password { pw.toCharArray() }), keepaliveSeconds = 5)

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
                } finally {
                    forward.close()
                }
            }
        } finally {
            echo.close()
            server.join(2_000)
        }
    }

    @Test
    fun `unreachable host fails fast with ConnectFailed`() = runBlocking {
        val endpoint = SshEndpoint(host = "127.0.0.1", port = 1, user = user, auth = listOf(SshAuth.Password { password.toCharArray() }), connectTimeoutMillis = 2_000)
        val error = assertFailsWith<SshError.ConnectFailed> {
            SshConnection(endpoint, AcceptAllHostKeys).use { it.connect() }
        }
        assertTrue(error.isTransientSshFailure())
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
