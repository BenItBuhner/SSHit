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

/** What [SessionManager.close] hands back so a snackbar can offer Reopen (spec C3, Closing). */
data class ClosedTab(val record: SessionRecord) {
    /** Only a tab that was connected earns the snackbar; detached tabs close quietly. */
    val wasActive: Boolean get() = record.state.isActive
}

/**
 * Owns every tab (one runtime session each) across groups, keeps the persisted records in step,
 * restores detached frames after process death, and runs the foreground service while anything is
 * active. Sessions are tabs (spec C3): this class also owns their order, their groups and which one
 * is active, and writes each change so the strip comes back the same after process death.
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
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())

    val workspaces: StateFlow<List<Workspace>> = workspaceRepository.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every open tab's record, in strip order: groups in their order, then positions within each group. */
    val records: StateFlow<List<SessionRecord>> = combine(
        _sessions.flatMapLatest { map ->
            if (map.isEmpty()) MutableStateFlow(emptyList()) else combine(map.values.map { it.record }) { it.toList() }
        },
        workspaces,
    ) { list, groups -> TabOrder.strip(list, groups) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** All tabs in strip order. */
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

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    val activeSession: StateFlow<TerminalSession?> = combine(_activeSessionId, _sessions) { id, map -> id?.let { map[it] } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * File transfers in flight, set by the transfer queue. They ride a live session, so they never
     * hold the service up on their own; the count only shapes the notification.
     */
    val activeTransfers = MutableStateFlow(0)

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
        override fun tunnelsFor(hostId: String): Flow<List<Tunnel>> = tunnelRepository.observeForHost(hostId)
        override suspend fun connectCommands(host: Host, workspaceId: String): List<String> =
            snippetRepository.observeAll().first()
                .filter { it.runOnConnect && it.hostId == host.id && (it.workspaceId == null || it.workspaceId == workspaceId) }
                .map { it.render() }
    }

    init {
        scope.launch { restore() }
        scope.launch {
            combine(
                records.map { list -> list.count { it.state.keepsService } },
                tunnelStatuses.map { statuses -> statuses.count { it.value is TunnelStatus.Up } },
                activeTransfers,
            ) { active, tunnels, transfers -> Triple(active, tunnels, transfers) }.distinctUntilChanged().collect { (active, tunnels, transfers) ->
                if (active > 0) SessionService.start(context, active, tunnels, transfers) else SessionService.stop(context)
            }
        }
        scope.launch {
            // Keep the stage flag on the session that is showing so attention is raised correctly.
            _activeSessionId.collect { id -> _sessions.value.values.forEach { it.onStage = it.id == id } }
        }
        scope.launch { records.collect { electCarriers(it) } }
    }

    /**
     * One session per host carries its tunnels. The current carrier keeps the role while Live;
     * otherwise the first Live session takes it, or the first connecting one so the forwards start
     * the moment it comes up. Sessions that lose the role release their ports first.
     */
    private fun electCarriers(records: List<SessionRecord>) {
        val byHost = records.filter { it.hostId != null && it.state.isActive }.groupBy { it.hostId!! }
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

    fun retryTunnel(id: String) = _sessions.value.values.forEach { it.retryTunnel(id) }

    /** Loads persisted tabs as detached frames, in their saved order. Safe to call more than once. */
    suspend fun restore() = restored.withLock {
        if (didRestore) return@withLock
        didRestore = true
        val defaultWorkspace = workspaceRepository.ensureDefault()
        val groups = workspaceRepository.observeAll().first()
        val map = LinkedHashMap<String, TerminalSession>()
        val restoredRecords = ArrayList<SessionRecord>()
        for (record in sessionRepository.getAll()) {
            if (record.state == SessionState.CLOSED) {
                sessionRepository.delete(record.id)
                continue
            }
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
            val session = TerminalSession(record, scope, environment) { sessionRepository.upsert(it) }
            sessionRepository.loadFrame(record.id)?.let { session.restoreFrame(it) }
            map[record.id] = session
        }
        _sessions.value = map
        val last = settings.lastActiveSessionId.first()?.takeIf { map.containsKey(it) }
        _activeSessionId.value = last
        _currentWorkspaceId.value = last?.let { map[it]?.record?.value?.workspaceId }
            ?: settings.currentWorkspaceId.first()?.takeIf { id -> groups.any { it.id == id } }
            ?: defaultWorkspace.id
        val reconnectWorkspaces = groups.filter { it.reconnectAtLaunch }.map { it.id }.toSet()
        map.values.filter { it.record.value.workspaceId in reconnectWorkspaces }.forEach { it.connect() }
    }

    /** The strip as it is right now, from the live records rather than the (asynchronous) flows. */
    private fun stripNow(): List<SessionRecord> = TabOrder.strip(_sessions.value.values.map { it.record.value }, workspaces.value)

    /** Applies placements computed by [TabOrder] to the live sessions and writes them in one transaction. */
    private fun applyPlacements(changes: List<SessionRecord>) {
        if (changes.isEmpty()) return
        val updated = changes.mapNotNull { change -> _sessions.value[change.id]?.place(change.workspaceId, change.sortOrder) }
        if (updated.isNotEmpty()) scope.launch { sessionRepository.upsertAll(updated) }
    }

    /**
     * Opens a new tab for [host] and puts it on stage. It lands directly after [afterId] (the active
     * tab by default) in that tab's group, or at the end of [workspaceId] when there is no anchor.
     */
    suspend fun open(host: Host, workspaceId: String? = null, afterId: String? = _activeSessionId.value, customTitle: String? = null): TerminalSession {
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
        val changes = TabOrder.insertAfter(stripNow(), fresh, afterId?.takeIf { it in _sessions.value }, group)
        return start(fresh, changes)
    }

    /** Recreates a closed tab in its old slot and connects it again (spec C3, Reopen). */
    suspend fun reopen(closed: ClosedTab): TerminalSession {
        restore()
        val old = closed.record
        val group = old.workspaceId.takeIf { id -> workspaces.value.any { it.id == id } } ?: workspaceRepository.ensureDefault().id
        val fresh = old.copy(
            id = UUID.randomUUID().toString(),
            workspaceId = group,
            state = SessionState.IDLE,
            layer = PersistenceLayer.IN_APP,
            title = old.hostSnapshot.name,
            cwd = null,
            lastCommand = null,
            needsAttention = false,
            attentionReason = null,
            createdAt = System.currentTimeMillis(),
            lastLiveAt = null,
            frameKey = null,
        )
        return start(fresh, TabOrder.insertAt(stripNow(), fresh, group, old.sortOrder))
    }

    /** Opens a second tab on the same host directly after [id], in its group (spec C3, Duplicate). */
    suspend fun duplicate(id: String): TerminalSession? {
        val source = _sessions.value[id]?.record?.value ?: return null
        return open(source.hostSnapshot, workspaceId = source.workspaceId, afterId = id)
    }

    private suspend fun start(fresh: SessionRecord, changes: List<SessionRecord>): TerminalSession {
        val placed = changes.firstOrNull { it.id == fresh.id } ?: fresh
        val session = TerminalSession(placed, scope, environment) { sessionRepository.upsert(it) }
        _sessions.update { it + (placed.id to session) }
        val shifted = changes.filter { it.id != fresh.id }.mapNotNull { change -> _sessions.value[change.id]?.place(change.workspaceId, change.sortOrder) }
        sessionRepository.upsertAll(shifted + placed)
        placed.hostId?.let { hostRepository.markConnected(it, placed.createdAt) }
        setActive(placed.id)
        session.connect()
        return session
    }

    fun get(id: String): TerminalSession? = _sessions.value[id]

    /** Puts [id] on stage; the current group follows the active tab. */
    fun setActive(id: String?) {
        _activeSessionId.value = id
        val session = id?.let { _sessions.value[it] }
        session?.markSeen()
        session?.record?.value?.workspaceId?.let { group -> if (_currentWorkspaceId.value != group) setCurrentWorkspace(group, activate = false) }
        scope.launch { settings.setLastActiveSessionId(id) }
    }

    /** Ctrl+Tab and the swipe: the next (or previous) tab in strip order, wrapping, skipping collapsed groups. */
    fun stepActive(delta: Int) {
        val strip = stripNow()
        if (strip.isEmpty()) return
        val collapsed = workspaces.value.filter { it.collapsed }.map { it.id }.toSet()
        val active = _activeSessionId.value
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
     * Jumps to a group from the drawer: with [activate], the group's most recently active tab (or its
     * first) goes on stage; a group with no tabs just becomes the target for the next new tab.
     */
    fun setCurrentWorkspace(id: String, activate: Boolean = true) {
        _currentWorkspaceId.value = id
        scope.launch { settings.setCurrentWorkspaceId(id) }
        if (!activate) return
        val activeGroup = _activeSessionId.value?.let { _sessions.value[it]?.record?.value?.workspaceId }
        if (activeGroup == id) return
        stripNow().firstOrNull { it.workspaceId == id }?.let { setActive(it.id) }
    }

    // ---- reorder, rename, groups ------------------------------------------------------------------

    /** Drag and drop: [id] ends up at [toIndex] in strip order (see [TabOrder.move]). */
    fun moveTab(id: String, toIndex: Int, groupId: String? = null) = applyPlacements(TabOrder.move(stripNow(), id, toIndex, groupId))

    /** Long-press menu › Move to group: re-homes the tab at the end of [groupId]. */
    fun moveToGroup(id: String, groupId: String) {
        applyPlacements(TabOrder.moveToGroup(stripNow(), id, groupId))
        if (_activeSessionId.value == id) setCurrentWorkspace(groupId, activate = false)
    }

    fun rename(id: String, title: String?) = _sessions.value[id]?.rename(title)

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

    fun reconnect(id: String) = _sessions.value[id]?.reconnectNow()

    fun detach(id: String) {
        val session = _sessions.value[id] ?: return
        session.detach()
        scope.launch { sessionRepository.saveFrame(id, session.snapshotFrame()) }
    }

    /**
     * Closes a tab. The tab to its right becomes active, else the one to its left (Chrome order).
     * Returns what is needed to reopen it, or null when there was no such tab.
     */
    fun close(id: String): ClosedTab? {
        val session = _sessions.value[id] ?: return null
        val strip = stripNow()
        val closed = ClosedTab(session.record.value)
        val next = TabOrder.nextActiveAfterClose(strip, id)
        session.close()
        _sessions.update { it - id }
        if (_activeSessionId.value == id) setActive(next)
        scope.launch { sessionRepository.delete(id) }
        return closed
    }

    /** Long-press menu › Close others: every tab in the strip except [id]. */
    fun closeOthers(id: String) {
        stripNow().filter { it.id != id }.forEach { close(it.id) }
        if (_activeSessionId.value != id) setActive(id)
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
