package app.berth.android.session

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.berth.android.diagnostics.BerthLog
import app.berth.android.diagnostics.CrashReporter
import app.berth.android.diagnostics.ReportKind
import app.berth.android.di.ProcessLifecycle
import app.berth.android.security.AppLockController
import app.berth.android.security.LockState
import app.berth.android.security.RemoteClipboardGate
import app.berth.domain.model.AltKeyMode
import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TabOrder
import app.berth.domain.model.TerminalSettings
import app.berth.domain.model.Tunnel
import app.berth.domain.model.Workspace
import app.berth.domain.repository.CommandHistoryRepository
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SessionRepository
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import app.berth.ssh.AgentSignRequest
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 * comes back detached on its last frame (vision §4.3, L0); and while the app is away, a tab that
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
    private val remoteClipboard: RemoteClipboardGate,
    private val lock: AppLockController,
    val notifier: SessionNotifier,
    @ProcessLifecycle private val processLifecycle: Lifecycle,
    private val reports: CrashReporter,
    private val commandHistory: CommandHistoryRepository,
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

    /**
     * Where the Stage is: the active tab, and while the Stage is split (spec C23), who shares it
     * and on which side. One value, since the two move together: a tab dropped on a pane becomes
     * the active tab as the tab that was active becomes its companion, and staging the companion
     * trades the roles. As two flows they were read as two, and [panes], derived on the manager's
     * scope, could take the new split with the old active id between the writes: the same tab as
     * active and as companion, one tab in both panes. The Stage keeps each tab's saveable state
     * under its id, once, and composing that pair threw ("Key … was used multiple times"), in one
     * of every few runs of the pane tests and on any device whose main thread read the flow in
     * that moment. Written under [stageLock], once per move.
     */
    private data class Stage(val activeId: String?, val split: Split?)

    private val _stage = MutableStateFlow(Stage(activeId = null, split = null))

    /** The active tab's id, or null with nothing on stage. The value is [_stage]'s as it is read, so a caller reads its own write back. */
    val activeTabId: StateFlow<String?> = _stage.view { it.activeId }

    /** The Stage split in two (spec C23): who shares it with the active tab and on which side; null while one tab has it. */
    val split: StateFlow<Split?> = _stage.view { it.split }

    /** The tab on stage, whatever it runs. */
    val activeTab: StateFlow<ManagedTab?> = combine(_stage, tabsById) { stage, map -> stage.activeId?.let { map[it] } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The tab on stage when it is a terminal; null while a Files tab, a Tunnels tab, or nothing, is showing. */
    val activeSession: StateFlow<TerminalSession?> = activeTab.map { (it as? TerminalSession)?.takeIf { s -> !s.tunnelsOnly } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The two tabs side by side, or null while the Stage shows one: the active tab on the split's
     * side, the companion on the other. It is up to the Stage to lay them out only on a width that
     * fits them; on a compact width the active tab alone shows and the split waits (see [setPanesShown]).
     * Derived from [_stage] alone, so no pair it emits names one tab twice (the split never names
     * the active tab as companion, and the two are one value).
     */
    val panes: StateFlow<Panes?> = combine(_stage, tabsById) { (active, split), map ->
        val focused = active?.let { map[it] }
        val companion = split?.let { map[it.companionId] }
        when {
            split == null || focused == null || companion == null -> null
            split.activeSide == PaneSide.LEFT -> Panes(left = focused, right = companion, focused = PaneSide.LEFT)
            else -> Panes(left = companion, right = focused, focused = PaneSide.RIGHT)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether the Stage is laying both panes out, so the companion is in view; under [stageLock]. */
    private var panesShown = false

    /**
     * File transfers in flight, set by the transfer queue. They ride a live session, so they never
     * hold the service up on their own; the count only shapes the notification.
     */
    val activeTransfers = MutableStateFlow(0)

    /** Of [activeTransfers], how many wait for an answer only the Files pane can give; the notification says so. */
    val waitingTransfers = MutableStateFlow(0)

    /**
     * The terminal whose transfer has waited longest, null while none does; the notification's
     * tap opens that host's Files tab (see [activateFilesFromNotification]), so the question can
     * be answered even after the tab that asked it was closed.
     */
    val waitingTransferSession = MutableStateFlow<String?>(null)

    private val restoreLock = Mutex()
    private var didRestore = false
    private val _restored = MutableStateFlow(false)

    /** Whether the persisted tabs have been loaded; until then nothing is known about the strip, not even that it is empty. */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    private val _foreground = MutableStateFlow(false)

    /**
     * Whether an activity is on screen ([Lifecycle.Event.ON_START] to [Lifecycle.Event.ON_STOP]);
     * the lock window counts, so the tabs are in view only when this holds and the app lock is
     * lifted ([onScreen]). Off screen, attention goes to the shade.
     */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _stageRequests = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A tab a notification put on stage; the shell pops back to the Stage so it is actually seen. */
    val stageRequests: SharedFlow<String> = _stageRequests.asSharedFlow()

    private val _closedTabs = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The id of each tab as it closes ([close]), once [get] and [tab] no longer answer to it: for
     * what is kept by tab id outside the manager and goes with the tab. An event, not a state, so
     * a tab opened and closed between two looks at [tabs] is not missed the way a conflated list is.
     */
    val closedTabs: SharedFlow<String> = _closedTabs.asSharedFlow()

    /** A notification's tap that arrived before the strip was restored; honoured the moment it is. */
    private var pendingActivation: Activation? = null

    /** A notification's tap that arrived under the app lock (or before it was decided); the latest stands for the earlier, honoured the moment the lock lifts. */
    private var lockedActivation: Activation? = null
    private val pendingLock = Any()

    /** Problems collectors, one per terminal tab, cancelled when the tab closes. */
    private val trackers = HashMap<String, Job>()

    /** Screen version each tab's frame was last saved at, so the background cadence skips quiet tabs. */
    private val savedVersions = HashMap<String, Long>()
    private var backgroundSaver: Job? = null
    private var firstLiveSeen = false

    /** True from ON_STOP to ON_START and while the lock screen is up: nothing is in front of the user, so no tab is on stage. */
    @Volatile private var stageDark = false

    /**
     * Holds [_foreground], the active id and the stage together. The lifecycle flips the stage on
     * the main thread, the lock collector on the manager's scope and [setActive] on whichever
     * thread stages a tab; without one monitor a collector could read the app as on screen, lose
     * the thread to ON_STOP, and light a stage the app had just left, and the active tab's bells
     * would go unheard until the next return.
     */
    private val stageLock = Any()

    private val commandHistoryEnabled = settings.commandHistoryEnabled.stateIn(scope, SharingStarted.Eagerly, true)
    private val hardwareKeyboard = settings.hardwareKeyboardSettings.stateIn(scope, SharingStarted.Eagerly, HardwareKeyboardSettings())

    /** Settings › Connection (spec C20): the idle-detach policy and whether the battery explainer has had its one showing. */
    private val connectionSettings: StateFlow<ConnectionSettings> = settings.connectionSettings.stateIn(scope, SharingStarted.Eagerly, ConnectionSettings())

    /** Settings › Connection › Detach idle sessions, as the span in milliseconds or null for Never; one read for every session. */
    private val idleDetachAfter: StateFlow<Long?> = connectionSettings.map { it.idleDetach.millis }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Settings › Terminal › Scrollback, one read for every session; a host with its own cap stands
     * over it. Shared without a starting value, so no tab is held to the default before the setting
     * is read and a restored frame kept under a wider cap is not cut on the way in.
     */
    private val appScrollback: SharedFlow<Int> =
        settings.terminalSettings.map { it.scrollbackLines }.distinctUntilChanged().shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private val _batteryExplainerDue = MutableStateFlow(false)

    /**
     * Whether the battery-optimisation explainer is owed its one showing (vision §4.4): a live
     * connection was lost while the app was away, for a reason a network change does not account
     * for, and the explainer has never been raised. The shell raises Settings › Connection ›
     * Background over whatever is up on the next return and calls [batteryExplainerRaised]; the
     * setting it marks then keeps this from ever going true again.
     */
    val batteryExplainerDue: StateFlow<Boolean> = _batteryExplainerDue.asStateFlow()

    fun batteryExplainerRaised() {
        _batteryExplainerDue.value = false
    }

    /**
     * The network's changes once for every session rather than a callback each (the system caps
     * an app's callbacks), registered while a tab listens and let go when the last one closes.
     */
    private val networkChanges: SharedFlow<Unit> = network.changes.shareIn(scope, SharingStarted.WhileSubscribed())

    /**
     * History writes in the order the sessions made them (spec C16): one consumer, so two commands
     * a moment apart land as they ran and a repeat of the host's latest is seen as one, which two
     * coroutines racing to the table could not promise.
     */
    private val historyWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * The wall clock the sessions stamp their records and marker rows with ([SessionEnvironment.now]);
     * a screenshot test of a live tab shifts it, so a `detached 14:07` row reads the same on every run.
     */
    internal var clock: () -> Long = System::currentTimeMillis

    private val environment = object : SessionEnvironment {
        override fun now(): Long = clock()
        override fun commandHistoryEnabled(): Boolean = this@SessionManager.commandHistoryEnabled.value
        override fun recordCommand(hostId: String, text: String, at: Long) {
            historyWrites.trySend { commandHistory.record(hostId, text, at) }
        }
        override fun importCommands(hostId: String, entries: List<Pair<String, Long>>) {
            historyWrites.trySend { commandHistory.importEntries(hostId, entries) }
        }
        override fun altKeyFor(hostId: String?): AltKeyMode = hardwareKeyboard.value.altKeyFor(hostId)
        override suspend fun authFor(host: Host): List<SshAuth> = authResolver.resolve(host)
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = KnownHostsPolicy(host, knownHosts, prompts)
        override fun hostKeyPolicyFor(host: Host, via: HopRole?, linkFingerprint: String?): HostKeyPolicy =
            KnownHostsPolicy(host, knownHosts, prompts, via = via, linkFingerprint = linkFingerprint)
        override suspend fun jumpHostsFor(host: Host): List<Host> = resolveJumpChain(host)
        override val networkAvailable: Flow<Unit> = network.available
        override val networkChanges: Flow<Unit> = this@SessionManager.networkChanges
        override val idleDetachAfter: Flow<Long?> = this@SessionManager.idleDetachAfter
        // The document itself, not the eager copy: a tab restored at launch may connect before that copy's first read lands.
        override suspend fun connectionDefaults(): ConnectionSettings = settings.connectionSettings.first()

        // The saved host as it is now, so an edit in the host editor reaches its open tabs; a
        // Quick-connect login or a host deleted since keeps the snapshot the tab opened with.
        override fun scrollbackLinesFor(host: Host): Flow<Int> =
            combine(hostRepository.observe(host.id).map { it ?: host }, appScrollback) { saved, app ->
                TerminalSettings.scrollbackFor(saved.scrollbackLines, app)
            }.distinctUntilChanged()
        override fun onClipboardText(host: Host, text: String) = remoteClipboard.offer(host, text)
        override suspend fun agentSignsSilently(host: Host): Boolean = settings.securitySettings.first().signsAgentSilently(host.id)

        // Like the key prompts, never over the lock screen: the request waits, the tab lit, until the lock lifts.
        override suspend fun approveAgentRequest(host: Host, request: AgentSignRequest): AgentAnswer {
            lock.awaitUnlocked()
            return prompts.agentRequest(host, request)
        }
        override fun tunnelsFor(hostId: String): Flow<List<Tunnel>> = tunnelRepository.observeForHost(hostId)
        override suspend fun connectCommands(host: Host, workspaceId: String): List<String> =
            snippetRepository.observeAll().first()
                .filter { it.runOnConnect && it.hostId == host.id && (it.workspaceId == null || it.workspaceId == workspaceId) }
                .map { it.render() }

        // The same capture as a crash, one report per failure, under Settings › Diagnostics; the host is
        // named the way the strip names it, the address so the report says which server, never a credential.
        override fun onTransportFailure(host: Host, phase: String, error: Throwable?, detail: String) {
            reports.report(
                ReportKind.TRANSPORT,
                "$phase: ${host.name}",
                error,
                details = listOf("Host" to "${host.name} \u00B7 ${host.user}@${host.address}:${host.port}", "Phase" to phase, "Reason" to detail),
            )
        }
    }

    /**
     * The saved hosts a login to [host] goes through, first hop first, the way OpenSSH reads
     * ProxyJump: a hop's own chain comes before the hop (so a bastion that is itself reached
     * through another is), each host at most once and never the target itself, and an id no saved
     * host answers to any more is left out rather than failing the connect. The chain is read
     * from the hosts table at connect time, so editing a bastion's address or key takes effect on
     * the next attempt of every host behind it.
     */
    suspend fun resolveJumpChain(host: Host): List<Host> {
        val chain = ArrayList<Host>()
        val seen = HashSet<String>()
        seen += host.id
        suspend fun walk(ids: List<String>, depth: Int) {
            if (depth > MAX_JUMP_DEPTH) return
            for (id in ids) {
                if (!seen.add(id)) continue
                val hop = hostRepository.get(id) ?: continue
                walk(hop.jumpHostIds, depth + 1)
                chain += hop
            }
        }
        walk(host.jumpHostIds, 0)
        return chain
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
        reports.addCrashHook { saveAllFramesNow() }
        scope.launch { for (write in historyWrites) runCatching { write() }.onFailure { BerthLog.w(LOG_TAG, "command history write failed", it) } }
        scope.launch { restore() }
        scope.launch {
            // The split beside the last active tab (spec C23 with C3's Persistence), written as it
            // changes, once the strip is restored: before that the Stage is empty, and a write would
            // erase the split the last process left for this one to rebuild. The first value written
            // is the restored split itself, or null for a saved companion no open tab answered to.
            restored.first { it }
            split.collect { settings.setStageSplit(it?.stored) }
        }
        scope.launch {
            // Only a login holds a socket (a terminal, or a Tunnels tab); a Files tab mirrors its
            // ride's state and never keeps the service up on its own. The notification says what every
            // such tab is doing, with the tunnels up, the transfers in flight, how many of those wait
            // on the user and whose Files tab its tap should open; the service itself only starts and
            // stops with the count of tabs holding a socket.
            var lastActive = 0
            combine(records, tunnelStatuses, activeTransfers, waitingTransfers, waitingTransferSession) { list, statuses, transfers, waiting, waitingSession ->
                SessionsSummary(
                    lines = list.filter { it.kind != TabKind.Files && it.state != SessionState.CLOSED }.map { SessionLine(it.displayTitle, it.state) },
                    tunnels = statuses.count { it.value is TunnelStatus.Up },
                    transfers = transfers,
                    waiting = waiting,
                    waitingSession = waitingSession,
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
            // The lock deciding or lifting while an activity is on screen (spec C20 with C21): locked,
            // the stage goes dark as it does when the app leaves, since the user faces the lock screen
            // and not a tab; unlocked, the active tab comes into view and its news counts as seen.
            lock.state.collect { state ->
                synchronized(stageLock) {
                    if (!_foreground.value) return@synchronized
                    when (state) {
                        LockState.UNLOCKED -> lightStage()
                        LockState.LOCKED -> darkenStage()
                        LockState.UNKNOWN -> Unit
                    }
                }
            }
        }
        scope.launch {
            records.collect {
                electCarriers(it)
                refollow()
            }
        }
        scope.launch {
            // Each record against its last emission: for a terminal, frames on the way out of Live
            // and the first Live of the process; for every tab, attention lighting or clearing.
            val seen = HashMap<String, SessionRecord>()
            records.collect { list ->
                seen.keys.retainAll(list.mapTo(HashSet()) { it.id })
                for (record in list) {
                    val previous = seen.put(record.id, record)
                    onRecordChanged(previous, record)
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
        synchronized(stageLock) {
            _foreground.value = true
            // MainActivity's onStart has the lock decide before this ON_START reaches the process owner
            // (from API 29, minSdk, it hears of a start through onActivityPostStarted, once onStart has
            // returned), so the state read here is this return's. With the lock up the user faces the
            // lock screen, not a tab: the stage stays dark and the active tab's news unseen until the
            // lock lifts (the collector in init). With no lock, the tab is in front of the user again now.
            if (lock.state.value == LockState.UNLOCKED) lightStage() else darkenStage()
        }
        backgroundSaver?.cancel()
        backgroundSaver = null
        BerthLog.d(LOG_TAG, "app on screen")
        // The user may have flipped notifications in system settings while away.
        notifier.refresh()
    }

    /**
     * The active tab is in front of the user: it is on stage, and whatever it raised while the app
     * was away or locked has been seen (its notification goes with it, through the record). Under
     * [stageLock], like [darkenStage].
     */
    private fun lightStage() {
        stageDark = false
        refreshStage()
        tabsNow().forEach { if (it.onStage) it.markSeen() }
    }

    /**
     * Under [stageLock]: the active tab is on stage, and so is the companion while the panes show
     * (spec C23: two tabs in view, neither raises attention); every other tab is off it, and none
     * is while the stage is dark.
     */
    private fun refreshStage() {
        val (active, split) = _stage.value
        val companion = if (panesShown) split?.companionId else null
        tabsNow().forEach { it.onStage = !stageDark && (it.id == active || it.id == companion) }
    }

    /** Nothing is in front of the user (the app away, or the lock screen up): no tab is on stage, so every tab's bells and long commands count as attention. */
    private fun darkenStage() {
        stageDark = true
        tabsNow().forEach { it.onStage = false }
    }

    /** Whether the user can see the tabs: an activity on screen and the app lock, if any, lifted. Otherwise attention and problems go to the shade. */
    private fun onScreen() = _foreground.value && lock.state.value == LockState.UNLOCKED

    /**
     * The app left the screen: nothing is on stage any more, so the active tab's bells and long
     * commands count as attention like any other tab's (spec C21, Attention). Every frame is saved
     * now, then again every [BACKGROUND_SAVE_MS] for tabs whose screen moved while a session is
     * still connected, so however the process ends the relaunch shows what each tab last showed.
     */
    private fun onBackground() {
        synchronized(stageLock) {
            _foreground.value = false
            darkenStage()
        }
        BerthLog.d(LOG_TAG, "app left the screen; saving ${_sessions.value.values.count { it.state != SessionState.CLOSED }} frames")
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
     * [ComponentCallbacks2.onTrimMemory], registered on the application. From
     * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] up the app is off the screen and a kill may
     * follow, so frames whose screen moved since their last save go to disk. The `RUNNING` levels
     * below it (API 29-33) arrive while the app is on screen and are only advice about memory;
     * snapshotting every tab's scrollback then would stall the very frame the user is touching,
     * for a process that is not about to die. Public for tests and for any host that wants to
     * forward its own callback.
     */
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        BerthLog.d(LOG_TAG, "memory trim at level $level; saving changed frames")
        saveAllFrames(onlyChanged = true)
    }

    private fun onRecordChanged(previous: SessionRecord?, record: SessionRecord) {
        val session = _sessions.value[record.id]
        if (session == null && _filesTabs.value[record.id] == null) return
        if (session != null) {
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
        }
        val hadAttention = previous?.needsAttention == true
        if (record.needsAttention && !hadAttention) {
            // On screen the ring says it; away or under the lock, the shade does (spec C21, Attention),
            // for a Files tab whose copy stopped on a question as much as for a terminal's bell. A
            // terminal that lost its connection is lit too, but the Problems notification carries that
            // one, with Retry and Detach.
            if (!onScreen() && session?.attentionProblem == null) {
                notifier.postAttention(record, session?.attentionAt ?: System.currentTimeMillis())
            }
        } else if (!record.needsAttention && hadAttention) {
            notifier.cancelAttention(record.id)
        }
    }

    /**
     * Problems from one tab, for as long as it is open. Like attention, they reach the shade only
     * while the app is away or locked; on screen the tab is lit (the session raised attention with
     * the problem) and the ring or the count tile says it, with no heads-up over Berth's own header.
     */
    private fun track(session: TerminalSession) {
        trackers.remove(session.id)?.cancel()
        trackers[session.id] = scope.launch {
            launch {
                session.problems.collect { problem ->
                    if (!onScreen()) notifier.postProblem(session.record.value, problem)
                }
            }
            launch {
                // The first connection lost with the app away, unless the network moved under it
                // (the probe's own reason), is the cue for the battery explainer, once (vision §4.4).
                session.drops.collect { reason ->
                    if (!_foreground.value && reason != TerminalSession.PROBE_LOST_REASON && !connectionSettings.value.batteryExplained) {
                        _batteryExplainerDue.value = true
                    }
                }
            }
        }
    }

    /**
     * One session per host carries its tunnels. A Live Tunnels tab always does, since carrying them
     * is what it is for and its stage shows them; failing one, the current carrier keeps the role
     * while Live, then the first Live session takes it, then the first connecting one (a Tunnels tab
     * first) so the forwards start the moment it comes up. Sessions that lose the role release their
     * ports first; ties fall to strip order.
     */
    private fun electCarriers(records: List<SessionRecord>) {
        val byHost = records.filter { it.kind != TabKind.Files && it.hostId != null && it.state.isActive }.groupBy { it.hostId!! }
        val chosen = HashMap<String, String>()
        for ((hostId, candidates) in byHost) {
            val current = carrierByHost[hostId]
            fun rank(r: SessionRecord): Int = when {
                r.kind == TabKind.Tunnels && r.state == SessionState.LIVE -> if (r.id == current) 0 else 1
                r.state == SessionState.LIVE -> if (r.id == current) 2 else 3
                r.kind == TabKind.Tunnels -> 4
                else -> 5
            }
            chosen[hostId] = candidates.minBy(::rank).id
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
        // live: their frames end in a `detached` marker stamped then, the pill's word, so the
        // relaunch reads as a session that was cut, not one that vanished.
        val detachedAt = HashMap<String, Long?>()
        for (record in sessionRepository.getAll()) {
            if (record.state == SessionState.CLOSED) {
                sessionRepository.delete(record.id)
                continue
            }
            if (record.state.isActive) detachedAt[record.id] = record.lastLiveAt
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
                TabKind.Ssh, TabKind.Tunnels -> {
                    val session = TerminalSession(record, scope, environment) { sessionRepository.upsert(it) }
                    // A Tunnels tab has no frame: its stage is rebuilt from the forwards when it reconnects.
                    if (!session.tunnelsOnly) session.restoreFrame(sessionRepository.loadFrame(record.id), detachedAt = detachedAt[record.id])
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
        // The split comes back with the active tab (spec C23 with C3's Persistence), when the companion
        // it names is still open and is not the active tab itself (a document from before the active
        // id moved under it); otherwise the Stage opens on the one tab, and the collector in init
        // writes the split as it now stands, clearing what no tab answers to.
        val savedSplit = settings.stageSplit.first()
            ?.takeIf { last != null && it.companionId != last && (map.containsKey(it.companionId) || files.containsKey(it.companionId)) }
            ?.let { Split.of(it) }
        restoreStage(last, savedSplit)
        _currentWorkspaceId.value = last?.let { tabNow(it)?.record?.value?.workspaceId } ?: currentGroup
        if (last != null && last != persisted) scope.launch { settings.setLastActiveSessionId(last) }
        val reconnectWorkspaces = groups.filter { it.reconnectAtLaunch }.map { it.id }.toSet()
        map.values.filter { it.record.value.workspaceId in reconnectWorkspaces }.forEach { it.connect() }
        BerthLog.i(LOG_TAG, "restored ${map.size} terminal tabs and ${files.size} Files tabs; ${reconnectWorkspaces.size} groups reconnect at launch")
        val pending = synchronized(pendingLock) {
            _restored.value = true
            pendingActivation.also { pendingActivation = null }
        }
        pending?.let { perform(it) }
    }

    /**
     * A notification's tab (spec C21: every notification opens the tab it is about). Staged now,
     * or the moment the strip is restored when the tap is what started the process.
     */
    fun activateFromNotification(id: String) = activate(Activation.Tab(id))

    /**
     * The transfers notification's tap while a copy waits on the user: the Files tab of the
     * terminal [sessionId] comes on stage, the host's if it has one, else a new one riding that
     * terminal ([openFiles]), so the question is reachable even after the tab that asked it was
     * closed. Same route as a tab's own notification, including the wait for the strip.
     */
    fun activateFilesFromNotification(sessionId: String) = activate(Activation.Files(sessionId))

    private fun activate(target: Activation) {
        val now = synchronized(pendingLock) {
            if (_restored.value) true else {
                pendingActivation = target
                false
            }
        }
        if (now) perform(target)
    }

    /**
     * Staging marks the tab seen and cancels its notification, and a Files tap opens a tab: not under
     * the app lock, where the user faces the lock screen and may yet leave with the news unseen (spec
     * C20 with C21). Locked, or before the lock has decided, the tap waits for the unlock the way the
     * key prompts do, the latest tap standing for the earlier ones; unlocked, it acts now.
     */
    private fun perform(target: Activation) {
        if (lock.state.value == LockState.UNLOCKED) {
            performNow(target)
            return
        }
        val waiting = synchronized(pendingLock) { lockedActivation.also { lockedActivation = target } != null }
        if (waiting) return
        scope.launch {
            lock.awaitUnlocked()
            synchronized(pendingLock) { lockedActivation.also { lockedActivation = null } }?.let { performNow(it) }
        }
    }

    private fun performNow(target: Activation) {
        when (target) {
            is Activation.Tab -> stage(target.id)
            is Activation.Files -> scope.launch { openFiles(target.sessionId)?.let { stage(it.id) } }
        }
    }

    private fun stage(id: String) {
        if (tabNow(id) == null) return
        setActive(id)
        _stageRequests.tryEmit(id)
    }

    /** What a notification's tap asks the strip for: a tab by id, or the Files tab of a terminal whose transfer waits. */
    private sealed interface Activation {
        data class Tab(val id: String) : Activation
        data class Files(val sessionId: String) : Activation
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
     * [workspaceId] when there is no anchor. With [kind] = [TabKind.Tunnels] the login opens no
     * shell and carries the host's forwards instead ([openTunnels]). [linkFingerprint] is set when
     * an `ssh://` link opened the tab and carried a fingerprint for the server's key: the trust
     * sheets compare by it.
     */
    suspend fun open(
        host: Host,
        workspaceId: String? = null,
        afterId: String? = activeTabId.value,
        customTitle: String? = null,
        activate: Boolean = true,
        kind: TabKind = TabKind.Ssh,
        linkFingerprint: String? = null,
    ): TerminalSession {
        restore()
        val group = workspaceId ?: _currentWorkspaceId.value ?: workspaceRepository.ensureDefault().id
        val fresh = SessionRecord(
            id = UUID.randomUUID().toString(),
            workspaceId = group,
            hostId = host.id,
            hostSnapshot = host,
            state = SessionState.IDLE,
            layer = PersistenceLayer.IN_APP,
            title = titleFor(kind, host),
            createdAt = System.currentTimeMillis(),
            kind = if (kind == TabKind.Tunnels) TabKind.Tunnels else TabKind.Ssh,
            customTitle = customTitle,
        )
        val changes = TabOrder.insertAfter(stripNow(), fresh, anchorFor(afterId, workspaceId), group)
        return start(fresh, changes, activate, linkFingerprint)
    }

    /**
     * Opens a Tunnels tab for [host] (spec C14): one login that carries the host's port forwards
     * and no shell, placed and staged like a terminal. A second one on the same host is allowed
     * but pointless; the forwards run on one carrier, and it shows them.
     */
    suspend fun openTunnels(
        host: Host,
        workspaceId: String? = null,
        afterId: String? = activeTabId.value,
        activate: Boolean = true,
        linkFingerprint: String? = null,
    ): TerminalSession = open(host, workspaceId, afterId, activate = activate, kind = TabKind.Tunnels, linkFingerprint = linkFingerprint)

    /**
     * Connect from the host list, the New tab sheet or an `ssh://` link: a terminal, or the host's
     * forwards alone when the host is marked tunnels only ([Host.tunnelsOnly]). [linkFingerprint]
     * is the fingerprint the link carried for the server's key, when a link with one opened this.
     */
    suspend fun connect(host: Host, workspaceId: String? = null, linkFingerprint: String? = null): TerminalSession =
        if (host.tunnelsOnly) openTunnels(host, workspaceId, linkFingerprint = linkFingerprint) else open(host, workspaceId, linkFingerprint = linkFingerprint)

    /** The automatic title of a fresh tab of [kind] on [host]: the host's name, or `Tunnels · name` like a Files tab's `Files · name`. */
    private fun titleFor(kind: TabKind, host: Host): String = if (kind == TabKind.Tunnels) "Tunnels \u00B7 ${host.name}" else host.name

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
            TabKind.Ssh, TabKind.Tunnels -> {
                val fresh = old.copy(
                    id = id,
                    workspaceId = group,
                    state = SessionState.IDLE,
                    layer = PersistenceLayer.IN_APP,
                    title = titleFor(old.kind, old.hostSnapshot),
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

    /**
     * Opens a second tab on the same host directly after [id], in its group, and puts it on stage
     * unless [activate] is off (spec C3, Duplicate): a terminal after a terminal, a browser at the
     * same folder after a Files tab. After a Tunnels tab the second tab is a shell, the one its
     * menu's Terminal row opens: a second Tunnels login on one host would bind the same local ports
     * again (and the same remote ones for a remote forward), so the twin's forwards would fail with
     * address-in-use by construction, and the host's shell is the tab worth having beside its
     * forwards. The menu offers no Duplicate row on a Tunnels tab, since Terminal is that row; the
     * plus tab's long-press and Split ([splitActive]) come here.
     */
    suspend fun duplicate(id: String, activate: Boolean = true): ManagedTab? {
        val source = tabNow(id) ?: return null
        val record = source.record.value
        return when (record.kind) {
            TabKind.Ssh, TabKind.Tunnels -> open(record.hostSnapshot, workspaceId = record.workspaceId, afterId = id, activate = activate)
            TabKind.Files -> startFiles(record.hostSnapshot, workspaceId = record.workspaceId, afterId = id, preferred = (source as? FilesTab)?.ride?.value?.id, folder = record.cwd, activate = activate)
        }
    }

    private suspend fun start(fresh: SessionRecord, changes: List<SessionRecord>, activate: Boolean, linkFingerprint: String? = null): TerminalSession {
        val placed = changes.firstOrNull { it.id == fresh.id } ?: fresh
        val session = TerminalSession(placed, scope, environment, linkFingerprint) { sessionRepository.upsert(it) }
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
    fun filesTabFor(hostId: String): FilesTab? = filesTabsFor(hostId).firstOrNull()

    /** The host's Files tabs in strip order. */
    private fun filesTabsFor(hostId: String): List<FilesTab> =
        stripNow().filter { it.kind == TabKind.Files && it.hostSnapshot.id == hostId }.mapNotNull { _filesTabs.value[it.id] }

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
     * the host is up, or offers to connect one; a login it asks for takes [linkFingerprint] along
     * when an `sftp://` link with a fingerprint opened the tab ([FilesTab.linkFingerprint]).
     */
    suspend fun openFilesForHost(host: Host, workspaceId: String? = null, folder: String? = null, linkFingerprint: String? = null): FilesTab {
        restore()
        // The host's Files tab comes on stage; an `sftp://` link naming a folder takes the tab showing it, or opens one of its own there.
        val showing = filesTabsFor(host.id)
        (if (folder == null) showing.firstOrNull() else showing.firstOrNull { it.folder == folder })?.let {
            if (linkFingerprint != null) it.linkFingerprint = linkFingerprint
            setActive(it.id)
            return it
        }
        return startFiles(host, workspaceId = workspaceId, afterId = activeTabId.value, preferred = null, folder = folder, linkFingerprint = linkFingerprint)
    }

    private suspend fun startFiles(host: Host, workspaceId: String?, afterId: String?, preferred: String?, folder: String?, activate: Boolean = true, linkFingerprint: String? = null): FilesTab {
        val group = workspaceId ?: _currentWorkspaceId.value ?: workspaceRepository.ensureDefault().id
        val fresh = FilesTab.newRecord(UUID.randomUUID().toString(), host, group, System.currentTimeMillis(), folder)
        return placeFiles(fresh, TabOrder.insertAfter(stripNow(), fresh, anchorFor(afterId, workspaceId), group), preferred, activate, linkFingerprint)
    }

    private suspend fun placeFiles(fresh: SessionRecord, changes: List<SessionRecord>, preferred: String?, activate: Boolean = true, linkFingerprint: String? = null): FilesTab {
        val placed = changes.firstOrNull { it.id == fresh.id } ?: fresh
        val tab = FilesTab(placed, scope) { sessionRepository.upsert(it) }
        tab.linkFingerprint = linkFingerprint
        tab.prefer(preferred)
        _filesTabs.update { it + (placed.id to tab) }
        val shifted = changes.filter { it.id != fresh.id }.mapNotNull { change -> tabNow(change.id)?.place(change.workspaceId, change.sortOrder) }
        sessionRepository.upsertAll(shifted + placed)
        refollow()
        if (activate) setActive(placed.id)
        return tab
    }

    /**
     * Opens a terminal on a Files tab's host directly after it, off stage, so the browser has a
     * login to ride; the tab picks it up as it connects. The pane's Connect button.
     */
    fun connectFor(tab: FilesTab) {
        scope.launch { open(tab.host, workspaceId = tab.record.value.workspaceId, afterId = tab.id, activate = false, linkFingerprint = tab.linkFingerprint) }
    }

    /**
     * Terminal from a Files tab's menu: the terminal it rides comes on stage, or, when the host has
     * none (a Tunnels tab's login carries the browser but has no shell), a new one opens directly
     * after the Files tab and the browser rides that. From a Tunnels tab's menu: a terminal on its
     * host, directly after it. Null when [id] is neither.
     */
    suspend fun openTerminalFor(id: String): TerminalSession? {
        _sessions.value[id]?.let { tunnels ->
            if (!tunnels.tunnelsOnly) return null
            return open(tunnels.host, workspaceId = tunnels.record.value.workspaceId, afterId = id)
        }
        val tab = _filesTabs.value[id] ?: return null
        tab.ride.value?.takeIf { !it.tunnelsOnly }?.let {
            setActive(it.id)
            return it
        }
        val session = open(tab.host, workspaceId = tab.record.value.workspaceId, afterId = tab.id, linkFingerprint = tab.linkFingerprint)
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
        moveStage(id, seen = true)
        follow(tab)
    }

    /** The current group and the persisted active id follow the tab that just took the stage. */
    private fun follow(tab: ManagedTab?) {
        tab?.record?.value?.workspaceId?.let { group -> if (_currentWorkspaceId.value != group) setCurrentWorkspace(group, activate = false) }
        scope.launch { settings.setLastActiveSessionId(tab?.id) }
    }

    /**
     * Makes [id] the active tab and moves the stage to it, as one step under [stageLock], before
     * returning: the flags are right for whatever arrives next, and nothing else writes them for
     * the id. A collector of the id did this once, a moment after [setActive] had marked the new tab
     * seen, and a copy's question relayed by FilesCenter (or a bell) landing in that moment found
     * the tab off stage and lit the very tab the user was looking at, with nothing to clear it.
     * With [seen], the tab's attention is cleared here too, so an event from before the move is
     * cleared and one from after finds the tab on stage, never the reverse. While the app is away
     * or locked ([stageDark]) nothing is on stage, whatever the id: the return lights it.
     */
    private fun moveStage(id: String?, seen: Boolean) {
        synchronized(stageLock) {
            val (active, split) = _stage.value
            // Staging the companion (its tab in the strip, Ctrl+Tab reaching it) hands it the keys:
            // the two trade roles and keep their panes. Staging nothing leaves nothing to share with.
            val next = when {
                split == null || id == null -> null
                id == split.companionId -> active?.let { Split(it, split.companionSide) }
                else -> split
            }
            _stage.value = Stage(id, next)
            refreshStage()
            if (seen && id != null) tabNow(id)?.markSeen()
        }
    }

    /**
     * The Stage as the last process left it ([restore]): the active tab with the split it had, in
     * the one write [moveStage] makes, so the panes flow never sees the active tab without its
     * companion or the reverse. Nothing is marked seen: the tabs are back detached, and what they
     * raised before the process died is theirs to raise again.
     */
    private fun restoreStage(id: String?, split: Split?) {
        synchronized(stageLock) {
            _stage.value = Stage(id, split)
            refreshStage()
        }
    }

    // ---- panes (spec C23) --------------------------------------------------------------------------

    /**
     * Puts [id] in the pane on [side] and hands it the keys (a tab dragged from the strip onto a
     * pane, or the menu's Open beside). Whatever that pane showed leaves it; the other pane keeps
     * its tab, which becomes the companion when it was the active one. On a single Stage the
     * active tab becomes the companion, so this is what splits it. The active tab dropped on the
     * other pane trades places with the companion and keeps the keys; dropped on its own, nothing
     * moves. An id no tab answers to is ignored, like [setActive].
     */
    fun placeInPane(id: String, side: PaneSide) {
        val tab = tabNow(id) ?: return
        synchronized(stageLock) {
            val (active, split) = _stage.value
            if (id == active) {
                if (split != null && split.activeSide != side) _stage.value = Stage(active, Split(split.companionId, side))
                return
            }
            val companion = if (split != null && side == split.activeSide && id != split.companionId) split.companionId else active
            _stage.value = Stage(id, companion?.let { Split(it, side) })
            refreshStage()
            tab.markSeen()
        }
        follow(tab)
    }

    /** Long-press menu › Open beside: [id] takes the pane opposite the active tab, and the keys. */
    fun openBeside(id: String) = placeInPane(id, split.value?.companionSide ?: PaneSide.RIGHT)

    /**
     * The Session sheet's Split (spec C6): a second tab on the active tab's host opens in the pane
     * opposite it and takes the keys; the active tab keeps its pane. The tab is what [duplicate]
     * opens, so beside a Tunnels tab it is a shell on that host, not a second carrier: a twin would
     * bind the same ports and fail by construction, and the forwards are one row away in the pane
     * that has them. Null with nothing on stage.
     */
    suspend fun splitActive(): ManagedTab? {
        val (active, split) = _stage.value
        val source = active ?: return null
        val side = split?.companionSide ?: PaneSide.RIGHT
        val fresh = duplicate(source, activate = false) ?: return null
        placeInPane(fresh.id, side)
        return fresh
    }

    /**
     * Closes the pane on [side]; its tab stays open in the strip, so the pane can be reopened
     * without losing anything. Closing the focused pane hands the keys to the other pane's tab.
     */
    fun closePane(side: PaneSide) {
        val split = this.split.value ?: return
        synchronized(stageLock) {
            _stage.update { it.copy(split = null) }
            refreshStage()
        }
        if (side == split.activeSide) setActive(split.companionId)
    }

    /** One tab on the Stage again: the companion leaves its pane and stays in the strip. */
    fun unsplit() = split.value?.let { closePane(it.companionSide) } ?: Unit

    /**
     * Whether the Stage is laying both panes out. On a width that fits them (spec C23) the
     * companion is in view: it is on stage, its bells never raise attention and its news counts as
     * seen. Folded or rotated to a compact width the active tab alone shows, and the companion is
     * off stage like any other tab until the panes come back; the split itself stays, so unfolding
     * brings both tabs back where they were.
     */
    fun setPanesShown(shown: Boolean) {
        synchronized(stageLock) {
            if (panesShown == shown) return
            panesShown = shown
            refreshStage()
            if (shown && !stageDark) _stage.value.split?.companionId?.let { tabNow(it)?.markSeen() }
        }
    }

    /** Ctrl+Tab and the swipe: the next (or previous) tab in strip order, wrapping, skipping collapsed groups. */
    fun stepActive(delta: Int) {
        val strip = stripNow()
        if (strip.isEmpty()) return
        val collapsed = workspaces.value.filter { it.collapsed }.map { it.id }.toSet()
        val active = activeTabId.value
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
        val active = activeTabId.value
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
        val activeGroup = activeTabId.value?.let { tabNow(it)?.record?.value?.workspaceId }
        if (activeGroup == id) return
        stripNow().firstOrNull { it.workspaceId == id }?.let { setActive(it.id) }
    }

    // ---- reorder, rename, groups ------------------------------------------------------------------

    /** Drag and drop: [id] ends up at [toIndex] in strip order (see [TabOrder.move]). */
    fun moveTab(id: String, toIndex: Int, groupId: String? = null) = applyPlacements(TabOrder.move(stripNow(), id, toIndex, groupId))

    /** Long-press menu › Move to group: re-homes the tab at the end of [groupId]. */
    fun moveToGroup(id: String, groupId: String) {
        applyPlacements(TabOrder.moveToGroup(stripNow(), id, groupId))
        if (activeTabId.value == id) setCurrentWorkspace(groupId, activate = false)
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
        BerthLog.i(LOG_TAG, "[${session.host.name}] detached by the user")
        session.detach()
        saveFrame(session)
    }

    /**
     * Closes a tab. The tab to its right becomes active, else the one to its left (Chrome order);
     * a tab that held a pane closes its pane instead, and the other pane's tab has the Stage to
     * itself (spec C23). Returns what is needed to reopen it, or null when there was no such tab.
     */
    fun close(id: String): ClosedTab? {
        val tab = tabNow(id) ?: return null
        val strip = stripNow()
        val closed = ClosedTab(tab.record.value)
        val next = TabOrder.nextActiveAfterClose(strip, id)
        val (active, split) = _stage.value
        BerthLog.i(LOG_TAG, "[${tab.host.name}] ${if (tab.kind == TabKind.Ssh) "terminal" else "Files"} tab closed")
        tab.close()
        _sessions.update { it - id }
        _filesTabs.update { it - id }
        _closedTabs.tryEmit(id)
        trackers.remove(id)?.cancel()
        savedVersions.remove(id)
        notifier.cancelFor(id)
        val wasActive = active == id
        if (split != null && (wasActive || split.companionId == id)) {
            synchronized(stageLock) { _stage.update { it.copy(split = null) } }
            if (wasActive) setActive(split.companionId)
        } else if (wasActive) setActive(next)
        scope.launch { sessionRepository.delete(id) }
        return closed
    }

    /** Long-press menu › Close others: every tab in the strip except [id]. */
    fun closeOthers(id: String) {
        stripNow().filter { it.id != id }.forEach { close(it.id) }
        if (activeTabId.value != id) setActive(id)
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

    /**
     * The snapshot walks the whole scrollback under the emulator lock, so it runs on the manager's
     * scope with the write, never on the thread that called (the main thread, for ON_STOP and a
     * trim); the lock makes it consistent whenever it runs, and the write was asynchronous anyway.
     */
    private fun saveFrame(session: TerminalSession) {
        savedVersions[session.id] = session.screenVersion.value
        session.markLive()
        // A Tunnels tab shows no screen, so there is nothing to keep; the date above is all it needs.
        if (session.tunnelsOnly) return
        scope.launch { sessionRepository.saveFrame(session.id, session.snapshotFrame()) }
    }

    /**
     * The crash path's save ([CrashReporter.addCrashHook]): every open terminal's frame
     * snapshotted and written here, on the calling thread, so the relaunch shows what each tab
     * showed at the crash rather than at the last background save. Bounded, since the process
     * ends right after; a write not through by then is lost, which is no worse than before.
     */
    internal fun saveAllFramesNow(timeoutMs: Long = CrashReporter.HOOK_TIMEOUT_MS) {
        val open = _sessions.value.values.filter { it.state != SessionState.CLOSED }
        if (open.isEmpty()) return
        BerthLog.i(LOG_TAG, "saving ${open.size} frames before the process ends")
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                for (session in open) {
                    runCatching { sessionRepository.saveFrame(session.id, session.snapshotFrame()) }
                }
            }
        }
    }

    override fun detachAll() = _sessions.value.keys.toList().forEach { detach(it) }

    companion object {
        private const val LOG_TAG = "Sessions"

        /** How often frames are re-saved while live sessions run in the background. */
        const val BACKGROUND_SAVE_MS = 30_000L

        /** How many hops deep a jump chain is followed through the hops' own chains before it is cut. */
        const val MAX_JUMP_DEPTH = 8

        fun openAppIntent(context: Context): Intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    }
}

/**
 * A read-only [StateFlow] of [transform] over this flow: its value is the source's, transformed,
 * at the moment it is read, so a caller that has just written the source reads its own write back
 * as it would from the source itself; collectors get the current result and then each distinct
 * one as the source moves. `map` + `stateIn` would give a value a step behind the write, on the
 * scope's dispatcher, and the strip, the panes and the tests all read the active id right after
 * staging a tab.
 */
private fun <T, R> StateFlow<T>.view(transform: (T) -> R): StateFlow<R> = object : StateFlow<R> {
    override val value: R get() = transform(this@view.value)
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var last: Any? = Unread
        this@view.collect { source ->
            val next = transform(source)
            if (last === Unread || next != last) {
                last = next
                collector.emit(next)
            }
        }
    }
}

/** What a [view]'s collector has emitted before its first value: distinct from any value, null included. */
private object Unread
