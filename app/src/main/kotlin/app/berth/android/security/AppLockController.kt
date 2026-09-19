package app.berth.android.security

import app.berth.domain.model.LockTimeout
import app.berth.domain.model.SecuritySettings
import app.berth.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class LockState {
    /** Settings have not been read yet; the splash stays up and nothing is drawn. */
    UNKNOWN,
    LOCKED,
    UNLOCKED,
}

/**
 * Decides when the lock screen covers the app (spec C20, App lock). A launch always locks when the
 * lock is on; coming back from the background locks once the app has been away for at least the
 * chosen timeout, and never for a rotation. The state lives in this singleton, so an activity
 * recreated under it comes back to the same answer, and process death starts at [LockState.UNKNOWN],
 * which a fresh launch resolves to locked.
 */
@Singleton
class AppLockController(
    settings: SettingsRepository,
    private val clock: MonotonicClock,
    private val authenticator: DeviceAuthenticator,
    scope: CoroutineScope,
) {
    @Inject constructor(settings: SettingsRepository, clock: MonotonicClock, authenticator: DeviceAuthenticator) :
        this(settings, clock, authenticator, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state.asStateFlow()

    /** Why the last unlock attempt did not unlock, for the lock screen to show; null after success or a plain cancel. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _authenticating = MutableStateFlow(false)
    val authenticating: StateFlow<Boolean> = _authenticating.asStateFlow()

    private val lock = Any()
    private var current: SecuritySettings? = null
    private var launched = false
    private var backgroundedAt: Long? = null
    private var foreground = 0
    private var pending: Evaluation? = null

    private class Evaluation(val coldStart: Boolean, val since: Long?)

    init {
        scope.launch { settings.securitySettings.collect { onSettings(it) } }
    }

    private fun onSettings(settings: SecuritySettings) {
        synchronized(lock) {
            current = settings
            if (!settings.appLock) {
                _state.value = LockState.UNLOCKED
                pending = null
                return
            }
            pending?.let {
                pending = null
                evaluate(it, settings)
            }
        }
    }

    /** An activity started. Only the first of overlapping activities counts as coming to the foreground. */
    fun onForeground() {
        synchronized(lock) {
            foreground++
            if (foreground > 1) return
            val evaluation = Evaluation(coldStart = !launched, since = backgroundedAt)
            launched = true
            backgroundedAt = null
            // The prompt itself can take the app away and back (the credential screen on Android 10).
            if (_authenticating.value) return
            val settings = current
            if (settings == null) pending = evaluation else evaluate(evaluation, settings)
        }
    }

    /** An activity stopped. A rotation is not leaving: the clock only starts for a real background. */
    fun onBackground(changingConfigurations: Boolean) {
        synchronized(lock) {
            foreground = (foreground - 1).coerceAtLeast(0)
            if (foreground > 0 || changingConfigurations || _authenticating.value) return
            backgroundedAt = clock.elapsed()
        }
    }

    private fun evaluate(evaluation: Evaluation, settings: SecuritySettings) {
        if (!settings.appLock) {
            _state.value = LockState.UNLOCKED
            return
        }
        val since = evaluation.since
        val expired = since != null && settings.lockTimeout != LockTimeout.NEVER && clock.elapsed() - since >= settings.lockTimeout.millis
        if (evaluation.coldStart || expired) {
            _state.value = LockState.LOCKED
            _failure.value = null
        } else if (_state.value == LockState.UNKNOWN) {
            _state.value = LockState.UNLOCKED
        }
    }

    /** Runs the system prompt; true when the app is (now) unlocked. A second call while one is up returns false. */
    suspend fun unlock(): Boolean {
        if (_state.value != LockState.LOCKED) return true
        if (!_authenticating.compareAndSet(expect = false, update = true)) return false
        val outcome = try {
            authenticator.authenticate("Unlock Berth", null)
        } finally {
            _authenticating.value = false
        }
        return when (outcome) {
            is AuthOutcome.Succeeded -> {
                synchronized(lock) {
                    _state.value = LockState.UNLOCKED
                    _failure.value = null
                }
                true
            }
            AuthOutcome.Cancelled -> {
                _failure.value = null
                false
            }
            is AuthOutcome.Failed -> {
                _failure.value = outcome.message
                false
            }
        }
    }

    /** Suspends until the app is showing content; prompts for keys wait here so they never appear over the lock screen. */
    suspend fun awaitUnlocked() {
        _state.first { it == LockState.UNLOCKED }
    }
}
