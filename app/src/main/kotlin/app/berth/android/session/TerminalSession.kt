package app.berth.android.session

import app.berth.android.diagnostics.BerthLog
import app.berth.domain.model.AddressFamily
import app.berth.domain.model.AltKeyMode
import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.ReconnectBackoff
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.TabKind
import app.berth.domain.model.TerminalSettings
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.sftp.SftpClient
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.ssh.AgentApprover
import app.berth.ssh.AgentSignPurpose
import app.berth.ssh.AgentSignRequest
import app.berth.ssh.ForwardHandle
import app.berth.ssh.ForwardTraffic
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.ShellChannel
import app.berth.ssh.SshAgent
import app.berth.ssh.SshAuth
import app.berth.ssh.SshConnection
import app.berth.ssh.SshConnectionState
import app.berth.ssh.SshEndpoint
import app.berth.ssh.SshError
import app.berth.ssh.SshHop
import app.berth.ssh.isTransientSshFailure
import app.berth.terminal.CellPos
import app.berth.terminal.Mod
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalKey
import app.berth.terminal.TerminalListener
import app.berth.terminal.TerminalText
import app.berth.terminal.TypedLine
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
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
import java.util.concurrent.atomic.AtomicBoolean

/** What a connected session needs from the app: auth material, trust decisions, connectivity. */
interface SessionEnvironment {
    suspend fun authFor(host: Host): List<SshAuth>
    fun hostKeyPolicyFor(host: Host): HostKeyPolicy

    /**
     * The policy for [host] with what the login knows about it: [via] when the host is a jump host
     * of another login, so what the policy asks the user says which hop it is and where the chain is
     * going; [linkFingerprint] when a link opened the login and carried a fingerprint for the
     * server's key (`;fingerprint=`), so the trust sheets can say how it compares. A link names the
     * destination's key, never a hop's, so the two are never set together. The plain policy unless
     * overridden; stand-ins that accept every key need not override it.
     */
    fun hostKeyPolicyFor(host: Host, via: HopRole?, linkFingerprint: String?): HostKeyPolicy = hostKeyPolicyFor(host)
    val networkAvailable: Flow<Unit>

    /**
     * The network under the app changed (another default network, none, or new addresses on the
     * same one): a live session asks its server for a reply at once ([TerminalSession.probe]),
     * so a socket the change left dead is found in seconds rather than after the keepalive count
     * (vision §4.4). A stand-in that never emits leaves the keepalive to it.
     */
    val networkChanges: Flow<Unit> get() = emptyFlow()

    /**
     * How long a terminal may sit idle before it detaches itself and keeps its frame, in
     * milliseconds, or null for never (spec C20 Connection, vision §4.4); the current value and
     * each change. A stand-in that says nothing keeps every session.
     */
    val idleDetachAfter: Flow<Long?> get() = flowOf(null)

    /**
     * Settings › Connection as it stands (spec C20): the Keepalive and Reconnect a host that sets
     * neither of its own gets, read when a login is made and each time a retry is weighed. A
     * stand-in keeps the shipped defaults.
     */
    suspend fun connectionDefaults(): ConnectionSettings = ConnectionSettings()

    /**
     * Lines of history [host]'s terminal keeps: the host's own (Advanced › Scrollback) or Settings ›
     * Terminal › Scrollback, held to the spec's bounds ([TerminalSettings.scrollbackFor]); the current
     * value and each change, so an edit reaches a tab already open. A stand-in keeps the host's or the default.
     */
    fun scrollbackLinesFor(host: Host): Flow<Int> =
        flowOf(TerminalSettings.scrollbackFor(host.scrollbackLines, TerminalSettings.DEFAULT_SCROLLBACK))

    /**
     * The host's ProxyJump chain as saved hosts, first hop first; an id no host answers to any
     * more is skipped. Each hop logs in with its own [authFor] and is trusted by its own [hostKeyPolicyFor].
     */
    suspend fun jumpHostsFor(host: Host): List<Host> = emptyList()

    /** A program on [host] asked to write the phone's clipboard (OSC 52); the app decides whether it may. */
    fun onClipboardText(host: Host, text: String)

    /**
     * Whether the agent forwarded to [host] signs without asking (the host editor's Signatures,
     * Always allow); read at each request, so a change reaches a tab already connected.
     * A stand-in asks.
     */
    suspend fun agentSignsSilently(host: Host): Boolean = false

    /** A program on [host] asked the agent forwarded to it for [request]; the user's answer. A stand-in refuses. */
    suspend fun approveAgentRequest(host: Host, request: AgentSignRequest): AgentAnswer = AgentAnswer.DENY
    fun now(): Long = System.currentTimeMillis()

    /** The tunnels configured for a host, as they change. */
    fun tunnelsFor(hostId: String): Flow<List<Tunnel>> = flowOf(emptyList())

    /** Rendered snippet bodies to type into a fresh shell on [host] in [workspaceId]. */
    suspend fun connectCommands(host: Host, workspaceId: String): List<String> = emptyList()

    /** Whether sessions keep the commands they run (spec C16, the Settings toggle). */
    fun commandHistoryEnabled(): Boolean = true

    /**
     * The session ran [text] at [at] on the host whose history key is [hostId] (spec C16, History
     * per host). Called from the emulator's thread, in the order the commands ran; the store keeps
     * the cap and treats a repeat of the host's latest as one. A stand-in may keep nothing.
     */
    fun recordCommand(hostId: String, text: String, at: Long) = Unit

    /**
     * Commands an older build kept in this tab's frame, as (text, time) oldest first, to be handed
     * to the host's history once the frame is restored; entries already there are not added again.
     */
    fun importCommands(hostId: String, entries: List<Pair<String, Long>>) = Unit

