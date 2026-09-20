package app.berth.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.berth.android.files.FilesCenter
import app.berth.android.links.LinkInbox
import app.berth.android.security.SecurityCenter
import app.berth.android.session.AuthResolver
import app.berth.android.session.ClosedTab
import app.berth.android.session.FilesTab
import app.berth.android.session.ManagedTab
import app.berth.android.session.PromptCenter
import app.berth.android.session.SessionManager
import app.berth.android.session.SessionNotifier
import app.berth.android.session.TabSlot
import app.berth.android.session.TerminalSession
import app.berth.android.session.TunnelStatus
import android.os.Build
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Snippet
import app.berth.domain.model.SnippetAction
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import app.berth.ssh.SshConfigForward
import app.berth.ssh.SshConfigHost
import app.berth.ssh.SshConfigParseResult
import app.berth.ssh.SshConfigParser
import app.berth.ssh.SshKeys
import app.berth.ssh.SshLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** Activity-scoped state for every screen; screens stay stateless and talk to this. */
@HiltViewModel
class AppViewModel @Inject constructor(
    val sessions: SessionManager,
    private val hostRepository: HostRepository,
    private val identityRepository: IdentityRepository,
    private val knownHostRepository: KnownHostRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val hardwareKeys: HardwareKeys,
    val prompts: PromptCenter,
    private val tunnelRepository: TunnelRepository,
    private val snippetRepository: SnippetRepository,
    private val workspaceRepository: WorkspaceRepository,
    /** File browsers and the transfer queue; the Files screen talks to this directly. */
    val files: FilesCenter,
    /** App lock, clipboard hygiene, the OSC 52 gate and their settings (spec C20); Settings › Security talks to this directly. */
    val security: SecurityCenter,
    /** `ssh://` and `sftp://` links other apps hand to the activity; read here once the lock allows. */
    private val links: LinkInbox,
) : ViewModel() {
    init {
        viewModelScope.launch {
            links.links.collect { raw ->
                // Under the lock the user faces the lock screen; a link then would open a login behind
                // it. It waits for the unlock, as a notification's tap and the key prompts do.
                security.lock.awaitUnlocked()
                openLink(raw)
            }
        }
    }

    val hosts: StateFlow<List<Host>> = hostRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val identities: StateFlow<List<Identity>> = identityRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knownHosts: StateFlow<List<KnownHostKey>> = knownHostRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tunnels: StateFlow<List<Tunnel>> = tunnelRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val snippets: StateFlow<List<Snippet>> = snippetRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Live status of every carried tunnel, by tunnel id; a missing id means the tunnel is not running. */
    val tunnelStatuses: StateFlow<Map<String, TunnelStatus>> = sessions.tunnelStatuses

    val deckLayout: StateFlow<DeckLayout> = settings.deckLayout.stateIn(viewModelScope, SharingStarted.Eagerly, DeckLayout.default())
    val hapticLevel: StateFlow<HapticLevel> = settings.hapticLevel.stateIn(viewModelScope, SharingStarted.Eagerly, HapticLevel.FULL)
    val interfaceTheme: StateFlow<InterfaceTheme> = settings.interfaceTheme.stateIn(viewModelScope, SharingStarted.Eagerly, InterfaceTheme.DEFAULT)
    val terminalFont: StateFlow<TerminalFont> = settings.terminalFont.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalFont())
    val terminalThemes: StateFlow<List<TerminalTheme>> = settings.terminalThemes.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalTheme.builtIns)
    val defaultTerminalTheme: StateFlow<TerminalTheme> = combine(settings.terminalThemes, settings.defaultTerminalThemeId) { themes, id ->
        themes.firstOrNull { it.id == id } ?: TerminalTheme.BERTH_DARK
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalTheme.BERTH_DARK)

    /** Every tab's record in strip order (spec C3). */
    val records: StateFlow<List<SessionRecord>> = sessions.records
    val workspaces: StateFlow<List<Workspace>> = sessions.workspaces
    val currentWorkspaceId: StateFlow<String?> = sessions.currentWorkspaceId
    val activeTabId: StateFlow<String?> = sessions.activeTabId

    /** Whether the persisted tabs are loaded; before that the Stage shows nothing rather than an empty state it cannot vouch for. */
    val restored: StateFlow<Boolean> = sessions.restored

    /** The tab on stage, whatever it runs. */
    val activeTab: StateFlow<ManagedTab?> = sessions.activeTab

    /** The tab on stage when it is a terminal; null while a Files tab, or nothing, is showing. */
    val activeSession: StateFlow<TerminalSession?> = sessions.activeSession

    /** Every tab in strip order, terminals and Files tabs alike. */
    val tabs: StateFlow<List<ManagedTab>> = sessions.tabs

    /**
     * The strip's skeleton: each tab with its group, in strip order. Emits only when membership,
     * order or grouping change (state flows skip equal lists), so titles and states flowing through
     * [records] never rebuild the strip; each tab observes its own record for those.
     */
    val stripSlots: StateFlow<List<TabSlot>> = combine(sessions.records, sessions.tabs) { list, live ->
        val byId = live.associateBy { it.id }
        list.mapNotNull { r -> byId[r.id]?.let { TabSlot(it, r.workspaceId) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Ids of the tabs whose ring is lit; emits only when the set changes. */
    val attentionTabIds: StateFlow<Set<String>> = records.map { list -> list.filter { it.needsAttention }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Tabs of the current group in strip order; the Session sheet's secondary list. */
    val workspaceTabs: StateFlow<List<ManagedTab>> = combine(sessions.tabs, sessions.currentWorkspaceId) { list, ws ->
        list.filter { ws == null || it.record.value.workspaceId == ws }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Tabs other than the active one that need attention; drives the count tile's ring and the grip jump. */
    val attentionCount: StateFlow<Int> = combine(records, sessions.activeTabId) { list, active ->
        list.count { it.needsAttention && it.id != active }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** The notification permission's sheet or notice when one is due, and whether the shade is reachable (spec C21). */
    val notifier: SessionNotifier get() = sessions.notifier

    /** A tab a notification put on stage; the shell pops back to the Stage so it is seen. */
    val stageRequests: SharedFlow<String> = sessions.stageRequests

    val tabSwipeGesture: StateFlow<TabSwipeGesture> = settings.tabSwipeGesture.stateIn(viewModelScope, SharingStarted.Eagerly, TabSwipeGesture.TWO_FINGER)
    val ctrlTabKeysReachTerminal: StateFlow<Boolean> = settings.ctrlTabKeysReachTerminal.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val commandHistoryEnabled: StateFlow<Boolean> = settings.commandHistoryEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun themeFor(host: Host, workspaceId: String? = null): TerminalTheme =
        resolveTerminalTheme(terminalThemes.value, defaultTerminalTheme.value, host, workspaces.value.byId(workspaceId))

    fun fontFor(host: Host): TerminalFont {
        val base = terminalFont.value
        val size = host.appearance.fontSizeSp ?: return base
        return base.copy(sizeSp = size)
    }

    // ---- sessions --------------------------------------------------------------------------------

    /** Connect from the host list or the New tab sheet: a terminal, or the host's forwards alone when it is marked tunnels only. */
    fun open(host: Host, workspaceId: String? = null) {
        viewModelScope.launch { sessions.connect(host, workspaceId) }
    }

    /** A Tunnels tab on [host] (spec C14): its port forwards with no shell, whatever the host's toggle says. */
    fun openTunnels(host: Host, workspaceId: String? = null) {
        viewModelScope.launch { sessions.openTunnels(host, workspaceId) }
    }

    // ---- links -----------------------------------------------------------------------------------

    private val _linkOutcome = MutableStateFlow<LinkOutcome?>(null)

    /** What the last link came to, for the shell to act on and then [clearLinkOutcome]. */
    val linkOutcome: StateFlow<LinkOutcome?> = _linkOutcome.asStateFlow()

    fun clearLinkOutcome() {
        _linkOutcome.value = null
    }

    /**
     * An `ssh://` or `sftp://` link, or a bare `user@host:port` ([SshLink]). A saved host at the
     * link's address, port and user opens straight away, as the link asks ([openFromLink]), unless
     * the link carries forwards that host does not have: nothing a link asks for is saved or
     * started unseen, so those open the host's editor with the forwards as pending rows, and Save
     * there adds them and connects ([LinkOutcome.ConfirmForwards]). No such host sends the shell to
     * the editor prefilled from the link, where the same rows show, and saving there connects. A
     * link that cannot be read ends in a notice naming what was wrong, never in a crash.
     */
    suspend fun openLink(raw: String) {
        _linkOutcome.value = when (val result = SshLink.parse(raw)) {
            is SshLink.Result.Malformed -> LinkOutcome.Malformed(result.reason)
            is SshLink.Result.Parsed -> {
                val link = result.link
                val host = hostFor(link)
                when {
                    host == null -> LinkOutcome.NewHost(raw)
                    pendingForwards(link, tunnelsOf(host.id)).isNotEmpty() -> LinkOutcome.ConfirmForwards(host.id, raw)
                    else -> {
                        openFromLink(host, link)
                        LinkOutcome.Staged
                    }
                }
            }
        }
    }

    /**
     * The saved host a link names: the same address (case aside) and port, and the link's user when
     * it gives one. Of several, the one connected most recently, then the oldest saved.
     */
    suspend fun hostFor(link: SshLink): Host? = hostRepository.observeAll().first()
        .filter { it.address.equals(link.host, ignoreCase = true) && it.port == link.port && (link.user == null || it.user == link.user) }
        .maxWithOrNull(compareBy<Host> { it.lastConnectedAt ?: Long.MIN_VALUE }.thenByDescending { it.createdAt })

    /**
     * Opens what [link] asks for on [host]: Files at the link's folder for `sftp://`, the host's
     * forwards alone for a tunnels-only link, otherwise a terminal (or the forwards, when the host
     * itself is marked tunnels only). Saves nothing: forwards the link carries reach the host's
     * tunnels only through the editor's Save ([saveHostFromLink]).
     */
    suspend fun openFromLink(host: Host, link: SshLink) {
        when {
            link.scheme == SshLink.Scheme.SFTP -> sessions.openFilesForHost(host, folder = link.path)
            link.tunnelsOnly -> sessions.openTunnels(host)
            else -> sessions.connect(host)
        }
    }

    /**
     * The editor's Save for a host opened from a link, new or saved: stores the host, adds
     * [forwards] (the link's pending rows the user kept, already seen on screen) to its tunnels,
     * enabled so they start with the login the link asks for, then opens what the link asked for.
     */
    fun saveHostFromLink(host: Host, password: String?, link: SshLink, forwards: List<SshConfigForward>) {
        viewModelScope.launch {
            val saved = saveHostNow(host, password)
            for (fwd in pendingForwards(forwards, tunnelsOf(saved.id))) tunnelRepository.upsert(fwd.toTunnel(saved.id))
            openFromLink(saved, link)
            _linkOutcome.value = LinkOutcome.Staged
        }
    }

    /** The saved tunnels of the host [hostId], read once. */
    suspend fun tunnelsOf(hostId: String): List<Tunnel> = tunnelRepository.observeAll().first().filter { it.hostId == hostId }

    /** `user@host:port`, `host:port`, `ssh://user@host:port` or a bare address, connected as an unsaved host. */
    fun quickConnect(spec: String, identityId: String?, workspaceId: String? = null): Boolean {
        val parsed = parseQuickConnect(spec) ?: return false
        val (user, address, port) = parsed
        val name = address
        val host = Host(
            id = "quick-" + UUID.randomUUID().toString(),
            name = name,
            color = SwatchColor.forName(name),
            monogram = Host.monogramFor(name),
            address = address,
            port = port,
            user = user,
            auth = if (identityId != null) AuthMethod.Key(identityId) else AuthMethod.AskEachTime,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch { sessions.open(host, workspaceId) }
        return true
    }

    fun setActive(id: String?) = sessions.setActive(id)

    fun reconnect(id: String) {
        sessions.reconnect(id)
    }

    fun detach(id: String) {
        sessions.detach(id)
    }

    /** Closes a tab and returns what a Reopen snackbar needs, or null when there was no such tab. */
    fun close(id: String): ClosedTab? = sessions.close(id)
    fun closeOthers(id: String) = sessions.closeOthers(id)

    fun reopen(closed: ClosedTab) {
        viewModelScope.launch { sessions.reopen(closed) }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { sessions.duplicate(id) }
    }

    // ---- files tabs ------------------------------------------------------------------------------

    /**
     * Files from a terminal tab's menu or the Session sheet: the host's Files tab, riding that
     * terminal, comes on stage. Handed a Files tab's own id, it just puts that tab on stage.
     */
    fun openFiles(tabId: String) {
        if (sessions.filesTab(tabId) != null) {
            sessions.setActive(tabId)
            return
        }
        viewModelScope.launch { sessions.openFiles(tabId) }
    }

    /** Terminal from a Files tab's menu or the Session sheet: the terminal it rides, or a new one on its host, comes on stage. */
    fun openTerminal(filesTabId: String) {
        viewModelScope.launch { sessions.openTerminalFor(filesTabId) }
    }

    /** Files from the host list or the New tab sheet: the host's Files tab, opened if it has none. */
    fun openFilesForHost(host: Host, workspaceId: String? = null) {
        viewModelScope.launch { sessions.openFilesForHost(host, workspaceId) }
    }

    /** A Files tab's Connect: a terminal on its host opens off stage, so the browser has a login to ride. */
    fun connectFor(tab: FilesTab) = sessions.connectFor(tab)

    fun rename(id: String, title: String?) = sessions.rename(id, title)
    fun moveTab(id: String, toIndex: Int, groupId: String? = null) = sessions.moveTab(id, toIndex, groupId)
    fun moveToGroup(id: String, groupId: String) = sessions.moveToGroup(id, groupId)

    /** Ctrl+Tab / Ctrl+Shift+Tab and the tab swipe. */
    fun stepTab(delta: Int) = sessions.stepActive(delta)

    /** Ctrl+1…9. */
    fun activateTabAt(index: Int) = sessions.activateAt(index)

    /** Jump to unread (spec C3, C4, C22): the tab lit most recently goes on stage; false when no other tab needs the user. */
    fun jumpToUnread(): Boolean = sessions.jumpToUnread()

    fun setWorkspace(id: String) = sessions.setCurrentWorkspace(id)

    /** Creates a group; with [switchTo] it becomes the target for the next new tab. Returns its id through [onCreated]. */
    fun createWorkspace(name: String, switchTo: Boolean = true, color: SwatchColor? = null, onCreated: (Workspace) -> Unit = {}) {
        viewModelScope.launch {
            val ws = sessions.createWorkspace(name, color ?: SwatchColor.forName(name))
            if (switchTo) sessions.setCurrentWorkspace(ws.id)
            onCreated(ws)
        }
    }

    fun renameWorkspace(id: String, name: String) = sessions.renameWorkspace(id, name)
    fun setWorkspaceColor(id: String, color: SwatchColor) = sessions.setWorkspaceColor(id, color)
    fun setWorkspaceCollapsed(id: String, collapsed: Boolean) = sessions.setWorkspaceCollapsed(id, collapsed)
    fun moveGroup(id: String, toIndex: Int) = sessions.moveGroup(id, toIndex)
    fun closeGroup(id: String) = sessions.closeGroup(id)
    fun deleteWorkspace(id: String, closeTabs: Boolean) = sessions.deleteWorkspace(id, closeTabs)

    fun setTabSwipeGesture(gesture: TabSwipeGesture) {
        viewModelScope.launch { settings.setTabSwipeGesture(gesture) }
    }

    fun setCtrlTabKeysReachTerminal(enabled: Boolean) {
        viewModelScope.launch { settings.setCtrlTabKeysReachTerminal(enabled) }
    }

    fun setCommandHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setCommandHistoryEnabled(enabled) }
    }

    // ---- hosts --------------------------------------------------------------------------------------

    suspend fun host(id: String): Host? = hostRepository.get(id)

    /** Saves the host; a non-null [password] is stored encrypted and referenced by the auth method. */
    fun saveHost(host: Host, password: String?) {
        viewModelScope.launch { saveHostNow(host, password) }
    }

    /** [saveHost] in the caller's coroutine; returns the host as saved, its auth pointing at the stored password. */
    suspend fun saveHostNow(host: Host, password: String?): Host {
        var h = host
        if (h.auth is AuthMethod.Password) {
            val secretId = AuthResolver.passwordSecretId(h.id)
            if (!password.isNullOrEmpty()) {
                secrets.put(secretId, password.toByteArray(Charsets.UTF_8))
                h = h.copy(auth = AuthMethod.Password(secretId))
            } else if ((h.auth as AuthMethod.Password).secretId == null) {
                h = h.copy(auth = AuthMethod.Password(null))
            }
        } else {
            secrets.delete(AuthResolver.passwordSecretId(h.id))
        }
        hostRepository.upsert(h)
        return h
    }

    fun deleteHost(id: String) {
        viewModelScope.launch {
            secrets.delete(AuthResolver.passwordSecretId(id))
            hostRepository.delete(id)
            security.forgetHost(id)
        }
    }

    // ---- identities ------------------------------------------------------------------------------

    sealed interface KeyGenResult {
        data class Done(val identity: Identity) : KeyGenResult
        data class Failed(val reason: String) : KeyGenResult
    }

    val strongBoxAvailable: Boolean get() = hardwareKeys.strongBoxAvailable

    /**
     * The model a new biometric key on this device gets: per use from Android 11, the timed window
     * on 10 (see [HardwareKeys]); the generate sheet says which before the key is made.
     */
    val newBiometricKeyModel: KeyAuthModel
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) KeyAuthModel.PER_USE else KeyAuthModel.TIMED_WINDOW

    /**
     * How the Keystore key behind [identity] lets itself sign, or null for a software key or a
     * Keystore entry that cannot be read (gone, or a fixture); the Keys screen names it on the row.
     */
    fun keyAuthModel(identity: Identity): KeyAuthModel? {
        if (identity.storage != KeyStorage.ANDROID_KEYSTORE) return null
        val alias = identity.keystoreAlias ?: HardwareKeys.aliasFor(identity.id)
        return runCatching { hardwareKeys.authModel(alias) }.getOrNull()
    }

    suspend fun generateIdentity(name: String, algorithm: KeyAlgorithm, hardware: Boolean, protection: KeyProtection, comment: String, passphrase: CharArray?): KeyGenResult =
        withContext(Dispatchers.Default) {
            runCatching {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                if (hardware) {
                    val alias = HardwareKeys.aliasFor(id)
                    val public = hardwareKeys.generate(alias, protection)
                    val identity = Identity(
                        id = id,
                        name = name,
                        algorithm = KeyAlgorithm.ECDSA_P256,
                        storage = KeyStorage.ANDROID_KEYSTORE,
                        protection = protection,
                        publicKeyOpenSsh = SshKeys.openSshPublic(public, comment),
                        fingerprintSha256 = SshKeys.fingerprintSha256(public),
                        comment = comment,
                        keystoreAlias = alias,
                        createdAt = now,
                    )
                    identityRepository.insert(identity, null)
                    identity
                } else {
                    val pair = SshKeys.generate(algorithm)
                    val pem = SshKeys.openSshPrivate(pair, comment, passphrase)
                    val identity = Identity(
                        id = id,
                        name = name,
                        algorithm = algorithm,
                        storage = KeyStorage.SOFTWARE_ENCRYPTED,
                        protection = if (passphrase != null && passphrase.isNotEmpty()) KeyProtection.PASSPHRASE else KeyProtection.NONE,
                        publicKeyOpenSsh = SshKeys.openSshPublic(pair.public, comment),
                        fingerprintSha256 = SshKeys.fingerprintSha256(pair.public),
                        comment = comment,
                        createdAt = now,
                    )
                    identityRepository.insert(identity, pem.toByteArray(Charsets.UTF_8))
                    identity
                }
            }.fold({ KeyGenResult.Done(it) }, { KeyGenResult.Failed(it.message ?: it.javaClass.simpleName) })
        }

    suspend fun hostsUsing(identityId: String): List<Host> = identityRepository.hostsUsing(identityId)

    fun deleteIdentity(id: String) {
        viewModelScope.launch { identityRepository.delete(id) }
    }

    fun forgetKnownHost(id: String) {
        viewModelScope.launch { knownHostRepository.delete(id) }
    }

    fun setKnownHostPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { knownHostRepository.setPinned(id, pinned) }
    }

    // ---- tunnels ----------------------------------------------------------------------------------

    fun saveTunnel(tunnel: Tunnel) {
        viewModelScope.launch { tunnelRepository.upsert(tunnel) }
    }

    fun deleteTunnel(id: String) {
        viewModelScope.launch { tunnelRepository.delete(id) }
    }

    fun setTunnelEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { tunnelRepository.setEnabled(id, enabled) }
    }

    fun retryTunnel(id: String) = sessions.retryTunnel(id)

    /** True while some session of [hostId] is Live or on its way there, so its tunnels can run. */
    fun hostHasActiveSession(hostId: String): Boolean = sessions.sessions.value.any { it.record.value.hostId == hostId && it.state.isActive }

    // ---- snippets ---------------------------------------------------------------------------------

    fun saveSnippet(snippet: Snippet) {
        viewModelScope.launch { snippetRepository.upsert(snippet) }
    }

    fun deleteSnippet(id: String) {
        viewModelScope.launch {
            snippetRepository.delete(id)
            // A Deck key bound to the snippet would otherwise point at nothing.
            val layout = settings.deckLayout.first()
            val cleaned = layout.copy(
                layers = layout.layers.map { layer ->
                    layer.copy(keys = layer.keys.filterNot { key -> (key.tap as? DeckAction.Snippet)?.snippetId == id })
                }.filter { it.keys.isNotEmpty() },
            )
            if (cleaned != layout) settings.setDeckLayout(cleaned)
        }
    }

    /**
     * Renders [snippet] with [values] and sends it to [session]: Run types it and presses Enter
     * on every line; Paste puts it on the prompt (bracketed when the shell asks for it) and waits.
     */
    fun runSnippet(session: TerminalSession, snippet: Snippet, values: Map<String, String> = emptyMap(), action: SnippetAction = snippet.defaultAction) {
        val text = snippet.render(values)
        when (action) {
            SnippetAction.RUN -> session.sendText(text.trimEnd('\n', '\r') + "\n")
            SnippetAction.PASTE -> session.paste(text)
        }
    }

    /**
     * Binds a snippet to a Deck key so tapping it runs the snippet. This is the hook the layout
     * editor builds on: a key with `tap = DeckAction.Snippet(id)` is all it takes.
     */
    fun deckKeyFor(snippet: Snippet): DeckKey = DeckKey(tap = DeckAction.Snippet(snippet.id), display = snippet.name.take(8))

    // ---- import and export --------------------------------------------------------------------------

    fun parseSshConfig(text: String): SshConfigParseResult = SshConfigParser.parse(text)

    /** A host row from a config alias; the identity match and jump chain are resolved by the caller. */
    fun hostFromConfig(entry: SshConfigHost, identityId: String?, jumpHostIds: List<String>): Host {
        val name = entry.alias
        return Host(
            id = UUID.randomUUID().toString(),
            name = name,
            color = SwatchColor.forName(name),
            monogram = Host.monogramFor(name),
            address = entry.hostName,
            port = entry.port,
            user = entry.user ?: "root",
            auth = if (identityId != null) AuthMethod.Key(identityId) else AuthMethod.AskEachTime,
            jumpHostIds = jumpHostIds,
            persistence = app.berth.domain.model.PersistencePolicy(keepaliveSeconds = entry.serverAliveInterval ?: 15),
            startupCommand = entry.remoteCommand,
            agentForwarding = entry.forwardAgent ?: false,
            compression = entry.compression ?: false,
            addressFamily = when (entry.addressFamily) {
                "inet" -> app.berth.domain.model.AddressFamily.IPV4
                "inet6" -> app.berth.domain.model.AddressFamily.IPV6
                else -> app.berth.domain.model.AddressFamily.AUTO
            },
            tags = listOf("imported"),
            createdAt = System.currentTimeMillis(),
        )
    }

    /** Saves imported hosts with their forwards as tunnels; returns how many hosts were added. */
    suspend fun importHosts(hosts: List<Pair<Host, SshConfigHost>>): Int {
        for ((host, entry) in hosts) {
            hostRepository.upsert(host)
            for (fwd in entry.forwards) {
                tunnelRepository.upsert(
                    Tunnel(
                        id = UUID.randomUUID().toString(),
                        hostId = host.id,
                        type = fwd.type,
                        bindAddress = fwd.bindAddress,
                        bindPort = fwd.bindPort,
                        destinationHost = fwd.destinationHost,
                        destinationPort = fwd.destinationPort,
                        enabled = false,
                    ),
                )
            }
        }
        return hosts.size
    }

    sealed interface KeyImportResult {
        data class Done(val identity: Identity) : KeyImportResult
        data object PassphraseNeeded : KeyImportResult
        data class Failed(val reason: String) : KeyImportResult
    }

    /** Reads a pasted or picked private key and stores it re-encoded as OpenSSH, encrypted at rest. */
    suspend fun importIdentity(name: String, keyText: String, passphrase: CharArray?): KeyImportResult = withContext(Dispatchers.Default) {
        val imported = try {
            SshKeys.importPrivate(keyText, passphrase)
        } catch (e: SshKeys.ImportError.PassphraseNeeded) {
            return@withContext KeyImportResult.PassphraseNeeded
        } catch (e: SshKeys.ImportError) {
            return@withContext KeyImportResult.Failed(e.message ?: "Couldn't read this key.")
        }
        val algorithm = imported.algorithm
            ?: return@withContext KeyImportResult.Failed("Berth stores Ed25519, ECDSA P-256, P-384 and RSA keys; this key is another type.")
        val fingerprint = SshKeys.fingerprintSha256(imported.pair.public)
        identityRepository.observeAll().first().firstOrNull { it.fingerprintSha256 == fingerprint }?.let {
            return@withContext KeyImportResult.Failed("This key is already here as ${it.name}.")
        }
        val keepPassphrase = passphrase != null && passphrase.isNotEmpty()
        val comment = imported.comment
        val identity = Identity(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { comment.ifBlank { "imported ${algorithm.displayName.lowercase()}" } },
            algorithm = algorithm,
            storage = KeyStorage.SOFTWARE_ENCRYPTED,
            protection = if (keepPassphrase) KeyProtection.PASSPHRASE else KeyProtection.NONE,
            publicKeyOpenSsh = SshKeys.openSshPublic(imported.pair.public, comment),
            fingerprintSha256 = fingerprint,
            comment = comment,
            createdAt = System.currentTimeMillis(),
        )
        val pem = SshKeys.openSshPrivate(imported.pair, comment, passphrase?.takeIf { keepPassphrase })
        identityRepository.insert(identity, pem.toByteArray(Charsets.UTF_8))
        KeyImportResult.Done(identity)
    }

    // ---- settings -------------------------------------------------------------------------------------

    fun setFontSize(sizeSp: Int) {
        viewModelScope.launch {
            val current = settings.terminalFont.first()
            settings.setTerminalFont(current.copy(sizeSp = sizeSp.coerceIn(TerminalFont.MIN_SIZE_SP, TerminalFont.MAX_SIZE_SP)))
        }
    }

    fun setInterfaceTheme(theme: InterfaceTheme) {
        viewModelScope.launch { settings.setInterfaceTheme(theme) }
    }

    fun setHapticLevel(level: HapticLevel) {
        viewModelScope.launch { settings.setHapticLevel(level) }
    }

    fun setDefaultTerminalTheme(id: String) {
        viewModelScope.launch { settings.setDefaultTerminalTheme(id) }
    }

    fun setTerminalFont(font: TerminalFont) {
        viewModelScope.launch { settings.setTerminalFont(font) }
    }

    fun setDeckLayout(layout: DeckLayout) {
        viewModelScope.launch { settings.setDeckLayout(layout) }
    }

    // ---- terminal themes ----------------------------------------------------------------------------

    /** Stores a custom theme; a stock id is never overwritten because the repository refuses built-ins. */
    fun saveTerminalTheme(theme: TerminalTheme) {
        viewModelScope.launch { settings.upsertTerminalTheme(theme.copy(builtIn = false)) }
    }

    /** Removes a custom theme and points anything that used it back at the app default. */
    fun deleteTerminalTheme(id: String) {
        viewModelScope.launch {
            settings.deleteTerminalTheme(id)
            if (settings.defaultTerminalThemeId.first() == id) settings.setDefaultTerminalTheme(TerminalTheme.BERTH_DARK_ID)
            hostRepository.observeAll().first().filter { it.appearance.terminalThemeId == id }.forEach { h ->
                hostRepository.upsert(h.copy(appearance = h.appearance.copy(terminalThemeId = null)))
            }
            workspaces.value.filter { it.terminalThemeId == id }.forEach { ws -> workspaceRepository.upsert(ws.copy(terminalThemeId = null)) }
        }
    }

    /** Per-host assignment; null returns the host to inheriting. */
    fun setHostTerminalTheme(hostId: String, themeId: String?) {
        viewModelScope.launch {
            val h = hostRepository.get(hostId) ?: return@launch
            hostRepository.upsert(h.copy(appearance = h.appearance.copy(terminalThemeId = themeId)))
        }
    }

    /** Per-workspace assignment; null returns the workspace to inheriting. */
    fun setWorkspaceTerminalTheme(workspaceId: String, themeId: String?) {
        viewModelScope.launch {
            val ws = workspaceRepository.get(workspaceId) ?: return@launch
            workspaceRepository.upsert(ws.copy(terminalThemeId = themeId))
        }
    }

    companion object {
        /** Host override first, then the workspace's theme, then the app default. */
        fun resolveTerminalTheme(themes: List<TerminalTheme>, default: TerminalTheme, host: Host, workspace: Workspace?): TerminalTheme {
            fun find(id: String?) = id?.let { wanted -> themes.firstOrNull { it.id == wanted } }
            return find(host.appearance.terminalThemeId) ?: find(workspace?.terminalThemeId) ?: default
        }

        fun newThemeId(): String = "theme-" + UUID.randomUUID().toString().take(8)

        /**
         * The forwards of [link] that a host's [saved] tunnels do not already carry: what its editor
         * lists as pending rows and Save adds. One the host already has is nothing to ask about, so a
         * link opened twice adds nothing the second time.
         */
        fun pendingForwards(link: SshLink, saved: List<Tunnel>): List<SshConfigForward> = pendingForwards(link.forwards, saved)

        fun pendingForwards(forwards: List<SshConfigForward>, saved: List<Tunnel>): List<SshConfigForward> =
            forwards.filterNot { fwd -> saved.any { it.carries(fwd) } }

        private fun Tunnel.carries(fwd: SshConfigForward): Boolean =
            type == fwd.type && bindAddress == fwd.bindAddress && bindPort == fwd.bindPort &&
                (fwd.type == TunnelType.DYNAMIC || (destinationHost == fwd.destinationHost && destinationPort == fwd.destinationPort))

        /** A forward from a link as a tunnel on [hostId], enabled: the login the link asks for carries it. */
        fun SshConfigForward.toTunnel(hostId: String, id: String = UUID.randomUUID().toString()): Tunnel = Tunnel(
            id = id,
            hostId = hostId,
            type = type,
            bindAddress = bindAddress,
            bindPort = bindPort,
            destinationHost = destinationHost.ifEmpty { "localhost" },
            destinationPort = destinationPort,
            enabled = true,
        )

        private val QUICK = Regex("""^(?:ssh://)?(?:([^@\s]+)@)?(\[[0-9a-fA-F:.]+]|[^:\s/@]+)(?::(\d{1,5}))?/?$""")

        /** Returns (user, address, port) or null when [spec] is not an address. */
        fun parseQuickConnect(spec: String): Triple<String, String, Int>? {
            val m = QUICK.matchEntire(spec.trim()) ?: return null
            val user = m.groupValues[1].ifEmpty { "root" }
            val address = m.groupValues[2].removePrefix("[").removeSuffix("]")
            val port = m.groupValues[3].toIntOrNull() ?: 22
            if (address.isBlank() || port !in 1..65535) return null
            return Triple(user, address, port)
        }
    }
}

fun List<Workspace>.byId(id: String?): Workspace? = firstOrNull { it.id == id }

/** What an incoming link came to ([AppViewModel.linkOutcome]); the shell acts on it once and clears it. */
sealed interface LinkOutcome {
    /** A tab was opened or brought on stage; the shell pops back to the Stage. */
    data object Staged : LinkOutcome

    /** No saved host matched: the editor opens prefilled from [raw], and saving there connects. */
    data class NewHost(val raw: String) : LinkOutcome

    /**
     * The saved host [hostId] matched, but [raw] carries forwards it does not have: the editor
     * opens on the host with them as pending rows, and saving there adds them and connects.
     */
    data class ConfirmForwards(val hostId: String, val raw: String) : LinkOutcome

    /** The link could not be read; [reason] is one sentence for the notice bar. */
    data class Malformed(val reason: String) : LinkOutcome
}
