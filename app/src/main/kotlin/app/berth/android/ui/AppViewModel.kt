package app.berth.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.berth.android.session.AuthResolver
import app.berth.android.session.PromptCenter
import app.berth.android.session.SessionManager
import app.berth.android.session.TerminalSession
import app.berth.android.session.TunnelStatus
import app.berth.data.crypto.HardwareKeys
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
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.Workspace
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import app.berth.ssh.SshConfigHost
import app.berth.ssh.SshConfigParseResult
import app.berth.ssh.SshConfigParser
import app.berth.ssh.SshKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {
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

    val records: Flow<List<SessionRecord>> = sessions.records
    val workspaces: StateFlow<List<Workspace>> = sessions.workspaces
    val currentWorkspaceId: StateFlow<String?> = sessions.currentWorkspaceId
    val activeSession: StateFlow<TerminalSession?> = sessions.activeSession

    /** Sessions of the current workspace in rail order. */
    val workspaceSessions: StateFlow<List<TerminalSession>> = combine(sessions.sessions, sessions.currentWorkspaceId) { list, ws ->
        list.filter { ws == null || it.record.value.workspaceId == ws }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Sessions elsewhere that need attention; drives the "needs you" pill. */
    val attentionCount: StateFlow<Int> = combine(records, sessions.activeSessionId) { list, active ->
        list.count { it.needsAttention && it.id != active }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun themeFor(host: Host, workspaceId: String? = null): TerminalTheme =
        resolveTerminalTheme(terminalThemes.value, defaultTerminalTheme.value, host, workspaces.value.byId(workspaceId))

    fun fontFor(host: Host): TerminalFont {
        val base = terminalFont.value
        val size = host.appearance.fontSizeSp ?: return base
        return base.copy(sizeSp = size)
    }

    // ---- sessions --------------------------------------------------------------------------------

    fun open(host: Host, workspaceId: String? = null) {
        viewModelScope.launch { sessions.open(host, workspaceId) }
    }

    /** `user@host:port`, `host:port`, `ssh://user@host:port` or a bare address, connected as an unsaved host. */
    fun quickConnect(spec: String, identityId: String?): Boolean {
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
        viewModelScope.launch { sessions.open(host) }
        return true
    }

    fun setActive(id: String?) = sessions.setActive(id)
    fun reconnect(id: String) = sessions.reconnect(id)
    fun detach(id: String) = sessions.detach(id)
    fun close(id: String) = sessions.close(id)
    fun setWorkspace(id: String) = sessions.setCurrentWorkspace(id)

    fun createWorkspace(name: String, switchTo: Boolean = true) {
        viewModelScope.launch {
            val ws = sessions.createWorkspace(name)
            if (switchTo) sessions.setCurrentWorkspace(ws.id)
        }
    }

    // ---- hosts --------------------------------------------------------------------------------------

    suspend fun host(id: String): Host? = hostRepository.get(id)

    /** Saves the host; a non-null [password] is stored encrypted and referenced by the auth method. */
    fun saveHost(host: Host, password: String?) {
        viewModelScope.launch {
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
        }
    }

    fun deleteHost(id: String) {
        viewModelScope.launch {
            secrets.delete(AuthResolver.passwordSecretId(id))
            hostRepository.delete(id)
        }
    }

    // ---- identities ------------------------------------------------------------------------------

    sealed interface KeyGenResult {
        data class Done(val identity: Identity) : KeyGenResult
        data class Failed(val reason: String) : KeyGenResult
    }

    val strongBoxAvailable: Boolean get() = hardwareKeys.strongBoxAvailable

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
