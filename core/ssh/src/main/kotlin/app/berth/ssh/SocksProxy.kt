package app.berth.ssh

import net.schmizz.sshj.connection.channel.OpenFailException
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The remote end of one proxied connection: a `direct-tcpip` channel, or a plain socket in tests. */
internal interface ForwardedStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * A small SOCKS5 (and SOCKS4/4a) server whose every accepted connection becomes a `direct-tcpip`
 * channel on the SSH connection, the way `ssh -D` works. Only CONNECT is supported; BIND and UDP
 * ASSOCIATE are answered with "command not supported". No proxy authentication: the listener is
 * meant for loopback.
 */
internal class SocksProxy(
    private val serverSocket: ServerSocket,
    /** Opens the remote side of a forwarded connection; throws [OpenFailException] when the server refuses. */
    private val open: (host: String, port: Int) -> ForwardedStream,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "berth-socks-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

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
        var remote: ForwardedStream? = null
        try {
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val target = when (val version = input.readUnsignedByte()) {
                5 -> negotiateSocks5(input, output)
                4 -> negotiateSocks4(input, output)
                else -> throw IOException("not a SOCKS request (first byte $version)")
            } ?: return
            remote = try {
                open(target.host, target.port)
            } catch (e: OpenFailException) {
                target.reject(output, e.reason)
                return
            } catch (_: IOException) {
                target.reject(output, null)
                return
            }
            target.accept(output)
            pump(socket, remote)
        } catch (_: IOException) {
            // The client went away mid-handshake or the channel dropped; nothing to report.
        } finally {
            runCatching { remote?.close() }
            runCatching { socket.close() }
        }
    }

    /** Copies both directions until one side ends; returns when both copiers have finished. */
    private fun pump(socket: Socket, remote: ForwardedStream) {
        val toRemote = pool.submit {
            copy(socket.getInputStream(), remote.output)
            runCatching { remote.output.close() }
        }
        try {
            copy(remote.input, socket.getOutputStream())
        } finally {
            runCatching { socket.shutdownOutput() }
            runCatching { remote.close() }
            runCatching { toRemote.get() }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // Either end closed; the caller tears both down.
        }
    }

    private class Target(val host: String, val port: Int, val version: Int, val socks4Header: ByteArray = ByteArray(0)) {
        fun accept(out: OutputStream) {
            if (version == 5) out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)) else out.write(byteArrayOf(0, 0x5A) + socks4Header)
            out.flush()
        }

        fun reject(out: OutputStream, reason: OpenFailException.Reason?) {
            if (version == 5) {
                val code: Byte = when (reason) {
                    OpenFailException.Reason.CONNECT_FAILED -> 5
                    OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED -> 2
                    else -> 1
                }
                out.write(byteArrayOf(5, code, 0, 1, 0, 0, 0, 0, 0, 0))
            } else {
                out.write(byteArrayOf(0, 0x5B) + socks4Header)
            }
            out.flush()
        }
    }

    private fun negotiateSocks5(input: DataInputStream, output: OutputStream): Target? {
        val methodCount = input.readUnsignedByte()
        val methods = ByteArray(methodCount).also { input.readFully(it) }
        if (methods.none { it == 0.toByte() }) {
            output.write(byteArrayOf(5, 0xFF.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(5, 0))
        output.flush()

        if (input.readUnsignedByte() != 5) throw IOException("bad SOCKS5 request version")
        val command = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
            3 -> {
                val length = input.readUnsignedByte()
                String(ByteArray(length).also { input.readFully(it) }, Charsets.US_ASCII)
            }
            4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
            else -> {
                output.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                return null
            }
        }
        val port = input.readUnsignedShort()
        if (command != 1) {
            output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()
            return null
        }
        return Target(host, port, 5)
    }

    private fun negotiateSocks4(input: DataInputStream, output: OutputStream): Target? {
        val command = input.readUnsignedByte()
        val port = input.readUnsignedShort()
        val ip = ByteArray(4).also { input.readFully(it) }
        readCString(input) // user id, ignored
        val header = byteArrayOf((port shr 8).toByte(), port.toByte()) + ip
        if (command != 1) {
            output.write(byteArrayOf(0, 0x5B) + header)
            output.flush()
            return null
        }
        // SOCKS4a: an address of 0.0.0.x means a domain name follows.
        val host = if (ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()) {
            readCString(input)
        } else {
            InetAddress.getByAddress(ip).hostAddress
        }
        return Target(host, port, 4, header)
    }

    private fun readCString(input: DataInputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (b == 0) break
            bytes += b.toByte()
            if (bytes.size > 1024) throw IOException("SOCKS4 string too long")
        }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    private companion object {
        val counter = AtomicInteger()
    }
}
