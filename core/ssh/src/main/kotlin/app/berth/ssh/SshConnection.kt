package app.berth.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Ways to prove who we are, tried in order. Secrets are pulled lazily so prompts can happen late. */
sealed interface SshAuth {
    /** [password] returning null means the user cancelled; the method is skipped. */
    class Password(val password: () -> CharArray?) : SshAuth

    class PublicKey(val keyProvider: KeyProvider) : SshAuth

    /** [respond] receives each server prompt and whether the answer should echo; null cancels. */
    class KeyboardInteractive(val respond: (instruction: String, prompt: String, echo: Boolean) -> CharArray?) : SshAuth
}

data class SshEndpoint(
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: List<SshAuth>,
    val keepaliveSeconds: Int = 15,
    val compression: Boolean = false,
    val connectTimeoutMillis: Int = 15_000,
    /** Preferred address family; null lets the resolver choose. */
    val preferIpv6: Boolean? = null,
)

sealed class SshError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HostKeyRejected(host: String) : SshError("Host key for $host was not trusted")
    class AuthenticationFailed(user: String, cause: Throwable?) : SshError("Authentication failed for $user", cause)
    class ConnectFailed(host: String, port: Int, cause: Throwable) : SshError("Couldn't reach $host:$port: ${cause.message ?: cause.javaClass.simpleName}", cause)
    class Disconnected(reason: String) : SshError(reason)
}

sealed interface SshConnectionState {
    data object Idle : SshConnectionState
    data object Connecting : SshConnectionState
    data object Authenticating : SshConnectionState
    data object Connected : SshConnectionState
    data class Disconnected(val reason: String, val error: Throwable?) : SshConnectionState
}

