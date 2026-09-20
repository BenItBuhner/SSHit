package app.berth.android.screenshots

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.berth.android.files.FilesCenter
import app.berth.android.security.AppLockController
import app.berth.android.security.BerthClipboard
import app.berth.android.security.FakeAuthenticator
import app.berth.android.security.FakeClock
import app.berth.android.security.ForegroundActivity
import app.berth.android.security.KeyUnlocker
import app.berth.android.security.RemoteClipboardGate
import app.berth.android.security.SecurityCenter
import app.berth.android.session.AuthResolver
import app.berth.android.session.NetworkMonitor
import app.berth.android.session.PromptCenter
import app.berth.android.session.SessionManager
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.AppViewModel
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.KeystoreSigning
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.Workspace
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SessionRepository
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.robolectric.Shadows.shadowOf

/** In-memory repositories so screens render against the real view model without Room or Keystore. */
class InMemoryHosts : HostRepository {
    val items = MutableStateFlow<List<Host>>(emptyList())
    override fun observeAll(): Flow<List<Host>> = items
    override fun observe(id: String): Flow<Host?> = items.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun get(id: String): Host? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(host: Host) = items.update { list -> list.filter { it.id != host.id } + host }
    override suspend fun delete(id: String) = items.update { list -> list.filter { it.id != id } }
    override suspend fun markConnected(id: String, at: Long) =
        items.update { list -> list.map { if (it.id == id) it.copy(lastConnectedAt = at) else it } }
}

class InMemorySecrets : SecretStore {
    private val map = HashMap<String, ByteArray>()
    override suspend fun put(id: String, secret: ByteArray) { map[id] = secret }
    override suspend fun get(id: String): ByteArray? = map[id]
    override suspend fun delete(id: String) { map.remove(id) }
}

class InMemoryIdentities(private val hosts: InMemoryHosts) : IdentityRepository {
    val items = MutableStateFlow<List<Identity>>(emptyList())
    private val keys = HashMap<String, ByteArray>()
    override fun observeAll(): Flow<List<Identity>> = items
    override suspend fun get(id: String): Identity? = items.value.firstOrNull { it.id == id }
    override suspend fun insert(identity: Identity, privateKeyOpenSsh: ByteArray?) {
        items.update { it + identity }
        if (privateKeyOpenSsh != null) keys[identity.id] = privateKeyOpenSsh
    }
    override suspend fun update(identity: Identity) = items.update { list -> list.map { if (it.id == identity.id) identity else it } }
    override suspend fun delete(id: String) { items.update { list -> list.filter { it.id != id } }; keys.remove(id) }
    override suspend fun privateKey(id: String): ByteArray? = keys[id]
    override suspend fun hostsUsing(id: String): List<Host> =
        hosts.items.value.filter { (it.auth as? app.berth.domain.model.AuthMethod.Key)?.identityId == id }
}

class InMemoryKnownHosts : KnownHostRepository {
    val items = MutableStateFlow<List<KnownHostKey>>(emptyList())
    override fun observeAll(): Flow<List<KnownHostKey>> = items
    override suspend fun find(host: String, port: Int): List<KnownHostKey> = items.value.filter { it.host == host && it.port == port }
    override suspend fun upsert(key: KnownHostKey) = items.update { list -> list.filter { it.id != key.id } + key }
    override suspend fun delete(id: String) = items.update { list -> list.filter { it.id != id } }
    override suspend fun setPinned(id: String, pinned: Boolean) =
        items.update { list -> list.map { if (it.id == id) it.copy(pinned = pinned) else it } }
}

class InMemoryTunnels : TunnelRepository {
    val items = MutableStateFlow<List<Tunnel>>(emptyList())
    override fun observeAll(): Flow<List<Tunnel>> = items
    override fun observeForHost(hostId: String): Flow<List<Tunnel>> = items.map { list -> list.filter { it.hostId == hostId } }
    override suspend fun get(id: String): Tunnel? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(tunnel: Tunnel) = items.update { list -> list.filter { it.id != tunnel.id } + tunnel }
    override suspend fun delete(id: String) = items.update { list -> list.filter { it.id != id } }
    override suspend fun setEnabled(id: String, enabled: Boolean) =
        items.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
}

class InMemorySnippets : SnippetRepository {
    val items = MutableStateFlow<List<Snippet>>(emptyList())
    override fun observeAll(): Flow<List<Snippet>> = items
    override suspend fun get(id: String): Snippet? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(snippet: Snippet) = items.update { list -> list.filter { it.id != snippet.id } + snippet }
    override suspend fun delete(id: String) = items.update { list -> list.filter { it.id != id } }
}

