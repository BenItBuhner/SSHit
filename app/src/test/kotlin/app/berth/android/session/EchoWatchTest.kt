package app.berth.android.session

import app.berth.android.session.EchoWatch.Companion.ECHO_OFF_AFTER_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the grip's dot (spec C2, a password prompt): text typed that nothing answers
 * within the window was not echoed; anything that comes back, or the line's end, clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EchoWatchTest {
    private fun TestScope.watch() = EchoWatch(backgroundScope)

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a key nothing answers within the window was not echoed, and what comes back clears it`() = runTest {
        val w = watch()
        w.typed()
        pass(ECHO_OFF_AFTER_MS - 1)
        assertFalse("not before the window is out", w.off.value)
        pass(1)
        assertTrue(w.off.value)
        w.output()
        assertFalse(w.off.value)
    }

    @Test
    fun `a key whose echo lands inside the window is echoed, however long after it is looked at`() = runTest {
        val w = watch()
        w.typed()
        pass(40)
        w.output()
        pass(ECHO_OFF_AFTER_MS * 3)
        assertFalse(w.off.value)
    }

    @Test
    fun `further keys at the silent prompt keep the dot, and the line's end takes it down at once`() = runTest {
        val w = watch()
        w.typed()
        pass(ECHO_OFF_AFTER_MS)
        assertTrue(w.off.value)
        repeat(6) {
            w.typed()
            pass(120)
        }
        pass(ECHO_OFF_AFTER_MS)
        assertTrue(w.off.value)
        w.ended()
        assertFalse(w.off.value)
    }

    @Test
    fun `a line ended inside the window never raises the dot`() = runTest {
        val w = watch()
        w.typed()
        pass(100)
        w.ended()
        pass(ECHO_OFF_AFTER_MS * 2)
        assertFalse(w.off.value)
    }

    @Test
    fun `each key waits its own window from when it was typed`() = runTest {
        val w = watch()
        w.typed()
        pass(ECHO_OFF_AFTER_MS / 2)
        w.output()
        w.typed()
        pass(ECHO_OFF_AFTER_MS - 1)
        assertFalse("the second key's window is not out yet", w.off.value)
        pass(1)
        assertTrue("nothing answered the second key", w.off.value)
    }
}
