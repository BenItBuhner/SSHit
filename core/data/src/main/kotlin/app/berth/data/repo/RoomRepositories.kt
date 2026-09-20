package app.berth.data.repo

import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.SecretCrypto
import app.berth.data.db.BerthDatabase
import app.berth.data.db.PreferenceEntity
import app.berth.data.db.SecretEntity
import app.berth.data.db.SessionFrameEntity
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyStorage
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class RoomHostRepository(private val db: BerthDatabase) : HostRepository {
    override fun observeAll(): Flow<List<Host>> = db.hosts().observeAll().map { list -> list.map { it.toDomain() } }
    override fun observe(id: String): Flow<Host?> = db.hosts().observe(id).map { it?.toDomain() }
    override suspend fun get(id: String): Host? = db.hosts().get(id)?.toDomain()
    override suspend fun upsert(host: Host) = db.hosts().upsert(host.toEntity())
    override suspend fun delete(id: String) = db.hosts().delete(id)
    override suspend fun markConnected(id: String, at: Long) = db.hosts().markConnected(id, at)
}

class EncryptedSecretStore(private val db: BerthDatabase, private val crypto: SecretCrypto) : SecretStore {
    override suspend fun put(id: String, secret: ByteArray) =
        db.secrets().upsert(SecretEntity(id = id, blob = crypto.encrypt(secret), updatedAt = System.currentTimeMillis()))

    override suspend fun get(id: String): ByteArray? = db.secrets().get(id)?.let { crypto.decrypt(it.blob) }

    override suspend fun delete(id: String) = db.secrets().delete(id)
}

class RoomIdentityRepository(
    private val db: BerthDatabase,
    private val secrets: SecretStore,
    private val hardwareKeys: HardwareKeys,
) : IdentityRepository {
    override fun observeAll(): Flow<List<Identity>> = db.identities().observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: String): Identity? = db.identities().get(id)?.toDomain()

    override suspend fun insert(identity: Identity, privateKeyOpenSsh: ByteArray?) {
        if (identity.storage == KeyStorage.SOFTWARE_ENCRYPTED) {
            requireNotNull(privateKeyOpenSsh) { "software identities need private key material" }
            secrets.put(secretId(identity.id), privateKeyOpenSsh)
        }
        db.identities().insert(identity.toEntity())
    }

    override suspend fun update(identity: Identity) = db.identities().upsert(identity.toEntity())

    override suspend fun delete(id: String) {
        val existing = db.identities().get(id)?.toDomain()
        db.identities().delete(id)
        secrets.delete(secretId(id))
        existing?.keystoreAlias?.let { runCatching { hardwareKeys.delete(it) } }
    }

    override suspend fun privateKey(id: String): ByteArray? = secrets.get(secretId(id))

    override suspend fun hostsUsing(id: String): List<Host> =
        db.hosts().findByAuthContaining("%\"$id\"%").map { it.toDomain() }
            .filter { (it.auth as? AuthMethod.Key)?.identityId == id }

    companion object {
        fun secretId(identityId: String) = "identity:$identityId"
    }
}

class RoomKnownHostRepository(private val db: BerthDatabase) : KnownHostRepository {
    override fun observeAll(): Flow<List<KnownHostKey>> = db.knownHosts().observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun find(host: String, port: Int): List<KnownHostKey> = db.knownHosts().find(host, port).map { it.toDomain() }
    override suspend fun upsert(key: KnownHostKey) = db.knownHosts().upsert(key.toEntity())
    override suspend fun delete(id: String) = db.knownHosts().delete(id)
    override suspend fun setPinned(id: String, pinned: Boolean) = db.knownHosts().setPinned(id, pinned)
}

class RoomWorkspaceRepository(private val db: BerthDatabase) : WorkspaceRepository {
    override fun observeAll(): Flow<List<Workspace>> = db.workspaces().observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: String): Workspace? = db.workspaces().get(id)?.toDomain()
    override suspend fun upsert(workspace: Workspace) = db.workspaces().upsert(workspace.toEntity())
    override suspend fun upsertAll(workspaces: List<Workspace>) {
        if (workspaces.isNotEmpty()) db.workspaces().upsertAll(workspaces.map { it.toEntity() })
    }
    override suspend fun delete(id: String) = db.workspaces().delete(id)

    override suspend fun ensureDefault(): Workspace {
        db.workspaces().getAll().firstOrNull()?.let { return it.toDomain() }
        val workspace = Workspace(
            id = Workspace.DEFAULT_ID,
            name = Workspace.DEFAULT_NAME,
            color = SwatchColor.COPPER,
            monogram = Host.monogramFor(Workspace.DEFAULT_NAME),
            createdAt = System.currentTimeMillis(),
        )
        db.workspaces().upsert(workspace.toEntity())
        return workspace
    }
}

