package app.berth.android.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SessionRepository
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.WorkspaceRepository
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every runtime session across workspaces, keeps the persisted records in step, restores
 * detached frames after process death, and runs the foreground service while anything is active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val hostRepository: HostRepository,
    private val knownHosts: KnownHostRepository,
    private val settings: SettingsRepository,
    private val authResolver: AuthResolver,
    private val prompts: PromptCenter,
    private val network: NetworkMonitor,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())

    /** All sessions in rail order. */
    val sessions: StateFlow<List<TerminalSession>> = combine(_sessions, sessionRepository.observeAll()) { live, records ->
        records.mapNotNull { live[it.id] }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val records: Flow<List<SessionRecord>> = sessions.flatMapLatest { list ->
        if (list.isEmpty()) MutableStateFlow(emptyList()) else combine(list.map { it.record }) { it.toList() }
    }

    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _currentWorkspaceId = MutableStateFlow<String?>(null)
    val currentWorkspaceId: StateFlow<String?> = _currentWorkspaceId.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    val activeSession: StateFlow<TerminalSession?> = combine(_activeSessionId, _sessions) { id, map -> id?.let { map[it] } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val restored = Mutex()
    private var didRestore = false

    private val environment = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = authResolver.resolve(host)
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = KnownHostsPolicy(host, knownHosts, prompts)
        override val networkAvailable: Flow<Unit> = network.available
        override fun onClipboardText(text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
    }

    init {
        scope.launch { restore() }
        scope.launch {
            records.map { list -> list.count { it.state.keepsService } }.distinctUntilChanged().collect { active ->
                if (active > 0) SessionService.start(context, active) else SessionService.stop(context)
            }
        }
        scope.launch {
            // Keep the stage flag on the session that is showing so attention is raised correctly.
            _activeSessionId.collect { id -> _sessions.value.values.forEach { it.onStage = it.id == id } }
        }
    }

    /** Loads persisted sessions as detached frames. Safe to call more than once. */
    suspend fun restore() = restored.withLock {
        if (didRestore) return@withLock
        didRestore = true
        val defaultWorkspace = workspaceRepository.ensureDefault()
        _currentWorkspaceId.value = settings.currentWorkspaceId.first()?.takeIf { id -> workspaceRepository.get(id) != null } ?: defaultWorkspace.id
        val map = LinkedHashMap<String, TerminalSession>()
        for (record in sessionRepository.getAll()) {
            if (record.state == SessionState.CLOSED) {
                sessionRepository.delete(record.id)
                continue
            }
            val detached = record.copy(state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME, needsAttention = false, attentionReason = null)
            sessionRepository.upsert(detached)
            val session = TerminalSession(detached, scope, environment) { sessionRepository.upsert(it) }
            sessionRepository.loadFrame(record.id)?.let { session.restoreFrame(it) }
            map[record.id] = session
        }
        _sessions.value = map
        val last = settings.lastActiveSessionId.first()
        _activeSessionId.value = last?.takeIf { map.containsKey(it) }
        val reconnectWorkspaces = workspaces.first().filter { it.reconnectAtLaunch }.map { it.id }.toSet()
        map.values.filter { it.record.value.workspaceId in reconnectWorkspaces }.forEach { it.connect() }
    }

    /** Opens a new session for [host] in [workspaceId] (or the current workspace) and puts it on stage. */
    suspend fun open(host: Host, workspaceId: String? = null): TerminalSession {
        restore()
        val ws = workspaceId ?: _currentWorkspaceId.value ?: workspaceRepository.ensureDefault().id
        val order = (sessions.value.maxOfOrNull { it.record.value.sortOrder } ?: -1) + 1
        val record = SessionRecord(
            id = UUID.randomUUID().toString(),
            workspaceId = ws,
            hostId = host.id,
            hostSnapshot = host,
            state = SessionState.IDLE,
            layer = PersistenceLayer.IN_APP,
            title = host.name,
            sortOrder = order,
            createdAt = System.currentTimeMillis(),
        )
        sessionRepository.upsert(record)
        val session = TerminalSession(record, scope, environment) { sessionRepository.upsert(it) }
        _sessions.update { it + (record.id to session) }
        hostRepository.markConnected(host.id, record.createdAt)
        setActive(record.id)
        session.connect()
        return session
    }

    fun get(id: String): TerminalSession? = _sessions.value[id]

    fun setActive(id: String?) {
        _activeSessionId.value = id
        id?.let { _sessions.value[it]?.markSeen() }
        scope.launch { settings.setLastActiveSessionId(id) }
    }

    fun setCurrentWorkspace(id: String) {
        _currentWorkspaceId.value = id
        scope.launch { settings.setCurrentWorkspaceId(id) }
    }

    suspend fun createWorkspace(name: String): Workspace {
        val existing = workspaces.value
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            name = name,
            color = SwatchColor.forName(name),
            monogram = Host.monogramFor(name),
            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = System.currentTimeMillis(),
        )
        workspaceRepository.upsert(workspace)
        return workspace
    }

    fun reconnect(id: String) = _sessions.value[id]?.reconnectNow()

    fun detach(id: String) {
        val session = _sessions.value[id] ?: return
        session.detach()
        scope.launch { sessionRepository.saveFrame(id, session.snapshotFrame()) }
    }

    fun close(id: String) {
        val session = _sessions.value[id] ?: return
        session.close()
        _sessions.update { it - id }
        if (_activeSessionId.value == id) {
            val next = sessions.value.firstOrNull { it.id != id && it.record.value.workspaceId == session.record.value.workspaceId }
            setActive(next?.id)
        }
        scope.launch { sessionRepository.delete(id) }
    }

    /** Persists frames for every session; called when the app goes to the background. */
    fun saveAllFrames() {
        val snapshot = _sessions.value.values.toList()
        scope.launch {
            for (s in snapshot) if (s.state != SessionState.CLOSED) sessionRepository.saveFrame(s.id, s.snapshotFrame())
        }
    }

    fun detachAll() = _sessions.value.keys.toList().forEach { detach(it) }

    companion object {
        fun openAppIntent(context: Context): Intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    }
}
