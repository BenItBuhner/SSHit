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
import net.schmizz.sshj.Service
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.sftp.SFTPClient
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

    /**
     * A key. With a [signer] the userauth signature comes from it rather than from a JCA
     * `Signature` sshj builds itself: a Keystore key that a prompt has just unlocked for one use.
     * [agentKey] is the same key as a forwarded agent would hold it, set for a host that forwards
     * one; the agent holds it only when this is the method that logged in ([SshConnection.agentKey]).
     */
    class PublicKey(val keyProvider: KeyProvider, val signer: SshSigner? = null, val agentKey: AgentKey? = null) : SshAuth

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
    /** The cipher names offered, most preferred first ([SshCiphers.select]); empty offers sshj's own list. */
    val ciphers: List<String> = emptyList(),
)

/**
 * One hop of a ProxyJump chain: where to log in and whose word to take on its host key. Each hop
 * carries its own [SshEndpoint.auth] and is checked against the known hosts for its own address,
 * so a jump host is trusted or refused on its own record, never on the target's.
 */
class SshHop(val endpoint: SshEndpoint, val hostKeyPolicy: HostKeyPolicy)

sealed class SshError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HostKeyRejected(host: String) : SshError("Host key for $host was not trusted")
    class AuthenticationFailed(user: String, cause: Throwable?) : SshError("Authentication failed for $user", cause)
    class ConnectFailed(host: String, port: Int, cause: Throwable) : SshError("Couldn't reach $host:$port: ${cause.message ?: cause.javaClass.simpleName}", cause)
    class Disconnected(reason: String) : SshError(reason)

    /**
     * Key exchange found no cipher both sides speak: the server accepts none of [offered]. Not
     * something a retry fixes; the host's cipher list (or the server's) has to change.
     */
    class NoCommonCipher(host: String, port: Int, val offered: List<String>, cause: Throwable?) :
        SshError("$host${if (port == 22) "" else ":$port"} accepts none of the ciphers offered: ${offered.joinToString(", ")}", cause)

    /**
     * A jump host, not the target, failed: [reason] is what went wrong there, [hop] which hop
     * (0-based) of [hopCount] it was. The connect flow names the hop so the user fixes the right host.
     */
    class JumpHopFailed(val hop: Int, val hopCount: Int, val host: String, val port: Int, val user: String, val reason: SshError) :
        SshError("Jump host $host${if (port == 22) "" else ":$port"} (hop ${hop + 1} of $hopCount): ${reason.message}", reason)
}

sealed interface SshConnectionState {
    data object Idle : SshConnectionState
    data object Connecting : SshConnectionState

    /** Logging in to jump host [host], hop [hop] (0-based) of [hopCount], on the way to the target. */
    data class ConnectingVia(val hop: Int, val hopCount: Int, val host: String) : SshConnectionState
    data object Authenticating : SshConnectionState
    data object Connected : SshConnectionState
    data class Disconnected(val reason: String, val error: Throwable?) : SshConnectionState
}

/**
 * An interactive shell on a PTY. Reading is a cold flow; writing and resizing are immediate.
 * [agentForwarded] says whether the server agreed to forward an agent into it.
 */
class ShellChannel internal constructor(
    private val session: Session,
    private val shell: Session.Shell,
    val agentForwarded: Boolean = false,
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
    /** Bytes and connections carried so far; live, read it again for the next tick. */
    val traffic: ForwardTraffic,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() = onClose()
}

/**
 * One SSH connection to one endpoint, optionally through a chain of jump hosts ([jumpHosts],
 * first hop first, each with its own login and host key trust). Wraps sshj with a
 * coroutine-friendly surface; every blocking call runs on [Dispatchers.IO].
 */
