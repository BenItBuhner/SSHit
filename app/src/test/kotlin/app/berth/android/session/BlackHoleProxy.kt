package app.berth.android.session

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP relay in front of the test sshd that can play a network the socket did not survive: with
 * [swallowToServer] or [swallowToClient] set, bytes in that direction are read and dropped while
 * both sockets stay open, so neither end sees a FIN or a reset. That is what a handoff leaves
 * behind (the old network's socket is simply never answered), and the only way to make it on one
 * machine without root. [cutAll] is the other kind of loss, the peer closing: the client reads EOF.
 * New connections made after the flags are cleared relay normally, so a reconnect gets through.
 */
class BlackHoleProxy(private val targetHost: String, private val targetPort: Int) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    /** The port to point the host at. */
    val port: Int get() = server.localPort

    /** Bytes from the client towards the sshd are dropped on the floor while this holds. */
    @Volatile var swallowToServer = false

    /** Bytes from the sshd towards the client are dropped on the floor while this holds. */
    @Volatile var swallowToClient = false

    private val links = CopyOnWriteArrayList<Link>()

    /** How many connections have been relayed, ever; a reconnect is one more. */
    val connections: Int get() = links.size

    init {
        thread(name = "black-hole-accept", isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                try {
                    links += Link(client, Socket(targetHost, targetPort))
                } catch (_: IOException) {
                    runCatching { client.close() }
                }
            }
        }
    }

    /** Closes every relayed connection, both ends: the client reads EOF, as when the server goes away. */
    fun cutAll() = links.forEach { it.close() }

    override fun close() {
        runCatching { server.close() }
        cutAll()
    }

    private inner class Link(private val client: Socket, private val upstream: Socket) {
        init {
            pump("black-hole-up", client, upstream) { swallowToServer }
            pump("black-hole-down", upstream, client) { swallowToClient }
        }

        fun close() {
            runCatching { client.close() }
            runCatching { upstream.close() }
        }

        private fun pump(name: String, from: Socket, to: Socket, swallow: () -> Boolean) = thread(name = name, isDaemon = true) {
            val buffer = ByteArray(16 * 1024)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (!swallow()) {
                        output.write(buffer, 0, n)
                        output.flush()
                    }
                }
            } catch (_: IOException) {
                // One end went away; the other is closed below.
            }
            close()
        }
    }
}
