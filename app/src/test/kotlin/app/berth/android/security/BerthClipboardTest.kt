package app.berth.android.security

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.InMemorySettings
import app.berth.domain.model.ClipboardClear
import app.berth.domain.model.SecuritySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auto-clear after copy (spec C20, Clipboard) touches only what Berth put there: a copy carries a
 * token, and the timer clears the clipboard only while that very clip is still on it. Anything
 * another app, or a later Berth copy, put there since is left alone. The timer runs on a test
 * dispatcher and the clock is stepped alongside it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BerthClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = InMemorySettings()
    private val clock = FakeClock()
    private val scope = TestScope(StandardTestDispatcher())
    private val clipboard = BerthClipboard(context, settings, clock, scope).also { it.setVisible(true) }

    private val manager: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)
    private val clipText: String? get() = manager.primaryClip?.getItemAt(0)?.text?.toString()

    private fun policy(clear: ClipboardClear) {
        settings.security.value = SecuritySettings(clipboardClear = clear)
        scope.runCurrent()
    }

    /** [millis] pass for the timer and for the clock the timer checks against. */
    private fun elapse(millis: Long) {
        clock.advance(millis)
        scope.testScheduler.advanceTimeBy(millis)
        scope.runCurrent()
    }

    /** What another app does: a clip with no token of ours. */
    private fun foreignCopy(text: String) = manager.setPrimaryClip(ClipData.newPlainText("other app", text))

    @Test
    fun `off, a copy stays for good`() {
        policy(ClipboardClear.OFF)
        clipboard.copy("password1")
        elapse(60L * 60 * 1000)
        assertEquals("password1", clipText)
    }

    @Test
    fun `thirty seconds clears Berth's copy at thirty seconds, not before`() {
        policy(ClipboardClear.THIRTY_SECONDS)
        clipboard.copy("ssh-ed25519 AAAA")
        elapse(29_999)
        assertEquals("ssh-ed25519 AAAA", clipText)
        elapse(1)
        assertFalse(manager.hasPrimaryClip())
    }

    @Test
    fun `sixty seconds clears at sixty`() {
        policy(ClipboardClear.SIXTY_SECONDS)
        clipboard.copy("token")
        elapse(59_999)
        assertEquals("token", clipText)
        elapse(1)
        assertFalse(manager.hasPrimaryClip())
    }

    @Test
    fun `what another app copied since is left alone`() {
        policy(ClipboardClear.THIRTY_SECONDS)
        clipboard.copy("from berth")
        elapse(10_000)
        foreignCopy("from a note")
        elapse(20_000)
        assertEquals("from a note", clipText)
        elapse(60_000)
        assertEquals("the timer never touched the other app's clip", "from a note", clipText)
    }

    @Test
    fun `a later Berth copy is left to its own timer`() {
        policy(ClipboardClear.THIRTY_SECONDS)
        clipboard.copy("first")
        elapse(20_000)
        clipboard.copy("second")
        elapse(10_000)
        assertEquals("the first timer found the second copy and let it be", "second", clipText)
        elapse(20_000)
        assertFalse("the second copy's own timer cleared it", manager.hasPrimaryClip())
    }

    @Test
    fun `Berth's copies are marked sensitive and carry the token`() {
        policy(ClipboardClear.OFF)
        clipboard.copy("secret")
        val description = manager.primaryClipDescription!!
        assertTrue(description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertTrue(description.extras!!.containsKey(BerthClipboard.EXTRA_TOKEN))
    }

    @Test
    fun `a timer that fires in the background waits for the app to come back`() {
        policy(ClipboardClear.THIRTY_SECONDS)
        clipboard.copy("copied then left")
        clipboard.setVisible(false)
        elapse(30_000)
        assertEquals("the clipboard cannot be read from the background, so nothing was done yet", "copied then left", clipText)
        clipboard.setVisible(true)
        assertFalse("cleared the moment Berth is in front again", manager.hasPrimaryClip())
    }

    @Test
    fun `a deferred clear still leaves another app's clip alone`() {
        policy(ClipboardClear.THIRTY_SECONDS)
        clipboard.copy("mine")
        clipboard.setVisible(false)
        elapse(30_000)
        foreignCopy("theirs, pasted while Berth was away")
        clipboard.setVisible(true)
        assertEquals("theirs, pasted while Berth was away", clipText)
    }

    @Test
    fun `the policy in force at copy time is the one that applies`() {
        policy(ClipboardClear.OFF)
        clipboard.copy("copied while off")
        policy(ClipboardClear.THIRTY_SECONDS)
        elapse(60_000)
        assertEquals("copied while off", clipText)
        clipboard.copy("copied while on")
        elapse(30_000)
        assertNull(clipText)
    }
}
