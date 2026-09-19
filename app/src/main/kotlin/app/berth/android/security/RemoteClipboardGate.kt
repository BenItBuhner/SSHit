package app.berth.android.security

import app.berth.domain.model.Host
import app.berth.domain.model.RemoteClipboardPolicy
import app.berth.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A clipboard write from a remote that the gate held back; shown once per host. */
data class RemoteClipboardNotice(val host: Host, val text: String)

/**
 * Programs on a server can ask the terminal to write the phone's clipboard (OSC 52). Off unless
 * the app-wide switch or the host's own override says otherwise; the first write a host has
 * blocked raises a notice, so the user learns why nothing landed and can allow that host.
 */
@Singleton
class RemoteClipboardGate(
    private val settings: SettingsRepository,
    private val clipboard: BerthClipboard,
    private val scope: CoroutineScope,
) {
    @Inject constructor(settings: SettingsRepository, clipboard: BerthClipboard) :
        this(settings, clipboard, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _notices = MutableStateFlow<List<RemoteClipboardNotice>>(emptyList())

    /** The notice to show, if any; the next one waits its turn. */
    val notice: StateFlow<RemoteClipboardNotice?> = _notices.map { it.firstOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Hosts already told about a blocked write in this process; the persisted set covers earlier ones. */
    private val told = HashSet<String>()

    /** The terminal's hook: writes the clipboard when [host] may, otherwise drops the text and perhaps raises the notice. */
    fun offer(host: Host, text: String) {
        scope.launch { decide(host, text) }
    }

    /** True when the text went to the clipboard. */
    suspend fun decide(host: Host, text: String): Boolean {
        val current = settings.securitySettings.first()
        if (current.allowsRemoteClipboard(host.id)) {
            clipboard.copy(text, label = "terminal")
            return true
        }
        val firstTime = synchronized(told) { told.add(host.id) } && host.id !in current.remoteClipboardNoticed
        if (firstTime) {
            settings.updateSecuritySettings { it.copy(remoteClipboardNoticed = it.remoteClipboardNoticed + host.id) }
            _notices.update { it + RemoteClipboardNotice(host, text) }
        }
        return false
    }

    /** The notice's Allow: the host may write from now on, and the text it just tried lands. */
    fun allow(notice: RemoteClipboardNotice) {
        scope.launch {
            settings.updateSecuritySettings { it.withHostRemoteClipboard(notice.host.id, RemoteClipboardPolicy.ALLOW) }
            clipboard.copy(notice.text, label = "terminal")
        }
        dismiss(notice)
    }

    fun dismiss(notice: RemoteClipboardNotice) {
        _notices.update { list -> list.filter { it !== notice } }
    }
}
