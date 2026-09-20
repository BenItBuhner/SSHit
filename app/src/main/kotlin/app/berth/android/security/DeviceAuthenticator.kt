package app.berth.android.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.Signature
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** How the system's confirm-it-is-you prompt ended. */
sealed interface AuthOutcome {
    /** [signature] is the authorised object when one was handed in; the key it holds may sign once. */
    class Succeeded(val signature: Signature?) : AuthOutcome

    /** The user backed out: the cancel button, back, or the prompt was dismissed for them. */
    data object Cancelled : AuthOutcome

    /** The prompt could not run or ended in an error: lockout, hardware missing, no screen lock set. */
    data class Failed(val message: String) : AuthOutcome
}

/** The system prompt (fingerprint, face, or the device's PIN, pattern or password); tests supply fakes. */
interface DeviceAuthenticator {
    /** Whether the device has a screen lock at all; without one nothing here can gate anything. */
    val deviceSecure: Boolean

    /**
     * Shows the prompt and suspends until the user answers. With a [signature] the prompt
     * authorises that object (a `CryptoObject`), so a per-use Keystore key may sign through it.
     * Waits for Berth to be in the foreground first: a session reconnecting in the background
     * parks here and asks the moment the app is opened. Prompts run one at a time.
     */
    suspend fun authenticate(title: String, subtitle: String?, signature: Signature? = null): AuthOutcome
}

/** The activity on screen, for prompts that need a window to attach to. */
@Singleton
class ForegroundActivity @Inject constructor() {
    private val _current = MutableStateFlow<Activity?>(null)
    val current: StateFlow<Activity?> = _current.asStateFlow()

    fun started(activity: Activity) {
        _current.value = activity
    }

    fun stopped(activity: Activity) {
        _current.compareAndSet(activity, null)
    }
}

class FrameworkAuthenticator(private val context: Context, private val foreground: ForegroundActivity) : DeviceAuthenticator {
    private val oneAtATime = Mutex()

    override val deviceSecure: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override suspend fun authenticate(title: String, subtitle: String?, signature: Signature?): AuthOutcome = oneAtATime.withLock {
        if (!deviceSecure) return AuthOutcome.Failed("Set a screen lock on this device first")
        val activity = foreground.current.first { it != null }!!
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                val builder = BiometricPrompt.Builder(activity).setTitle(title)
                if (subtitle != null) builder.setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    // Android 10: the credential fallback is the old switch, and it cannot be paired with
                    // a CryptoObject, which is why keys made there open a timed window instead.
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(AuthOutcome.Succeeded(result.cryptoObject?.signature ?: signature))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        cont.resume(if (cancelled) AuthOutcome.Cancelled else AuthOutcome.Failed(errString.toString().ifBlank { "Authentication failed" }))
                    }
                    // onAuthenticationFailed is one wrong try; the prompt stays up, so nothing to do here.
                }
                val executor = activity.mainExecutor
                try {
                    val prompt = builder.build()
                    if (signature != null) {
                        prompt.authenticate(BiometricPrompt.CryptoObject(signature), cancel, executor, callback)
                    } else {
                        prompt.authenticate(cancel, executor, callback)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(AuthOutcome.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }
}
