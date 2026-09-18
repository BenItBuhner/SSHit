package app.berth.android.session

import app.berth.domain.model.AddressFamily
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.ReconnectBackoff
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.TmuxMode
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
        everLive = true
        if (isReconnect) marker("reconnected")
        transition(SessionState.LIVE, if (h.persistence.tmux != TmuxMode.OFF) PersistenceLayer.TMUX else PersistenceLayer.IN_APP)
        patch { copy(lastLiveAt = env.now(), needsAttention = false, attentionReason = null) }

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
        shell?.let { runCatching { it.close() } }
        shell = null
        connection?.let { c -> c.onDisconnected = null; runCatching { c.close() } }
        connection = null
    }

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
        private const val FRAME_VERSION = 1
        private const val MAX_FRAME_LINE = 4096
    }
}