class InMemoryWorkspaces : WorkspaceRepository {
    val items = MutableStateFlow<List<Workspace>>(emptyList())
    override fun observeAll(): Flow<List<Workspace>> = items.map { list -> list.sortedBy { it.sortOrder } }
    override suspend fun get(id: String): Workspace? = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(workspace: Workspace) = items.update { list -> list.filter { it.id != workspace.id } + workspace }
    override suspend fun upsertAll(workspaces: List<Workspace>) {
        val ids = workspaces.map { it.id }.toSet()
        items.update { list -> list.filter { it.id !in ids } + workspaces }
    }
    override suspend fun delete(id: String) = items.update { list -> list.filter { it.id != id } }
    override suspend fun ensureDefault(): Workspace {
        items.value.firstOrNull { it.id == Workspace.DEFAULT_ID }?.let { return it }
        val ws = Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = 0L)
        upsert(ws)
        return ws
    }
}

class InMemorySessions : SessionRepository {
    val items = MutableStateFlow<List<SessionRecord>>(emptyList())
    val frames = HashMap<String, ByteArray>()
    override fun observeAll(): Flow<List<SessionRecord>> = items.map { list -> list.sortedBy { it.sortOrder } }
    override suspend fun getAll(): List<SessionRecord> = items.value.sortedBy { it.sortOrder }
    override suspend fun upsert(record: SessionRecord) = items.update { list -> list.filter { it.id != record.id } + record }
    override suspend fun upsertAll(records: List<SessionRecord>) {
        val ids = records.map { it.id }.toSet()
        items.update { list -> list.filter { it.id !in ids } + records }
    }
    override suspend fun delete(id: String) { items.update { list -> list.filter { it.id != id } }; frames.remove(id) }
    override suspend fun saveFrame(sessionId: String, frame: ByteArray) { frames[sessionId] = frame }
    override suspend fun loadFrame(sessionId: String): ByteArray? = frames[sessionId]
}

class InMemorySettings : SettingsRepository {
    private val deck = MutableStateFlow(DeckLayout.default())
    private val haptics = MutableStateFlow(HapticLevel.FULL)
    private val theme = MutableStateFlow(InterfaceTheme.DEFAULT)
    private val font = MutableStateFlow(TerminalFont())
    private val themes = MutableStateFlow(TerminalTheme.builtIns)
    private val defaultTheme = MutableStateFlow(TerminalTheme.BERTH_DARK_ID)
    private val lastActive = MutableStateFlow<String?>(null)
    private val currentWorkspace = MutableStateFlow<String?>(null)
    private val tabSwipe = MutableStateFlow(TabSwipeGesture.TWO_FINGER)
    private val ctrlTabKeys = MutableStateFlow(false)

    override val deckLayout: Flow<DeckLayout> = deck
    override suspend fun setDeckLayout(layout: DeckLayout) { deck.value = layout }
    override val hapticLevel: Flow<HapticLevel> = haptics
    override suspend fun setHapticLevel(level: HapticLevel) { haptics.value = level }
    override val interfaceTheme: Flow<InterfaceTheme> = theme
    override suspend fun setInterfaceTheme(theme: InterfaceTheme) { this.theme.value = theme }
    override val terminalFont: Flow<TerminalFont> = font
    override suspend fun setTerminalFont(font: TerminalFont) { this.font.value = font }
    override val terminalThemes: Flow<List<TerminalTheme>> = themes
    override val defaultTerminalThemeId: Flow<String> = defaultTheme
    override suspend fun upsertTerminalTheme(theme: TerminalTheme) = themes.update { list -> list.filter { it.id != theme.id } + theme.copy(builtIn = false) }
    override suspend fun deleteTerminalTheme(id: String) = themes.update { list -> list.filter { it.id != id || it.builtIn } }
    override suspend fun setDefaultTerminalTheme(id: String) { defaultTheme.value = id }
    override val lastActiveSessionId: Flow<String?> = lastActive
    override suspend fun setLastActiveSessionId(id: String?) { lastActive.value = id }
    override val currentWorkspaceId: Flow<String?> = currentWorkspace
    override suspend fun setCurrentWorkspaceId(id: String) { currentWorkspace.value = id }
    private val files = MutableStateFlow(FilesPrefs())
    override val filesPrefs: Flow<FilesPrefs> = files
    override suspend fun setFilesPrefs(prefs: FilesPrefs) { files.value = prefs }