class RoomSessionRepository(private val db: BerthDatabase) : SessionRepository {
    override fun observeAll(): Flow<List<SessionRecord>> = db.sessions().observeAll().map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun getAll(): List<SessionRecord> = db.sessions().getAll().mapNotNull { it.toDomain() }
    override suspend fun upsert(record: SessionRecord) = db.sessions().upsert(record.toEntity())
    override suspend fun upsertAll(records: List<SessionRecord>) {
        if (records.isNotEmpty()) db.sessions().upsertAll(records.map { it.toEntity() })
    }

    override suspend fun delete(id: String) {
        db.sessions().delete(id)
        db.sessions().deleteFrame(id)
    }

    override suspend fun saveFrame(sessionId: String, frame: ByteArray) =
        db.sessions().upsertFrame(SessionFrameEntity(sessionId, frame, System.currentTimeMillis()))

    override suspend fun loadFrame(sessionId: String): ByteArray? = db.sessions().frame(sessionId)?.frame
}

class RoomTunnelRepository(private val db: BerthDatabase) : TunnelRepository {
    override fun observeAll(): Flow<List<Tunnel>> = db.tunnels().observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeForHost(hostId: String): Flow<List<Tunnel>> = db.tunnels().observeForHost(hostId).map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: String): Tunnel? = db.tunnels().get(id)?.toDomain()
    override suspend fun upsert(tunnel: Tunnel) = db.tunnels().upsert(tunnel.toEntity())
    override suspend fun delete(id: String) = db.tunnels().delete(id)
    override suspend fun setEnabled(id: String, enabled: Boolean) = db.tunnels().setEnabled(id, enabled)
}

class RoomSnippetRepository(private val db: BerthDatabase) : SnippetRepository {
    override fun observeAll(): Flow<List<Snippet>> = db.snippets().observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: String): Snippet? = db.snippets().get(id)?.toDomain()
    override suspend fun upsert(snippet: Snippet) = db.snippets().upsert(snippet.toEntity())
    override suspend fun delete(id: String) = db.snippets().delete(id)
}

class RoomSettingsRepository(private val db: BerthDatabase) : SettingsRepository {
    private fun <T> document(key: String, serializer: KSerializer<T>, default: () -> T): Flow<T> =
        db.preferences().observe(key).map { raw ->
            raw?.let { runCatching { dataJson.decodeFromString(serializer, it) }.getOrNull() } ?: default()
        }

