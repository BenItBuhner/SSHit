package app.berth.android.session

import app.berth.domain.model.AddressFamily
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.ReconnectBackoff
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.sftp.SftpClient
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.ssh.ForwardHandle
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.ShellChannel
import app.berth.ssh.SshAuth
import app.berth.ssh.SshConnection
import app.berth.ssh.SshEndpoint
import app.berth.ssh.SshError
import app.berth.ssh.isTransientSshFailure
import app.berth.terminal.Mod
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalKey
import app.berth.terminal.TerminalListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.BindException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a connected session needs from the app: auth material, trust decisions, connectivity. */
interface SessionEnvironment {
    suspend fun authFor(host: Host): List<SshAuth>
    fun hostKeyPolicyFor(host: Host): HostKeyPolicy
    val networkAvailable: Flow<Unit>
    fun onClipboardText(text: String)
    fun now(): Long = System.currentTimeMillis()

    /** The tunnels configured for a host, as they change. */
    fun tunnelsFor(hostId: String): Flow<List<Tunnel>> = flowOf(emptyList())

    /** Rendered snippet bodies to type into a fresh shell on [host] in [workspaceId]. */
    suspend fun connectCommands(host: Host, workspaceId: String): List<String> = emptyList()
}

/** Runtime state of one configured tunnel on the session that carries it. */
sealed interface TunnelStatus {
    data object Starting : TunnelStatus

    /** [localPort] is the port actually bound; it matters when 0 was configured. */
    data class Up(val localPort: Int) : TunnelStatus

    data class Failed(val reason: String) : TunnelStatus
}

/**
 * One terminal and the connection behind it. Owns the [TerminalEmulator], drives the state machine
 * from the product vision (IDLE, CONNECTING, LIVE, RECONNECTING, DETACHED, FAILED, CLOSED), and
 * runs the reconnect loop with the host's persistence policy.
 */
