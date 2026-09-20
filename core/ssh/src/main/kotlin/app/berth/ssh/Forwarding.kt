package app.berth.ssh

import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one forward has carried since it started: bytes each way and the connections it served.
 * The Tunnels tab reads it for its counters; the pump threads write it, so every field is atomic.
 */
class ForwardTraffic {
    private val up = AtomicLong()
    private val down = AtomicLong()
    private val served = AtomicInteger()
    private val active = AtomicInteger()

    /** Bytes that left this device for the server. */
    val bytesUp: Long get() = up.get()

    /** Bytes that arrived from the server. */
    val bytesDown: Long get() = down.get()

    /** Connections the forward has carried so far, including those still open. */
    val connections: Int get() = served.get()

    /** Connections being carried right now. */
    val openConnections: Int get() = active.get()

    internal fun countUp(n: Int) {
        up.addAndGet(n.toLong())
    }

    internal fun countDown(n: Int) {
        down.addAndGet(n.toLong())
    }

    internal fun opened() {
        served.incrementAndGet()
        active.incrementAndGet()
    }

    internal fun closed() {
        active.decrementAndGet()
    }
}

/** The server side of a forwarded connection as an SSH channel (`direct-tcpip` out, `forwarded-tcpip` in). */
internal class ChannelStream(private val channel: Channel) : ForwardedStream {
    override val input: InputStream get() = channel.inputStream
    override val output: OutputStream get() = channel.outputStream
    override fun close() {
        channel.close()
    }
}

/**
 * Copies a device-side socket and a server-side stream into each other. Device to server counts
 * as up, server to device as down. When the server side ends, the device gets its FIN and the
 * other copier a moment to drain before the socket closes under it; when the device side ends
 * first, the server gets EOF and may still answer, the way `ssh -L` treats a half-closed client.
 */
internal object StreamPump {
    private const val BUFFER = 32 * 1024
    private const val DRAIN_GRACE_MS = 2_000L
    private val counter = AtomicInteger()

    fun newPool(name: String): ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "berth-$name-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Runs on the caller's thread until both directions have finished; both ends are closed on return. */
    fun pump(device: Socket, server: ForwardedStream, traffic: ForwardTraffic, pool: ExecutorService) {
        traffic.opened()
        val toServer = pool.submit {
            copy(device.getInputStream(), server.output, traffic::countUp)
            runCatching { server.output.close() }
        }
        try {
            copy(server.input, device.getOutputStream(), traffic::countDown)
        } finally {
            runCatching { device.shutdownOutput() }
            runCatching { server.close() }
            runCatching { toServer.get(DRAIN_GRACE_MS, TimeUnit.MILLISECONDS) }
            runCatching { device.close() }
            runCatching { toServer.get() }
            traffic.closed()
        }
    }

    fun copy(from: InputStream, to: OutputStream, count: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
                count(n)
            }
        } catch (_: IOException) {
            // Either end closed; the caller tears both down.
        }
    }
}

/**
 * `ssh -L`: accepts on a device port and carries every connection through a channel the
 * connection opens to the destination. Closing stops the listener and drops what it carries.
 */
internal class LocalForwarder(
    private val serverSocket: ServerSocket,
    private val traffic: ForwardTraffic,
    /** Opens the server side of one connection; throws [IOException] when the server refuses. */
    private val open: () -> ForwardedStream,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val pool = StreamPump.newPool("forward")
    private val carried: MutableSet<Closeable> = Collections.synchronizedSet(HashSet())

    val localPort: Int get() = serverSocket.localPort

    /** Accepts until the listener is closed; runs on the caller's thread. */
    fun listen() {
        while (!closed.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            pool.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        carried += socket
        val remote = try {
            socket.tcpNoDelay = true
            open()
        } catch (_: IOException) {
            runCatching { socket.close() }
            carried -= socket
            return
        }
        try {
            StreamPump.pump(socket, remote, traffic, pool)
        } finally {
            carried -= socket
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        synchronized(carried) { carried.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }
}

/**
 * `ssh -R`: the server hands over a connection it accepted, and this connects it to
 * [destination] on this device and pumps. sshj calls [gotConnect] on a thread of its own and
 * rejects the channel when the connect throws, so a refused destination is reported to the
 * server rather than left hanging.
 */
internal class CountingConnectListener(
    private val destination: InetSocketAddress,
    private val traffic: ForwardTraffic,
) : ConnectListener, Closeable {
    private val pool = StreamPump.newPool("remote")
    private val carried: MutableSet<Closeable> = Collections.synchronizedSet(HashSet())

    override fun gotConnect(chan: Channel.Forwarded) {
        val socket = Socket()
        try {
            socket.connect(destination, CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        chan.confirm()
        carried += socket
        pool.execute {
            try {
                StreamPump.pump(socket, ChannelStream(chan), traffic, pool)
            } finally {
                carried -= socket
            }
        }
    }

    override fun close() {
        synchronized(carried) { carried.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
