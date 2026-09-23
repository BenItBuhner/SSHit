package app.berth.android.security

import android.app.Activity
import app.berth.domain.model.ClipboardClear
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.RemoteClipboardPolicy
import app.berth.domain.model.SecuritySettings
import app.berth.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One handle for the Security settings and the pieces behind them: the app lock, the clipboard,
 * the OSC 52 gate and the system prompt. Screens talk to this through the view model; the activity
 * reports its lifecycle here.
 */
@Singleton
class SecurityCenter(
    private val settingsRepository: SettingsRepository,
    val lock: AppLockController,
    val clipboard: BerthClipboard,
    val remoteClipboard: RemoteClipboardGate,
    val authenticator: DeviceAuthenticator,
    private val foreground: ForegroundActivity,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        settingsRepository: SettingsRepository,
        lock: AppLockController,
        clipboard: BerthClipboard,
        remoteClipboard: RemoteClipboardGate,
        authenticator: DeviceAuthenticator,
        foreground: ForegroundActivity,
    ) : this(settingsRepository, lock, clipboard, remoteClipboard, authenticator, foreground, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Null until the first read lands; the activity holds its splash until then. */
    val settings: StateFlow<SecuritySettings?> = settingsRepository.securitySettings.map<SecuritySettings, SecuritySettings?> { it }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The interface theme, for the lock window, which has no view model of its own. */
    val interfaceTheme: StateFlow<InterfaceTheme> = settingsRepository.interfaceTheme.stateIn(scope, SharingStarted.Eagerly, InterfaceTheme.DEFAULT)

    /** Whether the app lock can be offered: it rides the device's own screen lock. */
    val deviceSecure: Boolean get() = authenticator.deviceSecure

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Either window started. The two overlap while the lock window comes and goes; the lock counts them as one foreground. */
    fun onActivityStarted(activity: Activity) {
        foreground.started(activity)
        lock.onForeground()
    }

    fun onActivityStopped(activity: Activity, changingConfigurations: Boolean) {
        lock.onBackground(changingConfigurations)
        foreground.stopped(activity)
    }

    /**
     * The lock window is about to finish: it stops being the activity prompts attach to before its
     * window goes, so a key prompt the unlock released waits for the main window to be back on top
     * instead of attaching to one on its way out.
     */
    fun onLockWindowClosing(activity: Activity) = foreground.stopped(activity)

    fun onActivityResumed() = clipboard.setVisible(true)

    fun onActivityPaused() = clipboard.setVisible(false)

    // ---- settings -------------------------------------------------------------------------------

    private fun update(change: (SecuritySettings) -> SecuritySettings) {
        scope.launch { settingsRepository.updateSecuritySettings(change) }
    }

    /**
     * Turning the lock on first runs the prompt once, so a lock nobody can pass is never saved.
     * Returns whether the setting changed.
     */
    suspend fun setAppLock(enabled: Boolean): Boolean {
        if (enabled) {
            if (!deviceSecure) return false
            if (authenticator.authenticate("Turn on app lock", "Berth will ask for this each time it opens") !is AuthOutcome.Succeeded) return false
        }
        settingsRepository.updateSecuritySettings { it.copy(appLock = enabled) }
        return true
    }

    fun setLockTimeout(timeout: LockTimeout) = update { it.copy(lockTimeout = timeout) }

    fun setBlockScreenshots(enabled: Boolean) = update { it.copy(blockScreenshots = enabled) }

    fun setClipboardClear(clear: ClipboardClear) = update { it.copy(clipboardClear = clear) }

    fun setRemoteClipboard(enabled: Boolean) = update { it.copy(remoteClipboard = enabled) }

    fun setHostRemoteClipboard(hostId: String, policy: RemoteClipboardPolicy) = update { it.withHostRemoteClipboard(hostId, policy) }

    fun setHostAgentSilent(hostId: String, silent: Boolean) = update { it.withHostAgentSilent(hostId, silent) }

    /** A deleted host leaves nothing behind in the document: neither its override nor its notice. */
    fun forgetHost(hostId: String) = update { it.withoutHost(hostId) }
}