class TerminalSession(
    initial: SessionRecord,
    private val scope: CoroutineScope,
    private val env: SessionEnvironment,
    private val onRecordChanged: suspend (SessionRecord) -> Unit,
) {
    val id: String = initial.id

    private val _record = MutableStateFlow(initial)
    val record: StateFlow<SessionRecord> = _record.asStateFlow()
    val host: Host get() = _record.value.hostSnapshot
    val state: SessionState get() = _record.value.state

    /** Bumps on every visible change; the renderer reads it to know when to redraw. */
    private val _screenVersion = MutableStateFlow(0L)
    val screenVersion: StateFlow<Long> = _screenVersion.asStateFlow()

    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bell: SharedFlow<Unit> = _bell.asSharedFlow()

    /** Seconds until the next reconnect attempt while [SessionState.RECONNECTING]; null otherwise. */
    private val _retryIn = MutableStateFlow<Int?>(null)
    val retryIn: StateFlow<Int?> = _retryIn.asStateFlow()

    /** Plain-language reason for [SessionState.FAILED], plus the raw error text. */
    private val _failure = MutableStateFlow<Pair<String, String>?>(null)
    val failure: StateFlow<Pair<String, String>?> = _failure.asStateFlow()

    /** Whether this session is currently on stage; off-stage events raise attention. */
    @Volatile var onStage: Boolean = false

    val emulator: TerminalEmulator = TerminalEmulator(
        cols = 80,
        rows = 24,
        listener = object : TerminalListener {
            override fun onScreenChanged() {
                _screenVersion.update { it + 1 }
            }

            override fun onTitleChanged(title: String) = patch { copy(title = title) }

            override fun onBell() {
                _bell.tryEmit(Unit)
                if (!onStage) attention("Bell")
            }

            override fun onResponse(data: ByteArray) = send(data)

            override fun onClipboardWrite(text: String) = env.onClipboardText(text)

            override fun onWorkingDirectoryChanged(url: String) {
                val path = url.substringAfter("://", url).let { rest -> rest.substring(rest.indexOf('/').coerceAtLeast(0)) }
                patch { copy(cwd = path.ifBlank { null }) }
            }

            override fun onNotification(title: String, body: String) {
                if (!onStage) attention(title.ifBlank { body }.ifBlank { "Notification" })
            }

            override fun onShellIntegration(mark: Char, param: String) {
                when (mark) {
                    'C' -> commandRunning = true
                    'D' -> if (commandRunning) {
                        commandRunning = false
                        if (!onStage) attention(if (param.isEmpty() || param == "0") "Command finished" else "Command failed ($param)")
                    }
                }
            }
        },
    )

    private var connection: SshConnection? = null
    private var shell: ShellChannel? = null
    private var connectJob: Job? = null
    private var commandRunning = false
    private var everLive = false

    /** Geometry the renderer last requested; applied to the PTY once a shell exists. */
    private var cols = 80
    private var rows = 24

    // ---- tunnels -------------------------------------------------------------------------------

    /** Live status of every tunnel this session carries, by tunnel id; empty unless Live and carrying. */
    private val _tunnels = MutableStateFlow<Map<String, TunnelStatus>>(emptyMap())
    val tunnels: StateFlow<Map<String, TunnelStatus>> = _tunnels.asStateFlow()

    /**
     * Set by the manager: exactly one session per host carries that host's tunnels, so a port is
     * bound once however many terminals are open to the same server.
     */
    val carriesTunnels = MutableStateFlow(false)

    private class Slot(val tunnel: Tunnel, val handle: ForwardHandle?, val error: String?)
    private val slots = HashMap<String, Slot>()
    private val tunnelGate = Mutex()
    private val tunnelRetry = MutableStateFlow(0)

    init {
        val configured = initial.hostId?.let { env.tunnelsFor(it) } ?: flowOf(emptyList())
        val liveState = _record.map { it.state }.distinctUntilChanged()
        scope.launch {
            combine(carriesTunnels, liveState, configured, tunnelRetry) { carry, state, list, _ ->
                if (carry && state == SessionState.LIVE) list.filter { it.enabled } else emptyList()
            }.collect { wanted -> reconcileTunnels(wanted) }
        }
    }

    /**
     * Brings the running forwards in line with [wanted]: closes what was removed, disabled or
     * edited, then starts what is missing on the current connection. Runs again after every
     * reconnect because the Live transition re-emits, which is what makes tunnels survive drops.
     */
    private suspend fun reconcileTunnels(wanted: List<Tunnel>) = tunnelGate.withLock {
        val byId = wanted.associateBy { it.id }
        synchronized(slots) {
            for ((id, slot) in slots.entries.toList()) {
                if (byId[id] != slot.tunnel) {
                    slot.handle?.let { runCatching(it::close) }
                    slots.remove(id)
                }
            }
        }
        val pending = wanted.filter { synchronized(slots) { !slots.containsKey(it.id) } }.toMutableList()
        publishTunnels(pending)
        val conn = connection
        for (tunnel in pending.toList()) {
            if (conn == null || !conn.isConnected) break
            val slot = try {
                Slot(tunnel, startForward(conn, tunnel), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Slot(tunnel, null, tunnelError(tunnel, e))
            }
            // The connection may have been torn down while the forward was starting.
            val stale = synchronized(slots) {
                if (connection !== conn) true else {
                    slots[tunnel.id] = slot
                    false
                }
            }
            if (stale) slot.handle?.let { runCatching(it::close) }
            pending.remove(tunnel)
            publishTunnels(pending)
        }
        publishTunnels(emptyList())
    }

    /**
     * Binds one forward. A port still held by the session that just handed the role over, or by
     * this session's own previous connection, frees up within moments, so one bind failure gets a
     * second try before it is reported.
     */
    private suspend fun startForward(conn: SshConnection, tunnel: Tunnel): ForwardHandle {
        repeat(BIND_RETRIES) {
            try {
                return openForward(conn, tunnel)
            } catch (e: BindException) {
                delay(BIND_RETRY_DELAY_MS)
            }
        }
        return openForward(conn, tunnel)
    }

    private suspend fun openForward(conn: SshConnection, tunnel: Tunnel): ForwardHandle = when (tunnel.type) {
        TunnelType.LOCAL -> conn.startLocalForward(tunnel.bindAddress, tunnel.bindPort, tunnel.destinationHost, tunnel.destinationPort)
        TunnelType.REMOTE -> conn.startRemoteForward(tunnel.bindAddress, tunnel.bindPort, tunnel.destinationHost, tunnel.destinationPort)
        TunnelType.DYNAMIC -> conn.startDynamicForward(tunnel.bindAddress, tunnel.bindPort)
    }

    private fun publishTunnels(starting: List<Tunnel>) {
        _tunnels.value = synchronized(slots) {
            buildMap {
                for ((id, slot) in slots) {
                    put(id, if (slot.handle != null) TunnelStatus.Up(slot.handle.localPort) else TunnelStatus.Failed(slot.error ?: "Couldn't start the tunnel."))
                }
                for (t in starting) if (t.id !in this) put(t.id, TunnelStatus.Starting)
            }
        }
    }

    /** Starts a tunnel again after it failed, e.g. once the port it wanted is free. */
    fun retryTunnel(id: String) {
        synchronized(slots) { slots.remove(id)?.handle?.let { runCatching(it::close) } }
        tunnelRetry.update { it + 1 }
    }

    private fun closeTunnels() {
        synchronized(slots) {
            slots.values.forEach { slot -> slot.handle?.let { runCatching(it::close) } }
            slots.clear()
        }
        _tunnels.value = emptyMap()
    }

    private fun tunnelError(tunnel: Tunnel, e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            e is BindException || message.contains("Address already in use", ignoreCase = true) ->
                "Port ${tunnel.bindPort} is already in use on this device."
            message.contains("Cannot assign requested address", ignoreCase = true) ->
                "${tunnel.bindAddress} is not an address of this device."
            tunnel.type == TunnelType.REMOTE -> "The server refused to listen on port ${tunnel.bindPort}."
            else -> message.ifBlank { "Couldn't start the tunnel." }
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    fun connect() {
        if (state.isActive) return
        startLoop(initialAttempt = true)
    }

    fun reconnectNow() {
        if (state == SessionState.LIVE || state == SessionState.CONNECTING) return
        startLoop(initialAttempt = false)
    }

    fun detach() {
        connectJob?.cancel()
        connectJob = null
        teardownConnection()
        marker("detached")
        transition(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME)
    }

    fun close() {
        connectJob?.cancel()
        connectJob = null
        teardownConnection()
        transition(SessionState.CLOSED, PersistenceLayer.LOCAL_FRAME)
    }

    private fun startLoop(initialAttempt: Boolean) {
        connectJob?.cancel()
        _failure.value = null
        connectJob = scope.launch { runLoop(reconnecting = !initialAttempt && everLive) }
    }

    private suspend fun runLoop(reconnecting: Boolean) {
        var attempt = 0
        var since = env.now()
        var isReconnect = reconnecting
        while (true) {
            transition(if (isReconnect) SessionState.RECONNECTING else SessionState.CONNECTING, PersistenceLayer.IN_APP)
            _retryIn.value = null
            val outcome = try {
                runOnce(isReconnect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Outcome.Failed(e)
            }
            when (outcome) {
                Outcome.Ended -> {
                    marker("session ended")
                    transition(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME)
                    return
                }
                is Outcome.Dropped -> {
                    // A live session fell over: start a fresh backoff window.
                    attempt = 0
                    since = env.now()
                    isReconnect = true
                    marker("connection lost: ${outcome.reason}")
                }
                is Outcome.Failed -> {
                    val e = outcome.error
                    if (!e.isTransientSshFailure()) {
                        fail(e)
                        return
                    }
                    isReconnect = everLive
                }
            }
            teardownConnection()
            if (!ReconnectBackoff.shouldRetry(env.now() - since, host.persistence)) {
                marker("gave up reconnecting")
                transition(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME)
                return
            }
            transition(SessionState.RECONNECTING, PersistenceLayer.IN_APP)
            waitBeforeRetry(ReconnectBackoff.delaySeconds(attempt++))
        }
    }

    private sealed interface Outcome {
        data object Ended : Outcome
        data class Dropped(val reason: String) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private suspend fun runOnce(isReconnect: Boolean): Outcome {
        val h = host
        val auth = env.authFor(h)
        val endpoint = SshEndpoint(
            host = h.address,
            port = h.port,
            user = h.user,
            auth = auth,
            keepaliveSeconds = h.persistence.keepaliveSeconds,
            compression = h.compression,
            preferIpv6 = when (h.addressFamily) {
                AddressFamily.AUTO -> null
                AddressFamily.IPV4 -> false
                AddressFamily.IPV6 -> true
            },
        )
        val conn = SshConnection(endpoint, env.hostKeyPolicyFor(h))
        connection = conn
        withTimeout(CONNECT_TIMEOUT_MS) { conn.connect() }

        val command = buildString {
            when (h.persistence.tmux) {
                TmuxMode.OFF -> Unit
                TmuxMode.ATTACH_OR_CREATE -> append("tmux new-session -A -s '${tmuxName(h)}'")
                TmuxMode.ATTACH_ONLY -> append("tmux attach-session -t '${tmuxName(h)}'")
            }
            if (h.persistence.tmux == TmuxMode.OFF && !h.startupCommand.isNullOrBlank()) append(h.startupCommand)
        }.ifBlank { null }

        val sh = conn.openShell(cols, rows, h.terminalType, h.environment + mapOf("COLORTERM" to "truecolor", "TERM_PROGRAM" to "berth"), command)
        shell = sh
        val firstShell = !everLive
        everLive = true
        if (isReconnect) marker("reconnected")
        transition(SessionState.LIVE, if (h.persistence.tmux != TmuxMode.OFF) PersistenceLayer.TMUX else PersistenceLayer.IN_APP)
        patch { copy(lastLiveAt = env.now(), needsAttention = false, attentionReason = null) }
        if (firstShell) runOnConnect(sh, h)

        val dropped = MutableStateFlow<String?>(null)
        conn.onDisconnected = { dropped.value = it.message ?: "disconnected" }

        try {
            withContext(Dispatchers.IO) {
                sh.output().collect { chunk -> emulator.write(chunk) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome.Dropped(dropped.value ?: e.message ?: "connection lost")
        }
        // EOF: either the remote shell exited or the transport died underneath it.
        return if (dropped.value != null || !conn.isConnected) Outcome.Dropped(dropped.value ?: "connection lost") else Outcome.Ended
    }

    private fun tmuxName(h: Host): String =
        (h.persistence.tmuxSessionName ?: "berth-${h.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}").ifBlank { "berth" }

    /**
     * Types the host's run-on-connect snippets into the new shell, once per session: a reconnect
     * lands in the same tmux session or a fresh login shell, and neither should replay them.
     * The PTY buffers input typed before the shell prompts, so a short grace period is enough.
     */
    private suspend fun runOnConnect(sh: ShellChannel, h: Host) {
        val commands = runCatching { env.connectCommands(h, _record.value.workspaceId) }.getOrDefault(emptyList())
        if (commands.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            delay(RUN_ON_CONNECT_GRACE_MS)
            for (command in commands) {
                if (shell !== sh) return@launch
                runCatching { sh.write((command.trimEnd('\n', '\r') + "\n").toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    private suspend fun waitBeforeRetry(seconds: Int) {
        var remaining = seconds
        while (remaining > 0) {
            _retryIn.value = remaining
            // A network coming back cuts the wait short; otherwise tick once per second.
            val cameBack = withTimeoutOrNull(1_000L) { env.networkAvailable.first(); true } ?: false
            if (cameBack) break
            remaining--
        }
        _retryIn.value = null
    }

    private fun fail(e: Throwable) {
        val plain = when (e) {
            is SshError.AuthenticationFailed -> "The server did not accept the credentials for ${host.user}."
            is SshError.HostKeyRejected -> "The host key was not trusted, so the connection was not made."
            is IllegalStateException -> e.message ?: "Couldn't connect."
            else -> "Couldn't connect to ${host.address}:${host.port}."
        }
        _failure.value = plain to (e.message ?: e.javaClass.simpleName)
        teardownConnection()
        transition(SessionState.FAILED, PersistenceLayer.LOCAL_FRAME)
    }

    private fun teardownConnection() {
        // Forward listeners hold device ports; release them before the next attempt binds again.
        closeTunnels()
        shell?.let { runCatching { it.close() } }
        shell = null
        connection?.let { c -> c.onDisconnected = null; runCatching { c.close() } }
        connection = null
    }

    // ---- files ---------------------------------------------------------------------------------

    /**
     * Opens an `sftp` channel on the connection this terminal is using, so the file browser and
     * the transfer queue ride the login already made. The caller owns the returned channel and
     * closes it; a reconnect replaces the connection underneath, at which point the channel's
     * calls fail with [SftpError.Io] and the caller opens a fresh one.
     */
    suspend fun openSftp(): SftpFileSystem {
        val conn = connection?.takeIf { it.isConnected && state == SessionState.LIVE } ?: throw SftpError.NotConnected()
        return SftpClient.open(conn)
    }

    /** The shell's working directory from OSC 7, when the shell reports it. */
    val cwd: String? get() = _record.value.cwd

    // ---- input ---------------------------------------------------------------------------------

    fun send(bytes: ByteArray) {
        val sh = shell ?: return
        scope.launch(Dispatchers.IO) { runCatching { sh.write(bytes) } }
    }

    fun sendText(text: String, modifiers: Int = 0) {
        if (modifiers == 0) {
            send(text.toByteArray(Charsets.UTF_8))
            return
        }
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.write(emulator.encodeText(cp, modifiers))
            i += Character.charCount(cp)
        }
        send(out.toByteArray())
    }

    fun sendKey(key: TerminalKey, modifiers: Int = 0) = send(emulator.encodeKey(key, modifiers))

    fun paste(text: String) = send(emulator.encodePaste(text))

    fun sendControl(char: Char) = send(emulator.encodeText(char.code, Mod.CTRL))

    fun resize(newCols: Int, newRows: Int) {
        if (newCols < 2 || newRows < 2) return
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        emulator.resize(newCols, newRows)
        shell?.let { sh -> scope.launch(Dispatchers.IO) { runCatching { sh.resize(newCols, newRows) } } }
        _screenVersion.update { it + 1 }
    }

    // ---- attention and record ------------------------------------------------------------------

    fun markSeen() {
        if (_record.value.needsAttention) patch { copy(needsAttention = false, attentionReason = null) }
    }

    private fun attention(reason: String) = patch { copy(needsAttention = true, attentionReason = reason) }

    private fun transition(state: SessionState, layer: PersistenceLayer) = patch { copy(state = state, layer = layer) }

    private fun patch(change: SessionRecord.() -> SessionRecord) {
        val updated = _record.updateAndGet { it.change() }
        scope.launch { onRecordChanged(updated) }
    }

    private fun <T> MutableStateFlow<T>.updateAndGet(fn: (T) -> T): T {
        while (true) {
            val prev = value
            val next = fn(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    /** Writes a dim marker row into the terminal, e.g. `reconnected 14:07`. */
    private fun marker(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(env.now()))
        val line = "\r\n\u001b[0;2m\u2500\u2500 $text $time \u2500\u2500\u001b[0m\r\n"
        emulator.write(line)
    }

    // ---- frames --------------------------------------------------------------------------------

    /** Scrollback plus screen as text, enough to bring a detached session's frame back after a restart. */
    fun snapshotFrame(): ByteArray {
        val lines = ArrayList<String>()
        synchronized(emulator.lock) {
            val sb = emulator.scrollbackSize
            for (i in 0 until sb) lines += emulator.viewLine(0, sb - i).toText()
            lines += emulator.screenText()
        }
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(FRAME_VERSION)
            d.writeInt(lines.size)
            for (l in lines) d.writeUTF(l.take(MAX_FRAME_LINE))
        }
        return out.toByteArray()
    }

    fun restoreFrame(frame: ByteArray) {
        runCatching {
            DataInputStream(frame.inputStream()).use { d ->
                if (d.readInt() != FRAME_VERSION) return
                val n = d.readInt()
                val text = StringBuilder()
                repeat(n) { text.append(d.readUTF()).append("\r\n") }
                emulator.write("\u001b[2m")
                emulator.write(text.toString())
                emulator.write("\u001b[0m")
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val RUN_ON_CONNECT_GRACE_MS = 400L
        private const val BIND_RETRIES = 2
        private const val BIND_RETRY_DELAY_MS = 250L
        private const val FRAME_VERSION = 1
        private const val MAX_FRAME_LINE = 4096
    }
}