    /**
     * The transport failed on [host]: a connect attempt refused for good, a live connection that
     * fell over, or the retries given up on. [phase] says which; [error] is what the transport
     * threw, when it threw. The app writes these as connection reports ([app.berth.android.diagnostics.CrashReporter]),
     * beside the marker the terminal shows; a stand-in keeps nothing.
     */
    fun onTransportFailure(host: Host, phase: String, error: Throwable?, detail: String) = Unit

    /** What a hardware keyboard's Alt does to a character typed at [hostId] (spec C22, Settings › Hardware keyboard). */
    fun altKeyFor(hostId: String?): AltKeyMode = AltKeyMode.ESC_PREFIX
}

/**
 * What a login to [h] dials: its address and options, and the keepalive it sends, the host's own or
 * Settings › Connection's ([defaults]) when it inherits. A jump chain dials each hop this way, with
 * the hop's own host.
 */
internal fun sshEndpointFor(h: Host, auth: List<SshAuth>, defaults: ConnectionSettings) = SshEndpoint(
    host = h.address,
    port = h.port,
    user = h.user,
    auth = auth,
    keepaliveSeconds = h.persistence.effectiveKeepaliveSeconds(defaults),
    compression = h.compression,
    ciphers = h.ciphers,
    preferIpv6 = when (h.addressFamily) {
        AddressFamily.AUTO -> null
        AddressFamily.IPV4 -> false
        AddressFamily.IPV6 -> true
    },
)

/** A failure worth telling the user about away from the Stage (spec C21, Problems channel). */
sealed interface SessionProblem {
    /** Every retry in the host's window failed; the session is Detached with its frame kept. */
    data class GaveUp(val afterMillis: Long) : SessionProblem

    /** A connect attempt failed for a reason a retry will not fix; [reason] is the plain-language text of [TerminalSession.failure]. */
    data class Failed(val reason: String, val authentication: Boolean) : SessionProblem
}

/**
 * Why a login is [SessionState.FAILED]: the reason in plain language, the raw error behind
 * Details, and the saved jump host it failed at when a hop, not the target, is what went wrong.
 */
data class SessionFailure(val plain: String, val raw: String, val hop: FailedHop? = null)

/** The saved jump host a login failed at, for the action that opens its editor rather than the target's. */
data class FailedHop(val hostId: String, val name: String)

/** Runtime state of one configured tunnel on the session that carries it. */
sealed interface TunnelStatus {
    data object Starting : TunnelStatus

    /**
     * [localPort] is the port actually bound; it matters when 0 was configured. [traffic] is the
     * forward's live counters (bytes each way, connections), read rather than observed: they move
     * with every packet, so the Tunnels tab samples them on a clock instead of the state changing.
     */
    data class Up(val localPort: Int, val traffic: ForwardTraffic? = null) : TunnelStatus

    data class Failed(val reason: String) : TunnelStatus
}

/**
 * One SSH login and the connection behind it. Owns the [TerminalEmulator], drives the state
 * machine from the product vision (IDLE, CONNECTING, LIVE, RECONNECTING, DETACHED, FAILED, CLOSED),
 * and runs the reconnect loop with the host's persistence policy. A [TabKind.Tunnels] record makes
 * a session that opens no shell: the login exists to carry the host's forwards ([tunnels]) and is
 * Live for as long as the transport holds, reconnecting like a terminal when it drops.
 */
