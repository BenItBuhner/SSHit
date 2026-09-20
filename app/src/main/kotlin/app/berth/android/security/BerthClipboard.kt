package app.berth.android.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import app.berth.domain.model.ClipboardClear
import app.berth.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every copy Berth makes goes through here, so the clipboard can be cleared afterwards without
 * ever touching what another app put there (spec C20, Clipboard). Each copy carries a token in
 * the clip's description; the timer that follows clears the clipboard only while that exact clip
 * is still on it. Android 10 and later hide the clipboard from apps that are not in front, so a
 * timer that fires in the background waits, and the clear happens the moment Berth is back.
 */
@Singleton
class BerthClipboard(
    private val context: Context,
    settings: SettingsRepository,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) {
    @Inject constructor(@ApplicationContext context: Context, settings: SettingsRepository, clock: MonotonicClock) :
        this(context, settings, clock, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val manager: ClipboardManager? get() = context.getSystemService(ClipboardManager::class.java)
    private val tokens = AtomicLong()

    @Volatile private var policy: ClipboardClear = ClipboardClear.OFF

    /** Whether Berth can currently read the clipboard: an activity of ours is in front. */
    @Volatile private var visible = false

    /** A copy whose timer fired while the clipboard could not be read; settled on the next [setVisible]. */
    @Volatile private var deferred: Long? = null

    init {
        scope.launch { settings.securitySettings.collect { policy = it.clipboardClear } }
    }

    /** Puts [text] on the clipboard as Berth's own copy, to be cleared after the configured delay. */
    fun copy(text: CharSequence, label: String = "Berth") = copy(ClipData.newPlainText(label, text))

    fun copy(clip: ClipData) {
        val manager = manager ?: return
        val token = tokens.incrementAndGet()
        val extras = PersistableBundle(clip.description.extras ?: PersistableBundle())
        extras.putLong(EXTRA_TOKEN, token)
        // Keeps the text out of the system's clipboard preview on Android 13 and later.
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        manager.setPrimaryClip(clip)
        val after = policy.millis ?: return
        val due = clock.elapsed() + after
        scope.launch {
            var remaining = due - clock.elapsed()
            while (remaining > 0) {
                delay(remaining)
                remaining = due - clock.elapsed()
            }
            clearIfOurs(token)
        }
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (!visible) return
        deferred?.let {
            deferred = null
            clearIfOurs(it)
        }
    }

    /** Clears the clipboard only while the clip [token] names is still on it. */
    internal fun clearIfOurs(token: Long) {
        if (!visible) {
            deferred = token
            return
        }
        val manager = manager ?: return
        val current = manager.primaryClipDescription?.extras?.getLong(EXTRA_TOKEN, -1L) ?: -1L
        if (current == token) manager.clearPrimaryClip()
    }

    companion object {
        const val EXTRA_TOKEN = "app.berth.clipboard.token"
    }
}