/** An interactive shell on a PTY. Reading is a cold flow; writing and resizing are immediate. */
class ShellChannel internal constructor(
    private val session: Session,
    private val shell: Session.Shell,
) : Closeable {
    private val output: OutputStream = shell.outputStream
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = shell.isOpen && !closed.get()

    /** Exit status once the remote shell has ended; null while running or when the server sent none. */
    val exitStatus: Int? get() = (shell as? Session.Command)?.exitStatus

    /**
     * Waits for the channel itself to close. EOF on [output] can arrive a moment before the
     * server's `exit-status` request, so callers that need [exitStatus] wait here first.
     */
    fun awaitClose(timeoutMillis: Long): Boolean = try {
        shell.join(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    } catch (_: ConnectionException) {
        false
    }

    /** Remote output as it arrives. Completes when the channel reaches EOF. */
    fun output(bufferSize: Int = 32 * 1024): Flow<ByteArray> = flow {
        val input = shell.inputStream
        val buffer = ByteArray(bufferSize)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (closed.get()) break else throw e
            }
            if (n < 0) break
            if (n > 0) emit(buffer.copyOf(n))
        }
    }.flowOn(Dispatchers.IO)

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (closed.get()) return
        output.write(data, offset, length)
        output.flush()
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) {
        if (closed.get()) return
        try {
            shell.changeWindowDimensions(cols, rows, widthPx, heightPx)
        } catch (_: TransportException) {
            // The connection is gone; the reconnect path will notice through the reader.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}

/** A running port forward; closing it stops the listener and drops its channels. */
class ForwardHandle internal constructor(
    /** For local forwards, the port actually bound (useful when 0 was requested). */
    val localPort: Int,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() = onClose()
}

/**
 * One SSH connection to one endpoint, optionally through a chain of jump hosts. Wraps sshj with a
 * coroutine-friendly surface; every blocking call runs on [Dispatchers.IO].
 */
class SshConnection(
    private val endpoint: SshEndpoint,
    private val hostKeyPolicy: HostKeyPolicy,
    private val jumpHosts: List<SshEndpoint> = emptyList(),
) : Closeable {
    private val _state = MutableStateFlow<SshConnectionState>(SshConnectionState.Idle)
    val state: StateFlow<SshConnectionState> = _state.asStateFlow()

    private var client: SSHClient? = null
    private val hops = ArrayList<SSHClient>()

    /** Invoked from sshj's transport thread when the connection drops for any reason. */
    var onDisconnected: ((SshError.Disconnected) -> Unit)? = null

    val isConnected: Boolean get() = client?.isConnected == true && client?.isAuthenticated == true

    /** The server's host key as seen during key exchange, for the ribbon and the known hosts UI. */
    var serverHostKeyFingerprint: String? = null
        private set

    var serverHostKeyType: String? = null
        private set

    init {
        SshSecurity.ensureProviders()
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        _state.value = SshConnectionState.Connecting
        try {
            var previous: SSHClient? = null
            for (hop in jumpHosts) {
                val hopClient = newClient(hop)
                connectClient(hopClient, hop, previous)
                authenticate(hopClient, hop)
                hops += hopClient
                previous = hopClient
            }
            val target = newClient(endpoint)
            connectClient(target, endpoint, previous)
            _state.value = SshConnectionState.Authenticating
            authenticate(target, endpoint)
            target.transport.setDisconnectListener { reason, message ->
                val text = if (message.isNullOrBlank()) reason.toString() else message
                val error = SshError.Disconnected(text)
                _state.value = SshConnectionState.Disconnected(text, error)
                onDisconnected?.invoke(error)
            }
            client = target
            _state.value = SshConnectionState.Connected
        } catch (e: Throwable) {
            closeQuietly()
            val error = e.toSshError(endpoint)
            _state.value = SshConnectionState.Disconnected(error.message ?: "disconnected", error)
            throw error
        }
    }

    private fun Throwable.toSshError(ep: SshEndpoint): SshError = when (this) {
        is SshError -> this
        is UserAuthException -> SshError.AuthenticationFailed(ep.user, this)
        is TransportException ->
            if (disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) SshError.HostKeyRejected(ep.host)
            else SshError.ConnectFailed(ep.host, ep.port, this)
        else -> SshError.ConnectFailed(ep.host, ep.port, this)
    }

    private fun newClient(ep: SshEndpoint): SSHClient {
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val c = SSHClient(config)
        val isTarget = ep === endpoint
        c.addHostKeyVerifier(
            PolicyHostKeyVerifier(
                host = ep.host,
                port = ep.port,
                policy = object : HostKeyPolicy by hostKeyPolicy {
                override fun onKnownHostSeen(request: HostKeyRequest) {
                    if (isTarget) remember(request)
                    hostKeyPolicy.onKnownHostSeen(request)
                }

                override fun onUnknownHost(request: HostKeyRequest): Boolean {
                    if (isTarget) remember(request)
                    return hostKeyPolicy.onUnknownHost(request)
                }

                override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>): Boolean {
                    if (isTarget) remember(request)
                    return hostKeyPolicy.onChangedHostKey(request, known)
                }
            },
            ),
        )
        c.connectTimeout = ep.connectTimeoutMillis
        c.timeout = 0
        return c
    }

    private fun remember(request: HostKeyRequest) {
        serverHostKeyFingerprint = request.fingerprintSha256
        serverHostKeyType = request.keyType
    }

    private fun connectClient(c: SSHClient, ep: SshEndpoint, via: SSHClient?) {
        when {
            via != null -> c.connectVia(via.newDirectConnection(ep.host, ep.port))
            ep.preferIpv6 == null -> c.connect(ep.host, ep.port)
            else -> c.connect(resolve(ep), ep.port)
        }
        if (ep.compression) c.useCompression()
        c.connection.keepAlive.keepAliveInterval = ep.keepaliveSeconds.coerceAtLeast(0)
    }

    private fun resolve(ep: SshEndpoint): InetAddress {
        val all = InetAddress.getAllByName(ep.host)
        val preferred = when (ep.preferIpv6) {
            true -> all.firstOrNull { it.address.size == 16 }
            false -> all.firstOrNull { it.address.size == 4 }
            null -> null
        }
        return preferred ?: all.first()
    }

    private fun authenticate(c: SSHClient, ep: SshEndpoint) {
        val methods = ep.auth.map { it.toSshj() }
        if (methods.isEmpty()) throw SshError.AuthenticationFailed(ep.user, null)
        c.auth(ep.user, methods)
    }

    private fun SshAuth.toSshj(): AuthMethod = when (this) {
        is SshAuth.Password -> AuthPassword(object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray =
                password() ?: throw UserAuthException("Password entry cancelled")

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        })
        is SshAuth.PublicKey -> AuthPublickey(keyProvider)
        is SshAuth.KeyboardInteractive -> AuthKeyboardInteractive(object : ChallengeResponseProvider {
            private var instruction = ""
            override fun getSubmethods(): List<String> = emptyList()
            override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
                this.instruction = instruction ?: ""
            }

            override fun getResponse(prompt: String, echo: Boolean): CharArray =
                respond(instruction, prompt, echo) ?: throw UserAuthException("Prompt cancelled")

            override fun shouldRetry(): Boolean = false
        })
    }

    /** Opens an interactive shell with a PTY of [cols] x [rows]. */
    suspend fun openShell(
        cols: Int,
        rows: Int,
        terminalType: String = "xterm-256color",
        environment: Map<String, String> = emptyMap(),
        command: String? = null,
    ): ShellChannel = withContext(Dispatchers.IO) {
        val c = client ?: throw SshError.Disconnected("not connected")
        val session = c.startSession()
        for ((k, v) in environment) runCatching { session.setEnvVar(k, v) }
        session.allocatePTY(terminalType, cols, rows, 0, 0, emptyMap())
        val shell = session.startShell()
        val channel = ShellChannel(session, shell)
        if (!command.isNullOrBlank()) channel.write(command.trimEnd('\n', '\r') + "\n")
        channel
    }

    /** Runs [command] without a PTY and returns its stdout; for "install on host" and probes. */
    suspend fun exec(command: String, timeoutMillis: Long = 30_000): String = withContext(Dispatchers.IO) {
        val c = client ?: throw SshError.Disconnected("not connected")
        c.startSession().use { session ->
            val cmd = session.exec(command)
            val out = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            cmd.join(timeoutMillis, TimeUnit.MILLISECONDS)
            out
        }
    }

    /** Forwards [bindAddress]:[bindPort] on this device to [destHost]:[destPort] as seen from the server. */
    suspend fun startLocalForward(bindAddress: String, bindPort: Int, destHost: String, destPort: Int): ForwardHandle =
        withContext(Dispatchers.IO) {
            val c = client ?: throw SshError.Disconnected("not connected")
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, bindPort))
            }
            val forwarder: LocalPortForwarder =
                c.newLocalPortForwarder(Parameters(bindAddress, socket.localPort, destHost, destPort), socket)
            val thread = Thread({ runCatching { forwarder.listen() } }, "berth-forward-${socket.localPort}").apply {
                isDaemon = true
                start()
            }
            ForwardHandle(socket.localPort) {
                runCatching { forwarder.close() }
                runCatching { socket.close() }
                thread.interrupt()
            }
        }

    /** Asks the server to listen on [remoteBind]:[remotePort] and deliver connections to [destHost]:[destPort] here. */
    suspend fun startRemoteForward(remoteBind: String, remotePort: Int, destHost: String, destPort: Int): ForwardHandle =
        withContext(Dispatchers.IO) {
            val c = client ?: throw SshError.Disconnected("not connected")
            val forward = c.remotePortForwarder.bind(
                RemotePortForwarder.Forward(remoteBind, remotePort),
                SocketForwardingConnectListener(InetSocketAddress(destHost, destPort)),
            )
            ForwardHandle(forward.port) { runCatching { c.remotePortForwarder.cancel(forward) } }
        }

    override fun close() {
        closeQuietly()
        if (_state.value !is SshConnectionState.Disconnected) {
            _state.value = SshConnectionState.Disconnected("closed", null)
        }
    }

    private fun closeQuietly() {
        client?.let { c ->
            runCatching { c.transport.setDisconnectListener(null) }
            runCatching { c.disconnect() }
        }
        client = null
        hops.asReversed().forEach { runCatching { it.disconnect() } }
        hops.clear()
    }
}

/** True for the failures that a reconnect loop should treat as transient. */
fun Throwable.isTransientSshFailure(): Boolean = when (this) {
    is SshError.HostKeyRejected, is SshError.AuthenticationFailed -> false
    is SshError.ConnectFailed, is SshError.Disconnected -> true
    is ConnectionException, is TransportException, is IOException -> true
    else -> false
}
