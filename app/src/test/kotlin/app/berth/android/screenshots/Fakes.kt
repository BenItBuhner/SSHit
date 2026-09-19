package app.berth.android.screenshots

import android.content.Context
import app.berth.android.session.AuthResolver
import app.berth.android.session.NetworkMonitor
import app.berth.android.session.PromptCenter
import app.berth.android.session.SessionManager
import app.berth.android.ui.AppViewModel
import app.berth.data.crypto.HardwareKeys
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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
    override suspend fun delete(id: String) { items.update { list -> list.filter { it.id != id } }; frames.remove(id) }
    override suspend fun saveFrame(sessionId: String, frame: ByteArray) { frames[sessionId] = frame }
    override suspend fun loadFrame(sessionId: String): ByteArray? = frames[sessionId]
}

class InMemorySettings : SettingsRepository {
    private val deck = MutableStateFlow(DeckLayout.default())
    private val theme = MutableStateFlow(InterfaceTheme.DEFAULT)
    private val font = MutableStateFlow(TerminalFont())
    private val themes = MutableStateFlow(TerminalTheme.builtIns)
    private val defaultTheme = MutableStateFlow(TerminalTheme.BERTH_DARK_ID)
    private val lastActive = MutableStateFlow<String?>(null)
    private val currentWorkspace = MutableStateFlow<String?>(null)

    override val deckLayout: Flow<DeckLayout> = deck
    override suspend fun setDeckLayout(layout: DeckLayout) { deck.value = layout }
    override val interfaceTheme: Flow<InterfaceTheme> = theme
    override suspend fun setInterfaceTheme(theme: InterfaceTheme) { this.theme.value = theme }
    override val terminalFont: Flow<TerminalFont> = font
    override suspend fun setTerminalFont(font: TerminalFont) { this.font.value = font }
    override val terminalThemes: Flow<List<TerminalTheme>> = themes
    override val defaultTerminalThemeId: Flow<String> = defaultTheme
    override suspend fun upsertTerminalTheme(theme: TerminalTheme) = themes.update { list -> list.filter { it.id != theme.id } + theme }
    override suspend fun setDefaultTerminalTheme(id: String) { defaultTheme.value = id }
    override val lastActiveSessionId: Flow<String?> = lastActive
    override suspend fun setLastActiveSessionId(id: String?) { lastActive.value = id }
    override val currentWorkspaceId: Flow<String?> = currentWorkspace
    override suspend fun setCurrentWorkspaceId(id: String) { currentWorkspace.value = id }
}

/**
 * The production object graph with in-memory storage, the way Hilt would wire it on a device.
 * [sessions] and [viewModel] are created on first use so seeded records exist before the manager
 * restores them.
 */
class TestGraph(private val context: Context) {
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
    val authResolver = AuthResolver(identities, secrets, hardwareKeys, prompts)
    val sessions: SessionManager by lazy {
        SessionManager(context, sessionRecords, workspaces, hosts, knownHosts, settings, authResolver, prompts, NetworkMonitor(context), tunnels, snippets)
    }
    val viewModel: AppViewModel by lazy {
        AppViewModel(sessions, hosts, identities, knownHosts, settings, secrets, hardwareKeys, prompts, tunnels, snippets)
    }
}
