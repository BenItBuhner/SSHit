package app.berth.ssh

import net.schmizz.sshj.connection.channel.OpenFailException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Drives the SOCKS handshake against a proxy whose "remote" side is a local echo server. */
class SocksProxyTest {
    private lateinit var echo: ServerSocket
    private lateinit var proxy: SocksProxy
    private val opened = CopyOnWriteArrayList<Pair<String, Int>>()

    @Before
    fun start() {
        echo = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "echo") {
            while (!echo.isClosed) {
                val client = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    client.use { c ->
                        val input = c.getInputStream()
                        val output = c.getOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            output.flush()
                        }
                    }
                }
            }
        }
        proxy = SocksProxy(ServerSocket(0, 50, InetAddress.getLoopbackAddress())) { host, port ->
            opened += host to port
            if (port == 1) throw OpenFailException("direct-tcpip", OpenFailException.Reason.CONNECT_FAILED, "refused")
            val socket = Socket().apply { connect(InetSocketAddress(host, echo.localPort), 2_000) }
            object : ForwardedStream {
                override val input: InputStream get() = socket.getInputStream()
                override val output: OutputStream get() = socket.getOutputStream()
                override fun close() = socket.close()
            }
        }
        thread(isDaemon = true, name = "socks") { proxy.listen() }
    }

    @After
    fun stop() {
        proxy.close()
        echo.close()
    }

    private fun client(): Socket = Socket("127.0.0.1", proxy.localPort).apply { soTimeout = 5_000 }

    private fun Socket.readExactly(n: Int): ByteArray = ByteArray(n).also { DataInputStream(getInputStream()).readFully(it) }

    private fun Socket.roundTrip(payload: String) {
        getOutputStream().write(payload.toByteArray())
        getOutputStream().flush()
        assertEquals(payload, String(readExactly(payload.length)))
    }

    @Test
    fun `SOCKS5 CONNECT to an IPv4 address is relayed both ways`() {
        client().use { s ->
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertContentEquals(byteArrayOf(5, 0), s.readExactly(2))
            s.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0x1F, 0x40.toByte()))
            assertContentEquals(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0), s.readExactly(10))
            s.roundTrip("hello through socks5")
            s.roundTrip("and again")
        }
        assertEquals(listOf("127.0.0.1" to 8000), opened)
    }

    @Test
    fun `SOCKS5 CONNECT with a domain name hands the name to the tunnel unresolved`() {
        client().use { s ->
            s.getOutputStream().write(byteArrayOf(5, 2, 0, 2))
            assertContentEquals(byteArrayOf(5, 0), s.readExactly(2))
            val name = "localhost".toByteArray()
            s.getOutputStream().write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(0, 80))
            assertContentEquals(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0), s.readExactly(10))
            s.roundTrip("named")
        }
        assertEquals(listOf("localhost" to 80), opened)
    }

    @Test
    fun `SOCKS5 refuses when no acceptable auth method and rejects non-CONNECT commands`() {
        client().use { s ->
            s.getOutputStream().write(byteArrayOf(5, 1, 2)) // username/password only
            assertContentEquals(byteArrayOf(5, 0xFF.toByte()), s.readExactly(2))
        }
        client().use { s ->
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            s.readExactly(2)
            s.getOutputStream().write(byteArrayOf(5, 2, 0, 1, 127, 0, 0, 1, 0, 80)) // BIND
            assertEquals(7, s.readExactly(10)[1].toInt(), "command not supported")
        }
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a refused remote connection surfaces as SOCKS5 connection refused`() {
        client().use { s ->
            s.getOutputStream().write(byteArrayOf(5, 1, 0))
            s.readExactly(2)
            s.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 1))
            val reply = s.readExactly(10)
            assertEquals(5, reply[0].toInt())
            assertEquals(5, reply[1].toInt(), "connection refused")
        }
    }

    @Test
    fun `SOCKS4 and SOCKS4a CONNECT work`() {
        client().use { s ->
            s.getOutputStream().write(byteArrayOf(4, 1, 0x1F, 0x40.toByte(), 127, 0, 0, 1) + "berth".toByteArray() + byteArrayOf(0))
            val reply = s.readExactly(8)
            assertEquals(0, reply[0].toInt())
            assertEquals(0x5A, reply[1].toInt())
            s.roundTrip("socks4")
        }
        client().use { s ->
            val name = "localhost".toByteArray()
            s.getOutputStream().write(byteArrayOf(4, 1, 0, 22, 0, 0, 0, 1) + byteArrayOf(0) + name + byteArrayOf(0))
            assertEquals(0x5A, s.readExactly(8)[1].toInt())
            s.roundTrip("socks4a")
        }
        assertEquals(listOf("127.0.0.1" to 8000, "localhost" to 22), opened)
    }
}