class SshConnection(
    private val endpoint: SshEndpoint,
    private val hostKeyPolicy: HostKeyPolicy,
    private val jumpHosts: List<SshHop> = emptyList(),
) : Closeable {
    private val _state = MutableStateFlow<SshConnectionState>(SshConnectionState.Idle)
    val state: StateFlow<SshConnectionState> = _state.asStateFlow()

    private var client: SSHClient? = null
    private val hops = ArrayList<SSHClient>()

    /**
     * The client whose connect or login is in flight: a hop before it joins [hops], the target
     * before it becomes [client]. A [close] in that moment drops it too, so a hop that hangs at its
     * greeting (or a slow target) does not keep its socket past the tab that wanted it.
     */
    @Volatile private var connecting: SSHClient? = null

    /** Set by [close]; a connect still running ends at its next stage rather than open a login nobody holds. */
    @Volatile private var closed = false

    /** Invoked from sshj's transport thread when the connection drops for any reason. */
    var onDisconnected: ((SshError.Disconnected) -> Unit)? = null

    val isConnected: Boolean get() = client?.isConnected == true && client?.isAuthenticated == true

    /** The server's host key as seen during key exchange, for the ribbon and the known hosts UI. */
    var serverHostKeyFingerprint: String? = null
        private set

    var serverHostKeyType: String? = null
        private set

    /** The target's auth method that logged in; null before the login and for a login that failed. */
    @Volatile var authenticatedWith: SshAuth? = null
        private set

    /**
     * The key a forwarded agent on this connection may hold: the one that logged in to the target,
     * when a key did and it was offered with an [SshAuth.PublicKey.agentKey]. A login by password
     * or keyboard-interactive, or one where the key was refused and a password let the user in,
     * leaves the agent with nothing to offer.
     */
    val agentKey: AgentKey? get() = (authenticatedWith as? SshAuth.PublicKey)?.agentKey

    /** The agent the connection's shell forwards, if it forwards one; closed with the connection. */
    @Volatile private var agent: SshAgent? = null
    private var agentOpener: AgentChannelOpener? = null

    /** The cipher key exchange settled on with the target, client to server (the first of the host's list its server speaks). */
    @Volatile var negotiatedCipher: String? = null
        private set

    init {
        SshSecurity.ensureProviders()
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        _state.value = SshConnectionState.Connecting
        try {
            var previous: SSHClient? = null
            jumpHosts.forEachIndexed { index, hop ->
                val ep = hop.endpoint
                _state.value = SshConnectionState.ConnectingVia(index, jumpHosts.size, ep.host)
                val hopClient = begin(newClient(ep, hop.hostKeyPolicy, isTarget = false))
                try {
                    connectClient(hopClient, ep, previous)
                    authenticate(hopClient, ep)
                } catch (e: Throwable) {
                    runCatching { hopClient.disconnect() }
                    throw SshError.JumpHopFailed(index, jumpHosts.size, ep.host, ep.port, ep.user, e.toSshError(ep))
                }
                hops += hopClient
                connecting = null
                previous = hopClient
            }
            _state.value = SshConnectionState.Connecting
            val target = begin(newClient(endpoint, hostKeyPolicy, isTarget = true))
            connectClient(target, endpoint, previous)
            _state.value = SshConnectionState.Authenticating
            authenticatedWith = authenticate(target, endpoint)
            target.transport.setDisconnectListener { reason, message ->
                val text = if (message.isNullOrBlank()) reason.toString() else message
                val error = SshError.Disconnected(text)
                _state.value = SshConnectionState.Disconnected(text, error)
                onDisconnected?.invoke(error)
            }
            client = target
            connecting = null
            // Closed while the login was finishing: the catch drops what was made instead of leaving it up.
            if (closed) throw SshError.Disconnected("closed")
            _state.value = SshConnectionState.Connected
        } catch (e: Throwable) {
            closeQuietly()
            val error = e.toSshError(endpoint)
            _state.value = SshConnectionState.Disconnected(error.message ?: "disconnected", error)
            throw error
        }
    }

    /** Registers [c] as the client in flight; after a [close] the attempt ends here, before the client opens anything. */
    private fun begin(c: SSHClient): SSHClient {
        connecting = c
        if (closed) throw SshError.Disconnected("closed")
        return c
    }

    private fun Throwable.toSshError(ep: SshEndpoint): SshError = when (this) {
        is SshError -> this
        is UserAuthException -> SshError.AuthenticationFailed(ep.user, this)
        is TransportException ->
            if (disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) SshError.HostKeyRejected(ep.host)
            else if (isCipherSettlementFailure()) SshError.NoCommonCipher(ep.host, ep.port, offeredCiphers(ep), this)
            else SshError.ConnectFailed(ep.host, ep.port, this)
        else ->
            if (isCipherSettlementFailure()) SshError.NoCommonCipher(ep.host, ep.port, offeredCiphers(ep), this)
            else SshError.ConnectFailed(ep.host, ep.port, this)
    }

    /** sshj's word for a key exchange with no cipher in common, either direction, anywhere in the chain of causes. */
    private fun Throwable.isCipherSettlementFailure(): Boolean = generateSequence(this) { it.cause }.take(8).any { e ->
        val message = e.message.orEmpty()
        message.contains("settlement of") && message.contains("CipherAlgorithms")
    }

    private fun offeredCiphers(ep: SshEndpoint): List<String> = SshCiphers.select(DefaultConfig().cipherFactories, ep.ciphers).map { it.name }

    private fun newClient(ep: SshEndpoint, policy: HostKeyPolicy, isTarget: Boolean): SSHClient {
        val config = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            cipherFactories = SshCiphers.select(cipherFactories, ep.ciphers)
        }
        val c = SSHClient(config)
        if (isTarget) {
            c.transport.addAlgorithmsVerifier { negotiated ->
                negotiatedCipher = negotiated.client2ServerCipherAlgorithm
                true
            }
        }
        c.addHostKeyVerifier(
            PolicyHostKeyVerifier(
                host = ep.host,
                port = ep.port,
                policy = object : HostKeyPolicy by policy {
                override fun onKnownHostSeen(request: HostKeyRequest) {
                    if (isTarget) remember(request)
                    policy.onKnownHostSeen(request)
                }

                override fun onUnknownHost(request: HostKeyRequest): Boolean {
                    if (isTarget) remember(request)
                    return policy.onUnknownHost(request)
                }

                override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>): Boolean {
                    if (isTarget) remember(request)
                    return policy.onChangedHostKey(request, known)
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

    /**
     * Tries [ep]'s methods in order and returns the one that logged in. The loop is sshj's own
     * (`SSHClient.auth`) with the method kept, so the agent knows whether a key or a password
     * got the user in; the failure it throws is the one sshj would.
     */
    private fun authenticate(c: SSHClient, ep: SshEndpoint): SshAuth {
        if (ep.auth.isEmpty()) throw SshError.AuthenticationFailed(ep.user, null)
        val failures = ArrayDeque<UserAuthException>()
        for (auth in ep.auth) {
            val method = auth.toSshj()
            method.setLoggerFactory(c.transport.config.loggerFactory)
            try {
                if (c.userAuth.authenticate(ep.user, c.connection as Service, method, c.transport.timeoutMs)) return auth
            } catch (e: UserAuthException) {
                failures.addFirst(e)
            }
        }
        throw UserAuthException("Exhausted available authentication methods", failures.firstOrNull())
    }

    private fun SshAuth.toSshj(): AuthMethod = when (this) {
        is SshAuth.Password -> AuthPassword(object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray =
                password() ?: throw UserAuthException("Password entry cancelled")

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        })
        is SshAuth.PublicKey -> signer?.let { SignerAuthPublickey(keyProvider, it) } ?: AuthPublickey(keyProvider)
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

    /**
     * Opens an interactive shell with a PTY of [cols] x [rows]. With an [agent], the shell asks
     * for agent forwarding first, as `ssh -A` does, and the agent answers every agent channel the
     * server opens on this connection from then on; the connection's [close] closes it. Whether
     * the server agreed is [ShellChannel.agentForwarded]; a refusal still opens the shell.
     */
    suspend fun openShell(
        cols: Int,
        rows: Int,
        terminalType: String = "xterm-256color",
        environment: Map<String, String> = emptyMap(),
        command: String? = null,
        agent: SshAgent? = null,
    ): ShellChannel = withContext(Dispatchers.IO) {
        val c = client ?: throw SshError.Disconnected("not connected")
        val session = if (agent == null) c.startSession() else AgentSessionChannel(c.connection, c.remoteCharset).also { it.open() }
        val forwarded = agent != null && forwardAgent(c, session as AgentSessionChannel, agent)
        for ((k, v) in environment) runCatching { session.setEnvVar(k, v) }
        session.allocatePTY(terminalType, cols, rows, 0, 0, emptyMap())
        val shell = session.startShell()
        val channel = ShellChannel(session, shell, forwarded)
        if (!command.isNullOrBlank()) channel.write(command.trimEnd('\n', '\r') + "\n")
        channel
    }

    /**
     * Makes [agent] the one this connection's agent channels reach, replacing and closing an
     * earlier one, then asks the server to forward it into [session]. The opener goes on before
     * the request, so a channel the server opens the moment it agrees has somewhere to land.
     */
    private fun forwardAgent(c: SSHClient, session: AgentSessionChannel, agent: SshAgent): Boolean {
        val previous = this.agent
        this.agent = agent
        if (previous !== agent) previous?.close()
        if (agentOpener == null) {
            agentOpener = AgentChannelOpener(c.connection) { this.agent }.also { c.connection.attach(it) }
        }
        return session.requestAgentForwarding(c.connection.timeoutMs.toLong())
    }

    /**
     * Opens an `sftp` subsystem channel on this connection, so file browsing rides the login the
     * terminal already made. The caller owns the client and closes it; the connection outlives it.
     */
    suspend fun openSftp(): SFTPClient = withContext(Dispatchers.IO) {
        val c = client ?: throw SshError.Disconnected("not connected")
        c.newSFTPClient()
    }

    /**
     * Asks the server for a reply over the live transport and waits up to [timeoutMillis] for it:
     * a `keepalive@openssh.com` global request with want-reply set, the packet OpenSSH's own
     * `ServerAliveInterval` sends. OpenSSH answers it with a failure and other servers with either,
     * and any answer proves the socket still carries both ways. Returns whether the server
     * answered in time; false with nothing to ask. Decides nothing itself: the caller knows what
     * else came over the socket while it waited, and drops the connection with [dropAsLost] if
     * nothing did (vision §4.4).
     */
    suspend fun probe(timeoutMillis: Long): Boolean = withContext(Dispatchers.IO) {
        val c = client?.takeIf { it.isConnected } ?: return@withContext false
        try {
            val promise = c.connection.sendGlobalRequest(PROBE_REQUEST, true, ByteArray(0))
            try {
                promise.retrieve(timeoutMillis, TimeUnit.MILLISECONDS)
                true
            } catch (_: ConnectionException) {
                // A refusal (REQUEST_FAILURE) lands as an error on the promise, and so does a transport
                // that dies while it waits; a timeout leaves it unfulfilled. An answered promise over a
                // transport still up is the server's word; the dying transport tells its readers itself.
                promise.isFulfilled && c.isConnected
            }
        } catch (_: TransportException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Holds the connection lost, [reason] said: the transport is disconnected as
     * `CONNECTION_LOST`, which reaches [onDisconnected] and every open channel the way any drop
     * does, so the reconnect loop starts now rather than once the keepalive count runs out. For a
     * [probe] the network change left unanswered; nothing to do on a connection already gone.
     */
    fun dropAsLost(reason: String) {
        val c = client?.takeIf { it.isConnected } ?: return
        runCatching { c.transport.disconnect(DisconnectReason.CONNECTION_LOST, reason) }
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

    /**
     * Forwards [bindAddress]:[bindPort] on this device to [destHost]:[destPort] as seen from the
     * server (`ssh -L`). Each accepted connection becomes a `direct-tcpip` channel; the handle's
     * traffic counts what they carry.
     */
    suspend fun startLocalForward(bindAddress: String, bindPort: Int, destHost: String, destPort: Int): ForwardHandle =
        withContext(Dispatchers.IO) {
            val c = client ?: throw SshError.Disconnected("not connected")
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, bindPort))
            }
            val traffic = ForwardTraffic()
            val forwarder = LocalForwarder(socket, traffic) { ChannelStream(c.newDirectConnection(destHost, destPort)) }
            val thread = Thread({ forwarder.listen() }, "berth-forward-${socket.localPort}").apply {
                isDaemon = true
                start()
            }
            ForwardHandle(socket.localPort, traffic) {
                forwarder.close()
                thread.interrupt()
            }
        }

    /** Asks the server to listen on [remoteBind]:[remotePort] and deliver connections to [destHost]:[destPort] here (`ssh -R`). */
    suspend fun startRemoteForward(remoteBind: String, remotePort: Int, destHost: String, destPort: Int): ForwardHandle =
        withContext(Dispatchers.IO) {
            val c = client ?: throw SshError.Disconnected("not connected")
            val traffic = ForwardTraffic()
            val listener = CountingConnectListener(InetSocketAddress(destHost, destPort), traffic)
            val forward = c.remotePortForwarder.bind(RemotePortForwarder.Forward(remoteBind, remotePort), listener)
            ForwardHandle(forward.port, traffic) {
                runCatching { c.remotePortForwarder.cancel(forward) }
                listener.close()
            }
        }

    /**
     * Starts a SOCKS5/4a proxy on [bindAddress]:[bindPort]; every connection it accepts leaves
     * through the server as a `direct-tcpip` channel (`ssh -D`).
     */
    suspend fun startDynamicForward(bindAddress: String, bindPort: Int): ForwardHandle = withContext(Dispatchers.IO) {
        val c = client ?: throw SshError.Disconnected("not connected")
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, bindPort))
        }
        val traffic = ForwardTraffic()
        val proxy = SocksProxy(socket, traffic) { host, port -> ChannelStream(c.newDirectConnection(host, port)) }
        val thread = Thread({ proxy.listen() }, "berth-socks-${socket.localPort}").apply {
            isDaemon = true
            start()
        }
        ForwardHandle(socket.localPort, traffic) {
            proxy.close()
            thread.interrupt()
        }
    }

    override fun close() {
        closed = true
        closeQuietly()
        if (_state.value !is SshConnectionState.Disconnected) {
            _state.value = SshConnectionState.Disconnected("closed", null)
        }
    }

    private fun closeQuietly() {
        agent?.close()
        agent = null
        client?.let { c ->
            runCatching { c.transport.setDisconnectListener(null) }
            runCatching { c.disconnect() }
        }
        client = null
        // Closing the in-flight client's streams ends the blocked greeting or login on the connecting thread.
        connecting?.let { c -> runCatching { c.disconnect() } }
        connecting = null
        hops.asReversed().forEach { runCatching { it.disconnect() } }
        hops.clear()
    }

    companion object {
        /** The global request [probe] sends: what OpenSSH's client sends for `ServerAliveInterval`, answered by every server. */
        const val PROBE_REQUEST = "keepalive@openssh.com"
    }
}

/** True for the failures that a reconnect loop should treat as transient. */
fun Throwable.isTransientSshFailure(): Boolean = when (this) {
    is SshError.HostKeyRejected, is SshError.AuthenticationFailed, is SshError.NoCommonCipher -> false
    is SshError.JumpHopFailed -> reason.isTransientSshFailure()
    is SshError.ConnectFailed, is SshError.Disconnected -> true
    is ConnectionException, is TransportException, is IOException -> true
    else -> false
}
