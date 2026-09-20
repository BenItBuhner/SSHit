package app.berth.android.security

import android.app.Application
import app.berth.android.screenshots.InMemorySettings
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.SecuritySettings
import app.berth.domain.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * When the lock screen covers the app (spec C20, App lock). The controller is the singleton the
 * activity reports to, so an activity recreated under it (rotation) reads the same answer, and a
 * new controller stands for a process that died and was launched again. The prompt is scripted and
 * the clock stepped by hand, so every timeout is exact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLockControllerTest {
    /** Unconfined: a settings write reaches the controller before the next line, as Room's does behind the splash. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = InMemorySettings()
    private val clock = FakeClock()
    private val authenticator = FakeAuthenticator()

    private fun controller(repository: SettingsRepository = settings) = AppLockController(repository, clock, authenticator, scope)

    private fun lockOn(timeout: LockTimeout) {
        settings.security.value = SecuritySettings(appLock = true, lockTimeout = timeout)
    }

    /** A launch that gets past the lock, the way every test that then leaves and returns starts. */
    private fun launchedAndUnlocked(timeout: LockTimeout): AppLockController {
        lockOn(timeout)
        val lock = controller()
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
        authenticator.queue(FakeAuthenticator.SUCCEEDED)
        assertTrue(runBlocking { lock.unlock() })
        assertEquals(LockState.UNLOCKED, lock.state.value)
        return lock
    }

    /** The app goes to the background for [millis] and comes back. */
    private fun AppLockController.awayFor(millis: Long) {
        onBackground(changingConfigurations = false)
        clock.advance(millis)
        onForeground()
    }

    @Test
    fun `with the lock off a launch is unlocked and nobody is asked`() {
        val lock = controller()
        lock.onForeground()
        assertEquals(LockState.UNLOCKED, lock.state.value)
        assertTrue(authenticator.requests.isEmpty())
        lock.awayFor(TimeUnit.DAYS.toMillis(1))
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `with the lock on nothing is drawn until the first foreground, which locks`() {
        lockOn(LockTimeout.NEVER)
        val lock = controller()
        assertEquals("the splash holds until the activity starts", LockState.UNKNOWN, lock.state.value)
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
        assertTrue("locking asks nothing by itself; the lock screen runs the prompt", authenticator.requests.isEmpty())
    }

    @Test
    fun `the lock screen's prompt unlocks and is the system's confirm-it-is-you`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        authenticator.queue(FakeAuthenticator.SUCCEEDED)
        assertTrue(runBlocking { lock.unlock() })
        assertEquals(LockState.UNLOCKED, lock.state.value)
        assertEquals(listOf(FakeAuthenticator.Request("Unlock Berth", null, null)), authenticator.requests)
        assertNull(lock.failure.value)
    }

    @Test
    fun `settings that land after the activity started still decide the launch`() {
        val released = CompletableDeferred<Unit>()
        val late = object : SettingsRepository by settings {
            override val securitySettings: Flow<SecuritySettings> = flow {
                released.await()
                emitAll(settings.security)
            }
        }
        lockOn(LockTimeout.NEVER)
        val lock = controller(late)
        lock.onForeground()
        assertEquals(LockState.UNKNOWN, lock.state.value)
        released.complete(Unit)
        assertEquals("the pending launch is evaluated against the settings that arrived", LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `a rotation never locks, whatever the timeout and however long the relayout takes`() {
        val lock = launchedAndUnlocked(LockTimeout.IMMEDIATELY)
        lock.onBackground(changingConfigurations = true)
        clock.advance(TimeUnit.HOURS.toMillis(1))
        lock.onForeground()
        assertEquals(LockState.UNLOCKED, lock.state.value)
        assertEquals("no second prompt", 1, authenticator.requests.size)
    }

    @Test
    fun `an activity recreated under a locked app comes back locked`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        // The lock screen is rotated before the user has unlocked.
        lock.onBackground(changingConfigurations = true)
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `immediately locks on any return from the background`() {
        val lock = launchedAndUnlocked(LockTimeout.IMMEDIATELY)
        lock.awayFor(0)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `one minute locks at sixty seconds away and not a millisecond before`() {
        val lock = launchedAndUnlocked(LockTimeout.ONE_MINUTE)
        lock.awayFor(59_999)
        assertEquals(LockState.UNLOCKED, lock.state.value)
        lock.awayFor(60_000)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `five minutes locks at three hundred seconds away`() {
        val lock = launchedAndUnlocked(LockTimeout.FIVE_MINUTES)
        lock.awayFor(TimeUnit.MINUTES.toMillis(5) - 1)
        assertEquals(LockState.UNLOCKED, lock.state.value)
        lock.awayFor(TimeUnit.MINUTES.toMillis(5))
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `the background clock runs from the moment of leaving, not the last return`() {
        val lock = launchedAndUnlocked(LockTimeout.ONE_MINUTE)
        // Two short trips: neither reaches a minute on its own and they do not add up.
        lock.awayFor(40_000)
        lock.awayFor(40_000)
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `never means only a launch locks`() {
        val lock = launchedAndUnlocked(LockTimeout.NEVER)
        lock.awayFor(TimeUnit.DAYS.toMillis(3))
        assertEquals(LockState.UNLOCKED, lock.state.value)
        // The process dies and the app is launched again: a fresh controller, the same settings.
        val relaunched = controller()
        assertEquals(LockState.UNKNOWN, relaunched.state.value)
        relaunched.onForeground()
        assertEquals(LockState.LOCKED, relaunched.state.value)
    }

    @Test
    fun `overlapping activities count as one foreground`() {
        val lock = launchedAndUnlocked(LockTimeout.IMMEDIATELY)
        lock.onForeground()
        lock.onBackground(changingConfigurations = false)
        clock.advance(TimeUnit.MINUTES.toMillis(10))
        assertEquals("one activity of ours is still in front", LockState.UNLOCKED, lock.state.value)
        lock.onBackground(changingConfigurations = false)
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `backing out of the prompt keeps the lock with nothing to explain`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        authenticator.queue(AuthOutcome.Cancelled)
        assertFalse(runBlocking { lock.unlock() })
        assertEquals(LockState.LOCKED, lock.state.value)
        assertNull(lock.failure.value)
    }

    @Test
    fun `a prompt that errors shows why, and the next success clears it`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        authenticator.queue(AuthOutcome.Failed("Too many attempts. Try again later."))
        assertFalse(runBlocking { lock.unlock() })
        assertEquals(LockState.LOCKED, lock.state.value)
        assertEquals("Too many attempts. Try again later.", lock.failure.value)
        authenticator.queue(FakeAuthenticator.SUCCEEDED)
        assertTrue(runBlocking { lock.unlock() })
        assertNull(lock.failure.value)
    }

    @Test
    fun `one prompt at a time, and unlocking an unlocked app is a no-op`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        var first: Boolean? = null
        scope.launch { first = lock.unlock() }
        assertTrue(lock.authenticating.value)
        assertTrue(authenticator.pending)
        assertFalse("a second call while the prompt is up does not stack another", runBlocking { lock.unlock() })
        assertEquals(1, authenticator.requests.size)
        authenticator.answer(FakeAuthenticator.SUCCEEDED)
        assertEquals(true, first)
        assertFalse(lock.authenticating.value)
        assertTrue("already unlocked: true without a prompt", runBlocking { lock.unlock() })
        assertEquals(1, authenticator.requests.size)
    }

    @Test
    fun `the prompt taking the app away and back is not a trip to the background`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        var unlocked: Boolean? = null
        scope.launch { unlocked = lock.unlock() }
        // Android 10 shows the device credential screen as another activity: ours stops and starts again.
        lock.onBackground(changingConfigurations = false)
        clock.advance(20_000)
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
        authenticator.answer(FakeAuthenticator.SUCCEEDED)
        assertEquals(true, unlocked)
        assertEquals("the return during the prompt did not queue a re-lock", LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `turning the lock off unlocks at once`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
        settings.security.value = SecuritySettings(appLock = false)
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `awaitUnlocked holds key prompts back until the lock screen is gone`() {
        lockOn(LockTimeout.IMMEDIATELY)
        val lock = controller()
        lock.onForeground()
        var passed = false
        scope.launch {
            lock.awaitUnlocked()
            passed = true
        }
        assertFalse(passed)
        authenticator.queue(FakeAuthenticator.SUCCEEDED)
        runBlocking { lock.unlock() }
        assertTrue(passed)
    }
}
