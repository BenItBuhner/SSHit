package app.berth.android.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.berth.android.di.ProcessLifecycle
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TabOrder
import app.berth.domain.model.Tunnel
import app.berth.domain.model.Workspace
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SessionRepository
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What [SessionManager.close] hands back so a snackbar can offer Reopen (spec C3, Closing). */
data class ClosedTab(val record: SessionRecord) {
    /** Only a tab that was connected earns the snackbar; detached tabs close quietly. */
    val wasActive: Boolean get() = record.state.isActive
}

/**
 * Owns every tab across groups, keeps the persisted records in step, restores detached frames
 * after process death, and runs the foreground service while a terminal is connected. Tabs come
 * in kinds (spec C3): a [TerminalSession] runs one SSH login, a [FilesTab] browses a host's files
 * over one of its terminals' logins. This class owns their order, their groups and which one is
 * active, and writes each change so the strip comes back the same after process death.
 *
 * It also watches the process: frames are saved when the app leaves the screen, when the OS asks
 * for memory and on a cadence while live sessions run in the background, so a tab the OS kills
 * comes back as a session that paused (vision §4.3, L0); and while the app is away, a tab that
 * needs the user or loses its server says so through [SessionNotifier] (spec C21).
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
    private val tunnelRepository: TunnelRepository,
    private val snippetRepository: SnippetRepository,
    val notifier: SessionNotifier,
    @ProcessLifecycle private val processLifecycle: Lifecycle,
) : SessionCommands {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())
    private val _filesTabs = MutableStateFlow<Map<String, FilesTab>>(emptyMap())

    /** Every tab by id, whatever it runs. */
    private val tabsById: StateFlow<Map<String, ManagedTab>> = combine(_sessions, _filesTabs) { sessions, files ->
        buildMap<String, ManagedTab> {
            putAll(sessions)
            putAll(files)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every open tab's record, in strip order: groups in their order, then positions within each group. */
    val records: StateFlow<List<SessionRecord>> = combine(
        tabsById.flatMapLatest { map ->
            if (map.isEmpty()) MutableStateFlow(emptyList()) else combine(map.values.map { it.record }) { it.toList() }
        },
        workspaces,
    ) { list, groups -> TabOrder.strip(list, groups) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** All tabs in strip order, whatever they run. */
    val tabs: StateFlow<List<ManagedTab>> = combine(records, tabsById) { ordered, live ->
        ordered.mapNotNull { live[it.id] }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The terminal tabs in strip order. */
    val sessions: StateFlow<List<TerminalSession>> = combine(records, _sessions) { ordered, live ->
        ordered.mapNotNull { live[it.id] }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Status of every tunnel any session is carrying, by tunnel id. */
    val tunnelStatuses: StateFlow<Map<String, TunnelStatus>> = sessions.flatMapLatest { list ->
        if (list.isEmpty()) MutableStateFlow(emptyMap()) else combine(list.map { it.tunnels }) { maps -> maps.fold(emptyMap<String, TunnelStatus>()) { acc, m -> acc + m } }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Which host each tunnel-carrying session serves; sticky while that session stays Live. */
    private val carrierByHost = HashMap<String, String>()

    private val _currentWorkspaceId = MutableStateFlow<String?>(null)

    /** The current group: the active tab's group, or the group last chosen in the drawer when no tab is active. */
    val currentWorkspaceId: StateFlow<String?> = _currentWorkspaceId.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** The tab on stage, whatever it runs. */
    val activeTab: StateFlow<ManagedTab?> = combine(_activeTabId, tabsById) { id, map -> id?.let { map[it] } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The tab on stage when it is a terminal; null while a Files tab, or nothing, is showing. */
    val activeSession: StateFlow<TerminalSession?> = activeTab.map { it as? TerminalSession }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * File transfers in flight, set by the transfer queue. They ride a live session, so they never
     * hold the service up on their own; the count only shapes the notification.
     */
    val activeTransfers = MutableStateFlow(0)

    /** Of [activeTransfers], how many wait for an answer only the Files pane can give; the notification says so. */
    val waitingTransfers = MutableStateFlow(0)

    private val restoreLock = Mutex()
    private var didRestore = false
    private val _restored = MutableStateFlow(false)

    /** Whether the persisted tabs have been loaded; until then nothing is known about the strip, not even that it is empty. */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    private val _foreground = MutableStateFlow(false)

    /** Whether an activity is on screen ([Lifecycle.Event.ON_START] to [Lifecycle.Event.ON_STOP]); off screen, attention goes to the shade. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _stageRequests = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A tab a notification put on stage; the shell pops back to the Stage so it is actually seen. */
    val stageRequests: SharedFlow<String> = _stageRequests.asSharedFlow()

    /** A notification's tab asked for before the strip was restored; staged the moment it is. */
    private var pendingActivation: String? = null
    private val pendingLock = Any()

    /** Problems collectors, one per terminal tab, cancelled when the tab closes. */
    private val trackers = HashMap<String, Job>()

    /** Screen version each tab's frame was last saved at, so the background cadence skips quiet tabs. */
    private val savedVersions = HashMap<String, Long>()
    private var backgroundSaver: Job? = null
    private var firstLiveSeen = false

    /** True from ON_STOP to ON_START: the app is away and no tab is on stage. */
    @Volatile private var stageDark = false

    private val environment = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = authResolver.resolve(host)
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = KnownHostsPolicy(host, knownHosts, prompts)
        override val networkAvailable: Flow<Unit> = network.available
        override fun onClipboardText(text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun tunnelsFor(hostId: String): Flow<List<Tunnel>> = tunnelRepository.observeForHost(hostId)
        override suspend fun connectCommands(host: Host, workspaceId: String): List<String> =
            snippetRepository.observeAll().first()
                .filter { it.runOnConnect && it.hostId == host.id && (it.workspaceId == null || it.workspaceId == workspaceId) }
                .map { it.render() }
    }

    // Declared ahead of init, which registers them: Kotlin initialises properties in order.
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> onForeground()
            Lifecycle.Event.ON_STOP -> onBackground()
            else -> Unit
        }
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = this@SessionManager.onTrimMemory(level)
        override fun onLowMemory() = this@SessionManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    init {
        scope.launch { restore() }
        scope.launch {
            // Only a terminal holds a socket; a Files tab mirrors its ride's state and never keeps the
            // service up on its own. The notification says what every terminal tab is doing, with the
            // tunnels up, the transfers in flight and how many of those wait on the user; the service
            // itself only starts and stops with the count of tabs holding a socket.
            var lastActive = 0
            combine(records, tunnelStatuses, activeTransfers, waitingTransfers) { list, statuses, transfers, waiting ->
                SessionsSummary(
                    lines = list.filter { it.kind == TabKind.Ssh && it.state != SessionState.CLOSED }.map { SessionLine(it.displayTitle, it.state) },
                    tunnels = statuses.count { it.value is TunnelStatus.Up },
                    transfers = transfers,
                    waiting = waiting,
                )
            }.distinctUntilChanged().collect { summary ->
                notifier.updateSessions(summary)
                if (summary.active != lastActive) {
                    lastActive = summary.active
                    if (summary.active > 0) SessionService.start(context) else SessionService.stop(context)
                }
            }
        }
        scope.launch {
            // Keep the stage flag on the tab that is showing so attention is raised correctly; with the
            // app away nothing is showing, so the active tab's bells and finished commands count too.
            _activeTabId.collect { id -> tabsNow().forEach { it.onStage = !stageDark && it.id == id } }
        }
        scope.launch {
            records.collect {
                electCarriers(it)
                refollow()
            }
        }
        scope.launch {
            // Each terminal record against its last emission: frames on the way out of Live, the
            // first Live of the process, and attention lighting or clearing.
            val seen = HashMap<String, SessionRecord>()
            records.collect { list ->
                seen.keys.retainAll(list.mapTo(HashSet()) { it.id })
                for (record in list) {
                    val previous = seen.put(record.id, record)
                    if (record.kind == TabKind.Ssh) onRecordChanged(previous, record)
                }
            }
        }
        observeProcess()
    }

    // ---- process lifecycle -----------------------------------------------------------------------

    private fun observeProcess() {
        context.registerComponentCallbacks(memoryCallbacks)
        // ProcessLifecycleOwner's registry insists on the main thread; Hilt may build the manager elsewhere.
        if (Looper.myLooper() == Looper.getMainLooper()) processLifecycle.addObserver(lifecycleObserver)
        else Handler(Looper.getMainLooper()).post { processLifecycle.addObserver(lifecycleObserver) }
    }

    private fun onForeground() {
        _foreground.value = true
        stageDark = false
        // The active tab is in front of the user again: it is on stage, and whatever it raised while
        // the app was away has been seen (its notification goes with it, through the record).
        _activeTabId.value?.let { id -> tabNow(id)?.let { it.onStage = true; it.markSeen() } }
        backgroundSaver?.cancel()
        backgroundSaver = null
        // The user may have flipped notifications in system settings while away.
        notifier.refresh()
    }

    /**
     * The app left the screen: nothing is on stage any more, so the active tab's bells and long
     * commands count as attention like any other tab's (spec C21, Attention). Every frame is saved
     * now, then again every [BACKGROUND_SAVE_MS] for tabs whose screen moved while a session is
     * still connected, so however the process ends the relaunch shows what each tab last showed.
     */
    private fun onBackground() {
        _foreground.value = false
        stageDark = true
        tabsNow().forEach { it.onStage = false }
        saveAllFrames()
        backgroundSaver?.cancel()
        backgroundSaver = scope.launch {
            while (isActive) {
                delay(BACKGROUND_SAVE_MS)
                if (_sessions.value.values.any { it.state.isActive }) saveAllFrames(onlyChanged = true)
            }
        }
    }

    /**
     * [ComponentCallbacks2.onTrimMemory], registered on the application: the OS is about to
     * reclaim, so frames go to disk first, whatever the level. Public for tests and for any host
     * that wants to forward its own callback.
     */
    fun onTrimMemory(@Suppress("UNUSED_PARAMETER") level: Int) {
        saveAllFrames()
    }

    private fun onRecordChanged(previous: SessionRecord?, record: SessionRecord) {
        val session = _sessions.value[record.id] ?: return
        val before = previous?.state
        val now = record.state
        if (before != null && before != now && now != SessionState.CLOSED) {
            // Leaving Live (dropped, ended, detached) or giving up: keep what the screen showed.
            if (before == SessionState.LIVE || (before.isActive && !now.isActive)) saveFrame(session)
            // A tab connecting again has answered its problem notification itself.
            if (now.isActive && !before.isActive) notifier.cancelProblem(record.id)
        }
        if (now == SessionState.LIVE && before != SessionState.LIVE && !firstLiveSeen) {
            // The first successful connect of this process is the moment to ask for notifications (spec C1 note).
            firstLiveSeen = true
            notifier.onFirstLive()
        }
        val hadAttention = previous?.needsAttention == true
        if (record.needsAttention && !hadAttention) {
            // On screen the ring says it; away, the shade does (spec C21, Attention).
            if (!_foreground.value) notifier.postAttention(record, session.attentionAt ?: System.currentTimeMillis())
        } else if (!record.needsAttention && hadAttention) {
            notifier.cancelAttention(record.id)
        }
    }

    /** Problems from one tab, for as long as it is open: the shade hears about them unless the tab is on stage in the foreground. */
    private fun track(session: TerminalSession) {
        trackers.remove(session.id)?.cancel()
        trackers[session.id] = scope.launch {
            session.problems.collect { problem ->
                if (!_foreground.value || _activeTabId.value != session.id) notifier.postProblem(session.record.value, problem)
            }
        }
    }

    /**
     * One session per host carries its tunnels. The current carrier keeps the role while Live;
     * otherwise the first Live session takes it, or the first connecting one so the forwards start
     * the moment it comes up. Sessions that lose the role release their ports first.
     */
    private fun electCarriers(records: List<SessionRecord>) {
        val byHost = records.filter { it.kind == TabKind.Ssh && it.hostId != null && it.state.isActive }.groupBy { it.hostId!! }
        val chosen = HashMap<String, String>()
        for ((hostId, candidates) in byHost) {
            val current = carrierByHost[hostId]?.let { id -> candidates.firstOrNull { it.id == id && it.state == SessionState.LIVE } }
            chosen[hostId] = (current ?: candidates.firstOrNull { it.state == SessionState.LIVE } ?: candidates.first()).id
        }
        carrierByHost.clear()
        carrierByHost.putAll(chosen)
        val carriers = chosen.values.toSet()
        val live = _sessions.value.values
        live.filter { it.id !in carriers }.forEach { it.carriesTunnels.value = false }
        live.filter { it.id in carriers }.forEach { it.carriesTunnels.value = true }
    }

    /** Every Files tab re-elects its ride among its host's terminal tabs, in strip order. */
    private fun refollow() {
        val files = _filesTabs.value.values
        if (files.isEmpty()) return
        val live = _sessions.value
        val byHost = stripNow().mapNotNull { live[it.id] }.groupBy { it.host.id }
        for (tab in files) tab.follow(byHost[tab.host.id].orEmpty())
    }

    fun retryTunnel(id: String) = _sessions.value.values.forEach { it.retryTunnel(id) }

    /** Loads persisted tabs as detached frames, in their saved order. Safe to call more than once. */
    suspend fun restore() = restoreLock.withLock {
        if (didRestore) return@withLock
        didRestore = true
        val defaultWorkspace = workspaceRepository.ensureDefault()
        val groups = workspaceRepository.observeAll().first()
        val map = LinkedHashMap<String, TerminalSession>()
        val files = LinkedHashMap<String, FilesTab>()
        val restoredRecords = ArrayList<SessionRecord>()
        // Tabs that held a socket when the process died, with the last moment each was known to be
        // live: their frames end in a `paused` marker so the relaunch reads as a pause, not a loss.
        val pausedAt = HashMap<String, Long?>()
        for (record in sessionRepository.getAll()) {
            if (record.state == SessionState.CLOSED) {
                sessionRepository.delete(record.id)
                continue
            }
            if (record.state.isActive) pausedAt[record.id] = record.lastLiveAt
            restoredRecords += record.copy(
                state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME,
                needsAttention = false,
                attentionReason = null,
                workspaceId = record.workspaceId.takeIf { id -> groups.any { it.id == id } } ?: defaultWorkspace.id,
            )
        }
        // Positions written by earlier builds were global; renumber per group so the invariant holds from here on.
        val strip = TabOrder.strip(restoredRecords, groups)
        val renumbered = TabOrder.normalize(strip).associateBy { it.id }
        val finalRecords = strip.map { renumbered[it.id] ?: it }
        sessionRepository.upsertAll(finalRecords)
        for (record in finalRecords) {
            when (record.kind) {
                TabKind.Ssh -> {
                    val session = TerminalSession(record, scope, environment) { sessionRepository.upsert(it) }
                    session.restoreFrame(sessionRepository.loadFrame(record.id), pausedAt = pausedAt[record.id])
                    map[record.id] = session
                    track(session)
                }
                // A Files tab has no frame and nothing to reconnect; its browser reopens at the saved folder on first use.
                TabKind.Files -> files[record.id] = FilesTab(record, scope) { sessionRepository.upsert(it) }
            }
        }
        _sessions.value = map
        _filesTabs.value = files
        val persisted = settings.lastActiveSessionId.first()?.takeIf { map.containsKey(it) || files.containsKey(it) }
        val currentGroup = settings.currentWorkspaceId.first()?.takeIf { id -> groups.any { it.id == id } } ?: defaultWorkspace.id
        // The persisted active tab comes back on stage. A missing or stale id is a real state (the first
        // launch after the tabs migration, a tab closed from the notification while the app was dead, a
        // settings write that never landed), and the Stage must still open on a tab rather than on "No
        // tabs" over a strip that has them (spec C3, Launch and Persistence): the current group's first
        // tab, else the strip's first.
        val last = persisted ?: finalRecords.firstOrNull { it.workspaceId == currentGroup }?.id ?: finalRecords.firstOrNull()?.id
        _activeTabId.value = last
        _currentWorkspaceId.value = last?.let { tabNow(it)?.record?.value?.workspaceId } ?: currentGroup
        if (last != null && last != persisted) scope.launch { settings.setLastActiveSessionId(last) }
        val reconnectWorkspaces = groups.filter { it.reconnectAtLaunch }.map { it.id }.toSet()
        map.values.filter { it.record.value.workspaceId in reconnectWorkspaces }.forEach { it.connect() }
        val pending = synchronized(pendingLock) {
            _restored.value = true
            pendingActivation.also { pendingActivation = null }
        }
        pending?.let { stage(it) }
    }

    /**
     * A notification's tab (spec C21: every notification opens the tab it is about). Staged now,
     * or the moment the strip is restored when the tap is what started the process.
     */
    fun activateFromNotification(id: String) {
        val now = synchronized(pendingLock) {
            if (_restored.value) true else {
                pendingActivation = id
                false
            }
        }
        if (now) stage(id)
    }

    private fun stage(id: String) {
        if (tabNow(id) == null) return
        setActive(id)
        _stageRequests.tryEmit(id)
    }

    /** Every tab right now, from the maps rather than the (asynchronous) flows. */
    private fun tabsNow(): List<ManagedTab> {
        val all = ArrayList<ManagedTab>()
        all.addAll(_sessions.value.values)
        all.addAll(_filesTabs.value.values)
        return all
    }

    private fun tabNow(id: String): ManagedTab? = _sessions.value[id] ?: _filesTabs.value[id]

    /** The strip as it is right now, from the live records rather than the (asynchronous) flows. */
    private fun stripNow(): List<SessionRecord> = TabOrder.strip(tabsNow().map { it.record.value }, workspaces.value)

    /** Applies placements computed by [TabOrder] to the live tabs and writes them in one transaction. */
    private fun applyPlacements(changes: List<SessionRecord>) {
        if (changes.isEmpty()) return
        val updated = changes.mapNotNull { change -> tabNow(change.id)?.place(change.workspaceId, change.sortOrder) }
        if (updated.isNotEmpty()) scope.launch { sessionRepository.upsertAll(updated) }
    }

    /** An anchor in another group would drag the new tab into that group; an explicit group wins. */
    private fun anchorFor(afterId: String?, workspaceId: String?): String? =
        afterId?.takeIf { id -> tabNow(id)?.record?.value?.let { workspaceId == null || it.workspaceId == workspaceId } == true }

    /**
     * Opens a new terminal tab for [host] and, unless [activate] is off, puts it on stage. It lands
     * directly after [afterId] (the active tab by default) in that tab's group, or at the end of
     * [workspaceId] when there is no anchor.
     */
    suspend fun open(host: Host, workspaceId: String? = null, afterId: String? = _activeTabId.value, customTitle: String? = null, activate: Boolean = true): TerminalSession {
        restore()
        val group = workspaceId ?: _currentWorkspaceId.value ?: workspaceRepository.ensureDefault().id
        val fresh = SessionRecord(
            id = UUID.randomUUID().toString(),
            workspaceId = group,
            hostId = host.id,
            hostSnapshot = host,
            state = SessionState.IDLE,
            layer = PersistenceLayer.IN_APP,
            title = host.name,
            createdAt = System.currentTimeMillis(),
            customTitle = customTitle,
        )
        val changes = TabOrder.insertAfter(stripNow(), fresh, anchorFor(afterId, workspaceId), group)
        return start(fresh, changes, activate)
    }

    /** Recreates a closed tab in its old slot; a terminal connects again, a Files tab reopens at its folder (spec C3, Reopen). */
    suspend fun reopen(closed: ClosedTab): ManagedTab {
        restore()
        val old = closed.record
        val group = old.workspaceId.takeIf { id -> workspaces.value.any { it.id == id } } ?: workspaceRepository.ensureDefault().id
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        return when (old.kind) {
            TabKind.Files -> {
                val fresh = FilesTab.newRecord(id, old.hostSnapshot, group, now, folder = old.cwd).copy(title = old.title, customTitle = old.customTitle)
                placeFiles(fresh, TabOrder.insertAt(stripNow(), fresh, group, old.sortOrder), preferred = null)
            }
            TabKind.Ssh -> {
                val fresh = old.copy(
                    id = id,
                    workspaceId = group,
                    state = SessionState.IDLE,
                    layer = PersistenceLayer.IN_APP,
                    title = old.hostSnapshot.name,
                    cwd = null,
                    lastCommand = null,
                    needsAttention = false,
                    attentionReason = null,
                    createdAt = now,
                    lastLiveAt = null,
                    frameKey = null,
                )
                start(fresh, TabOrder.insertAt(stripNow(), fresh, group, old.sortOrder), activate = true)
            }
        }
    }

    /** Opens a second tab of the same kind on the same host directly after [id], in its group (spec C3, Duplicate). */
    suspend fun duplicate(id: String): ManagedTab? {
        val source = tabNow(id) ?: return null
        val record = source.record.value
        return when (record.kind) {
            TabKind.Ssh -> open(record.hostSnapshot, workspaceId = record.workspaceId, afterId = id)
            TabKind.Files -> startFiles(record.hostSnapshot, workspaceId = record.workspaceId, afterId = id, preferred = (source as? FilesTab)?.ride?.value?.id, folder = record.cwd)
        }
    }

    private suspend fun start(fresh: SessionRecord, changes: List<SessionRecord>, activate: Boolean): TerminalSession {
        val placed = changes.firstOrNull { it.id == fresh.id } ?: fresh
        val session = TerminalSession(placed, scope, environment) { sessionRepository.upsert(it) }
        _sessions.update { it + (placed.id to session) }
        track(session)
        val shifted = changes.filter { it.id != fresh.id }.mapNotNull { change -> tabNow(change.id)?.place(change.workspaceId, change.sortOrder) }
        sessionRepository.upsertAll(shifted + placed)
        placed.hostId?.let { hostRepository.markConnected(it, placed.createdAt) }
        if (activate) setActive(placed.id)
        session.connect()
        return session
    }

    // ---- files tabs --------------------------------------------------------------------------------

    /** The host's Files tab, first in strip order, when it has one. */
    fun filesTabFor(hostId: String): FilesTab? =
        stripNow().firstOrNull { it.kind == TabKind.Files && it.hostSnapshot.id == hostId }?.let { _filesTabs.value[it.id] }

    /**
     * Files from a terminal tab's menu or the Session sheet: the host's Files tab comes on stage
     * riding that terminal, opened directly after it (at the shell's directory) when the host has
     * no Files tab yet. Null when there is no such terminal.
     */
    suspend fun openFiles(sessionId: String): FilesTab? {
        restore()
        val session = _sessions.value[sessionId] ?: return null
        val existing = filesTabFor(session.host.id)
        if (existing != null) {
            existing.prefer(sessionId)
            refollow()
            setActive(existing.id)
            return existing
        }
        return startFiles(session.host, workspaceId = session.record.value.workspaceId, afterId = sessionId, preferred = sessionId, folder = session.cwd)
    }

    /**
     * Files from the host list or the New tab sheet: the host's Files tab, or a new one after the
     * active tab (at the end of [workspaceId] when given). The browser rides whichever terminal to
     * the host is up, or offers to connect one.
     */
    suspend fun openFilesForHost(host: Host, workspaceId: String? = null): FilesTab {
        restore()
        filesTabFor(host.id)?.let {
            setActive(it.id)
            return it
        }
        return startFiles(host, workspaceId = workspaceId, afterId = _activeTabId.value, preferred = null, folder = null)
    }

    private suspend fun startFiles(host: Host, workspaceId: String?, afterId: String?, preferred: String?, folder: String?): FilesTab {
        val group = workspaceId ?: _currentWorkspaceId.value ?: workspaceRepository.ensureDefault().id
        val fresh = FilesTab.newRecord(UUID.randomUUID().toString(), host, group, System.currentTimeMillis(), folder)
        return placeFiles(fresh, TabOrder.insertAfter(stripNow(), fresh, anchorFor(afterId, workspaceId), group), preferred)
    }

    private suspend fun placeFiles(fresh: SessionRecord, changes: List<SessionRecord>, preferred: String?): FilesTab {
        val placed = changes.firstOrNull { it.id == fresh.id } ?: fresh
        val tab = FilesTab(placed, scope) { sessionRepository.upsert(it) }
        tab.prefer(preferred)
        _filesTabs.update { it + (placed.id to tab) }
        val shifted = changes.filter { it.id != fresh.id }.mapNotNull { change -> tabNow(change.id)?.place(change.workspaceId, change.sortOrder) }
        sessionRepository.upsertAll(shifted + placed)
        refollow()
        setActive(placed.id)
        return tab
    }

    /**
     * Opens a terminal on a Files tab's host directly after it, off stage, so the browser has a
     * login to ride; the tab picks it up as it connects. The pane's Connect button.
     */
    fun connectFor(tab: FilesTab) {
        scope.launch { open(tab.host, workspaceId = tab.record.value.workspaceId, afterId = tab.id, activate = false) }
    }

    /**
     * Terminal from a Files tab's menu: the terminal it rides comes on stage, or, when the host has
     * none, a new one opens directly after the Files tab and the browser rides that. Null when [id]
     * is not a Files tab.
     */
    suspend fun openTerminalFor(id: String): TerminalSession? {
        val tab = _filesTabs.value[id] ?: return null
        tab.ride.value?.let {
            setActive(it.id)
            return it
        }
        val session = open(tab.host, workspaceId = tab.record.value.workspaceId, afterId = tab.id)
        tab.prefer(session.id)
        refollow()
        return session
    }

    fun get(id: String): TerminalSession? = _sessions.value[id]

    fun filesTab(id: String): FilesTab? = _filesTabs.value[id]

    fun tab(id: String): ManagedTab? = tabNow(id)

    /**
     * Puts [id] on stage; the current group follows the active tab. An id no tab answers to is
     * ignored rather than staged, so the active id always names an open tab (or nothing), whatever
     * a caller working from a frame-old strip asks for.
     */
    fun setActive(id: String?) {
        val tab = id?.let { tabNow(it) }
        if (id != null && tab == null) return
        _activeTabId.value = id
        tab?.markSeen()
        tab?.record?.value?.workspaceId?.let { group -> if (_currentWorkspaceId.value != group) setCurrentWorkspace(group, activate = false) }
        scope.launch { settings.setLastActiveSessionId(id) }
    }

    /** Ctrl+Tab and the swipe: the next (or previous) tab in strip order, wrapping, skipping collapsed groups. */
    fun stepActive(delta: Int) {
        val strip = stripNow()
        if (strip.isEmpty()) return
        val collapsed = workspaces.value.filter { it.collapsed }.map { it.id }.toSet()
        val active = _activeTabId.value
        val visible = strip.filter { it.workspaceId !in collapsed || it.id == active }.ifEmpty { strip }
        val current = visible.indexOfFirst { it.id == active }
        val next = if (current < 0) (if (delta >= 0) 0 else visible.lastIndex) else Math.floorMod(current + delta, visible.size)
        setActive(visible[next].id)
    }

    /** Ctrl+1…8 jump to tab n in strip order; Ctrl+9 (any index past the end) is the last tab. */
    fun activateAt(index: Int) {
        val strip = stripNow()
        if (strip.isEmpty()) return
        setActive(strip[index.coerceIn(0, strip.lastIndex)].id)
    }

    /**
     * Jump to unread (spec C3, C4, C22): the tab whose attention is the most recent goes on stage,
     * which clears it; ties (nothing timed) fall to strip order. False when no other tab needs the user.
     */
    fun jumpToUnread(): Boolean {
        val active = _activeTabId.value
        val target = stripNow()
            .filter { it.needsAttention && it.id != active }
            .maxByOrNull { _sessions.value[it.id]?.attentionAt ?: 0L } ?: return false
        setActive(target.id)
        return true
    }

    /**
     * Jumps to a group from the drawer: with [activate], the group's most recently active tab (or its
     * first) goes on stage; a group with no tabs just becomes the target for the next new tab.
     */
    fun setCurrentWorkspace(id: String, activate: Boolean = true) {
        _currentWorkspaceId.value = id
        scope.launch { settings.setCurrentWorkspaceId(id) }
        if (!activate) return
        val activeGroup = _activeTabId.value?.let { tabNow(it)?.record?.value?.workspaceId }
        if (activeGroup == id) return
        stripNow().firstOrNull { it.workspaceId == id }?.let { setActive(it.id) }
    }

    // ---- reorder, rename, groups ------------------------------------------------------------------

    /** Drag and drop: [id] ends up at [toIndex] in strip order (see [TabOrder.move]). */
    fun moveTab(id: String, toIndex: Int, groupId: String? = null) = applyPlacements(TabOrder.move(stripNow(), id, toIndex, groupId))

    /** Long-press menu › Move to group: re-homes the tab at the end of [groupId]. */
    fun moveToGroup(id: String, groupId: String) {
        applyPlacements(TabOrder.moveToGroup(stripNow(), id, groupId))
        if (_activeTabId.value == id) setCurrentWorkspace(groupId, activate = false)
    }

    fun rename(id: String, title: String?) = tabNow(id)?.rename(title)

    suspend fun createWorkspace(name: String, color: SwatchColor = SwatchColor.forName(name)): Workspace {
        val existing = workspaces.value
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            name = name,
            color = color,
            monogram = Host.monogramFor(name),
            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            createdAt = System.currentTimeMillis(),
        )
        workspaceRepository.upsert(workspace)
        return workspace
    }

    fun updateWorkspace(id: String, change: Workspace.() -> Workspace) {
        val group = workspaces.value.firstOrNull { it.id == id } ?: return
        scope.launch { workspaceRepository.upsert(group.change()) }
    }

    fun renameWorkspace(id: String, name: String) = updateWorkspace(id) { copy(name = name, monogram = Host.monogramFor(name)) }
    fun setWorkspaceColor(id: String, color: SwatchColor) = updateWorkspace(id) { copy(color = color) }
    fun setWorkspaceCollapsed(id: String, collapsed: Boolean) = updateWorkspace(id) { copy(collapsed = collapsed) }

    /** Drag a chip: the whole group moves to [toIndex] among the groups. */
    fun moveGroup(id: String, toIndex: Int) {
        val changed = TabOrder.moveGroup(workspaces.value, id, toIndex)
        if (changed.isNotEmpty()) scope.launch { workspaceRepository.upsertAll(changed) }
    }

    /** Closes every tab in the group; the group itself stays. */
    fun closeGroup(id: String) = stripNow().filter { it.workspaceId == id }.forEach { close(it.id) }

    /**
     * Deletes a group. Its tabs move to the previous group in order (or the next, for the first
     * group) unless [closeTabs]; the last remaining group cannot be deleted.
     */
    fun deleteWorkspace(id: String, closeTabs: Boolean) {
        val groups = workspaces.value.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt })
        val index = groups.indexOfFirst { it.id == id }
        if (index < 0 || groups.size < 2) return
        val receiver = (groups.getOrNull(index - 1) ?: groups[index + 1]).id
        val tabs = stripNow().filter { it.workspaceId == id }
        if (closeTabs) tabs.forEach { close(it.id) } else tabs.forEach { moveToGroup(it.id, receiver) }
        if (_currentWorkspaceId.value == id) setCurrentWorkspace(receiver, activate = false)
        scope.launch { workspaceRepository.delete(id) }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------

    /** Reconnects a terminal tab; for a Files tab, the terminal it rides, or a new one on its host when it has none. */
    override fun reconnect(id: String) {
        when (val tab = tabNow(id)) {
            is TerminalSession -> tab.reconnectNow()
            is FilesTab -> tab.ride.value?.reconnectNow() ?: connectFor(tab)
            else -> Unit
        }
    }

    /** Disconnects a terminal tab and keeps its frame. A Files tab has no connection of its own, so this does nothing to it. */
    override fun detach(id: String) {
        val session = _sessions.value[id] ?: return
        session.detach()
        saveFrame(session)
    }

    /**
     * Closes a tab. The tab to its right becomes active, else the one to its left (Chrome order).
     * Returns what is needed to reopen it, or null when there was no such tab.
     */
    fun close(id: String): ClosedTab? {
        val tab = tabNow(id) ?: return null
        val strip = stripNow()
        val closed = ClosedTab(tab.record.value)
        val next = TabOrder.nextActiveAfterClose(strip, id)
        tab.close()
        _sessions.update { it - id }
        _filesTabs.update { it - id }
        trackers.remove(id)?.cancel()
        savedVersions.remove(id)
        notifier.cancelFor(id)
        if (_activeTabId.value == id) setActive(next)
        scope.launch { sessionRepository.delete(id) }
        return closed
    }

    /** Long-press menu › Close others: every tab in the strip except [id]. */
    fun closeOthers(id: String) {
        stripNow().filter { it.id != id }.forEach { close(it.id) }
        if (_activeTabId.value != id) setActive(id)
    }

    /**
     * Persists the frame of every open terminal tab: on the way to the background, when the OS
     * trims memory, and on the background cadence, where [onlyChanged] skips tabs whose screen has
     * not moved since their last save. A Live tab is also re-dated, so "Detached · 4 min ago"
     * after a kill counts from the last save rather than from the connect.
     */
    fun saveAllFrames(onlyChanged: Boolean = false) {
        for (session in _sessions.value.values.toList()) {
            if (session.state == SessionState.CLOSED) continue
            if (onlyChanged && savedVersions[session.id] == session.screenVersion.value) continue
            saveFrame(session)
        }
    }

    private fun saveFrame(session: TerminalSession) {
        savedVersions[session.id] = session.screenVersion.value
        session.markLive()
        val frame = session.snapshotFrame()
        scope.launch { sessionRepository.saveFrame(session.id, frame) }
    }

    override fun detachAll() = _sessions.value.keys.toList().forEach { detach(it) }

    companion object {
        /** How often frames are re-saved while live sessions run in the background. */
        const val BACKGROUND_SAVE_MS = 30_000L

        fun openAppIntent(context: Context): Intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    }
}
