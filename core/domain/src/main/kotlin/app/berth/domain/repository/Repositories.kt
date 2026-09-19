package app.berth.domain.repository

import app.berth.domain.model.DeckLayout
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Snippet
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.Workspace
import kotlinx.coroutines.flow.Flow

interface HostRepository {
    fun observeAll(): Flow<List<Host>>
    fun observe(id: String): Flow<Host?>
    suspend fun get(id: String): Host?
    suspend fun upsert(host: Host)
    suspend fun delete(id: String)
    suspend fun markConnected(id: String, at: Long)
}

/** Private key material and passwords, handed out only through this boundary. */
interface SecretStore {
    suspend fun put(id: String, secret: ByteArray)
    suspend fun get(id: String): ByteArray?
    suspend fun delete(id: String)
}

interface IdentityRepository {
    fun observeAll(): Flow<List<Identity>>
    suspend fun get(id: String): Identity?

    /** Stores the identity and, for software keys, its OpenSSH-format private key encrypted at rest. */
    suspend fun insert(identity: Identity, privateKeyOpenSsh: ByteArray?)
    suspend fun update(identity: Identity)
    suspend fun delete(id: String)

    /** Decrypted OpenSSH private key for a software identity; null for hardware keys. */
    suspend fun privateKey(id: String): ByteArray?

    /** Hosts referencing this identity, to block deletion with a list. */
    suspend fun hostsUsing(id: String): List<Host>
}

interface KnownHostRepository {
    fun observeAll(): Flow<List<KnownHostKey>>
    suspend fun find(host: String, port: Int): List<KnownHostKey>
    suspend fun upsert(key: KnownHostKey)
    suspend fun delete(id: String)
    suspend fun setPinned(id: String, pinned: Boolean)
}

interface WorkspaceRepository {
    fun observeAll(): Flow<List<Workspace>>
    suspend fun get(id: String): Workspace?
    suspend fun upsert(workspace: Workspace)

    /** Writes several groups at once, e.g. after reordering them. */
    suspend fun upsertAll(workspaces: List<Workspace>) = workspaces.forEach { upsert(it) }
    suspend fun delete(id: String)

    /** Creates the default workspace when none exists and returns it. */
    suspend fun ensureDefault(): Workspace
}

interface SessionRepository {
    fun observeAll(): Flow<List<SessionRecord>>
    suspend fun getAll(): List<SessionRecord>
    suspend fun upsert(record: SessionRecord)

    /** Writes several records at once, e.g. the tabs a reorder shifted. */
    suspend fun upsertAll(records: List<SessionRecord>) = records.forEach { upsert(it) }
    suspend fun delete(id: String)

    /** Saved terminal frame (scrollback plus screen) for session restore. */
    suspend fun saveFrame(sessionId: String, frame: ByteArray)
    suspend fun loadFrame(sessionId: String): ByteArray?
}

interface TunnelRepository {
    fun observeAll(): Flow<List<Tunnel>>
    fun observeForHost(hostId: String): Flow<List<Tunnel>>
    suspend fun get(id: String): Tunnel?
    suspend fun upsert(tunnel: Tunnel)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
}

interface SnippetRepository {
    fun observeAll(): Flow<List<Snippet>>
    suspend fun get(id: String): Snippet?
    suspend fun upsert(snippet: Snippet)
    suspend fun delete(id: String)
}

/** User preferences: Deck layout, themes, fonts, and small flags. */
interface SettingsRepository {
    val deckLayout: Flow<DeckLayout>
    suspend fun setDeckLayout(layout: DeckLayout)

    val hapticLevel: Flow<HapticLevel>
    suspend fun setHapticLevel(level: HapticLevel)

    val interfaceTheme: Flow<InterfaceTheme>
    suspend fun setInterfaceTheme(theme: InterfaceTheme)

    val terminalFont: Flow<TerminalFont>
    suspend fun setTerminalFont(font: TerminalFont)

    val terminalThemes: Flow<List<TerminalTheme>>
    val defaultTerminalThemeId: Flow<String>
    suspend fun upsertTerminalTheme(theme: TerminalTheme)

    /** Removes a custom theme; stock themes are left alone. */
    suspend fun deleteTerminalTheme(id: String)
    suspend fun setDefaultTerminalTheme(id: String)

    /** The active tab, written on every switch so it survives process death (spec C3, Persistence). */
    val lastActiveSessionId: Flow<String?>
    suspend fun setLastActiveSessionId(id: String?)

    val currentWorkspaceId: Flow<String?>
    suspend fun setCurrentWorkspaceId(id: String)

    val filesPrefs: Flow<FilesPrefs>
    suspend fun setFilesPrefs(prefs: FilesPrefs)

    /** How the terminal switches tabs by touch (spec C3, Switching). */
    val tabSwipeGesture: Flow<TabSwipeGesture>
    suspend fun setTabSwipeGesture(gesture: TabSwipeGesture)

    /** Ctrl+T and Ctrl+W go to the terminal as readline keys instead of opening and closing tabs. */
    val ctrlTabKeysReachTerminal: Flow<Boolean>
    suspend fun setCtrlTabKeysReachTerminal(enabled: Boolean)
}