    override val tabSwipeGesture: Flow<TabSwipeGesture> = tabSwipe
    override suspend fun setTabSwipeGesture(gesture: TabSwipeGesture) { tabSwipe.value = gesture }
    override val ctrlTabKeysReachTerminal: Flow<Boolean> = ctrlTabKeys
    override suspend fun setCtrlTabKeysReachTerminal(enabled: Boolean) { ctrlTabKeys.value = enabled }

    val security = MutableStateFlow(SecuritySettings())
    override val securitySettings: Flow<SecuritySettings> = security
    override suspend fun updateSecuritySettings(change: (SecuritySettings) -> SecuritySettings) = security.update(change)
}

/**
 * Stands in for [androidx.lifecycle.ProcessLifecycleOwner]: tests move the app on and off the
 * screen with [start] and [stop] and the manager reacts as it would to the real process.
 */
class FakeLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** An activity reached the screen ([Lifecycle.Event.ON_START]). */
    fun start() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

    /** The last activity left the screen ([Lifecycle.Event.ON_STOP]). */
    fun stop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
}

/**
 * The production object graph with in-memory storage, the way Hilt would wire it on a device.
 * [sessions] and [viewModel] are created on first use so seeded records exist before the manager
 * restores them. [process] is the process lifecycle the manager watches; it starts created, off
 * screen, so a test decides when the app is in front.
 *
 * Robolectric's application holds no runtime permissions, so by default the graph grants
 * POST_NOTIFICATIONS first: the phone of a user who has already answered the ask, which is what
 * every screen test that is not about the ask should see (otherwise the process's first Live
 * raises the rationale sheet over whatever the test is looking at). A test of the permission
 * flow itself passes [notificationsGranted] false.
 */
class TestGraph(private val context: Context, notificationsGranted: Boolean = true) {
    init {
        if (notificationsGranted) {
            shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val hosts = InMemoryHosts()
    val secrets = InMemorySecrets()
    val identities = InMemoryIdentities(hosts)
    val knownHosts = InMemoryKnownHosts()
    val workspaces = InMemoryWorkspaces()
    val sessionRecords = InMemorySessions()
    val settings = InMemorySettings()
    val tunnels = InMemoryTunnels()
    val snippets = InMemorySnippets()
    val prompts = PromptCenter()
    val hardwareKeys = HardwareKeys(context)

    /** The security pieces with a scripted prompt and a hand-stepped clock; [keystore] stands in for Android Keystore. */
    val clock = FakeClock()
    val authenticator = FakeAuthenticator()
    val foreground = ForegroundActivity()
    val appLock = AppLockController(settings, clock, authenticator, scope)
    val clipboard = BerthClipboard(context, settings, clock, scope)
    val remoteClipboard = RemoteClipboardGate(settings, clipboard, scope)
    val security = SecurityCenter(settings, appLock, clipboard, remoteClipboard, authenticator, foreground, scope)
    var keystore: KeystoreSigning = hardwareKeys
    val keyUnlocker: KeyUnlocker by lazy { KeyUnlocker(keystore, identities, authenticator, prompts, appLock) }
    val authResolver: AuthResolver by lazy { AuthResolver(identities, secrets, keyUnlocker, prompts) }
    val process = FakeLifecycleOwner()
    val notifier = SessionNotifier(context)
    private val manager = lazy {
        SessionManager(context, sessionRecords, workspaces, hosts, knownHosts, settings, authResolver, prompts, NetworkMonitor(context), tunnels, snippets, remoteClipboard, appLock, notifier, process.lifecycle)
    }
    val sessions: SessionManager by manager
    val files: FilesCenter by lazy { FilesCenter(context, sessions, settings) }
    val viewModel: AppViewModel by lazy {
        AppViewModel(sessions, hosts, identities, knownHosts, settings, secrets, hardwareKeys, prompts, tunnels, snippets, workspaces, files, security)
    }

    private companion object {
        /** Unconfined, so the settings land in the security pieces before the first frame, as Room's do behind the splash. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    /**
     * Ends what a test left open, whether it passed or not: every tab (a live one closes its
     * socket), then the manager's coroutines, so nothing of one test runs under the next.
     */
    fun close() {
        if (!manager.isInitialized()) return
        val sessions = manager.value
        sessions.sessions.value.map { it.id }.forEach { sessions.close(it) }
        sessions.scope.cancel()
    }
}