    private suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) =
        db.preferences().upsert(PreferenceEntity(key, dataJson.encodeToString(serializer, value), System.currentTimeMillis()))

    override val deckLayout: Flow<DeckLayout> = document(KEY_DECK, DeckLayout.serializer()) { DeckLayout.default() }
    override suspend fun setDeckLayout(layout: DeckLayout) = write(KEY_DECK, DeckLayout.serializer(), layout)

    override val hapticLevel: Flow<HapticLevel> = document(KEY_HAPTIC_LEVEL, HapticLevel.serializer()) { HapticLevel.FULL }
    override suspend fun setHapticLevel(level: HapticLevel) = write(KEY_HAPTIC_LEVEL, HapticLevel.serializer(), level)

    override val interfaceTheme: Flow<InterfaceTheme> = document(KEY_INTERFACE_THEME, InterfaceTheme.serializer()) { InterfaceTheme.DEFAULT }
    override suspend fun setInterfaceTheme(theme: InterfaceTheme) = write(KEY_INTERFACE_THEME, InterfaceTheme.serializer(), theme)

    override val terminalFont: Flow<TerminalFont> = document(KEY_TERMINAL_FONT, TerminalFont.serializer()) { TerminalFont() }
    override suspend fun setTerminalFont(font: TerminalFont) = write(KEY_TERMINAL_FONT, TerminalFont.serializer(), font)

    private val customThemes: Flow<List<TerminalTheme>> =
        document(KEY_CUSTOM_THEMES, ListSerializer(TerminalTheme.serializer())) { emptyList() }

    override val terminalThemes: Flow<List<TerminalTheme>> = customThemes.map { custom ->
        TerminalTheme.builtIns + custom.filter { c -> TerminalTheme.builtIns.none { it.id == c.id } }
    }

    override val defaultTerminalThemeId: Flow<String> =
        document(KEY_DEFAULT_THEME, String.serializer()) { TerminalTheme.BERTH_DARK_ID }

    private suspend fun customThemesNow(): List<TerminalTheme> {
        val serializer = ListSerializer(TerminalTheme.serializer())
        return db.preferences().get(KEY_CUSTOM_THEMES)?.let { runCatching { dataJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    }

    override suspend fun upsertTerminalTheme(theme: TerminalTheme) {
        val current = customThemesNow()
        write(KEY_CUSTOM_THEMES, ListSerializer(TerminalTheme.serializer()), current.filter { it.id != theme.id } + theme.copy(builtIn = false))
    }

    override suspend fun deleteTerminalTheme(id: String) {
        if (TerminalTheme.builtIns.any { it.id == id }) return
        write(KEY_CUSTOM_THEMES, ListSerializer(TerminalTheme.serializer()), customThemesNow().filter { it.id != id })
    }

    override suspend fun setDefaultTerminalTheme(id: String) = write(KEY_DEFAULT_THEME, String.serializer(), id)

    override val lastActiveSessionId: Flow<String?> = db.preferences().observe(KEY_LAST_SESSION)
    override suspend fun setLastActiveSessionId(id: String?) {
        if (id == null) db.preferences().delete(KEY_LAST_SESSION)
        else db.preferences().upsert(PreferenceEntity(KEY_LAST_SESSION, id, System.currentTimeMillis()))
    }

    override val currentWorkspaceId: Flow<String?> = db.preferences().observe(KEY_CURRENT_WORKSPACE)
    override suspend fun setCurrentWorkspaceId(id: String) =
        db.preferences().upsert(PreferenceEntity(KEY_CURRENT_WORKSPACE, id, System.currentTimeMillis()))

    override val filesPrefs: Flow<FilesPrefs> = document(KEY_FILES, FilesPrefs.serializer()) { FilesPrefs() }
    override suspend fun setFilesPrefs(prefs: FilesPrefs) = write(KEY_FILES, FilesPrefs.serializer(), prefs)

    override val tabSwipeGesture: Flow<TabSwipeGesture> = document(KEY_TAB_SWIPE, TabSwipeGesture.serializer()) { TabSwipeGesture.TWO_FINGER }
    override suspend fun setTabSwipeGesture(gesture: TabSwipeGesture) = write(KEY_TAB_SWIPE, TabSwipeGesture.serializer(), gesture)

    override val ctrlTabKeysReachTerminal: Flow<Boolean> = document(KEY_CTRL_TAB_KEYS_TERMINAL, Boolean.serializer()) { false }
    override suspend fun setCtrlTabKeysReachTerminal(enabled: Boolean) = write(KEY_CTRL_TAB_KEYS_TERMINAL, Boolean.serializer(), enabled)

    private val securityLock = Mutex()
    override val securitySettings: Flow<SecuritySettings> = document(KEY_SECURITY, SecuritySettings.serializer()) { SecuritySettings() }
    override suspend fun updateSecuritySettings(change: (SecuritySettings) -> SecuritySettings) = securityLock.withLock {
        val current = db.preferences().get(KEY_SECURITY)?.let { runCatching { dataJson.decodeFromString(SecuritySettings.serializer(), it) }.getOrNull() } ?: SecuritySettings()
        write(KEY_SECURITY, SecuritySettings.serializer(), change(current))
    }

    companion object {
        const val KEY_FILES = "files_prefs"
        const val KEY_DECK = "deck_layout"
        const val KEY_HAPTIC_LEVEL = "haptic_level"
        const val KEY_INTERFACE_THEME = "interface_theme"
        const val KEY_TERMINAL_FONT = "terminal_font"
        const val KEY_CUSTOM_THEMES = "terminal_themes_custom"
        const val KEY_DEFAULT_THEME = "terminal_theme_default"
        const val KEY_LAST_SESSION = "last_active_session"
        const val KEY_CURRENT_WORKSPACE = "current_workspace"
        const val KEY_TAB_SWIPE = "tab_swipe_gesture"
        const val KEY_CTRL_TAB_KEYS_TERMINAL = "ctrl_tab_keys_reach_terminal"
        const val KEY_SECURITY = "security"
    }
}
