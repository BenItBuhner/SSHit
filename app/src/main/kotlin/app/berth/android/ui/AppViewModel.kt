package app.berth.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.berth.android.session.AuthResolver
import app.berth.android.session.PromptCenter
import app.berth.android.session.SessionManager
import app.berth.android.session.TerminalSession
import app.berth.data.crypto.HardwareKeys
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Workspace
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SettingsRepository
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
) : ViewModel() {
    val hosts: StateFlow<List<Host>> = hostRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val identities: StateFlow<List<Identity>> = identityRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knownHosts: StateFlow<List<KnownHostKey>> = knownHostRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val deckLayout: StateFlow<DeckLayout> = settings.deckLayout.stateIn(viewModelScope, SharingStarted.Eagerly, DeckLayout.default())
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

    fun themeFor(host: Host): TerminalTheme {
        val id = host.appearance.terminalThemeId ?: return defaultTerminalTheme.value
        return terminalThemes.value.firstOrNull { it.id == id } ?: defaultTerminalTheme.value
    }

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

    fun setDefaultTerminalTheme(id: String) {
        viewModelScope.launch { settings.setDefaultTerminalTheme(id) }
    }

    fun setTerminalFont(font: TerminalFont) {
        viewModelScope.launch { settings.setTerminalFont(font) }
    }

    fun setDeckLayout(layout: DeckLayout) {
        viewModelScope.launch { settings.setDeckLayout(layout) }
    }

    companion object {
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
