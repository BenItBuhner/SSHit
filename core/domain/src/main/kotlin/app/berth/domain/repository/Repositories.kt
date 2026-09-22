package app.berth.domain.repository

import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckSettings
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.Host
import app.berth.domain.model.HostCommand
import app.berth.domain.model.Identity
import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Snippet
import app.berth.domain.model.StageSplit
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalSettings
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

    /**
     * Writes a software identity's record and its OpenSSH private key together, for a key
     * re-encoded under a new protection: both land or neither does.
     */
    suspend fun replacePrivateKey(identity: Identity, privateKeyOpenSsh: ByteArray)

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

/**
 * The commands each host ran (spec C16): one history per host, shared by every tab on it, keyed
 * by [app.berth.domain.model.Host.commandHistoryKey] and capped at [CAP] entries a host, the
 * oldest going first. A command equal to the host's latest is not recorded twice in a row, the
 * way `HISTCONTROL=ignoredups` keeps a shell's own history readable.
 */
interface CommandHistoryRepository {
    /** [hostId]'s commands, oldest first. */
    fun observeForHost(hostId: String): Flow<List<HostCommand>>

    /** The newest [CAP] commands across every host, oldest first, for the sheet's All hosts view. */
    fun observeAll(): Flow<List<HostCommand>>

    /** Records [text] run on [hostId] at [at]; false when it was blank or repeats the host's latest entry. */
    suspend fun record(hostId: String, text: String, at: Long): Boolean

    /**
     * Entries an older build kept in a tab's frame, handed to the host's history once: an entry
     * already there with the same text and time is not added again, so restoring the same frame
     * twice (the process dying before the frame was saved without them) changes nothing.
     */
    suspend fun importEntries(hostId: String, entries: List<Pair<String, Long>>)

    /**
     * Moves every entry kept under [from] to [to], for a quick connect saved as a host: its history
     * was keyed `quick:user@host:port` ([app.berth.domain.model.Host.commandHistoryKey]) and goes
     * on under the saved host's id, so the sheet on the new host shows what was run before it had a
     * name. Entries [to] already holds stay, the merged history is oldest first and capped as one
     * host's; nothing is left under [from]. Two equal ids are nothing to do.
     */
    suspend fun rekey(from: String, to: String)

    suspend fun delete(id: Long)
    suspend fun clear(hostId: String)
    suspend fun clearAll()

    companion object {
        const val CAP = 2_000
    }
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

    /** The Stage split beside the active tab (spec C23), written as it changes; null while one tab has the Stage. */
    val stageSplit: Flow<StageSplit?>
    suspend fun setStageSplit(split: StageSplit?)

    /** Where the divider between the panes rests, as the left pane's share of the width; half until moved. */
    val paneDividerFraction: Flow<Float>
    suspend fun setPaneDividerFraction(fraction: Float)

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

    /** App lock, screenshot blocking, clipboard hygiene and the OSC 52 gate (spec C20, Security). */
    val securitySettings: Flow<SecuritySettings>

    /** Read-modify-write under one lock, so a host's override and a toggle flipped at the same moment both land. */
    suspend fun updateSecuritySettings(change: (SecuritySettings) -> SecuritySettings)

    /** The idle-detach policy and the two one-time notices about the background (spec C20, Connection; Part B). */
    val connectionSettings: Flow<ConnectionSettings>

    /** Read-modify-write under one lock, like [updateSecuritySettings]. */
    suspend fun updateConnectionSettings(change: (ConnectionSettings) -> ConnectionSettings)

    /** Whether sessions keep the commands they run for the History sheet (spec C16); on by default. */
    val commandHistoryEnabled: Flow<Boolean>
    suspend fun setCommandHistoryEnabled(enabled: Boolean)

    /** Alt key behaviour, per-host overrides and the compact Deck (spec C22, Settings › Hardware keyboard). */
    val hardwareKeyboardSettings: Flow<HardwareKeyboardSettings>

    /** Read-modify-write under one lock, like [updateSecuritySettings]. */
    suspend fun updateHardwareKeyboardSettings(change: (HardwareKeyboardSettings) -> HardwareKeyboardSettings)

    /** Scrollback size and the optional drag arrows (spec C20, Settings › Terminal and Gestures). */
    val terminalSettings: Flow<TerminalSettings>

    /** Read-modify-write under one lock, like [updateSecuritySettings]. */
    suspend fun updateTerminalSettings(change: (TerminalSettings) -> TerminalSettings)

    /** The Deck's gestures and its rows on a large screen (spec D2, C4; Settings › Deck and Gestures), a device's rather than the layout's. */
    val deckSettings: Flow<DeckSettings>
    suspend fun setDeckSettings(settings: DeckSettings)
}