class TerminalSession(
    initial: SessionRecord,
    private val scope: CoroutineScope,
    private val env: SessionEnvironment,
    /**
     * The fingerprint the link that opened this tab carried for the server's key, if a link did;
     * every connection of the tab compares the key it is offered with it ([LinkFingerprint]). Not
     * persisted: by the time the tab is restored the key is saved or the tab never connected.
     */
    val linkFingerprint: String? = null,
    private val onRecordChanged: suspend (SessionRecord) -> Unit,
) : ManagedTab {
    override val id: String = initial.id
    override val kind: TabKind = if (initial.kind == TabKind.Tunnels) TabKind.Tunnels else TabKind.Ssh

    /** True for a login that only carries the host's forwards: no shell, no terminal on stage. */
    val tunnelsOnly: Boolean get() = kind == TabKind.Tunnels

    private val _record = MutableStateFlow(initial)
    override val record: StateFlow<SessionRecord> = _record.asStateFlow()
    override val host: Host get() = _record.value.hostSnapshot
    override val state: SessionState get() = _record.value.state

    /**
     * While connecting through a jump chain, the hop being made: `via bastion (1 of 2)`; null
     * otherwise. The connect flow shows it under the state so a slow hop is seen as the hop.
     */
    private val _via = MutableStateFlow<String?>(null)
    val via: StateFlow<String?> = _via.asStateFlow()

    /** Bumps on every visible change; the renderer reads it to know when to redraw. */
    private val _screenVersion = MutableStateFlow(0L)
    val screenVersion: StateFlow<Long> = _screenVersion.asStateFlow()

    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bell: SharedFlow<Unit> = _bell.asSharedFlow()

    /**
     * A key typed into this tab while it was [SessionState.DETACHED] (review #15): the key was not
     * sent, since the shell it would reach is not the one it was typed at, and the tab is
     * reconnecting instead, the state pill's own action. The Stage says so over the terminal.
     */
    private val _keyReconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val keyReconnected: SharedFlow<Unit> = _keyReconnected.asSharedFlow()

    /** Seconds until the next reconnect attempt while [SessionState.RECONNECTING]; null otherwise. */
    private val _retryIn = MutableStateFlow<Int?>(null)
    val retryIn: StateFlow<Int?> = _retryIn.asStateFlow()

    /** Why the login is [SessionState.FAILED]: the plain reason, the raw error, and the hop it failed at. */
    private val _failure = MutableStateFlow<SessionFailure?>(null)
    val failure: StateFlow<SessionFailure?> = _failure.asStateFlow()

    /**
     * Failures the user should hear about away from this tab. Off stage they also raise attention,
     * so on screen the ring says it; while the app is away the manager posts them as Problems.
     */
    private val _problems = MutableSharedFlow<SessionProblem>(extraBufferCapacity = 4)
    val problems: SharedFlow<SessionProblem> = _problems.asSharedFlow()

    /**
     * A live connection fell over, with the reason the `connection lost` marker gives; the reconnect
     * loop is already running when this lands. The manager reads the first one that comes while the
     * app is away, and that a network change does not account for ([PROBE_LOST_REASON]), as the cue
     * for the battery-optimisation explainer (vision §4.4). Not a [SessionProblem]: a drop that is
     * being retried is no news for the shade, whose ongoing line already says `reconnecting`.
     */
    private val _drops = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val drops: SharedFlow<String> = _drops.asSharedFlow()

    /**
     * Holds [onStage] and the attention state together. Output raises attention on the reader's
     * thread (the emulator's listener, under its lock) while the manager moves the stage from
     * another, and the check "off stage, so ring" must not interleave with the move "on stage, and
     * seen": a bell landing between the two would light the tab the user is looking at, with
     * nothing to clear it. Taken inside the emulator's lock and the manager's stage monitor, never
     * the other way round.
     */
    private val attentionLock = Any()

    /** Whether this session is currently on stage; off-stage events raise attention, on-stage ones never do ([attention]). */
    @Volatile override var onStage: Boolean = false
        set(value) {
            synchronized(attentionLock) { field = value }
        }

    /**
     * When the current attention was raised, or null while the tab needs nothing. Kept off the
     * record (no schema change) for jump-to-unread, which goes to the most recent, and for the
     * Attention notification's timestamp.
     */
    @Volatile var attentionAt: Long? = null
        private set

    /**
     * The problem behind the current attention, when a lost connection rather than output raised
     * it. Away from the app that one is the Problems notification's, with Retry and Detach, so the
     * manager does not repeat it as Attention.
     */
    @Volatile var attentionProblem: SessionProblem? = null
        private set

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
                attention("Bell")
            }

            override fun onResponse(data: ByteArray) = send(data)

            override fun onClipboardWrite(text: String) = env.onClipboardText(host, text)

            override fun onWorkingDirectoryChanged(url: String) {
                val path = url.substringAfter("://", url).let { rest -> rest.substring(rest.indexOf('/').coerceAtLeast(0)) }
                patch { copy(cwd = path.ifBlank { null }) }
            }

            override fun onNotification(title: String, body: String) {
                attention(title.ifBlank { body }.ifBlank { "Notification" })
            }

            override fun onShellIntegration(mark: Char, param: String) {
                shellHasMarks = true
                when (mark) {
                    'C' -> commandStartedAt = env.now()
                    'D' -> commandStartedAt?.let { started ->
                        commandStartedAt = null
                        // Only a command that ran long enough to have been walked away from counts
                        // (vision §4.5): a quick `ls` in a background tab is not news.
                        if (env.now() - started >= ATTENTION_COMMAND_MS) {
                            attention(if (param.isEmpty() || param == "0") "Command finished" else "Command failed ($param)")
                        }
                    }
                }
            }

            override fun onCommandEntered(command: String) = recordCommand(command)
        },
    )

    // ---- command history (spec C16) ------------------------------------------------------------

    /**
     * The history this tab's commands go to: its host's, shared by every tab on the host and read
     * by the History sheet from the store ([Host.commandHistoryKey]). The commands are read off
     * the shell's OSC 133 marks, or for a shell without them from what was typed and then seen
     * echoed on the line it was typed on, so a password never lands there and nothing typed into
     * a full-screen program does.
     */
    val commandHistoryKey: String get() = _record.value.hostSnapshot.commandHistoryKey

    /** Once the shell has sent an OSC 133 mark it reports its commands itself and the typed-line fallback stands down. */
    @Volatile private var shellHasMarks = false
    private val typedLine = TypedLine()

    private fun recordCommand(text: String) {
        val command = text.trim()
        if (command.isEmpty() || !env.commandHistoryEnabled()) return
        env.recordCommand(commandHistoryKey, command, env.now())
        if (_record.value.lastCommand != command) patch { copy(lastCommand = command) }
    }

    private fun trackTyped(text: String, modifiers: Int) {
        if (shellHasMarks || !env.commandHistoryEnabled()) return
        synchronized(typedLine) {
            if (modifiers != 0) {
                // Ctrl+C and Ctrl+U abandon the line; any other chord edits it in a way this cannot follow.
                val c = text.firstOrNull()?.lowercaseChar()
                if (modifiers and Mod.CTRL != 0 && (c == 'c' || c == 'u')) typedLine.reset() else typedLine.unreliable()
                return
            }
            var start = 0
            while (true) {
                val nl = text.indexOfAny(LINE_BREAKS, start)
                if (nl < 0) {
                    typedLine.typed(text.substring(start))
                    return
                }
                typedLine.typed(text.substring(start, nl))
                commitTyped()
                start = nl + 1
                if (text[nl] == '\r' && start < text.length && text[start] == '\n') start++
            }
        }
    }

    private fun trackKey(key: TerminalKey, modifiers: Int) {
        if (shellHasMarks || !env.commandHistoryEnabled()) return
        synchronized(typedLine) {
            when {
                modifiers != 0 -> typedLine.unreliable()
                key == TerminalKey.ENTER -> commitTyped()
                key == TerminalKey.BACKSPACE -> typedLine.backspace()
                key == TerminalKey.TAB -> typedLine.completed()
                else -> typedLine.unreliable()
            }
        }
    }

    private fun trackPaste(text: String) {
        if (shellHasMarks || !env.commandHistoryEnabled()) return
        synchronized(typedLine) {
            val trimmed = text.trimEnd(' ', '\t')
            when {
                // One line lands on the prompt like typing.
                text.indexOfAny(LINE_BREAKS) < 0 -> typedLine.typed(text)
                // Every pasted line ran (or, bracketed, sits as one block Enter will run whole): the prompt is clean after.
                trimmed.endsWith('\n') || trimmed.endsWith('\r') -> typedLine.reset()
                else -> typedLine.unreliable()
            }
        }
    }

    /**
     * Enter without shell marks: the typed line is checked against the row the cursor is on. Typed
     * key by key, the echo is already there and the command is recorded at once, before it can
     * clear the screen; sent in one write with its Enter (a snippet, a test), the echo has not
     * landed yet, so the check runs again after a moment. The row is kept as a buffer row plus the
     * lines dropped, which output does not move; a resize re-wraps history, and then the line is
     * simply not found.
     */
    private fun commitTyped() {
        val pending = typedLine.commit() ?: return
        val (stable, now) = synchronized(emulator.lock) {
            if (emulator.isAlternateScreen) return
            val row = emulator.scrollbackSize + emulator.cursorY
            (row + emulator.linesDropped) to TerminalText.extract(emulator.grid, TerminalText.snapToLine(emulator.grid, CellPos(row, 0)))
        }
        pending.resolve(now)?.let {
            recordCommand(it)
            return
        }
        scope.launch {
            delay(ECHO_GRACE_MS)
            val line = synchronized(emulator.lock) {
                val row = (stable - emulator.linesDropped).toInt()
                if (emulator.isAlternateScreen || row < 0 || row >= emulator.bufferRows) return@launch
                TerminalText.extract(emulator.grid, TerminalText.snapToLine(emulator.grid, CellPos(row, 0)))
            }
            pending.resolve(line)?.let { recordCommand(it) }
        }
    }

    private var connection: SshConnection? = null

    /** The saved hosts the last attempt's chain went through, by hop; the pill and a hop's failure name the hop by them, and its failure opens its editor. */
    private var chainHosts: List<Host> = emptyList()

    /** The saved name of hop [index], or the [address] the transport knows it by when the chain has moved under the attempt. */
    private fun hopName(index: Int, address: String): String = chainHosts.getOrNull(index)?.name ?: address
    private var shell: ShellChannel? = null
    private var connectJob: Job? = null

    /** When the running command began (OSC 133 `C`), or null between commands. */
    private var commandStartedAt: Long? = null
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
                    put(id, if (slot.handle != null) TunnelStatus.Up(slot.handle.localPort, slot.handle.traffic) else TunnelStatus.Failed(slot.error ?: "Couldn't start the tunnel."))
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

    /**
     * Disconnects and keeps the frame, with a `detached` marker under the last line; [reason],
     * when the session detached itself rather than by the user's hand, follows the word
     * (`detached after 15 min idle`), so the frame says why on the relaunch too.
     */
    fun detach(reason: String? = null) {
        connectJob?.cancel()
        connectJob = null
        teardownConnection()
        marker(if (reason == null) "detached" else "detached $reason")
        transition(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME)
    }

    override fun close() {
        connectJob?.cancel()
        connectJob = null
        watchers.cancel()
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
        // What the last attempt in this window threw, for the report written when the window closes.
        var lastError: Throwable? = null
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
                    BerthLog.i(LOG_TAG, "[${host.name}] session ended: the shell exited")
                    transition(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME)
                    return
                }
                is Outcome.Dropped -> {
                    // A live session fell over: start a fresh backoff window.
                    attempt = 0
                    since = env.now()
                    isReconnect = true
                    lastError = outcome.cause
                    marker("connection lost: ${outcome.reason}")
                    BerthLog.w(LOG_TAG, "[${host.name}] connection lost: ${outcome.reason}", outcome.cause)
                    env.onTransportFailure(host, "Connection lost", outcome.cause, outcome.reason)
                    _drops.tryEmit(outcome.reason)
                }
                is Outcome.Failed -> {
                    val e = outcome.error
                    if (!e.isTransientSshFailure()) {
                        fail(e)
                        return
                    }
                    lastError = e
                    isReconnect = everLive
                    BerthLog.w(LOG_TAG, "[${host.name}] attempt ${attempt + 1} failed, will retry: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            teardownConnection()
            if (!ReconnectBackoff.shouldRetry(env.now() - since, host.persistence.effectiveReconnectMinutes(env.connectionDefaults()))) {
                marker("gave up reconnecting")
                transition(SessionState.DETACHED, PersistenceLayer.LOCAL_FRAME)
                val waited = env.now() - since
                BerthLog.w(LOG_TAG, "[${host.name}] gave up reconnecting after ${waited / 1_000} s and $attempt retries", lastError)
                env.onTransportFailure(host, "Gave up reconnecting", lastError, "after ${waited / 1_000} s and $attempt ${if (attempt == 1) "retry" else "retries"}")
                // Losing the server is news like a bell is (vision §4.5): off stage the ring and the count tile carry it.
                val problem = SessionProblem.GaveUp(waited)
                attention("Couldn't reconnect", problem)
                _problems.tryEmit(problem)
                return
            }
            transition(SessionState.RECONNECTING, PersistenceLayer.IN_APP)
            waitBeforeRetry(ReconnectBackoff.delaySeconds(attempt++))
        }
    }

    private sealed interface Outcome {
        data object Ended : Outcome

        /** A live connection fell over; [cause] is what the transport threw, when it threw rather than merely closed. */
        data class Dropped(val reason: String, val cause: Throwable?) : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    private suspend fun runOnce(isReconnect: Boolean): Outcome {
        val h = host
        // Hops first, in the order they are made, so their prompts come in that order too.
        val chain = env.jumpHostsFor(h)
        chainHosts = chain
        val defaults = env.connectionDefaults()
        val hops = chain.mapIndexed { index, hop -> SshHop(sshEndpointFor(hop, env.authFor(hop), defaults), env.hostKeyPolicyFor(hop, HopRole(index, chain.size, h), null)) }
        val endpoint = sshEndpointFor(h, env.authFor(h), defaults)
        val conn = SshConnection(endpoint, env.hostKeyPolicyFor(h, null, linkFingerprint), hops)
        connection = conn
        val progress = scope.launch {
            conn.state.collect { s ->
                _via.value = (s as? SshConnectionState.ConnectingVia)?.let { "via ${hopName(it.hop, it.host)}" + if (it.hopCount > 1) " (${it.hop + 1} of ${it.hopCount})" else "" }
            }
        }
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { conn.connect() }
        } finally {
            progress.cancel()
            _via.value = null
        }
        if (tunnelsOnly) return carryOnly(conn, isReconnect)

        val command = buildString {
            when (h.persistence.tmux) {
                TmuxMode.OFF -> Unit
                TmuxMode.ATTACH_OR_CREATE -> append("tmux new-session -A -s '${tmuxName(h)}'")
                TmuxMode.ATTACH_ONLY -> append("tmux attach-session -t '${tmuxName(h)}'")
            }
            if (h.persistence.tmux == TmuxMode.OFF && !h.startupCommand.isNullOrBlank()) append(h.startupCommand)
        }.ifBlank { null }

        // The agent lives as long as this connection; a Tunnels tab has returned above and has none.
        val agent = if (h.agentForwarding) SshAgent(conn.agentKey, agentApprover(h)) else null
        val sh = conn.openShell(cols, rows, h.terminalType, h.environment + mapOf("COLORTERM" to "truecolor", "TERM_PROGRAM" to "berth"), command, agent = agent)
        shell = sh
        val firstShell = !everLive
        everLive = true
        if (isReconnect) marker("reconnected")
        if (agent != null) {
            BerthLog.i(LOG_TAG, "[${h.name}] agent forwarding ${if (sh.agentForwarded) "on" else "refused by the server"}; the agent holds ${agent.key?.keyType ?: "no key"}")
            if (!sh.agentForwarded) marker("agent forwarding refused by the server")
        }
        // A fresh shell is activity: an idle span counts from here, not from before the connect.
        noteActivity()
        transition(SessionState.LIVE, if (h.persistence.tmux != TmuxMode.OFF) PersistenceLayer.TMUX else PersistenceLayer.IN_APP)
        patch { copy(lastLiveAt = env.now(), needsAttention = false, attentionReason = null) }
        if (firstShell) runOnConnect(sh, h)

        val dropped = MutableStateFlow<SshError.Disconnected?>(null)
        conn.onDisconnected = { dropped.value = it }

        try {
            withContext(Dispatchers.IO) {
                sh.output().collect { chunk ->
                    noteOutput()
                    emulator.write(chunk)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val disconnect = dropped.value
            return Outcome.Dropped(disconnect?.message ?: e.message ?: "connection lost", disconnect ?: e)
        }
        // EOF: either the remote shell exited or the transport died underneath it.
        val disconnect = dropped.value
        return if (disconnect != null || !conn.isConnected) Outcome.Dropped(disconnect?.message ?: "connection lost", disconnect) else Outcome.Ended
    }

    /**
     * Decides the sign requests of one connection's agent, one at a time, so a second request waits
     * for the answer to the first and Allow for this session answers it too; that answer lasts as
     * long as the connection, and a reconnect asks again. While the question is up the tab is lit
     * off stage (the ring, and the shade while the app is away), and once answered it goes dark, or
     * back to what lit it before (a bell the user has not seen yet). A request the remote gives up
     * on, its channel closing under it, is withdrawn the same way, sheet and all.
     */
    private fun agentApprover(h: Host): AgentApprover {
        val turn = Mutex()
        var allowedForSession = false
        return AgentApprover { request ->
            turn.withLock {
                if (allowedForSession || env.agentSignsSilently(h)) return@withLock true
                val what = when (request.purpose) {
                    is AgentSignPurpose.Login -> "a login"
                    is AgentSignPurpose.SshSig -> "an SSHSIG"
                    is AgentSignPurpose.Unknown -> "unread data"
                }
                val earlier = attentionOver(AGENT_REQUEST_REASON)
                val answer = try {
                    env.approveAgentRequest(h, request)
                } catch (e: CancellationException) {
                    BerthLog.i(LOG_TAG, "[${h.name}] agent sign request for $what: withdrawn")
                    throw e
                } finally {
                    settle(AGENT_REQUEST_REASON, earlier)
                }
                BerthLog.i(LOG_TAG, "[${h.name}] agent sign request for $what: ${answer.name.lowercase()}")
                if (answer == AgentAnswer.ALLOW_FOR_SESSION) allowedForSession = true
                answer != AgentAnswer.DENY
            }
        }
    }

    /**
     * A tunnels-only login: Live with no channel of its own, so the forwards ([reconcileTunnels])
     * start on the Live transition, and it ends when the transport does. The connection's own
     * state says when that is, which no listener installed after the connect can miss.
     */
    private suspend fun carryOnly(conn: SshConnection, isReconnect: Boolean): Outcome {
        everLive = true
        if (isReconnect) marker("reconnected")
        transition(SessionState.LIVE, PersistenceLayer.IN_APP)
        patch { copy(lastLiveAt = env.now(), needsAttention = false, attentionReason = null) }
        val end = conn.state.first { it is SshConnectionState.Disconnected } as SshConnectionState.Disconnected
        return Outcome.Dropped(end.reason.ifBlank { "connection lost" }, end.error)
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
        val plain = plainFailure(e)
        val hop = (e as? SshError.JumpHopFailed)?.let { j -> chainHosts.getOrNull(j.hop)?.let { FailedHop(it.id, it.name) } }
        _failure.value = SessionFailure(plain, e.message ?: e.javaClass.simpleName, hop)
        teardownConnection()
        transition(SessionState.FAILED, PersistenceLayer.LOCAL_FRAME)
        BerthLog.w(LOG_TAG, "[${host.name}] couldn't connect: $plain", e)
        env.onTransportFailure(host, "Couldn't connect", e, plain)
        val problem = SessionProblem.Failed(plain, authentication = e.rootSshError() is SshError.AuthenticationFailed)
        attention(plain, problem)
        _problems.tryEmit(problem)
    }

    /**
     * The failure in plain language. A hop's failure leads with the hop's name and puts its role
     * in parentheses, as the pill does (`old bastion (jump host 1 of 2) did not accept the
     * credentials for ops.`), so a name with a space in it still reads as the name, and the user
     * fixes that host rather than the target.
     */
    private fun plainFailure(e: Throwable): String = when (e) {
        is SshError.JumpHopFailed -> {
            val hop = "${hopName(e.hop, e.host)} (jump host${if (e.hopCount > 1) " ${e.hop + 1} of ${e.hopCount}" else ""})"
            when (val reason = e.reason) {
                is SshError.AuthenticationFailed -> "$hop did not accept the credentials for ${e.user}."
                is SshError.HostKeyRejected -> "$hop presented a host key that was not trusted, so the connection stopped there."
                is SshError.ConnectFailed -> "$hop couldn't be reached."
                is SshError.NoCommonCipher -> "$hop ${noCommonCipher(chainHosts.getOrNull(e.hop))}"
                else -> "$hop failed: ${reason.message ?: "couldn't connect"}."
            }
        }
        is SshError.AuthenticationFailed -> "The server did not accept the credentials for ${host.user}."
        is SshError.HostKeyRejected -> "The host key was not trusted, so the connection was not made."
        is SshError.NoCommonCipher -> "The server ${noCommonCipher(host)}"
        is IllegalStateException -> e.message ?: "Couldn't connect."
        else -> "Couldn't connect to ${host.address}:${host.port}."
    }

    /** The rest of a no-common-cipher sentence for [h], naming the host editor's row when the list offered was the host's own. */
    private fun noCommonCipher(h: Host?): String =
        if (h?.ciphers.isNullOrEmpty()) "accepts none of the ciphers Berth offers."
        else "accepts none of the ciphers set under Advanced \u203A Ciphers."

    private fun Throwable.rootSshError(): Throwable = if (this is SshError.JumpHopFailed) reason else this

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

    /**
     * Writes to the shell one at a time, in the order they were sent: two sends in a row (a
     * command and its Enter, two keys typed fast) must land in that order, which two launches on
     * the IO pool did not promise.
     */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Handed every write [send] is given, before the shell is; how a test reads what a gesture or a key sent. */
    internal var sendObserver: ((ByteArray) -> Unit)? = null

    fun send(bytes: ByteArray) {
        sendObserver?.invoke(bytes)
        val sh = shell
        if (sh == null) {
            // A detached tab does not eat what is typed into it: the first key reconnects, as the pill
            // would, and the Stage is told the key itself went nowhere. Later keys, while the connection
            // is being made, are dropped the way they always were: there is no shell to hold them for.
            // The state leaves Detached on the loop's own thread, so a burst that lands before it does
            // (a drag's arrows, a paste) is told apart by the job the first key started.
            if (state == SessionState.DETACHED && connectJob?.isActive != true) {
                reconnectNow()
                _keyReconnected.tryEmit(Unit)
            }
            return
        }
        scope.launch(writer) { runCatching { sh.write(bytes) } }
    }

    fun sendText(text: String, modifiers: Int = 0) {
        noteActivity()
        trackTyped(text, modifiers)
        if (modifiers == 0) {
            send(text.toByteArray(Charsets.UTF_8))
            return
        }
        val altSendsMeta = modifiers and Mod.ALT != 0 && env.altKeyFor(_record.value.hostId) == AltKeyMode.META
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.write(emulator.encodeText(cp, modifiers, altSendsMeta))
            i += Character.charCount(cp)
        }
        send(out.toByteArray())
    }

    fun sendKey(key: TerminalKey, modifiers: Int = 0) {
        noteActivity()
        trackKey(key, modifiers)
        send(emulator.encodeKey(key, modifiers))
    }

    fun paste(text: String) {
        noteActivity()
        trackPaste(text)
        send(emulator.encodePaste(text))
    }

    fun sendControl(char: Char) {
        noteActivity()
        trackTyped(char.toString(), Mod.CTRL)
        send(emulator.encodeText(char.code, Mod.CTRL))
    }

    // ---- the network under the socket, and idleness (vision §4.4) --------------------------------

    /**
     * When the user last typed into this session or the server last sent it anything, whichever
     * is later; what an idle span is measured from. A fresh shell counts too, so a reconnect is not
     * idle from before it.
     */
    @Volatile private var lastActivityAt = env.now()

    /** When the server last sent anything; what a probe weighs its silence against. */
    @Volatile private var lastOutputAt = 0L

    private fun noteActivity() {
        lastActivityAt = env.now()
    }

    private fun noteOutput() {
        val now = env.now()
        lastOutputAt = now
        lastActivityAt = now
    }

    /** A probe in flight, so the burst of events a handoff fires asks the server once. */
    private val probing = AtomicBoolean(false)

    /**
     * The network changed under the app (vision §4.4, A23). A live connection asks its server for
     * a reply now ([SshConnection.probe]) rather than wait for the keepalive timer to miss enough
     * times. The server's answer within [PROBE_TIMEOUT_MS] settles it; so does any output that
     * arrived while the probe waited, since data came over the socket (a reply queued behind a
     * window of output on a slow link is late, not lost, and a shell without tmux must not be
     * dropped for it). Nothing at all, and the transport is dropped as lost, which the reader
     * turns into the reconnect loop, whose first attempt then starts at once instead of a minute
     * or more on. A session that is not Live has nothing to ask.
     */
    private fun probe() {
        if (state != SessionState.LIVE) return
        val conn = connection ?: return
        if (!probing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val askedAt = env.now()
                val answered = conn.probe(PROBE_TIMEOUT_MS)
                val alive = answered || lastOutputAt >= askedAt
                when {
                    answered -> BerthLog.i(LOG_TAG, "[${host.name}] probe after a network change: the server answered")
                    alive -> BerthLog.i(LOG_TAG, "[${host.name}] probe after a network change: no reply, but output arrived meanwhile")
                    else -> {
                        BerthLog.i(LOG_TAG, "[${host.name}] probe after a network change: nothing for ${PROBE_TIMEOUT_MS} ms, the connection is lost")
                        conn.dropAsLost(PROBE_LOST_REASON)
                    }
                }
            } finally {
                probing.set(false)
            }
        }
    }

    /**
     * Whether the idle policy leaves this session alone for now: while it carries the host's
     * tunnels (the forwards are its work), and while it is on stage in front of the user, who may
     * be reading a screen that is not moving. A Tunnels tab is never idle; it does not watch.
     */
    private val idleExempt: Boolean
        get() = onStage || (carriesTunnels.value && _tunnels.value.isNotEmpty())

    /**
     * Detaches the session once nothing has been typed into it and nothing has arrived from it for
     * the policy's span (spec C20 Connection), keeping the frame with a marker that says why. Each
     * change of policy starts the watch over; Never watches nothing. The wait is until the
     * earliest moment the span can be up and the check runs again then, so a session in use is
     * never woken for and one left alone costs one timer; an exempt one is looked at again later.
     */
    private suspend fun watchIdle() {
        if (tunnelsOnly) return
        env.idleDetachAfter.collectLatest { limit ->
            if (limit == null) return@collectLatest
            while (true) {
                _record.first { it.state == SessionState.LIVE }
                val remaining = limit - (env.now() - lastActivityAt)
                when {
                    remaining > 0 -> delay(remaining)
                    idleExempt -> delay(limit.coerceAtMost(IDLE_RECHECK_MS))
                    state == SessionState.LIVE -> {
                        val span = idleSpan(limit)
                        BerthLog.i(LOG_TAG, "[${host.name}] idle for $span; detaching (Settings \u203A Connection)")
                        detach("after $span idle")
                    }
                }
            }
        }
    }

    /**
     * The history cap the environment last gave ([SessionEnvironment.scrollbackLinesFor]), null
     * until it has; [capLock] orders setting it against [restoreFrame] replaying a frame.
     */
    private var historyCap: Int? = null
    private val capLock = Any()

    /** The probe's, the idle watch's and the history cap's collectors, for the life of the tab; [close] ends them. */
    private val watchers: Job = scope.launch {
        launch { env.networkChanges.collect { probe() } }
        launch { watchIdle() }
        launch {
            env.scrollbackLinesFor(host).collect { lines ->
                synchronized(capLock) {
                    historyCap = lines
                    emulator.maxScrollback = lines
                }
            }
        }
    }

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

    override fun markSeen() {
        synchronized(attentionLock) {
            attentionAt = null
            attentionProblem = null
            if (_record.value.needsAttention) patch { copy(needsAttention = false, attentionReason = null) }
        }
    }

    /**
     * Refreshes `lastLiveAt` while Live. The manager calls it as it saves frames in the background,
     * so a tab the OS kills mid-session comes back dated to its last save rather than to the moment
     * it connected, and "Detached · 4 min ago" stays honest.
     */
    fun markLive() {
        if (state == SessionState.LIVE) patch { copy(lastLiveAt = env.now()) }
    }

    /** Sets the tab's custom title; blank restores the automatic one (spec C3, Rename). */
    override fun rename(title: String?) = patch { copy(customTitle = title?.trim()?.takeIf { it.isNotEmpty() }) }

    /**
     * Applies a placement (group and position) to the record without persisting it. The manager
     * writes whole batches in one transaction after a reorder, so this stays a memory-only step.
     */
    override fun place(workspaceId: String, sortOrder: Int): SessionRecord =
        _record.updateAndGet { if (it.workspaceId == workspaceId && it.sortOrder == sortOrder) it else it.copy(workspaceId = workspaceId, sortOrder = sortOrder) }

    /**
     * Raises attention for [reason], with the [problem] behind it when a lost connection rather
     * than output is the cause, unless the tab is on stage, where the user sees it happen. The
     * check and the raise are one step under [attentionLock], against the manager moving the stage.
     */
    private fun attention(reason: String, problem: SessionProblem? = null) {
        synchronized(attentionLock) {
            if (onStage) return
            attentionAt = env.now()
            attentionProblem = problem
            patch { copy(needsAttention = true, attentionReason = reason) }
        }
    }

    /** What a tab was lit for: the reason, when, and the problem behind it, as [attentionOver] hands it to [settle] to put back. */
    private class Lit(val reason: String?, val at: Long?, val problem: SessionProblem?)

    /**
     * Raises attention for [reason] as [attention] does, over whatever the tab was already lit for,
     * and hands that back, read in the same step, so [settle] can put it back once [reason] is over.
     */
    private fun attentionOver(reason: String): Lit? = synchronized(attentionLock) {
        val before = _record.value.takeIf { it.needsAttention }?.let { Lit(it.attentionReason, attentionAt, attentionProblem) }
        attention(reason)
        before
    }

    /**
     * Takes down the attention [reason] raised once what it was about is over, unless something else
     * has lit the tab since or the user has seen it. What the tab was lit for before [reason]
     * ([earlier]) comes back as it was, since nobody has seen it yet.
     */
    private fun settle(reason: String, earlier: Lit? = null) {
        synchronized(attentionLock) {
            if (!_record.value.needsAttention || _record.value.attentionReason != reason) return
            attentionAt = earlier?.at
            attentionProblem = earlier?.problem
            patch { copy(needsAttention = earlier != null, attentionReason = earlier?.reason) }
        }
    }

    private fun transition(state: SessionState, layer: PersistenceLayer) {
        val before = _record.value.state
        patch { copy(state = state, layer = layer) }
        if (before != state) BerthLog.i(LOG_TAG, "[${host.name}] ${before.name.lowercase()} \u2192 ${state.name.lowercase()}")
    }

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

    /** Writes a dim marker row into the terminal, e.g. `reconnected 14:07`, stamped [at] (now by default). */
    private fun marker(text: String, at: Long = env.now()) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        val line = "\r\n\u001b[0;2m\u2500\u2500 $text $time \u2500\u2500\u001b[0m\r\n"
        emulator.write(line)
    }

    // ---- frames --------------------------------------------------------------------------------

    /**
     * Scrollback plus screen as text, enough to bring a detached session's frame back after a
     * restart. Version 2 carried the session's command history behind the text; since version 3
     * the history is the host's and lives in its own table (spec C16), so the frame is text again.
     */
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

    /**
     * Replays a saved frame into the emulator, dimmed: this version's or either before it. A
     * version 2 frame ends in the commands the tab had run when history was per tab; they are
     * handed to the host's history ([SessionEnvironment.importCommands]), once, and the next save
     * writes the frame without them. [detachedAt] is set for a tab that was connected when the
     * process died: the frame then ends in a `detached 14:07` marker stamped with the last moment
     * it was known to be live, the same word as the pill above it, Detach all and tmux, so the
     * relaunch reads as a session that was cut rather than one that vanished (vision §4.3, L0).
     * Reconnect opens a fresh shell, so the marker promises nothing more. The frame is replayed
     * under the widest cap and then held to the host's, so history saved under a cap above the
     * emulator's default comes back whole whether or not the cap has arrived yet.
     */
    fun restoreFrame(frame: ByteArray?, detachedAt: Long? = null) {
        if (frame != null) synchronized(capLock) {
            emulator.maxScrollback = TerminalSettings.MAX_SCROLLBACK
            replayFrame(frame)
            historyCap?.let { emulator.maxScrollback = it }
        }
        if (detachedAt != null) marker("detached", detachedAt)
    }

    private fun replayFrame(frame: ByteArray) {
        runCatching {
            DataInputStream(frame.inputStream()).use { d ->
                val version = d.readInt()
                if (version !in FRAME_VERSION_TEXT_ONLY..FRAME_VERSION) return@runCatching
                val n = d.readInt()
                val text = StringBuilder()
                repeat(n) { text.append(d.readUTF()).append("\r\n") }
                emulator.write("\u001b[2m")
                emulator.write(text.toString())
                emulator.write("\u001b[0m")
                if (version == FRAME_VERSION_WITH_HISTORY) {
                    val m = d.readInt()
                    val saved = ArrayList<Pair<String, Long>>(m)
                    repeat(m) { saved += d.readUTF() to d.readLong() }
                    if (saved.isNotEmpty()) env.importCommands(commandHistoryKey, saved)
                }
            }
        }
    }

    companion object {
        /** How long an OSC 133 command must have run before its finishing off stage counts as attention (vision §4.5). */
        const val ATTENTION_COMMAND_MS = 10_000L
        private const val LOG_TAG = "Session"
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val RUN_ON_CONNECT_GRACE_MS = 400L
        private const val BIND_RETRIES = 2
        private const val BIND_RETRY_DELAY_MS = 250L

        /**
         * How long a probe waits for the server after a network change before the connection is
         * held lost (vision §4.4: a handoff found in under two seconds). A live server answers in
         * one round trip, well inside this on any link the shell itself is usable over; output
         * arriving meanwhile counts as an answer, so a link busy with it is not mistaken.
         */
        const val PROBE_TIMEOUT_MS = 2_000L

        /** What the ring stands for while a program on the host waits for the user to answer its sign request. */
        const val AGENT_REQUEST_REASON = "Signature request"

        /** What the `connection lost` marker says after a probe found nobody: the network moved, and the socket did not follow. */
        const val PROBE_LOST_REASON = "the network changed and the server did not answer"

        /** How long an exempt idle session (carrying tunnels, on stage) waits before the watch looks again. */
        private const val IDLE_RECHECK_MS = 60_000L

        /** The idle span as the marker and the log say it: `15 min`, `1 h`, `4 h`, or seconds for anything else. */
        fun idleSpan(millis: Long): String = when {
            millis >= 3_600_000L && millis % 3_600_000L == 0L -> "${millis / 3_600_000L} h"
            millis >= 60_000L && millis % 60_000L == 0L -> "${millis / 60_000L} min"
            else -> "${millis / 1_000L} s"
        }

        private const val FRAME_VERSION = 3
        private const val FRAME_VERSION_WITH_HISTORY = 2
        private const val FRAME_VERSION_TEXT_ONLY = 1
        private const val MAX_FRAME_LINE = 4096

        /** How long a typed command's echo may take to land before it is checked against the screen. */
        private const val ECHO_GRACE_MS = 200L
        private val LINE_BREAKS = charArrayOf('\r', '\n')
    }
}
